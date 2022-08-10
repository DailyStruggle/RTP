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

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public record BukkitRTPWorld(World world) implements RTPWorld {
    private static Function<Location,String> getBiome = location -> {
        World world = Objects.requireNonNull(location.getWorld());
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        return (RTP.getInstance().serverAccessor.getServerIntVersion() < 17)
                ? world.getBiome(x,y).name()
                : world.getBiome(x,y,z).name();
    };
    private static Supplier<Set<String>> getBiomes
            = ()->Arrays.stream(Biome.values()).map(biome -> biome.name().toUpperCase()).collect(Collectors.toSet());

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
    public CompletableFuture<RTPChunk> getChunkAt(long cx, long cz) {
        CompletableFuture<RTPChunk> res = new CompletableFuture<>();
        if(Bukkit.isPrimaryThread()) {
            res.complete(new BukkitRTPChunk(world.getChunkAt((int)cx,(int)cz)));
        }
        else {
            CompletableFuture<Chunk> chunkAtAsyncUrgently;
            chunkAtAsyncUrgently = PaperLib.getChunkAtAsyncUrgently(world, (int)cx, (int)cz, true);

            RTPBukkitPlugin plugin = RTPBukkitPlugin.getInstance();
            int[] xz = {(int) (cx), (int) (cz)};
            plugin.chunkLoads.put(xz, chunkAtAsyncUrgently);
            chunkAtAsyncUrgently.whenComplete((chunk, throwable) -> {
                res.complete(new BukkitRTPChunk(chunk));
                plugin.chunkLoads.remove(xz);
            });
        }
        return res;
    }

    @Override
    public String getBiome(int x, int y, int z) {
        return getBiome.apply(new Location(world,x,y,z)).toUpperCase();
    }

    @Override
    public void platform(RTPLocation rtpLocation) {
        int version = RTP.getInstance().serverAccessor.getServerIntVersion();

        Location location = new Location(
                Bukkit.getWorld(rtpLocation.world().name()),
                rtpLocation.x(),
                rtpLocation.y(),
                rtpLocation.z());
        Chunk chunk = location.getChunk();
        if(!chunk.isLoaded()) chunk.load(true);
        Block airBlock = location.getBlock();
        airBlock.breakNaturally();
        Material air = (airBlock.isLiquid()) ? Material.AIR : airBlock.getType();
        Material solid = location.getBlock().getRelative(BlockFace.DOWN).getType();

        ConfigParser<SafetyKeys> safety = (ConfigParser<SafetyKeys>) RTP.getInstance().configs.getParser(SafetyKeys.class);
        Set<String> unsafeBlocks = safety.yamlFile.getStringList("unsafeBlocks")
                .stream().map(String::toUpperCase).collect(Collectors.toSet());

        Object o = safety.yamlFile.getString("platformMaterial", Material.COBBLESTONE.name());
        Material platformMaterial;
        if(o instanceof String s) platformMaterial = Material.valueOf(s.toUpperCase());
        else throw new IllegalStateException();

        int platformRadius = safety.yamlFile.getInt("platformRadius", 0);
        int platformDepth = safety.yamlFile.getInt("platformDepth", 1);
        int platformAirHeight = safety.yamlFile.getInt("platformAirHeight", 2);

        boolean checkWaterlogged = unsafeBlocks.contains("WATERLOGGED");

        if(!solid.isSolid()) solid = platformMaterial;
            for(int i = 7-platformRadius; i <= 7+platformRadius; i++) {
                for(int j = 7-platformRadius; j <= 7+platformRadius; j++) {
                    for(int y = location.getBlockY()-1; y >= location.getBlockY()-platformDepth; y--) {
                        Block block = chunk.getBlock(i,y,j);

                        boolean isSolid;
                        try {
                            isSolid = block.getType().isSolid();
                        }
                        catch (NullPointerException exception) {
                            isSolid = false;
                        }

                        if(     !isSolid
                                || unsafeBlocks.contains(block.getType().name().toUpperCase())
                                || (checkWaterlogged && version > 12 && block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged())) {
                            block.setType(solid, false);
                        }
                    }
                    for(int y = location.getBlockY()+platformAirHeight-1; y >= location.getBlockY(); y--) {
                        Block block = chunk.getBlock(i,y,j);
                        block.breakNaturally();
                        block.setType(air,false); //also clear liquids
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
}
