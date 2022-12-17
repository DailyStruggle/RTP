package io.github.dailystruggle.rtp.bukkit.server.substitutions;

import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPChunk;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.papermc.lib.PaperLib;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Waterlogged;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class BukkitRTPWorld implements RTPWorld {
    private final UUID id;
    private final String name;
    private final World world;

    public final Map<List<Integer>, Map.Entry<Chunk,Long>> chunkMap = new ConcurrentHashMap<>();
    public final Map<List<Integer>,List<CompletableFuture<Chunk>>> chunkLoads = new ConcurrentHashMap<>();

    public BukkitRTPWorld(World world) {
        this.world = world;
        if(world == null) {
            this.id = null;
            this.name = null;
        }
        else {
            this.id = world.getUID();
            this.name = world.getName();
        }
    }

    public static void setBiomeGetter(@NotNull Function<Location, String> getBiome) {
        BukkitRTPWorld.getBiome = getBiome;
    }

    public static void setBiomesGetter(@NotNull Supplier<Set<String>> getBiomes) {
        BukkitRTPWorld.getBiomes = getBiomes;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public UUID id() {
        return id;
    }

    @Override
    public CompletableFuture<RTPChunk> getChunkAt(int cx, int cz) {
        List<Integer> xz = Arrays.asList(cx,cz);
        CompletableFuture<RTPChunk> res = new CompletableFuture<>();
        Map.Entry<Chunk, Long> chunkLongPair = chunkMap.get(xz);
        if(chunkLongPair!=null && chunkLongPair.getKey()!=null) {
            Chunk chunk = chunkLongPair.getKey();
            res.complete(new BukkitRTPChunk(chunk));
            return res;
        }

        if (Bukkit.isPrimaryThread() || world.isChunkLoaded(cx, cz)) {
            Chunk chunk = world.getChunkAt(cx, cz);
            BukkitRTPChunk rtpChunk = new BukkitRTPChunk(chunk);
            res.complete(rtpChunk);
        }
        else if(RTP.serverAccessor.getServerIntVersion()<13) {
            Bukkit.getScheduler().runTask(RTPBukkitPlugin.getInstance(),() -> res.complete(new BukkitRTPChunk(world.getChunkAt(cx,cz))));
        }
        else {
//            Bukkit.getScheduler().runTask(RTPBukkitPlugin.getInstance(),() -> res.complete(new BukkitRTPChunk(world.getChunkAt(cx,cz))));
            CompletableFuture<Chunk> chunkAtAsync = PaperLib.getChunkAtAsyncUrgently(world, cx, cz, true);

            List<CompletableFuture<Chunk>> list = chunkLoads.get(xz);
            if(list == null) list = new ArrayList<>();
            list.add(chunkAtAsync);
            chunkLoads.put(xz,list);

            chunkAtAsync.whenComplete((chunk, throwable) -> {
                res.complete(new BukkitRTPChunk(chunk));
                chunkLoads.remove(xz);
                if(!RTPBukkitPlugin.getInstance().isEnabled()) throw new IllegalStateException("completed chunk after plugin disabled");
            });
        }
        return res;
    }

    @Override
    public void keepChunkAt(int cx, int cz) {
        List<Integer> xz = Arrays.asList(cx,cz);
        if(chunkMap.containsKey(xz)) {
            Map.Entry<Chunk, Long> chunkLongPair = chunkMap.get(xz);
            if(Bukkit.isPrimaryThread()) setChunkForceLoaded(cx,cz,true);
            else Bukkit.getScheduler().runTask(RTPBukkitPlugin.getInstance(),()->setChunkForceLoaded(cx,cz,true));
            chunkLongPair.setValue(chunkLongPair.getValue()+1);
        }
        else {
            CompletableFuture<RTPChunk> chunkAt = getChunkAt(cx, cz);
            chunkAt.whenComplete((rtpChunk, throwable) -> {
                if(chunkMap.containsKey(xz)) {
                    Map.Entry<Chunk, Long> chunkLongPair = chunkMap.get(xz);
                    if(Bukkit.isPrimaryThread()) setChunkForceLoaded(cx,cz,true);
                    else Bukkit.getScheduler().runTask(RTPBukkitPlugin.getInstance(),()->setChunkForceLoaded(cx,cz,true));
                    chunkLongPair.setValue(chunkLongPair.getValue()+1);
                }
                else if(rtpChunk instanceof BukkitRTPChunk) {
                    BukkitRTPChunk bukkitRTPChunk = ((BukkitRTPChunk) rtpChunk);
                    Map.Entry<Chunk,Long> pair = new AbstractMap.SimpleEntry<>(bukkitRTPChunk.chunk(), 1L);
                    if(Bukkit.isPrimaryThread()) setChunkForceLoaded(cx,cz,true);
                    else Bukkit.getScheduler().runTask(RTPBukkitPlugin.getInstance(),()->setChunkForceLoaded(cx,cz,true));
                    chunkMap.put(xz,pair);
                }
                else throw new IllegalStateException();
            });
        }
    }

    @Override
    public void forgetChunkAt(int cx, int cz) {
        List<Integer> xz = Arrays.asList(cx,cz);
        Map.Entry<Chunk, Long> chunkLongPair = chunkMap.get(xz);
        if(chunkLongPair == null) return;

        long i = chunkLongPair.getValue()-1;
        if(i<=0) {
            chunkMap.remove(xz);
            if(Bukkit.isPrimaryThread()) setChunkForceLoaded(cx,cz,true);
            else Bukkit.getScheduler().runTask(RTPBukkitPlugin.getInstance(),()->setChunkForceLoaded(cx,cz,true));
        }
        else chunkLongPair.setValue(i);
    }

    @Override
    public void forgetChunks() {
        chunkMap.forEach((integers, chunkLongPair) -> setChunkForceLoaded(integers.get(0),integers.get(1),false));
        chunkMap.clear();
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
        Map.Entry<Chunk, Long> chunkLongPair = chunkMap.get(xz);
        if(chunkLongPair == null) throw new IllegalStateException();

        Chunk chunk = chunkLongPair.getKey();
        if (chunk == null) throw new IllegalStateException();
        if (!chunk.isLoaded()) throw new IllegalStateException();

        Block airBlock = location.getBlock();
        Material air = (airBlock.isLiquid() || airBlock.getType().isSolid()) ? Material.AIR : airBlock.getType();
        Material solid = location.getBlock().getRelative(BlockFace.DOWN).getType();

        ConfigParser<SafetyKeys> safety = (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
        Object value = safety.getConfigValue(SafetyKeys.unsafeBlocks, new ArrayList<>());
        Set<String> unsafeBlocks = (((value instanceof Collection) ? (Collection<String>)value : new ArrayList<>()))
                .stream().map(o -> o.toString().toUpperCase()).collect(Collectors.toSet());

        Object o = safety.getConfigValue(SafetyKeys.platformMaterial,Material.COBBLESTONE.name());
        Material platformMaterial;
        if (o instanceof String) {
            try {
                platformMaterial = Material.valueOf(((String) o).toUpperCase());
            } catch (IllegalArgumentException exception) {
                platformMaterial = Material.COBBLESTONE;
            }
        }
        else throw new IllegalStateException();

        int platformRadius = safety.getNumber(SafetyKeys.platformRadius, 0).intValue();
        int platformDepth = safety.getNumber(SafetyKeys.platformDepth,1).intValue();
        int platformAirHeight = safety.getNumber(SafetyKeys.platformAirHeight, 2).intValue();

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
                            || (checkWaterlogged && version > 12 && block.getBlockData() instanceof Waterlogged && ((Waterlogged) block.getBlockData()).isWaterlogged())) {
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

    @Override
    public boolean isActive() {
        return Bukkit.getWorld(id)!=null;
    }

    @Override
    public boolean isForceLoaded(int cx, int cz) {
        return chunkMap.containsKey(Arrays.asList(cx, cz));
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
        BukkitRTPWorld that = (BukkitRTPWorld) obj;
        return Objects.equals(this.world, that.world);
    }

    @Override
    public String toString() {
        return "BukkitRTPWorld[" +
                "world=" + world + ']';
    }

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

    public void setChunkForceLoaded(int cx, int cz, boolean forceLoaded) {
        if(RTP.serverAccessor.getServerIntVersion()<13) return;
        if(Bukkit.isPrimaryThread()) world.setChunkForceLoaded(cx,cz,forceLoaded);
        else Bukkit.getScheduler().runTask(RTPBukkitPlugin.getInstance(),() -> world.setChunkForceLoaded(cx,cz,forceLoaded));
    }
}
