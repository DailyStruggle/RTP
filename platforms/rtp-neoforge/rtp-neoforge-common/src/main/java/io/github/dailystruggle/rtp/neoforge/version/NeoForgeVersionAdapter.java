package io.github.dailystruggle.rtp.neoforge.version;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Per-MC-version SPI for the NeoForge platform (rtp-neoforge-ADR-001), the
 * NeoForge analogue of {@code FabricVersionAdapter}.
 *
 * <p>Each {@code rtp-neoforge-vXX_YY_RN} carrier supplies exactly one
 * implementation, living under {@code io.github.dailystruggle.rtp.neoforge.vXX_YY_RN}.
 * {@code RTPNeoForgeMod} resolves the implementation at server start based on
 * the running MC version.</p>
 *
 * <p><b>Mojmap-at-runtime.</b> NeoForge ships Mojang-mapped names at runtime
 * from MC 1.20.4+, so {@code rtp-neoforge-common} compiles directly against
 * {@code net.minecraft.*} types and there is no obf/intermediary split. This
 * SPI still exists to host the S-005 async-chunk and non-persistent-ticket
 * contracts so the version-volatile vanilla call sites (whose method shapes,
 * not class names, drift across MC point releases) live in exactly one place
 * per MC line. Because the names that matter here are stable Mojmap class
 * names ({@code ServerLevel}, {@code ChunkAccess}), the seam passes those
 * types directly rather than boxing them; coordinates pass as primitives.</p>
 *
 * <p><b>Threading:</b> implementations shall not load chunks (S-005), block on
 * tick threads, or hold locks. Server-thread-affine calls must be dispatched by
 * the caller (typically via {@code MinecraftServer#submit} / {@code RTP.scheduler}).</p>
 */
public interface NeoForgeVersionAdapter {

    /**
     * Short identifier for the MC version this carrier targets, e.g.
     * {@code "1.21.1"}. Used for logging only.
     */
    String mcVersion();

    // -------------------------------------------------------------------------
    // Registry access.
    // -------------------------------------------------------------------------

    /**
     * Snapshot of the runtime's {@code minecraft:block} tag bindings, in the
     * {@code namespace:path -> set of upper-case "namespace:path"} multimap
     * shape documented on
     * {@link io.github.dailystruggle.rtp.api.server.RTPServerAccessor#blockTagSnapshot()}.
     *
     * <p>Return {@code null} to signal "carrier cannot resolve — fall back" so
     * {@code NeoForgeServerAccessor} can attempt its reflective walk. An empty
     * map is a valid "walked but no tags yet" result. Default returns
     * {@code null}.</p>
     */
    default @Nullable Map<String, Set<String>> snapshotBlockTags() {
        return null;
    }

    // -------------------------------------------------------------------------
    // Chunk access — S-005 async path (no synchronous chunk load on the
    // server tick thread).
    // -------------------------------------------------------------------------

    /**
     * Loads (synchronously, on the server thread) the chunk at {@code (cx, cz)}
     * at full status, generating if absent. Callers are responsible for
     * dispatching to the server thread. Returns a future for
     * forward-compatibility — implementations complete it inline.
     */
    CompletableFuture<ChunkAccess> getChunkFull(ServerLevel level, int cx, int cz);

    /**
     * <b>Non-blocking</b> dispatch of a FULL chunk generation request via
     * {@code ServerChunkCache#getChunkFuture(cx, cz, FULL, /*create=*&#47;true)},
     * which returns immediately and completes asynchronously when vanilla
     * finishes generation. This is the S-005-compliant path: callers MUST
     * dispatch this method onto the server tick thread; the dispatch itself
     * returns in microseconds.
     *
     * <p>The returned future completes with {@code null} on a chunk-loading
     * failure so callers attribute it through {@code FailTypes.nullChunk}
     * (REQ-RTP-S-004 — no silent discards). Default falls back to the blocking
     * {@link #getChunkFull}; RTP-shipped carriers all override.</p>
     */
    default CompletableFuture<ChunkAccess> requestFullChunkAsync(ServerLevel level, int cx, int cz) {
        return getChunkFull(level, cx, cz);
    }

    // -------------------------------------------------------------------------
    // Non-persistent chunk tickets (S-002). NeoForge's ForgeChunkManager /
    // ticket API keeps the chunk loaded for the JVM lifetime only, never
    // written to level.dat — the analogue of Bukkit's addPluginChunkTicket.
    //
    // Threading: callers MUST dispatch to the server tick thread. On failure
    // the future completes exceptionally so callers surface it (REQ-RTP-S-004).
    // -------------------------------------------------------------------------

    /**
     * Apply a non-persistent RTP-owned chunk ticket at {@code (cx, cz)}.
     * Default is a defensive failure — every carrier overrides. Failing rather
     * than silently no-op'ing is required by S-006.
     */
    default CompletableFuture<Void> applyTicket(ServerLevel level, int cx, int cz) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "applyTicket not implemented for carrier mcVersion=" + mcVersion()));
    }

    /**
     * Release a previously-applied non-persistent RTP ticket at {@code (cx, cz)}.
     * No-op semantics if the ticket is absent.
     */
    default CompletableFuture<Void> releaseTicket(ServerLevel level, int cx, int cz) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "releaseTicket not implemented for carrier mcVersion=" + mcVersion()));
    }

    /**
     * Periodic refresh hook, called by {@code RTPNeoForgeMod} on a fixed-rate
     * scheduler. Default is a no-op — only carriers that use auto-expiring
     * tickets need to override and re-issue tickets for chunks still held.
     */
    default void tickRefresh() {
        // no-op — carriers with auto-expiring tickets override this.
    }

    // -------------------------------------------------------------------------
    // Player / world / worldborder factories.
    // -------------------------------------------------------------------------

    /**
     * Construct an {@link RTPPlayer} around a platform-typed {@code ServerPlayer}
     * handle, or {@code null} to let the caller use its built-in
     * {@code NeoForgeRTPPlayer} fallback.
     */
    default @Nullable RTPPlayer createPlayer(Object serverPlayer) {
        return null;
    }

    /**
     * Refresh an existing wrapper's underlying {@code ServerPlayer} handle.
     * Default no-op.
     */
    default void rebindPlayer(RTPPlayer existing, Object serverPlayer) {
        // no-op — carriers that own a custom RTPPlayer impl override this.
    }

    /**
     * Construct an {@link RTPWorld} around a platform-typed {@code ServerLevel}
     * handle, or {@code null} to let the caller use its built-in
     * {@code NeoForgeRTPWorld} fallback.
     */
    default @Nullable RTPWorld<?> createWorld(Object serverLevel) {
        return null;
    }

    /**
     * Construct a native-backed {@link io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder}
     * for the given {@code ServerLevel} handle, or {@code null} if the carrier
     * does not ship a per-version implementation.
     */
    default @Nullable io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder
            createNativeWorldBorder(Object serverLevel) {
        return null;
    }

    // -------------------------------------------------------------------------
    // Server-thread / console-command / Brigadier-source typed seams.
    // -------------------------------------------------------------------------

    /**
     * Return the server's main tick thread, or {@code null} to let the caller
     * fall through to its reflective lookup.
     */
    default @Nullable Thread getServerThread(Object server) {
        return null;
    }

    /**
     * Dispatch a console command on the server's primary command stack. Return
     * {@code true} if handled; {@code false} to let the caller fall back.
     */
    default boolean dispatchConsoleCommand(Object server, String command) {
        return false;
    }

    /**
     * Resolve the {@link UUID} of the player driving a Brigadier
     * {@code CommandSourceStack}, or {@code null} when the source is not a
     * player (console / command block / unknown).
     */
    default @Nullable UUID resolveSenderUuid(Object src) {
        return null;
    }

    /**
     * Extract the {@code ServerPlayer} from a {@code ServerGamePacketListenerImpl}
     * handler delivered by the connection event, or {@code null} on a miss.
     */
    default @Nullable Object extractPlayerFromConnection(Object handler) {
        return null;
    }

    /**
     * Extract the player's {@link UUID} from a {@code ServerPlayer} instance, or
     * {@code null} if the carrier cannot resolve it (caller falls through with
     * S-006 fail-loud-but-survive semantics).
     */
    default @Nullable UUID getPlayerUUID(Object player) {
        return null;
    }

    // -------------------------------------------------------------------------
    // Player-facing dispatch seams (title / action-bar).
    // -------------------------------------------------------------------------

    /**
     * Dispatch a title / subtitle splash to the given {@code ServerPlayer}.
     * Return {@code true} if handled; {@code false} to fall back.
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
     * Dispatch an action-bar message to the given {@code ServerPlayer}.
     * Return {@code true} if handled; {@code false} to fall back.
     */
    default boolean dispatchActionbar(Object player, String text) {
        return false;
    }

    // -------------------------------------------------------------------------
    // Maps-api parity (deferred; carriers opt in via supportsMapCharts).
    // -------------------------------------------------------------------------

    /**
     * Render an RTP chart onto a per-viewer vanilla filled-map and deliver it.
     * Return {@code true} if handled; {@code false} if unsupported (caller
     * falls back to the no-op map binding) or the work failed (logged, never
     * silently swallowed per S-004).
     */
    default boolean renderMapChart(Object serverPlayer,
                                   String chartKey,
                                   int[] argb,
                                   boolean locked,
                                   boolean deliverItem) {
        return false;
    }

    /** Release any per-chart map state cached for {@code chartKey}. Default no-op. */
    default void releaseMapChart(String chartKey) {
        // no-op: carriers that implement renderMapChart override this.
    }

    /** Whether this carrier implements {@link #renderMapChart}. Default {@code false}. */
    default boolean supportsMapCharts() {
        return false;
    }

    // -------------------------------------------------------------------------
    // Menu parity — written-book modal (carriers opt in by overriding).
    // -------------------------------------------------------------------------

    /**
     * Open an interactive written-book modal for the given {@code ServerPlayer},
     * binding the platform-neutral {@code NeoForgeBookSpec} to this MC version's
     * {@code WrittenBookContent} / {@code ClientboundOpenBookPacket} types
     * (NeoForge analogue of {@code FabricVersionAdapter#openBookMenu}).
     *
     * <p>The {@code spec} is typed as {@link Object} so this interface carries no
     * compile-time reference to {@code NeoForgeBookSpec} beyond the caller; RTP
     * carriers cast it back. Return {@code true} if the book modal was
     * dispatched; {@code false} when the carrier does not implement it (caller
     * falls back to the chat renderer) or the dispatch failed (logged, never
     * silently swallowed per S-004). Default returns {@code false}.
     */
    default boolean openBookMenu(Object serverPlayer, Object spec) {
        return false;
    }
}
