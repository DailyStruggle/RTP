package io.github.dailystruggle.rtp.common.commands.editor.channel;

import java.util.concurrent.CompletableFuture;

/**
 * Carrier for {@link EditorChannel} frames (ADR-106 §5.4): text out, text in, open / closed.
 *
 * <p>Every transport is a broadcast medium: a frame sent reaches every connected page, and replies
 * are addressed by the {@code to} field inside the signed message, exactly as on the public relay.
 * Transports know nothing about envelopes, keys or trust, so the protocol code is identical for the
 * relay (hosted), the loopback socket (local) and the in-memory pair (tests).
 */
public interface ChannelTransport {

    /** Callbacks may run on any thread; keep them short and non-blocking. */
    interface Listener {
        void onFrame(String text);

        /** A page connected or reconnected; the channel may re-announce itself. */
        default void onPeerOpen() {
        }

        /** The transport closed for good (expired, stopped, failed); {@code reason} is logged. */
        default void onClosed(String reason) {
        }

        /**
         * The transport's lifetime is over while it is still connected: the owner may send a last
         * frame and must then {@link ChannelTransport#close close} it (the transport closes itself
         * right after this returns either way).
         */
        default void onExpired(String reason) {
        }
    }

    /**
     * Opens the transport; frames reach {@code listener} from then on. Never blocks: local
     * transports return a completed future, the relay one completes when its first join does.
     *
     * @return completes once open; exceptionally with an {@link java.io.IOException} naming the
     * reason when the transport cannot be opened (the session continues snapshot-only)
     */
    CompletableFuture<Void> start(Listener listener);

    /**
     * Queues one text frame to every connected page; any thread, never blocks.
     *
     * @return {@code false} when the transport is closed or refused the frame
     */
    boolean send(String text);

    /** Address the page connects to, written to the snapshot's {@code channel.relay}. */
    String relay();

    boolean isOpen();

    /** Idempotent; any thread. */
    void close(String reason);
}
