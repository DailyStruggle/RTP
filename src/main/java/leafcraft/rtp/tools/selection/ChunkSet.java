package leafcraft.rtp.tools.selection;

import leafcraft.rtp.RTP;
import org.bukkit.Chunk;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class ChunkSet {
    public AtomicInteger completed;
    public int expectedSize;
    public ArrayList<CompletableFuture<Chunk>> chunks;

    public ChunkSet() {
        completed = new AtomicInteger(0);
        int vd = RTP.getConfigs().config.vd;
        expectedSize = (vd*2+1)*(vd*2+1);
        chunks = new ArrayList<>(expectedSize);
    }

    public void shutDown() {
        for(CompletableFuture<Chunk> chunk : chunks) {
            if(!chunk.isDone()) chunk.cancel(true);
        }
    }
}
