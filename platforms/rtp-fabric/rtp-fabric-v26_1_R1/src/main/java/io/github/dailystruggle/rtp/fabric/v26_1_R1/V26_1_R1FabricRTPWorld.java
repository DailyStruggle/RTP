package io.github.dailystruggle.rtp.fabric.v26_1_R1;

import io.github.dailystruggle.rtp.api.world.ChunkColumnProbe;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.anvil.AnvilColumnProbeAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * MC 26.1.2 RTPWorld wrapper.
 *
 * <p>Lives in {@code rtp-fabric-v26_1_R1} (Loom unobfuscated, no intermediary
 * remapping) so its compiled bytecode references {@code net.minecraft.server.level.ServerLevel}
 * and friends by Mojang names — which is the actual class layout on a deobfuscated
 * 26.1.2 runtime. Common's {@code FabricRTPWorld} cannot link there because its
 * constant pool contains intermediary aliases ({@code class_3218} etc.) that
 * don't exist on that runtime.
 *
 * <p>Includes an ADR-016 anvil pre-filter override of {@code probeChunkColumn}
 * (parity with {@code FabricRTPWorldUnobf}) so {@code ScanTask} / {@code PregenTask}
 * / {@code QueueTask} can cheaply reject ungenerated chunks via {@code .mca}
 * reads instead of falling back to {@code runFullLoadPath} and tripping the
 * 2s chunk-ticket budget.
 */
public final class V26_1_R1FabricRTPWorld extends RTPWorld<ServerLevel> {

    private final String name;
    private final UUID id;

    /** Cache of recently-loaded chunks keyed by {@code (cz << 32) | cx}. */
    private final ConcurrentHashMap<Long, V26_1_R1FabricRTPChunk> chunkCache = new ConcurrentHashMap<>();

    public V26_1_R1FabricRTPWorld(ServerLevel level) {
        super(level);
        this.name = resolveDimensionName(level);
        // Stable UUID derived from the dimension name (matches FabricRTPWorld convention).
        this.id = UUID.nameUUIDFromBytes(("rtp-fabric-world:" + this.name).getBytes());
    }

    private static String resolveDimensionName(ServerLevel level) {
        // On MC 26.1.2 (verified via javap on the deobf jar) Level exposes its
        // ResourceKey<Level> via dimension(); ResourceKey was renamed
        // location() -> identifier() on this MC release.
        try {
            return level.dimension().identifier().toString();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    @Override public String name() { return name; }
    @Override public UUID id() { return id; }

    private static long key(int cx, int cz) {
        return ((long) cx & 0xffffffffL) | ((long) cz << 32);
    }

    /**
     * Loads {@code (cx, cz)} at {@code ChunkStatus.FULL} and caches a
     * {@link V26_1_R1FabricRTPChunk} wrapper, resolving the future with the
     * chunk key.
     *
     * <p><b>Non-blocking dispatch (rtp-fabric-ADR-008).</b> The request is
     * dispatched onto the server tick thread via {@link MinecraftServer#submit},
     * but it calls vanilla's <i>non-blocking</i> generation entry point
     * {@code ServerChunkCache#getChunkFuture(cx, cz, FULL, /*create=*&#47;true)}
     * and chains on the returned future rather than blocking. The previous
     * implementation called the synchronous-blocking
     * {@code ServerLevel#getChunk(cx, cz, FULL, /*load=*&#47;true)} from inside
     * a {@code submit()} task — when the calling thread is the tick thread
     * (which it always is here) it ends up driving its own task queue from
     * inside one of its own tasks, deadlocking against any other queued task
     * in the chunk-generation dependency graph. On the 26.x chunk system that
     * manifested as chunks never finishing generation (the "chunks are not
     * getting loaded" symptom). {@code create=true} makes vanilla self-issue
     * the transient generation ticket, so no extra ticket is required here.</p>
     */
    @Override
    public CompletableFuture<Long> getChunkAt(int cx, int cz) {
        ServerLevel level = world;
        if (level == null) return CompletableFuture.completedFuture(null);
        MinecraftServer server = level.getServer();
        if (server == null) return CompletableFuture.completedFuture(null);
        long k = key(cx, cz);
        // Cache hit short-circuits — keeps getOrLoadChunk's cached path cheap.
        if (chunkCache.containsKey(k)) {
            return CompletableFuture.completedFuture(k);
        }
        // (1) Dispatch the non-blocking generation request on the tick thread.
        //     server.submit returns CompletableFuture<CompletableFuture<...>>;
        //     thenCompose flattens to the inner future that vanilla completes
        //     off-thread when generation finishes.
        return server.submit(() -> requestChunkFuture(level, cx, cz))
                .thenCompose(inner -> inner == null
                        ? CompletableFuture.completedFuture((ChunkAccess) null)
                        : inner)
                .thenApply(ca -> {
                    if (ca == null) return null;
                    chunkCache.put(k, new V26_1_R1FabricRTPChunk(ca, level, id));
                    return k;
                })
                .exceptionally(t -> {
                    RTP.log(Level.WARNING,
                            "[RTP][v26_1_R1] getChunkAt(" + cx + "," + cz + ") failed for "
                                    + name + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    return null;
                });
    }

    /** Cached resolver for {@code ServerChunkCache#getChunkFuture(int,int,ChunkStatus,boolean)}. */
    private static volatile java.lang.reflect.Method GET_CHUNK_FUTURE_METHOD;

    /**
     * Invoke vanilla's non-blocking {@code getChunkFuture(cx, cz, FULL, true)}
     * and adapt the result to a {@code CompletableFuture<ChunkAccess>}. MUST be
     * called on the server tick thread. The vanilla return type drifted
     * ({@code Either<ChunkAccess,...>} on old lines, {@code ChunkResult<ChunkAccess>}
     * on 1.20.5+), so the inner value is unwrapped reflectively (see
     * {@link #unwrapChunk}). Returns {@code null} (an absent future) on a
     * resolution failure so the caller attributes it through the standard
     * {@code FailTypes.nullChunk} path (S-004 — no silent discard).
     */
    @SuppressWarnings("unchecked")
    private CompletableFuture<ChunkAccess> requestChunkFuture(ServerLevel level, int cx, int cz) {
        try {
            ServerChunkCache cache = level.getChunkSource();
            java.lang.reflect.Method getter = resolveGetChunkFutureMethod(cache);
            Object raw = getter.invoke(cache, cx, cz, ChunkStatus.FULL, /*create=*/ true);
            if (!(raw instanceof CompletableFuture<?> cf)) {
                RTP.log(Level.WARNING,
                        "[RTP][v26_1_R1] getChunkFuture returned non-CompletableFuture for chunk=("
                                + cx + "," + cz + "): " + (raw == null ? "null" : raw.getClass()));
                return null;
            }
            return ((CompletableFuture<Object>) cf).thenApply(V26_1_R1FabricRTPWorld::unwrapChunk);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][v26_1_R1] requestChunkFuture(" + cx + "," + cz + ") failed for "
                            + name + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    private static java.lang.reflect.Method resolveGetChunkFutureMethod(ServerChunkCache cache)
            throws ReflectiveOperationException {
        java.lang.reflect.Method cached = GET_CHUNK_FUTURE_METHOD;
        if (cached != null) return cached;
        synchronized (V26_1_R1FabricRTPWorld.class) {
            cached = GET_CHUNK_FUTURE_METHOD;
            if (cached != null) return cached;
            java.lang.reflect.Method found = null;
            for (Class<?> c = cache.getClass(); c != null && found == null; c = c.getSuperclass()) {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (!CompletableFuture.class.isAssignableFrom(m.getReturnType())) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length != 4) continue;
                    if (p[0] != int.class || p[1] != int.class) continue;
                    if (p[2] != ChunkStatus.class) continue;
                    if (p[3] != boolean.class) continue;
                    m.setAccessible(true);
                    found = m;
                    break;
                }
            }
            if (found == null) {
                throw new NoSuchMethodException(
                        "ServerChunkCache#getChunkFuture(int,int,ChunkStatus,boolean) not found on "
                                + cache.getClass().getName());
            }
            GET_CHUNK_FUTURE_METHOD = found;
            return found;
        }
    }

    /**
     * Unwrap the {@link ChunkAccess} from vanilla's {@code getChunkFuture}
     * result, tolerant of both the {@code ChunkResult<ChunkAccess>} (1.20.5+)
     * and legacy {@code Either<ChunkAccess,...>} shapes.
     */
    private static ChunkAccess unwrapChunk(Object result) {
        if (result == null) return null;
        if (result instanceof ChunkAccess ca) return ca;
        // ChunkResult<ChunkAccess> (1.20.5+): orElse(null).
        try {
            java.lang.reflect.Method orElse = result.getClass().getMethod("orElse", Object.class);
            Object v = orElse.invoke(result, (Object) null);
            if (v instanceof ChunkAccess ca) return ca;
        } catch (Throwable ignored) {
            // fall through to the Either shape
        }
        // Either<ChunkAccess,...> (<=1.20.4): left().orElse(null).
        try {
            java.lang.reflect.Method left = result.getClass().getMethod("left");
            Object opt = left.invoke(result);
            if (opt != null) {
                java.lang.reflect.Method orElse = opt.getClass().getMethod("orElse", Object.class);
                Object v = orElse.invoke(opt, (Object) null);
                if (v instanceof ChunkAccess ca) return ca;
            }
        } catch (Throwable ignored) {
            // no recognised shape
        }
        return null;
    }

    /**
     * Single-chunk {@link ChunkSet} mirroring common's {@code FabricRTPWorld#getChunkAtAsync}.
     * The {@code done} future is completed on the same callback so consumers
     * attaching via {@code thenCombine}/{@code allOf} are released and their
     * dependent CF graph becomes GC-eligible.
     */
    @Override
    public CompletableFuture<ChunkSet> getChunkAtAsync(int cx, int cz) {
        return getChunkAt(cx, cz).thenApply(k -> {
            CompletableFuture<Boolean> done = new CompletableFuture<>();
            ChunkSet set = new ChunkSet(this, cx, cz,
                    Collections.singletonList(CompletableFuture.completedFuture(k)),
                    done);
            done.complete(k != null);
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

    /**
     * Apply or release a non-persistent RTP-owned chunk ticket via the
     * version adapter (see {@link V26_1_R1FabricVersionAdapter#applyTicket}).
     *
     * <p>Replaces the previous {@code level.setChunkForced(cx, cz, forceLoad)}
     * call, which writes through to {@code level.dat#ForcedChunks} and would
     * persist across an unclean shutdown (S-002 hazard). Tickets issued via
     * {@code DistanceManager.addTicketWithRadius} live only for the JVM
     * lifetime and never touch disk.</p>
     */
    @Override
    protected CompletableFuture<Void> setForceLoadedImpl(int cx, int cz, boolean forceLoad) {
        ServerLevel level = world;
        if (level == null) return CompletableFuture.completedFuture(null);
        MinecraftServer server = level.getServer();
        if (server == null) return CompletableFuture.completedFuture(null);
        io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter adapter =
                io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry.peek();
        if (adapter == null) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException(
                    "V26_1_R1FabricRTPWorld.setForceLoadedImpl: FabricVersionAdapter not yet installed"
                            + " (world=" + name + " chunk=(" + cx + "," + cz + "))"));
            return failed;
        }
        return server.submit(() -> {
            try {
                io.github.dailystruggle.rtp.fabric.version.RTPLevelHandle levelHandle =
                        io.github.dailystruggle.rtp.fabric.version.RTPLevelHandle.of(level);
                CompletableFuture<Void> f = forceLoad
                        ? adapter.applyTicket(levelHandle, cx, cz)
                        : adapter.releaseTicket(levelHandle, cx, cz);
                f.getNow(null);
            } catch (Throwable t) {
                RTP.log(Level.WARNING,
                        "[RTP][v26_1_R1] setForceLoadedImpl(" + cx + "," + cz + "," + forceLoad
                                + ") failed for " + name + ": "
                                + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Integer> getServerForceLoadedCount() {
        // 26.1.2's ServerLevel does not expose a public getForcedChunks()
        // accessor. Since RTP now uses non-persistent DistanceManager tickets
        // (not setChunkForced), the relevant count is the plugin's own
        // active-ticket count tracked by RTPWorld#chunkTickets.
        return CompletableFuture.completedFuture((int) numForceLoaded());
    }

    @Override
    public RTPChunk<?> getCachedChunk(long key) {
        return chunkCache.get(key);
    }

    @Override
    public void keepChunkAt(int cx, int cz) {
        setForceLoaded(cx, cz, true);
    }

    @Override
    public void forgetChunkAt(int cx, int cz) {
        setForceLoaded(cx, cz, false);
        // Drop our cached wrapper so a subsequent load re-enters the server submit
        // path and observes any state changes that happened while unkept.
        chunkCache.remove(key(cx, cz));
    }

    @Override
    public void forgetChunks() {
        chunkCache.clear();
    }

    // ---------------------------------------------------------------------------
    // ADR-016 anvil pre-filter -- column probe (parity with FabricRTPWorldUnobf)
    //
    // Without this override, RTPWorld's default probeChunkColumn returns a
    // completed-null future, which the ScanTask / PregenTask / QueueTask
    // probe-first fast paths treat as UNKNOWN and fall back to runFullLoadPath.
    // On a fresh 26.1 world that means every prescan candidate force-loads its
    // chunk, blows past the 2s ticket-apply budget, and emits the symptom in
    // the bug report ("Chunk ticket application did not complete within 2s ...
    // rejecting candidate"). Mirroring the unobf carrier's implementation
    // gates the cheap reject on .mca-read-only data and reserves chunk loads
    // for genuine accept candidates.
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
            RTP.log(Level.FINE,
                    "[RTP][v26_1_R1] probeChunkColumn: getWorldPath threw for world="
                            + name + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return CompletableFuture.completedFuture(null);
        }
        final String dim = dimensionRegionSubpath(worldFolder, level);
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
                RTP.log(Level.FINE,
                        "[RTP][v26_1_R1] probeChunkColumn failed for world=" + name
                                + " chunk=(" + cx + "," + cz + "): "
                                + t.getClass().getSimpleName() + ": " + t.getMessage());
                return null;
            }
        }, io.github.dailystruggle.rtp.anvil.AnvilIoPool.get());
    }

    /**
     * {@inheritDoc}
     *
     * <p>MC 26.1 mirror of {@code BukkitRTPWorld#readBiomesInRegionFile}.
     * Uses the two-arg {@code dimensionRegionSubpath(worldFolder, level)}
     * to handle MC 26.1's unified {@code dimensions/<ns>/<path>/region/}
     * layout alongside the legacy roots. S-005: no tick-thread chunk I/O.
     */
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
        final String dim = dimensionRegionSubpath(worldFolder, level);
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
            RTP.log(Level.FINE,
                    "[RTP][v26_1_R1] readBiomesInRegionFile failed for world=" + name
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

    // ---------------------------------------------------------------------------
    // isChunkGenerated -- DATA-side region-file header probe.
    //
    // We must answer "will PRESCAN's anvil probe see anything for this chunk?"
    // The previous server-side approach (ServerChunkCache#hasChunk +
    // reflective ChunkMap#read) consulted live engine state, which on a fresh
    // 26.1 world disagreed with what's actually on disk: many candidates were
    // reported generated, prescan then returned null from the .mca read, and
    // the pipeline fell back to runFullLoadPath one-by-one (2s ticket-budget
    // warnings).
    //
    // This implementation consults the exact same bytes PRESCAN reads:
    // AnvilPrefilter.regionFileFor + AnvilRegionByteCache.get + the Anvil
    // location-table entry at index (cx&31) + ((cz&31) << 5). A zero
    // sectorOffset/sectorCount means the chunk slot is empty -> not generated,
    // so GENSCAN can route it through runFullLoadPath up front and PRESCAN
    // is never asked about it. No JVM reflection, no engine state.
    // ---------------------------------------------------------------------------
    @Override
    public boolean isChunkGenerated(int cx, int cz) {
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
            String dim = dimensionRegionSubpath(worldFolder, level);
            java.nio.file.Path regionFile =
                    io.github.dailystruggle.rtp.anvil.AnvilPrefilter
                            .regionFileFor(worldFolder, dim, cx, cz);
            // Missing region file = no chunk on disk for this 32x32 tile.
            if (regionFile == null || !java.nio.file.Files.exists(regionFile)) {
                return false;
            }
            // Binned fast path: a 1024-bit per-region-file occupancy bitmap
            // amortises GENSCAN's per-chunk slot lookup across the entire 32x32
            // tile. Built once per region file at first touch, reused until the
            // .mca mtime advances. Avoids reparsing the location table on every
            // sibling-chunk call.
            return io.github.dailystruggle.rtp.anvil.AnvilRegionOccupancyCache
                    .isOccupied(regionFile, cx, cz);
        } catch (Throwable t) {
            RTP.log(Level.FINE,
                    "[RTP][v26_1_R1] isChunkGenerated anvil probe failed for world="
                            + name + " chunk=(" + cx + "," + cz + "): "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            // Conservative default per RTPWorld javadoc.
            return true;
        }
    }

    /**
     * Applicability gate for the column probe -- parity with
     * {@code FabricRTPWorldUnobf#shouldPrefilter}. Returns {@code true} only
     * when the chunk is not currently loaded and
     * {@code SafetyKeys.anvilPrefilterEnabled} is truthy. Any failure defaults
     * to "skip prefilter" so the pipeline always has a safe fall-through.
     */
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

    /**
     * Map a {@link ServerLevel}'s dimension {@link Identifier} to the list of
     * candidate on-disk region subdirectories used by vanilla, in priority
     * order. MC 26.1 unified all dimensions under
     * {@code <world>/dimensions/<ns>/<path>/region/}; pre-26 layouts still use
     * the legacy roots ({@code "" for overworld, "DIM-1"/"DIM1"} for the
     * nether/end). We return both so the probe can pick whichever exists on
     * disk -- no need to know the engine's version a priori.
     */
    private static java.util.List<String> dimensionRegionSubpaths(ServerLevel level) {
        if (level == null) return java.util.List.of("");
        try {
            Identifier loc = level.dimension().identifier();
            String ns = loc.getNamespace();
            String path = loc.getPath();
            String unified = "dimensions/" + ns + "/" + path;
            if ("minecraft".equals(ns)) {
                if ("overworld".equals(path))  return java.util.List.of(unified, "");
                if ("the_nether".equals(path)) return java.util.List.of(unified, "DIM-1");
                if ("the_end".equals(path))    return java.util.List.of(unified, "DIM1");
            }
            return java.util.List.of(unified);
        } catch (Throwable ignored) {
            return java.util.List.of("");
        }
    }

    /**
     * Resolve the actual on-disk region subpath for {@code level}, preferring
     * the first candidate from {@link #dimensionRegionSubpaths} whose
     * {@code region/} directory exists. Falls back to the first candidate so
     * callers always have a path to probe (and probes naturally miss for
     * ungenerated tiles).
     */
    private static String dimensionRegionSubpath(java.nio.file.Path worldFolder, ServerLevel level) {
        java.util.List<String> candidates = dimensionRegionSubpaths(level);
        for (String c : candidates) {
            java.nio.file.Path regionDir = c.isEmpty()
                    ? worldFolder.resolve("region")
                    : worldFolder.resolve(c).resolve("region");
            try {
                if (java.nio.file.Files.isDirectory(regionDir)) return c;
            } catch (Throwable ignored) {
                // try next
            }
        }
        return candidates.get(0);
    }

    @Override
    public String getBiome(int x, int y, int z) {
        ServerLevel level = world;
        if (level == null) return "";
        try {
            Holder<Biome> holder = level.getBiome(new BlockPos(x, y, z));
            // Holder#unwrapKey()'s ResourceKey on 26.1.2 exposes identifier() (was location()).
            // Normalise through PaletteIdentifierNormalizer to match the form ScanTask /
            // FabricServerAccessor.defaultBiomesFor / AnvilColumnProbeAdapter use
            // (namespace-stripped, upper-cased). Without normalisation FULLSCAN's
            // physical-biome check compares "MINECRAFT:PLAINS" against the
            // namespace-stripped "PLAINS" in defaultBiomes and rejects every candidate.
            String raw = holder.unwrapKey()
                    .map(k -> {
                        try { return k.identifier().toString(); }
                        catch (Throwable t) { return ""; }
                    })
                    .orElseGet(() -> {
                        // Fallback: registry reverse lookup via BuiltInRegistries.
                        try {
                            Identifier id = level.registryAccess()
                                    .lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
                                    .getKey(holder.value());
                            return (id == null) ? "" : id.toString();
                        } catch (Throwable t) {
                            return "";
                        }
                    });
            String n = io.github.dailystruggle.rtp.api.configuration
                    .PaletteIdentifierNormalizer.normalize(raw);
            return n == null ? "" : n;
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public void platform(RTPLocation location) {
        // No-op — safety platform placement is a follow-up phase. Pipeline still
        // works without it (commit-time recheck remains authoritative).
    }

    /**
     * ADR-058 — region-schematic paster. The decode/plan half is platform-neutral
     * ({@link io.github.dailystruggle.rtp.api.schematic.WorldBlockSchematicPaster}); the native
     * block writes route back through {@link #setBlocks(java.util.List)} below. Shared instance —
     * the paster is stateless.
     */
    private static final io.github.dailystruggle.rtp.api.schematic.SchematicPaster SCHEMATIC_PASTER =
            new io.github.dailystruggle.rtp.api.schematic.WorldBlockSchematicPaster();

    @Override
    public io.github.dailystruggle.rtp.api.schematic.SchematicPaster schematicPaster() {
        return SCHEMATIC_PASTER;
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
     * thrown (S-004).
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
            RTP.log(Level.WARNING, "[RTP][V26_1_R1] setBlocks could not resolve the block registry "
                    + "lookup: " + t.getClass().getSimpleName() + ": " + t.getMessage());
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
            RTP.log(Level.WARNING, "[RTP][V26_1_R1] setBlocks placed " + placed + " of "
                    + blocks.size() + " block(s); at least one block-state token could not be "
                    + "parsed and was skipped (S-004). First failure: '" + firstFailedToken + "'.");
        }
        return placed;
    }

    @Override
    public boolean isInactive() {
        return false;
    }

    @Override
    public void save() {
        // Server saves on its own cadence; explicit per-world save is not
        // required for /rtp's pipeline.
    }

    @Override
    public int getMaxHeight() {
        try {
            // LevelHeightAccessor#getMaxY() on MC 26.1.2 — inclusive max build Y.
            return world.getMaxY();
        } catch (Throwable t) {
            return 320;
        }
    }

    @Override
    public int getMinHeight() {
        try {
            return world.getMinY();
        } catch (Throwable t) {
            return -64;
        }
    }

    @Override
    public int getCacheSize() {
        return chunkCache.size();
    }

    @Override
    public long getSeed() {
        try {
            return world.getSeed();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** Fabric-internal accessor; not part of the platform-neutral API. */
    public ServerLevel level() {
        return world;
    }
}
