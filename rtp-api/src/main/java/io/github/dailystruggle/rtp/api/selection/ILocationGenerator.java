package io.github.dailystruggle.rtp.api.selection;
import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Entry point for requesting pre-validated teleport locations (S-005).
 * Thread-safe; all methods return a {@link CompletableFuture}.
 *
 * @see GenerationContext
 * @see GenerationResult
 */
@PublicApi
public interface ILocationGenerator {

    /**
     * Pulls the next pre-generated location from the region's queue.
     * Completes with failure result on queue starvation (REQ-RTP-S-004).
     *
     * @return future yielding a {@link GenerationResult}
     */
    CompletableFuture<GenerationResult> getLocation(Object region, GenerationContext context);

    /**
     * Generates a fresh location on-demand, bypassing the queue.
     *
     * <p>For admin paths only - player teleports should use
     * {@link #getLocation(Object, GenerationContext)} for bounded latency. Same result
     * contract as {@code getLocation}; chunk I/O stays off the main thread (REQ-RTP-S-005).
     */
    CompletableFuture<GenerationResult> generateLocation(Object region, GenerationContext context);

    /**
     * Pulls the next pre-generated location, optionally filtered to specific biomes.
     *
     * <p>{@code biomeNames} {@code null}/empty disables filtering; otherwise the result
     * is guaranteed to be inside one of the listed biomes. {@code player} may differ from
     * {@code sender} (admin-initiated teleports). Same failure contract as
     * {@link #getLocation(Object, GenerationContext)}.
     */
    CompletableFuture<GenerationResult> getLocation(
            Object region,
            io.github.dailystruggle.rtp.api.entity.RTPCommandSender sender,
            io.github.dailystruggle.rtp.api.entity.RTPPlayer player,
            Set<String> biomeNames);

    /**
     * Region-only variant (no sender/player), with optional biome filter
     * ({@code null}/empty = no restriction). Used for warm-up and scan tasks.
     */
    CompletableFuture<GenerationResult> getLocation(Object region, Set<String> biomeNames);
}
