package io.github.dailystruggle.rtp.common.commands.editor.channel;

import io.github.dailystruggle.rtp.common.commands.editor.EditorLoopbackChannel;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local transport (ADR-106 §5.4): the token-gated {@link EditorLoopbackChannel} on
 * {@code 127.0.0.1}. Its admission rules (loopback peer and Host, Origin, token, client limit)
 * stay as defence in depth under the signed protocol; every frame is broadcast to every client,
 * as on the relay.
 */
public final class LoopbackTransport implements ChannelTransport {

    /** Opens the socket; production uses {@link EditorLoopbackChannel#start}, tests a manually polled one. */
    @FunctionalInterface
    public interface Opener {
        EditorLoopbackChannel open(String token, EditorLoopbackChannel.Listener listener) throws IOException;
    }

    private final String token;
    private final Opener opener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile EditorLoopbackChannel socket;
    private volatile Listener listener;

    public LoopbackTransport() {
        this(EditorLoopbackChannel.newToken(), EditorLoopbackChannel::start);
    }

    public LoopbackTransport(String token, Opener opener) {
        this.token = Objects.requireNonNull(token, "token");
        this.opener = Objects.requireNonNull(opener, "opener");
    }

    @Override
    public CompletableFuture<Void> start(Listener l) {
        this.listener = Objects.requireNonNull(l, "listener");
        try {
            socket = opener.open(token, listener(l));
        } catch (IOException | RuntimeException e) {
            return CompletableFuture.failedFuture(e instanceof IOException ? e
                    : new IOException("loopback socket failed: " + e.getMessage(), e));
        }
        return CompletableFuture.completedFuture(null);
    }

    private static EditorLoopbackChannel.Listener listener(Listener l) {
        return new EditorLoopbackChannel.Listener() {
            @Override
            public void onMessage(EditorLoopbackChannel.Connection c, String text) {
                l.onFrame(text);
            }

            @Override
            public void onOpen(EditorLoopbackChannel.Connection c) {
                l.onPeerOpen();
            }
        };
    }

    @Override
    public boolean send(String text) {
        EditorLoopbackChannel s = socket;
        if (s == null || closed.get() || !s.isOpen()) return false;
        s.broadcast(text);
        return true;
    }

    @Override
    public String relay() {
        EditorLoopbackChannel s = socket;
        return s == null ? null : s.url();
    }

    @Override
    public boolean isOpen() {
        EditorLoopbackChannel s = socket;
        return !closed.get() && s != null && s.isOpen();
    }

    @Override
    public void close(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        EditorLoopbackChannel s = socket;
        if (s != null) {
            s.poll(); // best effort: flush a queued goodbye before the close frames
            s.stop(reason);
        }
        Listener l = listener;
        if (l != null) l.onClosed(reason);
    }

    /** The underlying socket, for manual polling in tests. */
    public EditorLoopbackChannel socket() {
        return socket;
    }
}
