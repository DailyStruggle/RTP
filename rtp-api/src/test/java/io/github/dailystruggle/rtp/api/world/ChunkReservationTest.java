package io.github.dailystruggle.rtp.api.world;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle coverage for {@link ChunkReservation} (S-002, ADR-012): open/keep/refresh/close,
 * ownership transfer (release suppression), and {@link #awaitReady} outcomes. Installs a stub
 * {@link RTPServerAccessor} so the diagnostic {@code log(...)} branches actually execute.
 */
class ChunkReservationTest {

    private RTPServerAccessor previous;
    private final AtomicInteger logCalls = new AtomicInteger();

    @BeforeEach
    void installAccessor() {
        previous = RTPAPI.serverAccessor;
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "log":
                    logCalls.incrementAndGet();
                    return null;
                case "toString":
                    return "StubAccessor";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    if (method.isDefault()) {
                        return InvocationHandler.invokeDefault(proxy, method, args);
                    }
                    throw new UnsupportedOperationException(method.getName());
            }
        };
        RTPAPI.serverAccessor = (RTPServerAccessor) Proxy.newProxyInstance(
                ChunkReservationTest.class.getClassLoader(),
                new Class<?>[]{RTPServerAccessor.class},
                handler);
    }

    @AfterEach
    void restoreAccessor() {
        RTPAPI.serverAccessor = previous;
    }

    private static ChunkSet chunkSet(RTPWorld<?> world) {
        return new ChunkSet(world, 3, 4,
                List.of(CompletableFuture.completedFuture(0L)),
                new CompletableFuture<>());
    }

    @Test
    void openKeepRefreshCloseDrivesNativeToggles() {
        StubWorld world = new StubWorld();
        ChunkReservation res = new ChunkReservation(chunkSet(world), world);
        // Constructor applies the initial keep(true).
        assertEquals(1, world.trueCalls.get());
        assertNotNull(res.getChunkSet());
        assertEquals(3, res.getChunkSet().getX());

        // Ref-counted: a second keep(true) only bumps the counter, no native re-apply.
        res.keep(true);
        assertEquals(1, world.trueCalls.get());
        // One release drops count 2 -> 1, still no native toggle.
        res.keep(false);
        assertEquals(0, world.falseCalls.get());

        // refresh() reaches the native layer directly via setForceLoadedImpl(true).
        res.refresh();
        assertEquals(2, world.trueCalls.get());

        // close() releases the last ticket (count 1 -> 0), firing the native drop.
        res.close();
        assertEquals(1, world.falseCalls.get(), "close() releases via keep(false)");
        // Second close is a no-op (already released/transferred).
        res.close();
        assertEquals(1, world.falseCalls.get());

        assertTrue(logCalls.get() > 0, "diagnostic logging path exercised");
    }

    @Test
    void transferOwnershipSuppressesReleaseOnClose() {
        StubWorld world = new StubWorld();
        ChunkSet set = chunkSet(world);
        ChunkReservation res = new ChunkReservation(set, world);
        assertSame(set, res.transferOwnership());
        res.close();
        // Ownership transferred -> close must not release the ticket.
        assertEquals(0, world.falseCalls.get());
    }

    @Test
    void awaitReadyReturnsTrueWhenApplyFutureCompletes() throws Exception {
        StubWorld world = new StubWorld();
        ChunkReservation res = new ChunkReservation(chunkSet(world), world);
        assertNotNull(res.readyFuture());
        assertTrue(res.awaitReady(1, TimeUnit.SECONDS));
    }

    @Test
    void awaitReadyReturnsFalseOnTimeout() throws Exception {
        StubWorld world = new StubWorld();
        world.pending = new CompletableFuture<>(); // never completes
        ChunkReservation res = new ChunkReservation(chunkSet(world), world);
        assertFalse(res.awaitReady(50, TimeUnit.MILLISECONDS));
    }

    @Test
    void awaitReadyReturnsFalseOnExecutionFailure() throws Exception {
        StubWorld world = new StubWorld();
        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("apply failed"));
        world.pending = failed;
        ChunkReservation res = new ChunkReservation(chunkSet(world), world);
        assertFalse(res.awaitReady(1, TimeUnit.SECONDS));
    }

    /**
     * Minimal {@link RTPWorld} whose {@code setForceLoadedImpl} counts native toggles and can
     * return a caller-controlled apply future to drive {@link ChunkReservation#awaitReady}.
     */
    private static final class StubWorld extends RTPWorld<String> {
        final AtomicInteger trueCalls = new AtomicInteger();
        final AtomicInteger falseCalls = new AtomicInteger();
        CompletableFuture<Void> pending;

        StubWorld() { super("stub"); }

        @Override protected CompletableFuture<Void> setForceLoadedImpl(int cx, int cz, boolean f) {
            // The only native toggle hook: fired on ref-count 0<->1 transitions and by refresh().
            if (f) trueCalls.incrementAndGet(); else falseCalls.incrementAndGet();
            return (pending != null) ? pending : CompletableFuture.completedFuture(null);
        }

        @Override public String name() { return "stub"; }
        @Override public UUID id() { return new UUID(0L, 0L); }
        @Override public CompletableFuture<Long> getChunkAt(int chunkX, int chunkZ) {
            return CompletableFuture.completedFuture(0L);
        }
        @Override public CompletableFuture<ChunkSet> getChunkAtAsync(int cx, int cz) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Integer> getServerForceLoadedCount() {
            return CompletableFuture.completedFuture(0);
        }
        @Override public RTPChunk<?> getCachedChunk(long key) { return null; }
        @Override public void keepChunkAt(int chunkX, int chunkZ) { }
        @Override public void forgetChunkAt(int chunkX, int chunkZ) { }
        @Override public void forgetChunks() { }
        @Override public String getBiome(int x, int y, int z) { return ""; }
        @Override public void platform(RTPLocation location) { }
        @Override public boolean isInactive() { return false; }
        @Override public void save() { }
        @Override public int getMaxHeight() { return 320; }
        @Override public int getMinHeight() { return -64; }
        @Override public int getCacheSize() { return 0; }
        @Override public long getSeed() { return 0L; }
    }
}
