package io.github.dailystruggle.rtp.fabric.v26_3_R1;

import io.github.dailystruggle.rtp.api.world.ChunkColumnProbe;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.anvil.AnvilColumnProbeAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
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
 * MC 26.3 RTPWorld wrapper.
 *
 * <p>Lives in {@code rtp-fabric-v26_3_R1} (Loom unobfuscated, no intermediary
 * remapping) so its compiled bytecode references {@code net.minecraft.server.level.ServerLevel}
 * and friends by Mojang names.
 */
public final class V26_3_R1FabricRTPWorld extends RTPWorld<ServerLevel> {

    private final String name;
    private final UUID id;

    /** Cache of recently-loaded chunks keyed by {@code (cz << 32) | cx}. */
    private final ConcurrentHashMap<Long, V26_3_R1FabricRTPChunk> chunkCache = new ConcurrentHashMap<>();

    public V26_3_R1FabricRTPWorld(ServerLevel level) {
        super(level);
        this.name = resolveDimensionName(level);
        this.id = UUID.nameUUIDFromBytes(("rtp-fabric-world:" + this.name).getBytes());
    }

    private static String resolveDimensionName(ServerLevel level) {
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

    private static final int RTP_TICKET_RADIUS = 1;
    private static final int RTP_TEMP_LOAD_TICKET_FLAGS =
            TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION;
    private static final TicketType RTP_TEMP_LOAD_TICKET_TYPE =
            new TicketType(600L, RTP_TEMP_LOAD_TICKET_FLAGS);

    private boolean tryAddTempLoadTicket(ServerLevel level, ChunkPos cp) {
        try {
            level.getChunkSource().addTicketWithRadius(RTP_TEMP_LOAD_TICKET_TYPE, cp, RTP_TICKET_RADIUS);
            return true;
        } catch (Throwable t) {
            RTP.log(Level.FINE,
                    "[RTP][V26_3_R1] temp load-ticket apply failed for chunk=" + cp + ": "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private void scheduleTempLoadTicketRemoval(ServerLevel level, ChunkPos cp) {
        MinecraftServer server = level.getServer();
        Runnable remove = () -> {
            try {
                level.getChunkSource().removeTicketWithRadius(RTP_TEMP_LOAD_TICKET_TYPE, cp, RTP_TICKET_RADIUS);
            } catch (Throwable t) {
                RTP.log(Level.FINE,
                        "[RTP][V26_3_R1] temp load-ticket release failed for chunk=" + cp + ": "
                                + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        };
        if (server != null && !server.isSameThread()) {
            server.execute(remove);
        } else {
            remove.run();
        }
    }

    @Override
    public CompletableFuture<Long> getChunkAt(int cx, int cz) {
        ServerLevel level = world;
        if (level == null) return CompletableFuture.completedFuture(null);
        MinecraftServer server = level.getServer();
        if (server == null) return CompletableFuture.completedFuture(null);
        long k = key(cx, cz);
        if (chunkCache.containsKey(k)) {
            return CompletableFuture.completedFuture(k);
        }
        final ChunkPos ticketPos = new ChunkPos(cx, cz);
        return server.submit(() -> tryAddTempLoadTicket(level, ticketPos))
                .thenComposeAsync(ticketAdded -> {
                    CompletableFuture<ChunkAccess> inner = requestChunkFuture(level, cx, cz);
                    if (inner == null) inner = CompletableFuture.completedFuture((ChunkAccess) null);
                    return inner.whenComplete((ca, ex) -> {
                        if (Boolean.TRUE.equals(ticketAdded)) {
                            scheduleTempLoadTicketRemoval(level, ticketPos);
                        }
                    });
                }, RTP_CONTINUATION_EXECUTOR)
                .thenApply(ca -> {
                    if (ca == null) return null;
                    chunkCache.put(k, new V26_3_R1FabricRTPChunk(ca, level, id));
                    return k;
                })
                .exceptionally(t -> {
                    RTP.log(Level.WARNING,
                            "[RTP][V26_3_R1] getChunkAt(" + cx + "," + cz + ") failed for "
                                    + name + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    return null;
                });
    }

    private static volatile java.lang.reflect.Method GET_CHUNK_FUTURE_METHOD;

    private static final java.util.concurrent.Executor RTP_CONTINUATION_EXECUTOR =
            r -> RTP.scheduler.runTaskAsynchronously(r);

    @SuppressWarnings("unchecked")
    private CompletableFuture<ChunkAccess> requestChunkFuture(ServerLevel level, int cx, int cz) {
        try {
            ServerChunkCache cache = level.getChunkSource();
            java.lang.reflect.Method getter = resolveGetChunkFutureMethod(cache);
            Object raw = getter.invoke(cache, cx, cz, ChunkStatus.FULL, /*create=*/ true);
            if (!(raw instanceof CompletableFuture<?> cf)) {
                RTP.log(Level.WARNING,
                        "[RTP][V26_3_R1] getChunkFuture returned non-CompletableFuture for chunk=("
                                + cx + "," + cz + "): " + (raw == null ? "null" : raw.getClass()));
                return null;
            }
            return ((CompletableFuture<Object>) cf)
                    .thenApplyAsync(V26_3_R1FabricRTPWorld::unwrapChunk, RTP_CONTINUATION_EXECUTOR);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][V26_3_R1] requestChunkFuture(" + cx + "," + cz + ") failed for "
                            + name + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    private static java.lang.reflect.Method resolveGetChunkFutureMethod(ServerChunkCache cache)
            throws ReflectiveOperationException {
        java.lang.reflect.Method cached = GET_CHUNK_FUTURE_METHOD;
        if (cached != null) return cached;
        synchronized (V26_3_R1FabricRTPWorld.class) {
            cached = GET_CHUNK_FUTURE_METHOD;
            if (cached != null) return cached;
            java.lang.reflect.Method found = null;
            String[] preferredNames = { "getChunkFuture", "getChunkFutureMainThread" };
            for (String pname : preferredNames) {
                for (Class<?> c = cache.getClass(); c != null && found == null; c = c.getSuperclass()) {
                    for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                        if (!pname.equals(m.getName())) continue;
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
                if (found != null) break;
            }
            if (found == null) {
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

    private static ChunkAccess unwrapChunk(Object result) {
        if (result == null) return null;
        if (result instanceof ChunkAccess ca) return ca;
        try {
            java.lang.reflect.Method orElse = result.getClass().getMethod("orElse", Object.class);
            Object v = orElse.invoke(result, (Object) null);
            if (v instanceof ChunkAccess ca) return ca;
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Method left = result.getClass().getMethod("left");
            Object opt = left.invoke(result);
            if (opt != null) {
                java.lang.reflect.Method orElse = opt.getClass().getMethod("orElse", Object.class);
                Object v = orElse.invoke(opt, (Object) null);
                if (v instanceof ChunkAccess ca) return ca;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

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
                    "V26_3_R1FabricRTPWorld.setForceLoadedImpl: FabricVersionAdapter not yet installed"
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
                        "[RTP][V26_3_R1] setForceLoadedImpl(" + cx + "," + cz + "," + forceLoad
                                + ") failed for " + name + ": "
                                + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Integer> getServerForceLoadedCount() {
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
        chunkCache.remove(key(cx, cz));
    }

    @Override
    public void forgetChunks() {
        chunkCache.clear();
    }

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
                    "[RTP][V26_3_R1] probeChunkColumn: getWorldPath threw for world="
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
                return ChunkColumnProbe.of(new AnvilColumnProbeAdapter(probe, cx, cz,
                        s -> (RTP.serverAccessor != null)
                                ? RTP.serverAccessor.reconcilePaletteIdentifier(s)
                                : io.github.dailystruggle.rtp.anvil.PaletteIdentifierNormalizer.normalize(s)));
            } catch (Throwable t) {
                RTP.log(Level.FINE,
                        "[RTP][V26_3_R1] probeChunkColumn failed for world=" + name
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
                    }
                }
            }
            return out;
        } catch (Throwable t) {
            RTP.log(Level.FINE,
                    "[RTP][V26_3_R1] readBiomesInRegionFile failed for world=" + name
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
            if (regionFile == null || !java.nio.file.Files.exists(regionFile)) {
                return false;
            }
            return io.github.dailystruggle.rtp.anvil.AnvilRegionOccupancyCache
                    .isOccupied(regionFile, cx, cz);
        } catch (Throwable t) {
            RTP.log(Level.FINE,
                    "[RTP][V26_3_R1] isChunkGenerated anvil probe failed for world="
                            + name + " chunk=(" + cx + "," + cz + "): "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            return true;
        }
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

    private static String dimensionRegionSubpath(java.nio.file.Path worldFolder, ServerLevel level) {
        java.util.List<String> candidates = dimensionRegionSubpaths(level);
        for (String c : candidates) {
            java.nio.file.Path regionDir = c.isEmpty()
                    ? worldFolder.resolve("region")
                    : worldFolder.resolve(c).resolve("region");
            try {
                if (java.nio.file.Files.isDirectory(regionDir)) return c;
            } catch (Throwable ignored) {
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
            String raw = holder.unwrapKey()
                    .map(k -> {
                        try { return k.identifier().toString(); }
                        catch (Throwable t) { return ""; }
                    })
                    .orElseGet(() -> {
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
    }

    private static final io.github.dailystruggle.rtp.api.schematic.SchematicPaster SCHEMATIC_PASTER =
            new io.github.dailystruggle.rtp.api.schematic.WorldBlockSchematicPaster();

    @Override
    public io.github.dailystruggle.rtp.api.schematic.SchematicPaster schematicPaster() {
        return SCHEMATIC_PASTER;
    }

    @Override
    public int setBlocks(java.util.List<io.github.dailystruggle.rtp.api.platform.BlockDelta> blocks) {
        ServerLevel level = world;
        if (level == null || blocks == null || blocks.isEmpty()) return 0;
        net.minecraft.core.HolderLookup<net.minecraft.world.level.block.Block> blockLookup;
        try {
            blockLookup = level.registryAccess()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK);
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP][V26_3_R1] setBlocks could not resolve the block registry "
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
                level.setBlock(new BlockPos(delta.x(), delta.y(), delta.z()), state, 2);
                placed++;
            } catch (Throwable e) {
                if (firstFailedToken == null) firstFailedToken = delta.token();
            }
        }
        if (firstFailedToken != null) {
            RTP.log(Level.WARNING, "[RTP][V26_3_R1] setBlocks placed " + placed + " of "
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
    }

    @Override
    public int getMaxHeight() {
        try {
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

    public ServerLevel level() {
        return world;
    }
}
