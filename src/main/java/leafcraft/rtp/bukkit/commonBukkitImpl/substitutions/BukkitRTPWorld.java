package leafcraft.rtp.bukkit.commonBukkitImpl.substitutions;

import io.papermc.lib.PaperLib;
import leafcraft.rtp.common.RTP;
import leafcraft.rtp.common.substitutions.RTPChunk;
import leafcraft.rtp.common.substitutions.RTPWorld;
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
    public RTPChunk getChunkAtNow(int[] location) {
        return new BukkitRTPChunk(world.getChunkAt(location[0], location[2]));
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
            chunkAtAsyncUrgently.whenCompleteAsync((chunk, throwable) -> res.complete(new BukkitRTPChunk(chunk)));
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
