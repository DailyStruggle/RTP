package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.api.server.PlayerLifecycleHook;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;

import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

/**
 * ADR-015 / REQ-RTP-NET-015: on player quit, cancel
 * any in-flight cross-server enrolment for the disconnecting player so
 * their slot does not occupy the shared waitlist / pending queue.
 *
 * <p>Routes through {@link NetworkRequestQueue#cancel(UUID,
 * NetworkRequestQueue.CancelReason)} with reason
 * {@link NetworkRequestQueue.CancelReason#PLAYER_DISCONNECT}. The SPI
 * contract makes this idempotent and safe to call even when there is no
 * live entry (no-op fast-path).</p>
 *
 * <p>S-004: any transport-level failure is logged via {@link RTP#log} but
 * never propagated - a player quit must not throw out of the platform event
 * dispatch, and the transport-side TTL reaper will eventually clean up
 * even if this single call fails.</p>
 *
 * <p>ADR-049: platform-neutral. Subscribes to player quit via the
 * {@link PlayerLifecycleHook} SPI rather than a Bukkit {@code Listener}.</p>
 */
public final class NetworkWaitlistQuitListener {

    /** Live quit subscription handle, or {@code null} when not registered. */
    private AutoCloseable quitSubscription;

    private final NetworkRequestQueue requestQueue;
    /**
     * Optional lobby-side auto-retry queue (introduced to
     * fix the "non-processing state" symptom on lobby backends). When
     * non-null, a disconnect also removes the player from the local
     * retry queue and clears their {@code processingPlayers} lock so
     * the next session starts clean. Null on non-lobby backends.
     */
    private final LobbyDispatchRetryQueue lobbyRetryQueue;

    public NetworkWaitlistQuitListener(NetworkRequestQueue requestQueue) {
        this(requestQueue, null);
    }

    public NetworkWaitlistQuitListener(NetworkRequestQueue requestQueue,
                                       LobbyDispatchRetryQueue lobbyRetryQueue) {
        this.requestQueue = Objects.requireNonNull(requestQueue, "requestQueue");
        this.lobbyRetryQueue = lobbyRetryQueue;
    }

    /**
     * Subscribe to player-quit events via the platform lifecycle hook.
     * Idempotent: a second call without an intervening {@link #unregister()}
     * is a no-op. Null-tolerant on a null hook.
     */
    public void register(PlayerLifecycleHook hook) {
        if (hook == null || quitSubscription != null) return;
        this.quitSubscription = hook.onPlayerQuit(this::onQuit);
    }

    /** Close the quit subscription. Idempotent; best-effort. */
    public void unregister() {
        AutoCloseable sub = this.quitSubscription;
        this.quitSubscription = null;
        if (sub != null) {
            try { sub.close(); } catch (Throwable ignored) { /* best-effort */ }
        }
    }

    void onQuit(UUID uuid) {
        // Lobby retry queue first: cheap in-memory point-remove + lock
        // release. Independent of the proxy-side cancel below; both can
        // safely fire (the retry queue is local, the request queue is
        // shared).
        if (lobbyRetryQueue != null) {
            try {
                // Pass null messageKey: the player is gone, there is no
                // one to receive a chat message; we only want the lock
                // released and the entry dropped.
                lobbyRetryQueue.cancel(uuid, null);
            } catch (Throwable t) {
                RTP.log(Level.WARNING,
                        "[RTP] lobby retry cancel-on-quit threw for "
                                + uuid + ": " + t.getMessage(), t);
            }
        }
        try {
            requestQueue.cancel(uuid, NetworkRequestQueue.CancelReason.PLAYER_DISCONNECT)
                    .exceptionally(t -> {
                        RTP.log(Level.WARNING,
                                "[RTP] waitlist cancel-on-quit failed for "
                                        + uuid + ": " + t.getMessage(), t);
                        return null;
                    });
        } catch (Throwable t) {
            // S-004: never silently swallow, but never let a quit handler
            // throw out of Bukkit's event dispatch.
            RTP.log(Level.WARNING,
                    "[RTP] waitlist cancel-on-quit threw synchronously for "
                            + uuid + ": " + t.getMessage(), t);
        }
    }
}
