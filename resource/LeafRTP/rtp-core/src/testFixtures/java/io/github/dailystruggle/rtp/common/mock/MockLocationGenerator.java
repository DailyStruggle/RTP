package io.github.dailystruggle.rtp.common.mock;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.selection.ILocationGenerator;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal in-memory implementation of {@link ILocationGenerator} for use in unit tests.
 *
 * <p>Returns a trivially completed {@link GenerationResult} at coordinates (0, 64, 0)
 * in the {@link MockRTPWorld}. Override {@link #buildResult} to customise the outcome.
 */
public class MockLocationGenerator implements ILocationGenerator {

    private final MockRTPWorld world;

    public MockLocationGenerator(MockRTPWorld world) {
        this.world = world;
    }

    public MockLocationGenerator() {
        this(new MockRTPWorld());
    }

    /**
     * Builds the result returned by every {@code getLocation} / {@code generateLocation} call.
     *
     * <p>The {@link ChunkSet} carries one pre-completed chunk future ({@code 0L}) so that
     * production-code paths calling {@code ticket.chunks().getFirst().get()} never block
     * when this mock is active. {@code allOf} on that already-completed future completes
     * the {@code ChunkSet.complete} future synchronously inside the compact constructor.
     */
    protected GenerationResult buildResult() {
        RTPCoords coords = new RTPCoords(world.name(), 0, 64, 0);
        ChunkSet chunkSet = new ChunkSet(
                world, 0, 0,
                List.of(CompletableFuture.completedFuture(0L)),
                new CompletableFuture<>());
        return new GenerationResult(coords, 1, chunkSet);
    }

    @Override
    public CompletableFuture<GenerationResult> getLocation(Object region, GenerationContext context) {
        return CompletableFuture.completedFuture(buildResult());
    }

    @Override
    public CompletableFuture<GenerationResult> generateLocation(Object region, GenerationContext context) {
        return CompletableFuture.completedFuture(buildResult());
    }

    @Override
    public CompletableFuture<GenerationResult> getLocation(
            Object region, RTPCommandSender sender, RTPPlayer player, Set<String> biomeNames) {
        return CompletableFuture.completedFuture(buildResult());
    }

    @Override
    public CompletableFuture<GenerationResult> getLocation(Object region, Set<String> biomeNames) {
        return CompletableFuture.completedFuture(buildResult());
    }
}
