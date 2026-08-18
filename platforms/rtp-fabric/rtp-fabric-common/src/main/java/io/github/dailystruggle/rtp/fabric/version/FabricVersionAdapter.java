package io.github.dailystruggle.rtp.fabric.version;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.server.ProgressBar;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Per-MC-version SPI for the Fabric platform.
 *
 * <p>Implemented per supported Minecraft version family. The platform bootstrap
 * resolves the matching adapter reflectively at startup based on runtime version.</p>
 *
 * <p>ADR-007: decouples core logic from drifting Mojmap names by wrapping Minecraft
 * objects in {@code RTPxxxHandle} records and passing coordinates as primitives.</p>
 *
 * <p>Implementations shall not load chunks (S-005) or block tick threads.</p>
 */
public interface FabricVersionAdapter {

    /**
     * Returns a short identifier for the MC version this adapter targets,
     * e.g. {@code "1.20.1"}, {@code "1.21.1"}, {@code "26.1.2"}. Used for
     * logging only.
     */
    String mcVersion();

    // --- Registry access ---

    /**
     * Snapshot runtime {@code minecraft:block} tag bindings as a multimap.
     *
     * <p>Typed adapters query {@code BuiltInRegistries.BLOCK} directly to avoid
     * reflection across version drifts. Returns {@code null} to trigger reflective
     * fallback in {@code FabricServerAccessor}.</p>
     *
     * @return immutable tag snapshot, or {@code null} to fall back
     */
    default @Nullable Map<String, Set<String>> snapshotBlockTags() {
        return null;
    }

    // --- Chunk access ---

    /**
     * Synchronously load or generate chunk at {@code (cx, cz)} on server thread.
     *
     * @return future completed with the loaded chunk handle
     */
    CompletableFuture<RTPChunkHandle> getChunkFull(RTPLevelHandle level, int cx, int cz);

    /**
     * Non-blocking dispatch of a FULL chunk generation request (ADR-008).
     *
     * <p>Schedules generation across vanilla worker threads without parking the tick thread.
     * Completes with {@code null} on {@code ChunkLoadingFailure} (REQ-RTP-S-004).</p>
     *
     * @param level level handle
     * @param cx    chunk X coordinate
     * @param cz    chunk Z coordinate
     * @return future completed with chunk handle, or null on generation failure
     */
    default CompletableFuture<RTPChunkHandle> requestFullChunkAsync(RTPLevelHandle level, int cx, int cz) {
        return getChunkFull(level, cx, cz);
    }

    // --- Non-persistent chunk tickets (ADR-003, ADR-004, ADR-006) ---

    /**
     * Apply non-persistent RTP chunk ticket at {@code (cx, cz)} (S-002, S-006).
     */
    default CompletableFuture<Void> applyTicket(RTPLevelHandle level, int cx, int cz) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "applyTicket not implemented for adapter mcVersion=" + mcVersion()));
    }

    /**
     * Release non-persistent RTP chunk ticket at {@code (cx, cz)}.
     */
    default CompletableFuture<Void> releaseTicket(RTPLevelHandle level, int cx, int cz) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "releaseTicket not implemented for adapter mcVersion=" + mcVersion()));
    }

    /**
     * Periodic ticket refresh hook for adapters using auto-expiring tickets (ADR-004).
     */
    default void tickRefresh() {
        // no-op
    }

    // --- Effect dispatchers ---

    /**
     * Register version-compiled sound and particle dispatchers with effects runtime.
     *
     * <p>Bypasses reflection when typed mappings are available. Default is no-op.</p>
     */
    default void installEffectsDispatchers() {
        // no-op
    }

    // --- Player factory & teleport ---

    /**
     * Construct an {@link RTPPlayer} wrapper around a platform {@code ServerPlayer}.
     *
     * @param serverPlayer raw server player handle
     * @return wrapped player, or {@code null} to use default fallback
     */
    default @Nullable RTPPlayer createPlayer(Object serverPlayer) {
        return null;
    }

    /**
     * Rebind an existing wrapper to a refreshed player handle.
     */
    default void rebindPlayer(RTPPlayer existing, Object serverPlayer) {
        // no-op
    }

    /**
     * Issue typed cross-dimension or same-dimension teleport on server thread (S-005).
     *
     * @return true if handled by adapter, false to use fallback path
     */
    default boolean teleport(Object serverPlayer, Object serverLevel,
                             double x, double y, double z, float yaw, float pitch) {
        return false;
    }

    // --- World & border factories ---

    /**
     * Construct an {@link RTPWorld} wrapper around a platform {@code ServerLevel}.
     *
     * @param serverLevel raw server level handle
     * @return wrapped world, or {@code null} to use default fallback
     */
    default @Nullable RTPWorld<?> createWorld(Object serverLevel) {
        return null;
    }

    /**
     * Construct native-backed world border for the given level handle.
     *
     * @param serverLevel raw server level handle
     * @return world border wrapper, or {@code null} if unsupported
     */
    default @Nullable io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder
            createNativeWorldBorder(Object serverLevel) {
        return null;
    }

    // --- Server thread & command dispatch ---

    /**
     * Return the server's main tick thread, or {@code null} to use reflection.
     */
    default @Nullable Thread getServerThread(Object server) {
        return null;
    }

    /**
     * Dispatch console command on primary command stack.
     *
     * @return true if dispatched, false to use reflection fallback
     */
    default boolean dispatchConsoleCommand(Object server, String command) {
        return false;
    }

    // --- Brigadier source bridge ---

    /**
     * Resolve player UUID from Brigadier {@code CommandSourceStack}.
     *
     * @param src raw command source stack
     * @return player UUID, or {@code null} if source is non-player or unresolved
     */
    default @Nullable java.util.UUID resolveSenderUuid(Object src) {
        return null;
    }

    // --- Effects-api wiring & player hooks ---

    /**
     * Install effects-api wiring against live {@code MinecraftServer}.
     *
     * @return true if handled, false to fall back to common typed path
     */
    default boolean installEffectsWiring(Object server) {
        return false;
    }

    /**
     * Extract {@code ServerPlayer} from connection handler (join/disconnect).
     *
     * @param handler raw connection packet listener
     * @return live player handle, or {@code null} if unresolved
     */
    default @Nullable Object extractPlayerFromConnection(Object handler) {
        return null;
    }

    /**
     * Extract player {@link UUID} from a {@code ServerPlayer} instance.
     *
     * @param player raw server player
     * @return player UUID, or {@code null} if unresolved
     */
    default @Nullable java.util.UUID getPlayerUUID(Object player) {
        return null;
    }

    /**
     * Dispatch title/subtitle splash to player.
     *
     * @return true if handled, false to fall back
     */
    default boolean dispatchTitle(Object player,
                                  String title,
                                  String subtitle,
                                  int fadeIn,
                                  int stay,
                                  int fadeOut) {
        return false;
    }

    /**
     * Dispatch action-bar message to player.
     *
     * @return true if handled, false to fall back
     */
    default boolean dispatchActionbar(Object player, String text) {
        return false;
    }

    /**
     * Open interactive written-book menu for player (ADR-012).
     *
     * @param serverPlayer raw server player
     * @param spec         page model
     * @return true if book was opened, false to fall back to chat renderer
     */
    default boolean openBookMenu(Object serverPlayer,
                                 io.github.dailystruggle.rtp.fabric.menu.FabricBookSpec spec) {
        return false;
    }

    // --- Maps-api parity (ADR-014) ---

    /**
     * Render RTP chart onto a per-viewer vanilla filled-map and deliver it.
     *
     * @param serverPlayer raw server player handle
     * @param chartKey     cache key for MapId reuse
     * @param argb         128x128 ARGB pixel buffer
     * @param locked       true to prevent client redraw
     * @param deliverItem  true to place filled map item in inventory
     * @return true if handled, false if unsupported or failed (S-004)
     */
    default boolean renderMapChart(Object serverPlayer,
                                   String chartKey,
                                   int[] argb,
                                   boolean locked,
                                   boolean deliverItem) {
        return false;
    }

    /**
     * Release per-chart map state for {@code chartKey} (REQ-RTP-MAP-003).
     */
    default void releaseMapChart(String chartKey) {
        // no-op
    }

    /**
     * Whether this adapter implements {@link #renderMapChart}.
     */
    default boolean supportsMapCharts() {
        return false;
    }

    // --- Progress bar surface ---

    /**
     * Whether this adapter renders progress bars directly.
     */
    default boolean supportsProgressBars() {
        return false;
    }

    /**
     * Reconcile displayed progress bars against {@code bars}.
     *
     * @param server          raw server handle
     * @param bars            active progress bars
     * @param eligibleViewers maps permission to eligible online player UUIDs
     */
    default void dispatchProgressBars(Object server,
                                      Map<String, ProgressBar> bars,
                                      Function<String, Set<UUID>> eligibleViewers) {
        // no-op
    }

    /**
     * Hide and discard all displayed progress bars.
     */
    default void clearProgressBars() {
        // no-op
    }
}
