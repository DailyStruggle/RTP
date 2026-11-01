package io.github.dailystruggle.rtp.common.commands.editor.channel;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Test transport (ADR-106 §5.4): one end of a connected in-memory pair. A frame sent on one end
 * is delivered to the other end's listener on the sending thread, after any frame already being
 * delivered (no re-entrant recursion), so JUnit drives the full protocol without sockets.
 */
public final class InMemoryTransport implements ChannelTransport {

    private final String relay;
    private InMemoryTransport peer;
    private volatile Listener listener;
    private volatile boolean closed;
    private final Deque<String> inbox = new ArrayDeque<>();
    private boolean delivering;
    private volatile int framesPerWindow;
    private final java.util.concurrent.atomic.AtomicLong sent = new java.util.concurrent.atomic.AtomicLong();

    private InMemoryTransport(String relay) {
        this.relay = relay;
    }

    /** {@code [plugin end, page end]}. */
    public static InMemoryTransport[] pair() {
        InMemoryTransport a = new InMemoryTransport("memory://plugin");
        InMemoryTransport b = new InMemoryTransport("memory://page");
        a.peer = b;
        b.peer = a;
        return new InMemoryTransport[]{a, b};
    }

    @Override
    public CompletableFuture<Void> start(Listener l) {
        this.listener = Objects.requireNonNull(l, "listener");
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean send(String text) {
        Objects.requireNonNull(text, "text");
        if (closed || peer.closed) return false;
        sent.incrementAndGet();
        peer.deliver(text);
        return true;
    }

    /** Makes this end declare a relay-style frame limit (pacing tests); 0 = unlimited. */
    public InMemoryTransport withFramesPerWindow(int frames) {
        this.framesPerWindow = frames;
        return this;
    }

    @Override
    public int framesPerWindow() {
        return framesPerWindow;
    }

    /** Frames sent from this end so far. */
    public long sentCount() {
        return sent.get();
    }

    private void deliver(String text) {
        synchronized (this) {
            inbox.add(text);
            if (delivering) return;
            delivering = true;
        }
        while (true) {
            String next;
            synchronized (this) {
                next = inbox.poll();
                if (next == null) {
                    delivering = false;
                    return;
                }
            }
            Listener l = listener;
            if (l != null && !closed) l.onFrame(next);
        }
    }

    /** Tells the other end a page connected (the relay / loopback "client joined" event). */
    public void announceOpen() {
        Listener l = peer.listener;
        if (l != null) l.onPeerOpen();
    }

    @Override
    public String relay() {
        return relay;
    }

    @Override
    public boolean isOpen() {
        return !closed;
    }

    @Override
    public void close(String reason) {
        if (closed) return;
        closed = true;
        Listener l = listener;
        if (l != null) l.onClosed(reason);
    }
}
