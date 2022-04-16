package leafcraft.rtp.bukkit.api.substitutions;

import io.papermc.lib.PaperLib;
import leafcraft.rtp.api.substitutions.RTPChunk;
import leafcraft.rtp.api.substitutions.RTPWorld;
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
    public CompletableFuture<RTPChunk> getChunkAt(int[] location) {
        CompletableFuture<Chunk> chunkAtAsyncUrgently = PaperLib.getChunkAtAsyncUrgently(world, location[0], location[2], true);
        CompletableFuture<RTPChunk> res = new CompletableFuture<>();
        chunkAtAsyncUrgently.whenCompleteAsync((chunk, throwable) -> res.complete(new BukkitRTPChunk(chunk)));
        return res;
    }

    @Override
    public int hashCode() {
        return id().hashCode();
    }
}
