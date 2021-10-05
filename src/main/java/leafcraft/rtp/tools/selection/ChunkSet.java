package leafcraft.rtp.tools.selection;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.HashableChunk;
import org.bukkit.Chunk;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public class ChunkSet {
    public Semaphore completedGuard;
    public AtomicInteger completed;

    public int expectedSize;
    public ArrayList<CompletableFuture<Chunk>> chunks;
    public ArrayList<HashableChunk> hashableChunks;

    public ChunkSet() {
        completed = new AtomicInteger(0);
        int vd = RTP.getConfigs().config.vd;
        expectedSize = (vd*2+1)*(vd*2+1);
        chunks = new ArrayList<>(expectedSize);
        hashableChunks = new ArrayList<>(expectedSize);
        completedGuard = new Semaphore(1);
    }

    public void shutDown() {
        for(CompletableFuture<Chunk> chunk : chunks) {
            if(!chunk.isDone()) chunk.cancel(true);
        }
    }
}
