package io.github.dailystruggle.rtp.bukkit.commonBukkitImpl.substitutions;

import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.substitutions.RTPChunk;
import io.papermc.lib.PaperLib;
import io.github.dailystruggle.rtp.common.substitutions.RTPWorld;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public record BukkitRTPWorld(World world) implements RTPWorld {

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
        return (RTP.getInstance().serverAccessor.getServerIntVersion() < 17)
                ? world.getBiome(x,y).name()
                : world.getBiome(x,y,z).name();
    }

    @Override
    public int hashCode() {
        return id().hashCode();
    }
}
