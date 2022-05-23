package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.substitutions.RTPChunk;
import io.github.dailystruggle.rtp.common.selection.region.ChunkSet;
import io.github.dailystruggle.rtp.common.substitutions.RTPLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

public record ChunkCleanup(RTPLocation location, Region region) implements Runnable {
    public static final List<Consumer<ChunkCleanup>> preActions = new ArrayList<>();
    public static final List<Consumer<ChunkCleanup>> postActions = new ArrayList<>();

    @Override
    public void run() {
        preActions.forEach(consumer -> consumer.accept(this));
        region.removeChunks(location);
        postActions.forEach(consumer -> consumer.accept(this));
    }
}
