package io.github.dailystruggle.rtp.neoforge.world;

import io.github.dailystruggle.rtp.api.world.ChunkColumnProbe;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.anvil.AnvilColumnProbeAdapter;
import io.github.dailystruggle.rtp.neoforge.version.NeoForgeVersionAdapter;
import io.github.dailystruggle.rtp.neoforge.version.NeoForgeVersionAdapterRegistry;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NeoForge {@link RTPWorld} implementation (rtp-neoforge-ADR-001), the NeoForge
 * analogue of {@code FabricRTPWorld}. Holds a {@link ServerLevel} and reaches
 * the server via {@link ServerLevel#getServer()}.
 *
 * <p><b>S-005:</b> live chunk generation is dispatched non-blocking through the
 * active {@link NeoForgeVersionAdapter} (which wraps
 * {@code ServerChunkCache#getChunkFuture(..., FULL, true)}). <b>S-002:</b> chunk
 * tickets use a non-persistent {@code TicketType} rather than vanilla
 * {@code setChunkForced} so RTP-owned chunks are never written to
 * {@code level.dat}. ADR-016 anvil pre-filter mirrors the Spigot/Fabric wiring.</p>
 *
 * <p>Mojmap-at-runtime: the SPI passes {@link ServerLevel} / {@link ChunkAccess}
 * directly (no {@code RTPLevelHandle}/{@code RTPChunkHandle} boxing — those were
 * dropped for NeoForge since there is no obf/intermediary split).</p>
 */
public final class NeoForgeRTPWorld extends RTPWorld<ServerLevel> {

    private final String name;
    private final UUID id;

    // Strong-reference chunk caches (NOT WeakReference). A freshly loaded chunk
    // is only pinned by vanilla's transient generation ticket (getChunkFuture
    // create=true), which is released as soon as the FULL-status future
    // resolves; the persistent RTP keep-ticket is applied LATER, only after the
    // cold->hot promotion verify (Region.execute) reads the chunk back via
    // getCachedChunk and the vertical adjustor accepts it. With WeakReference
    // maps the just-loaded wrapper could be reclaimed in that gap (deterministic
    // under the deficit-dispatch GC burst of generating many candidate chunks at
    // once), so getCachedChunk returned null for every promotion -> the L1 kept
    // cache never filled and /rtp never landed. Strong references guarantee the
    // loaded chunk survives the verify hop, mirroring the proven Fabric 26.1
    // carrier (V26_1_R1FabricRTPWorld#chunkCache). Entries are evicted explicitly
    // in forgetChunkAt / forgetChunks, so the map does not grow unbounded.
    private final ConcurrentHashMap<Long, ChunkAccess> chunkCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, NeoForgeRTPChunk> rtpChunkCache = new ConcurrentHashMap<>();
    private final io.github.dailystruggle.rtp.anvil.AnvilProbeSupport anvilProbeSupport =
            new io.github.dailystruggle.rtp.anvil.AnvilProbeSupport();

    public NeoForgeRTPWorld(@NotNull ServerLevel level) {
        super(level);
        this.name = resolveDimensionName(level, true);
        this.id = UUID.nameUUIDFromBytes(this.name.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Resolve the stable RTP world name ("namespace:path") for a
     * {@link ServerLevel}'s dimension, mirroring exactly the name used at
     * registration time. Prefers the reflective identifier accessor and, when it
     * returns {@code null} (e.g. the {@code ResourceLocation} -> {@code Identifier}
     * rename in the MC 1.21.11 mappings), parses the trailing "namespace:path"
     * token out of {@code ResourceKey#toString()}.
     *
     * <p>This must be the single source of truth for the world name: registration
     * ({@link #NeoForgeRTPWorld(ServerLevel)}) and lookup
     * ({@code NeoForgeRTPPlayer.getLocation()}) both go through here so a player's
     * current world always resolves to the same registered name.</p>
     *
     * @param warnOnFallback emit the one-time fallback warning (true at
     *                       registration, false on hot lookup paths)
     */
    @NotNull
    public static String resolveDimensionName(@NotNull ServerLevel level, boolean warnOnFallback) {
        String resolved = io.github.dailystruggle.rtp.neoforge.tools.NeoForgeResourceIds
                .locationString(level.dimension());
        if (resolved != null) return resolved;
        // Last-resort fallback so the world still resolves to a usable, stable
        // name even if the reflective identifier accessor fails on a mappings
        // rename. ResourceKey#toString() yields
        // "ResourceKey[minecraft:dimension / minecraft:overworld]"; pull the
        // trailing "namespace:path" token out of it.
        String raw = String.valueOf(level.dimension());
        int slash = raw.lastIndexOf('/');
        int end = raw.lastIndexOf(']');
        if (slash >= 0 && end > slash) {
            resolved = raw.substring(slash + 1, end).trim();
        } else {
            resolved = raw;
        }
        if (warnOnFallback) {
            // Expected on the MC 1.21.11 mappings (ResourceLocation -> Identifier
            // rename): the reflective id accessor returns null and the stable
            // ResourceKey#toString() parse below always yields the correct name.
            // This is a routine fallback, not a fault, so keep it at FINE to avoid
            // startup log spam (one line per loaded dimension).
            RTP.log(java.util.logging.Level.FINE,
                    "[RTP][NeoForge] dimension id accessor returned null; "
                            + "falling back to parsed name '" + resolved + "'.");
        }
        return resolved;
    }

    /**
     * ADR-058 — region-schematic paster. The decode/plan half is platform-neutral
     * ({@link io.github.dailystruggle.rtp.api.schematic.WorldBlockSchematicPaster}); the native
     * block writes route back through {@link #setBlocks(java.util.List)} below. Defaulting to a
     * real paster (rather than {@code NoOpSchematicPaster}) is what enables schematic parsing on
     * NeoForge — parity with {@code V26_2_R1FabricRTPWorld}.
     */
    private static @NotNull io.github.dailystruggle.rtp.api.schematic.SchematicPaster schematicPaster =
            new io.github.dailystruggle.rtp.api.schematic.WorldBlockSchematicPaster();

    public static void setSchematicPaster(
            @NotNull io.github.dailystruggle.rtp.api.schematic.SchematicPaster paster) {
        NeoForgeRTPWorld.schematicPaster = java.util.Objects.requireNonNull(paster, "paster");
    }

    public static @NotNull io.github.dailystruggle.rtp.api.schematic.SchematicPaster
            getSchematicPaster() {
        return schematicPaster;
    }

    @Override
    public io.github.dailystruggle.rtp.api.schematic.SchematicPaster schematicPaster() {
        return schematicPaster;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public UUID id() {
        return id;
    }

    /** Public accessor for the underlying {@link ServerLevel}. */
    public ServerLevel level() {
        return world;
    }

    // ---------------------------------------------------------------------------
    // Async chunk load (S-005)
    // ---------------------------------------------------------------------------

    @Override
    public CompletableFuture<Long> getChunkAt(int chunkX, int chunkZ) {
        final long key = ((long) chunkX & 0xffffffffL) | ((long) chunkZ << 32);

        if (shouldPrefilter(chunkX, chunkZ)) {
            ServerLevel probeLevel = world;
            MinecraftServer probeServer = (probeLevel != null) ? probeLevel.getServer() : null;
            if (probeLevel != null && probeServer != null) {
                java.nio.file.Path worldFolder;
                try {
                    worldFolder = probeServer.getWorldPath(LevelResource.ROOT);
                } catch (Throwable t) {
                    worldFolder = null;
                }
                if (worldFolder != null) {
                    String dim = dimensionRegionSubpath(probeLevel);
                    java.util.Set<String> rawUnsafe = currentUnsafeBlocks();
                    return anvilProbeSupport
                            .probeAndPublish(worldFolder, dim, chunkX, chunkZ, key, rawUnsafe,
                                    io.github.dailystruggle.rtp.common.anvil.PaletteNormalizer::reconcile)
                            .thenCompose(result -> {
                                io.github.dailystruggle.rtp.anvil.AnvilChunkView view = result.view();
                                if (view != null) {
                                    if (result.verdict() == io.github.dailystruggle.rtp.anvil.Verdict.REJECT) {
                                        RTP.log(java.util.logging.Level.FINE,
                                                "[RTP] Anvil surface-unsafe (advisory) world=" + name
                                                        + " chunk=(" + chunkX + "," + chunkZ
                                                        + ") — handing view to vert adjustor");
                                    }
                                    return CompletableFuture.completedFuture(key);
                                }
                                return loadLiveChunk(chunkX, chunkZ, key);
                            });
                }
            }
        }

        return loadLiveChunk(chunkX, chunkZ, key);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Set<String> currentUnsafeBlocks() {
        try {
            if (RTP.configs == null) return java.util.Collections.emptySet();
            Object raw = RTP.configs.getConfigValue(BlocksKeys.unsafeBlocks, new java.util.ArrayList<>());
            if (raw instanceof java.util.Collection<?> c) {
                java.util.Set<String> out = new java.util.HashSet<>(c.size());
                for (Object o : c) if (o != null) out.add(o.toString());
                return out;
            }
        } catch (Throwable ignored) {
            // Fall through to empty.
        }
        return java.util.Collections.emptySet();
    }

    private final ConcurrentHashMap<Long, CompletableFuture<Long>> inFlightLiveLoads = new ConcurrentHashMap<>();
    private final AtomicInteger liveLoadInFlight = new AtomicInteger();

    private static final long LIVE_LOAD_DEADLINE_MS = 30_000L;

    /**
     * Live-chunk load path. Dispatches the non-blocking
     * {@link NeoForgeVersionAdapter#requestFullChunkAsync} onto the server tick
     * thread via {@link MinecraftServer#execute(Runnable)}; vanilla's chunk
     * system completes the inner future off-thread.
     */
    private CompletableFuture<Long> loadLiveChunk(int chunkX, int chunkZ, long key) {
        final MinecraftServer server = world.getServer();
        if (server == null) {
            CompletableFuture<Long> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException(
                    "NeoForgeRTPWorld.getChunkAt: ServerLevel has no MinecraftServer (world=" + name + ")"));
            return failed;
        }

        CompletableFuture<Long> existing = inFlightLiveLoads.get(key);
        if (existing != null) return existing;

        CompletableFuture<Long> result = new CompletableFuture<>();
        CompletableFuture<Long> raced = inFlightLiveLoads.putIfAbsent(key, result);
        if (raced != null) return raced;

        final NeoForgeVersionAdapter adapter = NeoForgeVersionAdapterRegistry.peek();
        if (adapter == null) {
            inFlightLiveLoads.remove(key, result);
            result.completeExceptionally(new IllegalStateException(
                    "NeoForgeRTPWorld.loadLiveChunk: NeoForgeVersionAdapter not yet installed"));
            return result;
        }

        final int inFlightNow = liveLoadInFlight.incrementAndGet();
        totalChunkLoads.incrementAndGet();

        // Trace the live-load dispatch end to end at FINER: whether the
        // server.execute runnable runs on the tick thread, what the adapter
        // handed back, and whether the chunk future ultimately resolves.
        final long dispatchStartNanos = System.nanoTime();
        RTP.log(java.util.logging.Level.FINER,
                "[RTP][NeoForge] dispatch SUBMIT world=" + name
                        + " chunk=(" + chunkX + "," + chunkZ + ") inFlight=" + inFlightNow
                        + " serverThread.isSameThread=" + server.isSameThread());

        CompletableFuture<CompletableFuture<ChunkAccess>> bridge = new CompletableFuture<>();
        server.execute(() -> {
            RTP.log(java.util.logging.Level.FINER,
                    "[RTP][NeoForge] dispatch RUN (on tick thread) world=" + name
                            + " chunk=(" + chunkX + "," + chunkZ + ") waitedMs="
                            + ((System.nanoTime() - dispatchStartNanos) / 1_000_000L));
            try {
                CompletableFuture<ChunkAccess> adapterFuture = adapter.requestFullChunkAsync(world, chunkX, chunkZ);
                RTP.log(java.util.logging.Level.FINER,
                        "[RTP][NeoForge] adapter.requestFullChunkAsync RETURNED future="
                                + (adapterFuture == null ? "null" : "id@" + System.identityHashCode(adapterFuture)
                                        + " done=" + adapterFuture.isDone())
                                + " world=" + name + " chunk=(" + chunkX + "," + chunkZ + ")");
                if (adapterFuture != null) {
                    adapterFuture.whenComplete((ca, ex) -> RTP.log(java.util.logging.Level.FINER,
                            "[RTP][NeoForge] adapter future COMPLETE world=" + name
                                    + " chunk=(" + chunkX + "," + chunkZ + ") chunk="
                                    + (ca == null ? "null" : ca.getClass().getSimpleName())
                                    + (ex == null ? "" : " ex=" + ex.getClass().getSimpleName() + ":" + ex.getMessage())
                                    + " elapsedMs=" + ((System.nanoTime() - dispatchStartNanos) / 1_000_000L)));
                }
                bridge.complete(adapterFuture);
            } catch (Throwable t) {
                RTP.log(java.util.logging.Level.WARNING,
                        "[RTP][NeoForge] adapter.requestFullChunkAsync THREW world=" + name
                                + " chunk=(" + chunkX + "," + chunkZ + "): "
                                + t.getClass().getSimpleName() + ": " + t.getMessage());
                bridge.completeExceptionally(t);
            }
        });
        // NO short pre-dispatch timeout here. The bridge only completes once the
        // server.execute runnable actually runs on the tick thread and returns
        // the adapter's (pending) chunk future. A previous bridge.orTimeout(1_500ms)
        // failed the whole load whenever the tick thread was momentarily behind:
        // when the server logs "Running NNNNms behind" (e.g. first-chunk worldgen
        // bursts), the execute runnable cannot start within 1.5s, so the bridge
        // timed out and PregenTask attributed a spurious TimeoutException - L1
        // never promoted and /rtp never landed. The proven Fabric 26.1 carrier has
        // no such short pre-dispatch deadline; the only deadline is the generation
        // bound below. We rely solely on the LIVE_LOAD_DEADLINE_MS deadline applied
        // to the composed chain, which still bounds a genuinely dead dispatch.
        CompletableFuture<ChunkAccess> dispatch =
                bridge.thenCompose(f -> f == null ? CompletableFuture.completedFuture(null) : f);
        dispatch = dispatch.orTimeout(LIVE_LOAD_DEADLINE_MS, TimeUnit.MILLISECONDS);
        dispatch.whenComplete((chunk, error) -> {
            liveLoadInFlight.decrementAndGet();
            inFlightLiveLoads.remove(key, result);
            if (error != null) {
                Throwable cause = (error instanceof CompletionException && error.getCause() != null)
                        ? error.getCause() : error;
                RTP.log(java.util.logging.Level.FINE,
                        "[RTP][NeoForge] dispatch FAILED world=" + name
                                + " chunk=(" + chunkX + "," + chunkZ + ") "
                                + cause.getClass().getSimpleName() + ": " + cause.getMessage()
                                + " elapsedMs=" + ((System.nanoTime() - dispatchStartNanos) / 1_000_000L));
                result.completeExceptionally(cause);
                return;
            }
            if (chunk != null) {
                RTP.log(java.util.logging.Level.FINER,
                        "[RTP][NeoForge] dispatch OK world=" + name
                                + " chunk=(" + chunkX + "," + chunkZ + ") stored="
                                + chunk.getClass().getSimpleName()
                                + " elapsedMs=" + ((System.nanoTime() - dispatchStartNanos) / 1_000_000L));
                // Guard the cache-store block: a throwable here (e.g. the
                // NeoForgeRTPChunk wrapper construction or an anvil-probe evict
                // touching runtime-specific state on 26.x) used to escape into
                // the discarded whenComplete stage, so result.complete(key) below
                // was never reached. The L2->L1 promotion's getChunkAtAsync future
                // then hung forever: the load logged "dispatch OK" but Region's
                // thenAccept never fired and inFlightCalculations stayed pinned at
                // the deficit cap, so the kept (L1) cache never filled. Storing
                // inside a try and always completing result keeps the promotion
                // chain live and surfaces the real cause (S-004) instead of
                // silently stalling.
                try {
                    chunkCache.put(key, chunk);
                    rtpChunkCache.put(key, NeoForgeRTPChunk.forLiveChunk(chunk, world, id, chunkX, chunkZ));
                    anvilProbeSupport.evict(key);
                } catch (Throwable storeEx) {
                    RTP.log(java.util.logging.Level.WARNING,
                            "[RTP][NeoForge] cache-store FAILED world=" + name
                                    + " chunk=(" + chunkX + "," + chunkZ + "): "
                                    + storeEx.getClass().getName() + ": " + storeEx.getMessage(), storeEx);
                }
            } else {
                // S-004: never silently discard. A null chunk here means the
                // version adapter's getChunkFuture(create=true) resolved without
                // a ChunkAccess (e.g. an unwrap miss on this runtime's
                // ChunkResult shape), which would leave the promotion verify with
                // no chunk to adjust. Make it visible rather than indistinguishable
                // from a weak-ref eviction.
                RTP.log(java.util.logging.Level.FINE,
                        "[RTP][NeoForge] loadLiveChunk resolved a NULL ChunkAccess for " + name
                                + " chunk=(" + ((int) (key & 0xffffffffL)) + ","
                                + ((int) (key >> 32)) + ") - adapter.requestFullChunkAsync "
                                + "completed without a chunk (no cache entry stored).");
            }
            result.complete(key);
        });
        return result;
    }

    @Override
    public CompletableFuture<RTPChunk<?>> getOrLoadChunk(int cx, int cz) {
        final long key = ((long) cx & 0xffffffffL) | ((long) cz << 32);
        RTPChunk<?> cached = getCachedChunk(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return getChunkAt(cx, cz).thenCompose(probeKey -> {
            RTPChunk<?> afterProbe = (probeKey != null) ? getCachedChunk(probeKey) : null;
            if (afterProbe != null) {
                return CompletableFuture.completedFuture(afterProbe);
            }
            recordChunkLoadOrigin("unknown");
            return getChunkAtAsync(cx, cz).thenApply(chunkSet -> {
                if (chunkSet == null) return null;
                return getCachedChunk(key);
            });
        }).exceptionally(ex -> {
            Throwable cause = (ex instanceof CompletionException && ex.getCause() != null)
                    ? ex.getCause() : ex;
            if (cause instanceof TimeoutException) {
                RTP.log(java.util.logging.Level.FINE,
                        "[RTP][NeoForge] getOrLoadChunk deadline (" + LIVE_LOAD_DEADLINE_MS
                                + "ms) for " + name + " chunk=(" + cx + "," + cz + ")");
                return null;
            }
            RTP.log(java.util.logging.Level.FINE,
                    "[RTP][NeoForge] getOrLoadChunk failed for " + name
                            + " chunk=(" + cx + "," + cz + "): "
                            + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            return null;
        });
    }

    @Override
    public CompletableFuture<ChunkSet> getChunkAtAsync(int cx, int cz) {
        return getChunkAt(cx, cz).thenApply(key -> {
            CompletableFuture<Boolean> done = new CompletableFuture<>();
            ChunkSet set = new ChunkSet(this, cx, cz,
                Collections.singletonList(CompletableFuture.completedFuture(key)),
                done);
            done.complete(key != null);
            return set;
        });
    }

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
    // ADR-016 anvil pre-filter — column probe
    // ---------------------------------------------------------------------------

    @Override
    public CompletableFuture<ChunkColumnProbe> probeChunkColumn(
            int cx, int cz, int minY, int maxY) {
        if (minY > maxY) return CompletableFuture.completedFuture(null);
        if (!shouldPrefilter(cx, cz)) return CompletableFuture.completedFuture(null);
        ServerLevel level = world;
        if (level == null) return CompletableFuture.completedFuture(null);
        MinecraftServer server = level.getServer();
        if (server == null) return CompletableFuture.completedFuture(null);

        final java.nio.file.Path worldFolder;
        try {
            worldFolder = server.getWorldPath(LevelResource.ROOT);
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.FINE,
                "[RTP] NeoForgeRTPWorld.probeChunkColumn: getWorldPath threw for world="
                    + name + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return CompletableFuture.completedFuture(null);
        }
        final String dim = dimensionRegionSubpath(level);
        final int finalMinY = minY;
        final int finalMaxY = maxY;

        return CompletableFuture.supplyAsync(() -> {
            try {
                java.nio.file.Path regionFile =
                    io.github.dailystruggle.rtp.anvil.AnvilPrefilter
                        .regionFileFor(worldFolder, dim, cx, cz);
                byte[] regionBytes =
                    io.github.dailystruggle.rtp.anvil.AnvilRegionByteCache.get(regionFile);
                if (regionBytes == null) return null;
                int rx = Math.floorMod(cx, 32);
                int rz = Math.floorMod(cz, 32);
                io.github.dailystruggle.rtp.anvil.ColumnProbe probe =
                    io.github.dailystruggle.rtp.anvil.AnvilReader.readColumnProbe(
                        regionBytes, rx, rz, finalMinY, finalMaxY);
                if (probe == null) return null;
                return (ChunkColumnProbe) new AnvilColumnProbeAdapter(probe, cx, cz);
            } catch (Throwable t) {
                RTP.log(java.util.logging.Level.FINE,
                    "[RTP] NeoForgeRTPWorld.probeChunkColumn failed for world=" + name
                        + " chunk=(" + cx + "," + cz + "): "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
                return null;
            }
        }, io.github.dailystruggle.rtp.anvil.AnvilIoPool.get());
    }

    @Override
    public java.util.Map<Long, String> readBiomesInRegionFile(
            int rcx, int rcz, int y) {
        ServerLevel level = world;
        if (level == null) return java.util.Collections.emptyMap();
        MinecraftServer server = level.getServer();
        if (server == null) return java.util.Collections.emptyMap();
        final java.nio.file.Path worldFolder;
        try {
            worldFolder = server.getWorldPath(LevelResource.ROOT);
        } catch (Throwable t) {
            return java.util.Collections.emptyMap();
        }
        final String dim = dimensionRegionSubpath(level);
        try {
            java.nio.file.Path regionFile =
                io.github.dailystruggle.rtp.anvil.AnvilPrefilter
                    .regionFileFor(worldFolder, dim, rcx << 5, rcz << 5);
            if (regionFile == null) return java.util.Collections.emptyMap();
            byte[] regionBytes =
                io.github.dailystruggle.rtp.anvil.AnvilRegionByteCache.get(regionFile);
            if (regionBytes == null) return java.util.Collections.emptyMap();
            java.util.HashMap<Long, String> out = new java.util.HashMap<>(1024);
            for (int rx = 0; rx < 32; rx++) {
                for (int rz = 0; rz < 32; rz++) {
                    try {
                        io.github.dailystruggle.rtp.anvil.AnvilChunkView view =
                            io.github.dailystruggle.rtp.anvil.AnvilReader.readChunkView(
                                regionBytes, rx, rz);
                        if (view == null) continue;
                        String raw = view.getBiomeAt(8, y, 8);
                        if (raw == null) continue;
                        String canonical = canonicaliseBiome(raw);
                        if (canonical == null || canonical.isEmpty()) continue;
                        int cx = (rcx << 5) | rx;
                        int cz = (rcz << 5) | rz;
                        long key = ((long) cx << 32) | (cz & 0xFFFF_FFFFL);
                        out.put(key, canonical);
                    } catch (Throwable ignored) {
                        // chunk not present / unreadable; skip silently.
                    }
                }
            }
            return out;
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.FINE,
                "[RTP] NeoForgeRTPWorld.readBiomesInRegionFile failed for world=" + name
                    + " region=(" + rcx + "," + rcz + "): "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return java.util.Collections.emptyMap();
        }
    }

    private static String canonicaliseBiome(String name) {
        if (name == null) return null;
        String up = name.toUpperCase(java.util.Locale.ROOT);
        if (up.startsWith("MINECRAFT:")) return up.substring("MINECRAFT:".length());
        return up;
    }

    private boolean shouldPrefilter(int cx, int cz) {
        if (world == null) return false;
        try {
            if (isChunkLoaded(cx, cz)) return false;
        } catch (Throwable ignored) {
            return false;
        }
        try {
            @SuppressWarnings("unchecked")
            ConfigParser<SafetyKeys> safety =
                (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
            if (safety == null) return true;
            Object raw = safety.getConfigValue(SafetyKeys.anvilPrefilterEnabled, Boolean.TRUE);
            if (raw instanceof Boolean b) return b;
            if (raw != null) return Boolean.parseBoolean(raw.toString());
            return true;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static String dimensionRegionSubpath(ServerLevel level) {
        if (level == null) return "";
        try {
            Object loc = io.github.dailystruggle.rtp.neoforge.tools.NeoForgeResourceIds
                    .location(level.dimension());
            String ns = io.github.dailystruggle.rtp.neoforge.tools.NeoForgeResourceIds.namespace(loc);
            String path = io.github.dailystruggle.rtp.neoforge.tools.NeoForgeResourceIds.path(loc);
            if (ns == null || path == null) return "";
            if ("minecraft".equals(ns)) {
                if ("overworld".equals(path)) return "";
                if ("the_nether".equals(path)) return "DIM-1";
                if ("the_end".equals(path))    return "DIM1";
            }
            return "dimensions/" + ns + "/" + path;
        } catch (Throwable ignored) {
            return "";
        }
    }

    @Override
    public boolean isChunkGenerated(int cx, int cz) {
        try {
            ServerChunkCache cache = world.getChunkSource();
            if (cache != null && cache.hasChunk(cx, cz)) return true;
        } catch (Throwable ignored) {
            // Fall through to the data-side probe.
        }
        ServerLevel level = world;
        if (level == null) return true;
        MinecraftServer server = level.getServer();
        if (server == null) return true;

        final java.nio.file.Path worldFolder;
        try {
            worldFolder = server.getWorldPath(LevelResource.ROOT);
        } catch (Throwable t) {
            return true;
        }
        try {
            String dim = dimensionRegionSubpath(level);
            java.nio.file.Path regionFile =
                io.github.dailystruggle.rtp.anvil.AnvilPrefilter
                    .regionFileFor(worldFolder, dim, cx, cz);
            if (regionFile == null || !java.nio.file.Files.exists(regionFile)) {
                return false;
            }
            return io.github.dailystruggle.rtp.anvil.AnvilRegionOccupancyCache
                .isOccupied(regionFile, cx, cz);
        } catch (Throwable t) {
            return true;
        }
    }

    // ---------------------------------------------------------------------------
    // Chunk-ticket lifecycle (S-002 non-persistent tickets via the adapter)
    // ---------------------------------------------------------------------------

    @Override
    protected CompletableFuture<Void> setForceLoadedImpl(int cx, int cz, boolean forceLoad) {
        final MinecraftServer server = world.getServer();
        if (server == null) {
            return CompletableFuture.completedFuture(null);
        }
        final NeoForgeVersionAdapter adapter = NeoForgeVersionAdapterRegistry.peek();
        if (adapter == null) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException(
                    "NeoForgeRTPWorld.setForceLoadedImpl: NeoForgeVersionAdapter not yet installed"
                            + " (world=" + name + " chunk=(" + cx + "," + cz + "))"));
            return failed;
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                CompletableFuture<Void> f = forceLoad
                        ? adapter.applyTicket(world, cx, cz)
                        : adapter.releaseTicket(world, cx, cz);
                f.getNow(null);
                return null;
            } catch (Throwable t) {
                RTP.log(java.util.logging.Level.WARNING,
                        "[RTP] NeoForgeRTPWorld.setForceLoadedImpl failed for world=" + name
                                + " chunk=(" + cx + "," + cz + ") forceLoad=" + forceLoad
                                + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
                return null;
            }
        }, server);
    }

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

    @Override
    public RTPChunk<?> getCachedChunk(long key) {
        NeoForgeRTPChunk wrapper = rtpChunkCache.get(key);
        if (wrapper != null) {
            anvilProbeSupport.evict(key);
            return wrapper;
        }
        ChunkAccess chunk = chunkCache.get(key);
        if (chunk != null) {
            int builtCx = (int) (key & 0xffffffffL);
            int builtCz = (int) (key >> 32);
            NeoForgeRTPChunk built = NeoForgeRTPChunk.forLiveChunk(chunk, world, id, builtCx, builtCz);
            rtpChunkCache.put(key, built);
            anvilProbeSupport.evict(key);
            return built;
        }
        io.github.dailystruggle.rtp.anvil.AnvilChunkView view = anvilProbeSupport.takeCached(key);
        if (view != null) {
            int cx = (int) (key & 0xffffffffL);
            int cz = (int) (key >> 32);
            java.util.Set<String> reconciled =
                    io.github.dailystruggle.rtp.common.anvil.PaletteNormalizer
                            .reconcileAll(currentUnsafeBlocks());
            return new NeoForgeRTPChunk(view, cx, cz, id, reconciled);
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
            anvilProbeSupport.evict(key);
        });
    }

    @Override
    public void forgetChunks() {
        chunkTickets.forEach((key, count) -> {
            int cx = (int) (key & 0xffffffffL);
            int cz = (int) (key >> 32);
            while (count.get() > 0) {
                setForceLoaded(cx, cz, false);
            }
        });
        chunkCache.clear();
        rtpChunkCache.clear();
        anvilProbeSupport.clear();
    }

    @Override
    public int getCacheSize() {
        return chunkCache.size();
    }

    // ---------------------------------------------------------------------------
    // Read-only world data
    // ---------------------------------------------------------------------------

    @Override
    public String getBiome(int x, int y, int z) {
        try {
            var holder = world.getBiome(new BlockPos(x, y, z));
            return holder.unwrapKey()
                .map(k -> {
                    String s = io.github.dailystruggle.rtp.neoforge.tools.NeoForgeResourceIds.locationString(k);
                    return s == null ? "" : s.toUpperCase();
                })
                .orElse("");
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public boolean isInactive() {
        return world == null || world.getServer() == null;
    }

    /**
     * Native bulk block write (ADR-058). Parses each {@link io.github.dailystruggle.rtp.api.platform.BlockDelta}
     * token through the vanilla {@code BlockStateParser} (the same parser {@code /setblock} uses,
     * so the in-repo Sponge decoder's canonical {@code namespace:id[props]} tokens are accepted)
     * and writes the resulting {@code BlockState} via {@link ServerLevel#setBlock}.
     *
     * <p>Invoked on the server thread by the caller (the teleport pipeline dispatches the paste
     * through {@code RTP.scheduler.runTask(location, ...)}). Performs block writes only and never
     * loads chunks (S-005). A single undecodable token is counted as a skip and audited, never
     * thrown (S-004). Parity with {@code V26_2_R1FabricRTPWorld#setBlocks}.
     *
     * @return the number of blocks successfully placed
     */
    @Override
    public int setBlocks(java.util.List<io.github.dailystruggle.rtp.api.platform.BlockDelta> blocks) {
        ServerLevel level = world;
        if (level == null || blocks == null || blocks.isEmpty()) return 0;
        net.minecraft.core.HolderLookup<net.minecraft.world.level.block.Block> blockLookup;
        try {
            blockLookup = level.registryAccess()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK);
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.WARNING,
                    "[RTP][NeoForge] setBlocks could not resolve the block registry lookup: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            return 0;
        }
        int placed = 0;
        String firstFailedToken = null;
        for (io.github.dailystruggle.rtp.api.platform.BlockDelta delta : blocks) {
            try {
                net.minecraft.world.level.block.state.BlockState state =
                        net.minecraft.commands.arguments.blocks.BlockStateParser
                                .parseForBlock(blockLookup, delta.token(), false)
                                .blockState();
                // flags=2 (Block.UPDATE_CLIENTS): send to clients without triggering neighbour
                // physics updates — a bulk world rewrite, not a player edit.
                level.setBlock(new BlockPos(delta.x(), delta.y(), delta.z()), state, 2);
                placed++;
            } catch (Throwable e) {
                if (firstFailedToken == null) firstFailedToken = delta.token();
            }
        }
        if (firstFailedToken != null) {
            RTP.log(java.util.logging.Level.WARNING,
                    "[RTP][NeoForge] setBlocks placed " + placed + " of " + blocks.size()
                            + " block(s); at least one block-state token could not be parsed and "
                            + "was skipped (S-004). First failure: '" + firstFailedToken + "'.");
        }
        return placed;
    }

    @Override
    public void platform(io.github.dailystruggle.rtp.api.world.RTPLocation location) {
        try {
            // intentional no-op until palette parity lands (parity with Fabric)
        } finally {
            if (location != null && location.getReservation() != null) {
                location.getReservation().close();
            }
        }
    }

    @Override
    public void save() {
        // Intentional no-op (parity with Paper/Folia/Fabric overrides).
    }

    @Override
    public int getMaxHeight() {
        try {
            return world.getMaxBuildHeight();
        } catch (Throwable t) {
            return 320;
        }
    }

    @Override
    public int getMinHeight() {
        try {
            return world.getMinBuildHeight();
        } catch (Throwable t) {
            return -64;
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

    public @Nullable ChunkAccess peekChunk(long key) {
        return chunkCache.get(key);
    }
}
