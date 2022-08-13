package io.github.dailystruggle.rtp.bukkit.server.substitutions;

import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPChunk;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.papermc.lib.PaperLib;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Waterlogged;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;

public final class BukkitRTPWorld implements RTPWorld {
    private final Map<List<Integer>, Pair<Chunk,Long>> chunkMap = new ConcurrentHashMap<>();

    private static Function<Location, String> getBiome = location -> {
        World world = Objects.requireNonNull(location.getWorld());
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        return (RTP.serverAccessor.getServerIntVersion() < 17)
                ? world.getBiome(x, y).name()
                : world.getBiome(x, y, z).name();
    };
    private static Supplier<Set<String>> getBiomes
            = () -> Arrays.stream(Biome.values()).map(biome -> biome.name().toUpperCase()).collect(Collectors.toSet());
    private final World world;

    public BukkitRTPWorld(World world) {
        this.world = world;
    }

    public static void setBiomeGetter(@NotNull Function<Location, String> getBiome) {
        BukkitRTPWorld.getBiome = getBiome;
    }

    public static void setBiomesGetter(@NotNull Supplier<Set<String>> getBiomes) {
        BukkitRTPWorld.getBiomes = getBiomes;
    }

    @Override
    public String name() {
        return world.getName();
    }

    @Override
    public UUID id() {
        return world.getUID();
    }

    @Override
    public CompletableFuture<RTPChunk> getChunkAt(int cx, int cz) {
        List<Integer> xz = Arrays.asList(cx,cz);
        CompletableFuture<RTPChunk> res = new CompletableFuture<>();
        if(chunkMap.containsKey(xz)) {
            Pair<Chunk, Long> chunkLongPair = chunkMap.get(xz);
            Chunk chunk = chunkLongPair.getLeft();
            res.complete(new BukkitRTPChunk(chunk));
            return res;
        }

        if (Bukkit.isPrimaryThread() || world.isChunkLoaded(cx, cz)) {
            Chunk chunk = world.getChunkAt(cx, cz);
            BukkitRTPChunk rtpChunk = new BukkitRTPChunk(chunk);
            res.complete(rtpChunk);
        } else {
            CompletableFuture<Chunk> chunkAtAsyncUrgently;
            chunkAtAsyncUrgently = PaperLib.getChunkAtAsyncUrgently(world, cx, cz, true);

            RTPBukkitPlugin plugin = RTPBukkitPlugin.getInstance();
            plugin.chunkLoads.put(xz, chunkAtAsyncUrgently);
            chunkAtAsyncUrgently.whenComplete((chunk, throwable) -> {
                res.complete(new BukkitRTPChunk(chunk));
                plugin.chunkLoads.remove(xz);
            });
        }
        return res;
    }

    @Override
    public void keepChunkAt(int cx, int cz) {
        List<Integer> xz = Arrays.asList(cx,cz);
        if(chunkMap.containsKey(xz)) {
            Pair<Chunk, Long> chunkLongPair = chunkMap.get(xz);
            if(Bukkit.isPrimaryThread()) chunkLongPair.getLeft().setForceLoaded(true);
            else Bukkit.getScheduler().runTask(RTPBukkitPlugin.getInstance(),()->chunkLongPair.getLeft().setForceLoaded(true));
            chunkLongPair.setValue(chunkLongPair.getValue()+1);
            RTP.getInstance().forceLoads.putIfAbsent(xz,new BukkitRTPChunk(chunkLongPair.getLeft()));
        }
        else {
            CompletableFuture<RTPChunk> chunkAt = getChunkAt(cx, cz);
            chunkAt.whenComplete((rtpChunk, throwable) -> {
                if(chunkMap.containsKey(xz)) {
                    Pair<Chunk, Long> chunkLongPair = chunkMap.get(xz);
                    if(Bukkit.isPrimaryThread()) chunkLongPair.getLeft().setForceLoaded(true);
                    else Bukkit.getScheduler().runTask(RTPBukkitPlugin.getInstance(),()->chunkLongPair.getLeft().setForceLoaded(true));
                    chunkLongPair.setValue(chunkLongPair.getValue()+1);
                    RTP.getInstance().forceLoads.putIfAbsent(xz,new BukkitRTPChunk(chunkLongPair.getLeft()));
                }
                else if(rtpChunk instanceof BukkitRTPChunk bukkitRTPChunk) {
                    Pair<Chunk,Long> pair = new MutablePair<>(bukkitRTPChunk.chunk(), 1L);
                    if(Bukkit.isPrimaryThread()) bukkitRTPChunk.chunk().setForceLoaded(true);
                    else Bukkit.getScheduler().runTask(RTPBukkitPlugin.getInstance(),()->bukkitRTPChunk.chunk().setForceLoaded(true));
                    RTP.getInstance().forceLoads.putIfAbsent(xz,new BukkitRTPChunk(bukkitRTPChunk.chunk()));
                    chunkMap.put(xz,pair);
                }
                else throw new IllegalStateException();
            });
        }
    }

    @Override
    public void forgetChunkAt(int cx, int cz) {
        List<Integer> xz = Arrays.asList(cx,cz);
        Pair<Chunk, Long> chunkLongPair = chunkMap.get(xz);
        if(chunkLongPair == null) return;

        long i = chunkLongPair.getValue()-1;
        if(i<=0) {
            chunkMap.remove(xz);
            if(Bukkit.isPrimaryThread()) chunkLongPair.getLeft().setForceLoaded(false);
            else Bukkit.getScheduler().runTask(RTPBukkitPlugin.getInstance(),()->chunkLongPair.getLeft().setForceLoaded(false));
            RTP.getInstance().forceLoads.remove(xz);
        }
        else chunkLongPair.setValue(i);
    }

    @Override
    public String getBiome(int x, int y, int z) {
        return getBiome.apply(new Location(world, x, y, z)).toUpperCase();
    }

    @Override
    public void platform(RTPLocation rtpLocation) {
        int version = RTP.serverAccessor.getServerIntVersion();

        Location location = new Location(
                Bukkit.getWorld(rtpLocation.world().name()),
                rtpLocation.x(),
                rtpLocation.y(),
                rtpLocation.z());

        int cx = rtpLocation.x();
        int cz = rtpLocation.z();

        cx = (cx > 0) ? cx / 16 : cx / 16 - 1;
        cz = (cz > 0) ? cz / 16 : cz / 16 - 1;

        List<Integer> xz = Arrays.asList(cx,cz);
        Pair<Chunk, Long> chunkLongPair = chunkMap.get(xz);
        if(chunkLongPair == null) throw new IllegalStateException();

        Chunk chunk = chunkLongPair.getLeft();
        if (chunk == null) throw new IllegalStateException();
        if (!chunk.isLoaded()) throw new IllegalStateException();

        Block airBlock = location.getBlock();
        Material air = (airBlock.isLiquid()) ? Material.AIR : airBlock.getType();
        Material solid = location.getBlock().getRelative(BlockFace.DOWN).getType();

        ConfigParser<SafetyKeys> safety = (ConfigParser<SafetyKeys>) RTP.getInstance().configs.getParser(SafetyKeys.class);
        Set<String> unsafeBlocks = safety.yamlFile.getStringList("unsafeBlocks")
                .stream().map(String::toUpperCase).collect(Collectors.toSet());

        Object o = safety.yamlFile.getString("platformMaterial", Material.COBBLESTONE.name());
        Material platformMaterial;
        if (o instanceof String s) platformMaterial = Material.valueOf(s.toUpperCase());
        else throw new IllegalStateException();

        int platformRadius = safety.yamlFile.getInt("platformRadius", 0);
        int platformDepth = safety.yamlFile.getInt("platformDepth", 1);
        int platformAirHeight = safety.yamlFile.getInt("platformAirHeight", 2);

        boolean checkWaterlogged = unsafeBlocks.contains("WATERLOGGED");

        if (!solid.isSolid()) solid = platformMaterial;
        for (int i = 7 - platformRadius; i <= 7 + platformRadius; i++) {
            for (int j = 7 - platformRadius; j <= 7 + platformRadius; j++) {
                for (int y = location.getBlockY() - 1; y >= location.getBlockY() - platformDepth; y--) {
                    Block block = chunk.getBlock(i, y, j);

                    boolean isSolid;
                    try {
                        isSolid = block.getType().isSolid();
                    } catch (NullPointerException exception) {
                        isSolid = false;
                    }

                    if (!isSolid
                            || unsafeBlocks.contains(block.getType().name().toUpperCase())
                            || (checkWaterlogged && version > 12 && block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged())) {
                        block.setType(solid, true);
                    }
                }
                for (int y = location.getBlockY() + platformAirHeight - 1; y >= location.getBlockY(); y--) {
                    Block block = chunk.getBlock(i, y, j);
                    block.breakNaturally();
                    block.setType(air, true); //also clear liquids
                }
            }
        }
    }

    public static Set<String> getBiomes() {
        return getBiomes.get();
    }

    @Override
    public int hashCode() {
        return id().hashCode();
    }

    public World world() {
        return world;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (BukkitRTPWorld) obj;
        return Objects.equals(this.world, that.world);
    }

    @Override
    public String toString() {
        return "BukkitRTPWorld[" +
                "world=" + world + ']';
    }

}
