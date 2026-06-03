package io.github.dailystruggle.rtp.neoforge.world;

import io.github.dailystruggle.rtp.api.world.ChunkColumnProbe;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
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

import java.lang.ref.WeakReference;
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

    private final ConcurrentHashMap<Long, WeakReference<ChunkAccess>> chunkCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, WeakReference<NeoForgeRTPChunk>> rtpChunkCache = new ConcurrentHashMap<>();
    private final io.github.dailystruggle.rtp.anvil.AnvilProbeSupport anvilProbeSupport =
            new io.github.dailystruggle.rtp.anvil.AnvilProbeSupport();

    public NeoForgeRTPWorld(@NotNull ServerLevel level) {
        super(level);
        this.name = io.github.dailystruggle.rtp.neoforge.tools.NeoForgeResourceIds
                .locationString(level.dimension());
        this.id = UUID.nameUUIDFromBytes(this.name.getBytes(StandardCharsets.UTF_8));
    }

    private static @NotNull io.github.dailystruggle.rtp.api.schematic.SchematicPaster schematicPaster =
            io.github.dailystruggle.rtp.api.schematic.NoOpSchematicPaster.INSTANCE;

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
            ConfigParser<SafetyKeys> safety =
                    (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
            if (safety == null) return java.util.Collections.emptySet();
            Object raw = safety.getConfigValue(SafetyKeys.unsafeBlocks, new java.util.ArrayList<>());
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

        liveLoadInFlight.incrementAndGet();
        totalChunkLoads.incrementAndGet();

        CompletableFuture<CompletableFuture<ChunkAccess>> bridge = new CompletableFuture<>();
        server.execute(() -> {
            try {
                bridge.complete(adapter.requestFullChunkAsync(world, chunkX, chunkZ));
            } catch (Throwable t) {
                bridge.completeExceptionally(t);
            }
        });
        bridge.orTimeout(1_500L, TimeUnit.MILLISECONDS);
        CompletableFuture<ChunkAccess> dispatch =
                bridge.thenCompose(f -> f == null ? CompletableFuture.completedFuture(null) : f);
        dispatch = dispatch.orTimeout(LIVE_LOAD_DEADLINE_MS, TimeUnit.MILLISECONDS);
        dispatch.whenComplete((chunk, error) -> {
            liveLoadInFlight.decrementAndGet();
            inFlightLiveLoads.remove(key, result);
            if (error != null) {
                Throwable cause = (error instanceof CompletionException && error.getCause() != null)
                        ? error.getCause() : error;
                result.completeExceptionally(cause);
                return;
            }
            if (chunk != null) {
                chunkCache.put(key, new WeakReference<>(chunk));
                rtpChunkCache.put(key, new WeakReference<>(new NeoForgeRTPChunk(chunk, world, id)));
                anvilProbeSupport.evict(key);
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
        WeakReference<NeoForgeRTPChunk> rtpRef = rtpChunkCache.get(key);
        if (rtpRef != null) {
            NeoForgeRTPChunk wrapper = rtpRef.get();
            if (wrapper != null) {
                anvilProbeSupport.evict(key);
                return wrapper;
            }
            rtpChunkCache.remove(key);
        }
        WeakReference<ChunkAccess> ref = chunkCache.get(key);
        if (ref != null) {
            ChunkAccess chunk = ref.get();
            if (chunk != null) {
                NeoForgeRTPChunk wrapper = new NeoForgeRTPChunk(chunk, world, id);
                rtpChunkCache.put(key, new WeakReference<>(wrapper));
                anvilProbeSupport.evict(key);
                return wrapper;
            }
            chunkCache.remove(key);
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
        WeakReference<ChunkAccess> ref = chunkCache.get(key);
        return ref == null ? null : ref.get();
    }
}
