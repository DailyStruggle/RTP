package io.github.dailystruggle.rtp.fabric.world;

import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabric {@link RTPWorld} implementation (ADR-022). No {@code org.bukkit.*}
 * imports; holds a {@link ServerLevel} and reaches the server via
 * {@link ServerLevel#getServer()}. Every native chunk-system call
 * ({@code ServerChunkCache#getChunk}, {@code setChunkForced},
 * {@code getForcedChunks}) is dispatched onto the server tick thread via
 * {@link MinecraftServer#submit(java.util.function.Supplier)} (S-005).
 *
 * <p>End-to-end verification is deferred to Phase 2 Step H per
 * {@code MULTI_PLATFORM_PLAN.md}.
 */
public final class FabricRTPWorld extends RTPWorld<ServerLevel> {

    private final String name;
    private final UUID id;

    /**
     * Live-chunk weak-ref cache, keyed by the canonical packed chunk key
     * ({@code ((long) cx & 0xffffffffL) | ((long) cz << 32)}). Mirrors
     * {@code BukkitRTPWorld.chunkCache} so {@link #getCacheSize()} reports
     * a meaningful number once we wire {@code FabricRTPChunk}; today the
     * cache is populated by {@link #getChunkAt(int, int)} but never read
     * (see class-level note on {@link #getCachedChunk(long)}).
     */
    private final ConcurrentHashMap<Long, WeakReference<ChunkAccess>> chunkCache = new ConcurrentHashMap<>();

    /**
     * Cached {@link FabricRTPChunk} wrappers keyed by the same packed chunk key
     * as {@link #chunkCache}. Populated alongside {@code chunkCache} on every
     * successful {@link #getChunkAt(int, int)} so {@link #getCachedChunk(long)}
     * can return a live wrapper without re-allocating per call. Wrappers are
     * dropped when their backing {@link ChunkAccess} is GC'd.
     */
    private final ConcurrentHashMap<Long, WeakReference<FabricRTPChunk>> rtpChunkCache = new ConcurrentHashMap<>();

    public FabricRTPWorld(@NotNull ServerLevel level) {
        super(level);
        // dimension() returns ResourceKey<Level>; its location is the canonical
        // dimension id (e.g. minecraft:overworld). Use that as the world name —
        // this matches how Fabric command/log output identifies dimensions and
        // avoids depending on save-folder names which are server-config-specific.
        this.name = level.dimension().location().toString();
        // Fabric does not assign a per-world UUID the way Bukkit does; derive a
        // deterministic UUID from the dimension id so equals/hashCode behave
        // sensibly across restarts.
        this.id = UUID.nameUUIDFromBytes(this.name.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public UUID id() {
        return id;
    }

    /**
     * Public accessor for the underlying {@link ServerLevel}. The base
     * {@link RTPWorld#world} field is {@code protected}; expose it for
     * Fabric-side adapters (player teleport, event bridge) that legitimately
     * need the native handle. Callers in {@code rtp-core} / {@code rtp-api}
     * must NOT use this — keep the API platform-free.
     */
    public ServerLevel level() {
        return world;
    }

    // ---------------------------------------------------------------------------
    // Step A — async chunk load (S-005)
    // ---------------------------------------------------------------------------

    /**
     * S-005-compliant async chunk load.
     *
     * <p><b>Generation contract.</b> Calls
     * {@code ServerChunkCache#getChunk(x, z, ChunkStatus.FULL, /*load=*&#47;true)}
     * inside {@link MinecraftServer#submit}. The {@code load=true} flag is
     * what tells vanilla to <i>generate the chunk if absent</i> — without it,
     * an unloaded coordinate resolves to {@code null} and the RTP pipeline
     * attributes every attempt to {@code nullChunk/asyncLoadNull}, which is
     * exactly what we observed in the 2026-05-03 Fabric 1.21.1 smoke test.
     *
     * <p><b>Why not the non-blocking {@code getChunkFutureMainThread} path?</b>
     * An earlier revision routed through reflective {@code getChunkFutureMainThread}
     * to avoid blocking the tick thread on generation, but that method's
     * intermediary mapping varies across MC patch releases AND it can resolve
     * with an "unloaded" payload that the unwrapper legitimately
     * decodes as {@code null} — producing the same 32×{@code asyncLoadNull}
     * failure mode but for a different reason. The simpler
     * {@code cache.getChunk(..., true)} call is the public, version-stable
     * vanilla API and is the same call vanilla itself uses for
     * {@code /forceload} and command-driven generation.
     *
     * <p><b>Tick-lag note.</b> This call <i>does</i> block the server tick
     * thread for the duration of generation on a cache miss. In practice the
     * RTP pipeline's anvil pre-filter (ADR-016) reads NBT directly from
     * {@code .mca} files without loading chunks, so the bulk of pre-fill
     * work bypasses this path entirely; only the final placement chunk is
     * loaded synchronously here. Acceptable per user-confirmed scope on
     * 2026-05-03.
     */
    @Override
    public CompletableFuture<Long> getChunkAt(int chunkX, int chunkZ) {
        // Probe-entry: do NOT bump totalChunkLoads here. The increment happens inside
        // the server.submit body below, on the actual live chunk load. This avoids the
        // double-count via RTPWorld.getOrLoadChunk's probe-then-live composition; see
        // the Javadoc on RTPWorld.totalChunkLoads.
        final long key = ((long) chunkX & 0xffffffffL) | ((long) chunkZ << 32);
        final MinecraftServer server = world.getServer();
        if (server == null) {
            // Defensive: a ServerLevel without a server is a torn-down state.
            // Complete exceptionally rather than throwing on the caller thread
            // so the pipeline's existing failure-attribution path picks it up
            // (REQ-RTP-S-004 — no silent discards).
            CompletableFuture<Long> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException(
                "FabricRTPWorld.getChunkAt: ServerLevel has no MinecraftServer (world=" + name + ")"));
            return failed;
        }
        return server.submit(() -> {
            // Live chunk-load attempt — count it exactly once here, regardless of
            // whether the caller entered via getChunkAt directly or via getChunkAtAsync
            // (which delegates to this method).
            totalChunkLoads.incrementAndGet();
            ServerChunkCache cache = world.getChunkSource();
            // load=true => generate-if-absent. This is the contract that makes
            // RTP work on freshly-explored coordinates; do NOT change to false.
            ChunkAccess chunk = cache.getChunk(chunkX, chunkZ, ChunkStatus.FULL, true);
            if (chunk != null) {
                chunkCache.put(key, new WeakReference<>(chunk));
                rtpChunkCache.put(key,
                    new WeakReference<>(new FabricRTPChunk(chunk, world, id)));
            }
            return key;
        });
    }

    /**
     * Step A scope: minimal {@link ChunkSet} mirroring {@code BukkitRTPWorld#getChunkAtAsync}.
     * Single-chunk set with the load future as its only entry; the &quot;done&quot; future
     * is never completed because the Fabric pipeline currently does not consume it
     * (Bukkit only fires it for the ADR-016 stale-chunk guard — which is Bukkit-family
     * only per ADR-016 §13.2).
     */
    @Override
    public CompletableFuture<ChunkSet> getChunkAtAsync(int cx, int cz) {
        return getChunkAt(cx, cz).thenApply(key ->
            new ChunkSet(this, cx, cz,
                Collections.singletonList(CompletableFuture.completedFuture(key)),
                new CompletableFuture<>()));
    }

    /**
     * Non-blocking loaded-chunk lookup. {@link ServerChunkCache#hasChunk(int, int)}
     * is the documented non-loading state query — used by the stale-chunk guard
     * (ADR-015 / REQ-RTP-S-005) between an async load future resolution and the
     * subsequent block-evaluation task on a Count-Bound pipe. Defensive on torn-down
     * worlds.
     */
    @Override
    public boolean isChunkLoaded(int cx, int cz) {
        try {
            ServerChunkCache cache = world.getChunkSource();
            return cache != null && cache.hasChunk(cx, cz);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------
    // Region-file backed isChunkGenerated probe
    // ---------------------------------------------------------------------------

    /**
     * Reflective handles into Mojang internals for the non-blocking
     * &quot;does an .mca entry exist for this chunk?&quot; question. Lazily
     * resolved on first use; cached for the JVM lifetime. Reflection is used
     * (rather than an AccessWidener / accessor mixin) to keep this change
     * self-contained — {@code rtp-fabric-common} has no AW/mixin config today.
     *
     * <p>Resolution chain: {@code ServerChunkCache#chunkMap} field →
     * {@code ChunkMap#read(ChunkPos)} method returning
     * {@code CompletableFuture<Optional<CompoundTag>>}. The future is fed by
     * the {@code IOWorker} async region-file reader; it does NOT touch the
     * chunk system and does NOT trigger generation, so it satisfies the
     * S-005 non-blocking pre-check contract.</p>
     */
    private static volatile java.lang.reflect.Field CHUNK_MAP_FIELD;
    private static volatile java.lang.reflect.Method CHUNK_MAP_READ_METHOD;
    private static volatile boolean REFLECTION_RESOLVED = false;
    private static volatile boolean REFLECTION_AVAILABLE = false;

    private static void resolveReflectionOnce(@NotNull ServerChunkCache sample) {
        if (REFLECTION_RESOLVED) return;
        synchronized (FabricRTPWorld.class) {
            if (REFLECTION_RESOLVED) return;
            try {
                // ServerChunkCache#chunkMap — package-private final field; mappings
                // stable since 1.18 under the deobf name "chunkMap".
                java.lang.reflect.Field f = null;
                for (java.lang.reflect.Field cand : sample.getClass().getDeclaredFields()) {
                    if ("chunkMap".equals(cand.getName())
                        || cand.getType().getSimpleName().equals("ChunkMap")) {
                        f = cand;
                        break;
                    }
                }
                if (f == null) {
                    REFLECTION_RESOLVED = true;
                    return;
                }
                f.setAccessible(true);
                CHUNK_MAP_FIELD = f;

                // ChunkMap#read(ChunkPos) — protected method; returns
                // CompletableFuture<Optional<CompoundTag>>.
                Class<?> chunkMapClass = f.getType();
                java.lang.reflect.Method read = null;
                Class<?> chunkPosClass = net.minecraft.world.level.ChunkPos.class;
                for (java.lang.reflect.Method m : chunkMapClass.getDeclaredMethods()) {
                    if (!m.getName().equals("read")) continue;
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 1 && params[0] == chunkPosClass) {
                        read = m;
                        break;
                    }
                }
                if (read == null) {
                    REFLECTION_RESOLVED = true;
                    return;
                }
                read.setAccessible(true);
                CHUNK_MAP_READ_METHOD = read;
                REFLECTION_AVAILABLE = true;
            } catch (Throwable t) {
                RTP.log(java.util.logging.Level.WARNING,
                    "[RTP] FabricRTPWorld.isChunkGenerated reflection setup failed: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            } finally {
                REFLECTION_RESOLVED = true;
            }
        }
    }

    /**
     * Non-blocking check for whether the chunk has been generated and persisted
     * to disk. Implements the {@link RTPWorld#isChunkGenerated(int, int)}
     * contract using region-file probes — same primitive Bukkit's
     * {@code World#isChunkGenerated} reduces to. Used by ADR-016 §13.3 to gate
     * the vanilla seed-biome pre-check fast path.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>{@code ServerChunkCache#hasChunk} — loaded chunks are by definition
     *       generated; cheapest answer.</li>
     *   <li>{@code ChunkMap#read(ChunkPos).join().isPresent()} — IOWorker async
     *       region-file read; non-loading, non-generating. The join is bounded
     *       (a single header-table lookup against a cached
     *       {@code RegionFileStorage}); it does NOT block on the chunk system
     *       and is safe to call off-tick.</li>
     *   <li>If reflection is unavailable or throws, fall back to {@code true}
     *       per the conservative default — false-positives are harmless
     *       (only skip the perf fast path); false-negatives would risk the
     *       ADR-016 §13.3 palette-drift bug.</li>
     * </ol>
     */
    @Override
    public boolean isChunkGenerated(int cx, int cz) {
        try {
            ServerChunkCache cache = world.getChunkSource();
            if (cache == null) return true;
            // Loaded chunk → unambiguously generated.
            if (cache.hasChunk(cx, cz)) return true;

            resolveReflectionOnce(cache);
            if (!REFLECTION_AVAILABLE) return true;

            Object chunkMap = CHUNK_MAP_FIELD.get(cache);
            if (chunkMap == null) return true;
            Object result = CHUNK_MAP_READ_METHOD.invoke(
                chunkMap, new net.minecraft.world.level.ChunkPos(cx, cz));
            if (!(result instanceof CompletableFuture<?> future)) return true;
            // IOWorker-backed: completes from the IO executor without touching
            // the chunk system. Bounded (single region-file header lookup).
            Object payload = future.join();
            if (!(payload instanceof java.util.Optional<?> optional)) return true;
            return optional.isPresent();
        } catch (Throwable t) {
            // Conservative default: assume generated. Per RTPWorld javadoc, a
            // false-positive only forfeits a perf fast path; a false-negative
            // could re-introduce the ADR-016 §13.3 palette-drift bug.
            return true;
        }
    }

    // ---------------------------------------------------------------------------
    // Step C — chunk-ticket lifecycle
    // ---------------------------------------------------------------------------

    /**
     * Apply / remove a force-load ticket on the server tick thread.
     *
     * <p>Mojang's {@link ServerLevel#setChunkForced(int, int, boolean)} is the
     * Fabric equivalent of Bukkit's {@code addPluginChunkTicket} /
     * {@code removePluginChunkTicket}. It MUST be called on the server tick
     * thread (it mutates {@code ServerChunkCache.distanceManager}); we hop via
     * {@link MinecraftServer#submit}. The returned future completes when the
     * native call has actually executed, satisfying the ADR-015 ticket-application
     * race contract that {@link RTPWorld#setForceLoaded(int, int, boolean)} relies
     * on.</p>
     *
     * <p>Vanilla Fabric has no per-plugin ticket namespace — {@code setChunkForced}
     * sets a single &quot;forced&quot; flag persisted to {@code level.dat}. The
     * parent class's ref-counting in {@link RTPWorld#chunkTickets} guards against
     * us toggling the flag back to {@code false} while another caller still holds
     * a logical ticket; that is sufficient for correctness.</p>
     */
    @Override
    protected CompletableFuture<Void> setForceLoadedImpl(int cx, int cz, boolean forceLoad) {
        final MinecraftServer server = world.getServer();
        if (server == null) {
            // Torn-down world: complete normally so callers don't block forever.
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            try {
                world.setChunkForced(cx, cz, forceLoad);
            } catch (Throwable t) {
                RTP.log(java.util.logging.Level.WARNING,
                    "[RTP] FabricRTPWorld.setChunkForced failed for world=" + name
                        + " chunk=(" + cx + "," + cz + ") forceLoad=" + forceLoad
                        + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, server);
    }

    /**
     * Count chunks currently flagged as force-loaded by this server. Hops onto
     * the server tick thread to read {@link ServerLevel#getForcedChunks()} (a
     * {@code LongSet} that is not safe to iterate off-tick).
     *
     * <p>Note: vanilla Fabric does not track which mod owns a forced chunk, so
     * this returns the total server count — Bukkit's per-plugin filter has no
     * direct analogue. Callers using this for &quot;tickets we issued&quot;
     * accounting should additionally consult {@link RTPWorld#chunkTickets}.</p>
     */
    @Override
    public CompletableFuture<Integer> getServerForceLoadedCount() {
        final MinecraftServer server = world.getServer();
        if (server == null) return CompletableFuture.completedFuture(0);
        return server.submit(() -> {
            try {
                return world.getForcedChunks().size();
            } catch (Throwable t) {
                return 0;
            }
        });
    }

    /**
     * Resolve a cached {@link FabricRTPChunk} for the given packed chunk key.
     * Two-tier lookup: prefer the wrapper cache populated by
     * {@link #getChunkAt(int, int)}; fall back to lazily wrapping a still-live
     * {@link ChunkAccess} from {@link #chunkCache} if the wrapper was GC'd
     * but the backing chunk wasn't. Returns {@code null} when neither tier
     * has a live entry — every {@code rtp-core} caller already gates on a
     * non-null result before invoking {@code isSafe} / {@code getBiome}.
     */
    @Override
    public RTPChunk<?> getCachedChunk(long key) {
        WeakReference<FabricRTPChunk> rtpRef = rtpChunkCache.get(key);
        if (rtpRef != null) {
            FabricRTPChunk wrapper = rtpRef.get();
            if (wrapper != null) return wrapper;
            rtpChunkCache.remove(key);
        }
        // Fallback: backing chunk may still be live but the wrapper was GC'd.
        // Reconstruct lazily so stale-chunk-guard callers don't lose their handle.
        WeakReference<ChunkAccess> ref = chunkCache.get(key);
        if (ref != null) {
            ChunkAccess chunk = ref.get();
            if (chunk == null) {
                chunkCache.remove(key);
                return null;
            }
            FabricRTPChunk wrapper = new FabricRTPChunk(chunk, world, id);
            rtpChunkCache.put(key, new WeakReference<>(wrapper));
            return wrapper;
        }
        return null;
    }

    @Override
    public void keepChunkAt(int chunkX, int chunkZ) {
        RTP.scheduler.runTask(this, chunkX, chunkZ, () -> setForceLoaded(chunkX, chunkZ, true));
    }

    @Override
    public void forgetChunkAt(int chunkX, int chunkZ) {
        RTP.scheduler.runTask(this, chunkX, chunkZ, () -> {
            setForceLoaded(chunkX, chunkZ, false);
            long key = ((long) chunkX & 0xffffffffL) | ((long) chunkZ << 32);
            chunkCache.remove(key);
            rtpChunkCache.remove(key);
        });
    }

    @Override
    public void forgetChunks() {
        // Mirror BukkitRTPWorld#forgetChunks — drain the parent's ref-counted
        // ticket map by issuing decrements until each count reaches zero, then
        // clear the local cache. The decrements route through setForceLoaded
        // which hops to the server thread per ticket, so this is safe to call
        // from any context.
        chunkTickets.forEach((key, count) -> {
            int cx = (int) (key & 0xffffffffL);
            int cz = (int) (key >> 32);
            while (count.get() > 0) {
                setForceLoaded(cx, cz, false);
            }
        });
        chunkCache.clear();
        rtpChunkCache.clear();
    }

    @Override
    public int getCacheSize() {
        return chunkCache.size();
    }

    // ---------------------------------------------------------------------------
    // Step E — read-only world data
    // ---------------------------------------------------------------------------

    /**
     * Resolve the biome at the given block coordinates via
     * {@link ServerLevel#getBiome(BlockPos)}, returning the
     * {@code namespace:path} key (e.g. {@code minecraft:plains}) in upper case
     * to mirror {@code BukkitRTPWorld.getBiome}'s {@code Biome.name()} contract.
     *
     * <p>S-005 / threading: the dynamic biome registry read is generally safe
     * off the server thread (it's a read-only registry view), matching how
     * {@code Level#getBiome} behaves on Paper. If a future Mojang change makes
     * this thread-unsafe, switch to a {@link MinecraftServer#submit} hop —
     * the cost is one tick of latency per biome lookup, acceptable on the
     * vert-adjustor / safety-check path.</p>
     *
     * <p>Returns the empty string on lookup failure (unknown biome, missing
     * registry key) so callers performing string equality against the
     * configured biome list see a deterministic non-null value.</p>
     */
    @Override
    public String getBiome(int x, int y, int z) {
        try {
            var holder = world.getBiome(new BlockPos(x, y, z));
            return holder.unwrapKey()
                .map(k -> k.location().toString().toUpperCase())
                .orElse("");
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Worldborder-derived bounds are handled by {@code FabricServerAccessor};
     * Fabric has no Bukkit-style {@code Bukkit.getWorld(id) == null} check
     * because dimensions are server-bound for their lifetime. Treat as always
     * active while the {@link ServerLevel} is reachable.
     */
    @Override
    public boolean isInactive() {
        return world == null || world.getServer() == null;
    }

    @Override
    public void platform(RTPLocation location) {
        // Fabric platform-block writes require a server-thread hop and a
        // BlockState lookup that depends on Material → Block parity work
        // scheduled for Step E follow-ups (no MaterialRegistry on Fabric —
        // we'd need to parse the configured platform-material identifier
        // through BuiltInRegistries.BLOCK directly). Until then, the
        // platform sub-feature is a no-op: the safety pipeline's primary
        // contract is &quot;don't teleport into unsafe blocks&quot;, not
        // &quot;build a safety platform&quot;, so this preserves correctness
        // at the cost of one configurable convenience. Reservation cleanup
        // is preserved to mirror the Bukkit contract.
        try {
            // intentional no-op until palette parity lands
        } finally {
            if (location != null && location.getReservation() != null) {
                location.getReservation().close();
            }
        }
    }

    @Override
    public void save() {
        // Intentional no-op on Fabric (parity with Paper/Folia overrides).
        // Fabric's chunk system persists generated chunks via its own
        // dirty-tracking and the vanilla autosave path; the forced
        // World.save() that the Spigot adapter performs (to work around
        // Bukkit autosave not flushing Chunky-generated chunks — see
        // docs/dev/LESSONS_LEARNED.md "Pre-Generation & Shutdown") is not
        // needed here.
    }

    @Override
    public int getMaxHeight() {
        // ServerLevel inherits Level#getMaxBuildHeight — equivalent to Bukkit's
        // World#getMaxHeight semantics (exclusive upper bound).
        try {
            return world.getMaxBuildHeight();
        } catch (Throwable t) {
            return 320; // 1.18+ overworld default
        }
    }

    @Override
    public int getMinHeight() {
        try {
            return world.getMinBuildHeight();
        } catch (Throwable t) {
            return -64; // 1.18+ overworld default
        }
    }

    @Override
    public long getSeed() {
        try {
            return world.getSeed();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * Optional accessor for the most-recently cached {@link ChunkAccess} at the
     * given key. Returns {@code null} if the chunk was never loaded by this
     * adapter or has been GC'd. Currently unused by {@code rtp-core} (the
     * platform-neutral path goes through {@link #getCachedChunk(long)}); kept
     * as a Fabric-internal hook for {@code FabricRTPChunk} once it lands.
     */
    public @Nullable ChunkAccess peekChunk(long key) {
        WeakReference<ChunkAccess> ref = chunkCache.get(key);
        return ref == null ? null : ref.get();
    }
}
