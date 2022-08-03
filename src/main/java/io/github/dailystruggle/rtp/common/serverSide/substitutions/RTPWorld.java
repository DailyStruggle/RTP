package io.github.dailystruggle.rtp.common.serverSide.substitutions;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface RTPWorld {
    String name();
    UUID id();

    CompletableFuture<RTPChunk> getChunkAt(long chunkX, long chunkZ);

    String getBiome(int x, int y, int z);
}
