package io.github.dailystruggle.rtp.api.selection;
import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Entry point for requesting pre-validated teleport locations.
 *
 * <p>Each region keeps a queue of pre-generated, safety-checked locations. All methods
 * return a {@link CompletableFuture}; do not block the main thread waiting on it.
 *
 * <p>Returned {@link GenerationResult} coords have passed the region's safety checks
 * at generation time and are re-validated at dispatch (FM-003); a failed re-check
 * discards the location and notifies the caller. Thread-safe — call from any thread.
 *
 * @see GenerationContext
 * @see GenerationResult
 */
@PublicApi
public interface ILocationGenerator {

    /**
     * Pulls the next pre-generated location from the region's queue.
     *
     * <p>{@code region} must be a non-null enabled region; {@code context} and its
     * {@code sender()} must be non-null. On queue starvation the future completes with a
     * failure result, the sender is messaged, and a WARN is logged (REQ-RTP-S-004). The
     * future never completes exceptionally — internal errors are caught and logged.
     *
     * @return future yielding a {@link GenerationResult} (success or audited failure)
     */
    CompletableFuture<GenerationResult> getLocation(Object region, GenerationContext context);

    /**
     * Generates a fresh location on-demand, bypassing the queue.
     *
     * <p>For admin paths only — player teleports should use
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
