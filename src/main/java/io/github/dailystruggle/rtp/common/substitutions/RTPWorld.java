package io.github.dailystruggle.rtp.common.substitutions;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

public interface RTPWorld {
    String name();
    UUID id();

    CompletableFuture<RTPChunk> getChunkAt(long chunkX, long chunkZ);

    String getBiome(int x, int y, int z);
}
