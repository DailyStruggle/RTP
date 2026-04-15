package io.github.dailystruggle.rtp.api.selection;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Primary entry point for requesting pre-validated teleport locations.
 *
 * <p>Implementations maintain a pre-generated queue of safe locations per region.
 * All methods return a {@link CompletableFuture} that completes asynchronously;
 * callers must not block the main server thread waiting on the result.
 *
 * <p><b>Invariant:</b> A {@link GenerationResult} returned by any method in this
 * interface has been validated against the region's configured safety checks at the
 * time of pre-generation. A runtime re-validation check is performed at dispatch time;
 * if that check fails the location is discarded and the caller is notified (FM-003).
 *
 * <p><b>Thread safety:</b> All methods are safe to call from any thread. Internal
 * dispatch to platform-appropriate schedulers is handled by the implementation.
 *
 * @see GenerationContext
 * @see GenerationResult
 */
public interface ILocationGenerator {

    /**
     * Retrieves the next pre-generated location from the region queue.
     *
     * <p><b>Preconditions:</b>
     * <ul>
     *   <li>{@code region} must not be {@code null} and must refer to an enabled region.</li>
     *   <li>{@code context} must not be {@code null}; {@code context.sender()} must not be
     *       {@code null}.</li>
     * </ul>
     *
     * <p><b>Postconditions:</b>
     * <ul>
     *   <li>If the queue is non-empty, the future completes with a {@link GenerationResult}
     *       whose {@code coords} are within the region boundary and have passed all
     *       configured safety checks.</li>
     *   <li>If the queue is empty, the future completes with a result indicating failure;
     *       the sender receives a message and the event is logged at WARN level
     *       ({@code REQ-RTP-S-004}).</li>
     *   <li>The future never completes exceptionally under normal operation; unexpected
     *       exceptions are caught internally and logged.</li>
     * </ul>
     *
     * @param region  the target region object
     * @param context the generation context carrying sender, player, and optional biome filter
     * @return a future that completes with the generation result
     */
    CompletableFuture<GenerationResult> getLocation(Object region, GenerationContext context);

    /**
     * Generates a new location on-demand, bypassing the pre-generated queue.
     *
     * <p>This method performs a fresh validation cycle rather than consuming a queued
     * result. Use it only when the queue is intentionally unavailable (e.g. during
     * administrative operations). Prefer {@link #getLocation(Object, GenerationContext)}
     * for all player-facing teleport requests to maintain bounded response times.
     *
     * <p><b>Preconditions:</b>
     * <ul>
     *   <li>{@code region} must not be {@code null} and must refer to an enabled region.</li>
     *   <li>{@code context} must not be {@code null}.</li>
     * </ul>
     *
     * <p><b>Postconditions:</b> same guarantees as
     * {@link #getLocation(Object, GenerationContext)} except the result is not drawn
     * from the pre-generated queue.
     *
     * <p><b>Thread safety:</b> Safe to call from any thread; chunk I/O is dispatched
     * asynchronously ({@code REQ-RTP-S-005}).
     *
     * @param region  the target region object
     * @param context the generation context
     * @return a future that completes with the generation result
     */
    CompletableFuture<GenerationResult> generateLocation(Object region, GenerationContext context);

    /**
     * Retrieves the next pre-generated location, filtered to the specified biomes.
     *
     * <p><b>Preconditions:</b>
     * <ul>
     *   <li>{@code region} must not be {@code null} and must refer to an enabled region.</li>
     *   <li>{@code sender} must not be {@code null}.</li>
     *   <li>{@code biomeNames} may be {@code null} or empty, in which case no biome filter
     *       is applied.</li>
     * </ul>
     *
     * <p><b>Postconditions:</b> same as
     * {@link #getLocation(Object, GenerationContext)}; additionally, if {@code biomeNames}
     * is non-empty, the result coordinates are guaranteed to be within one of the specified
     * biomes.
     *
     * @param region     the target region object
     * @param sender     the command sender initiating the teleport
     * @param player     the player to be teleported (may differ from sender for admin commands)
     * @param biomeNames optional set of biome names to restrict selection; {@code null} means
     *                   no restriction
     * @return a future that completes with the generation result
     */
    CompletableFuture<GenerationResult> getLocation(
            Object region,
            io.github.dailystruggle.rtp.api.entity.RTPCommandSender sender,
            io.github.dailystruggle.rtp.api.entity.RTPPlayer player,
            Set<String> biomeNames);

    /**
     * Retrieves the next pre-generated location for a region-only request (no specific sender
     * or player), filtered to the specified biomes.
     *
     * <p><b>Preconditions:</b>
     * <ul>
     *   <li>{@code region} must not be {@code null} and must refer to an enabled region.</li>
     *   <li>{@code biomeNames} may be {@code null} or empty.</li>
     * </ul>
     *
     * <p><b>Postconditions:</b> same as
     * {@link #getLocation(Object, GenerationContext)}.
     *
     * @param region     the target region object
     * @param biomeNames optional biome filter; {@code null} means no restriction
     * @return a future that completes with the generation result
     */
    CompletableFuture<GenerationResult> getLocation(Object region, Set<String> biomeNames);
}
