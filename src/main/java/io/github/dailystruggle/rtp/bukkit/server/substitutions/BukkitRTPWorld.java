package io.github.dailystruggle.rtp.bukkit.server.substitutions;

import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPChunk;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.papermc.lib.PaperLib;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
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
            = ()->Arrays.stream(Biome.values()).map(Enum::name).collect(Collectors.toSet());

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
        return getBiome.apply(new Location(world,x,y,z));
    }

    public static Set<String> getBiomes() {
        return getBiomes.get();
    }

    @Override
    public int hashCode() {
        return id().hashCode();
    }
}
