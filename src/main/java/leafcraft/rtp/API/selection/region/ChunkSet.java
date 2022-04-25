package leafcraft.rtp.api.selection.region;

import leafcraft.rtp.api.substitutions.RTPChunk;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public record ChunkSet(
        List<CompletableFuture<RTPChunk>> chunks,
        CompletableFuture<Boolean> complete) {

    public ChunkSet(List<CompletableFuture<RTPChunk>> chunks, CompletableFuture<Boolean> complete) {
        this.chunks = chunks;
        this.complete = complete;
        AtomicInteger count = new AtomicInteger();
        Semaphore countAccess = new Semaphore(1);
        chunks.forEach(rtpChunkCompletableFuture -> rtpChunkCompletableFuture.whenComplete((rtpChunk, throwable) -> {
            if (complete.isDone()) return;
            try {
                countAccess.acquire();
                int i = count.incrementAndGet();
                if (i == chunks.size()) complete.complete(true);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                countAccess.release();
            }
        }));
    }
}
