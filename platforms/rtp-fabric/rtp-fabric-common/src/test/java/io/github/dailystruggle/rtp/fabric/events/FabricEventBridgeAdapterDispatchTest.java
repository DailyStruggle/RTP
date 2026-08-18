package io.github.dailystruggle.rtp.fabric.events;

import io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter;
import io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry;
import io.github.dailystruggle.rtp.fabric.version.RTPChunkHandle;
import io.github.dailystruggle.rtp.fabric.version.RTPLevelHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies that {@link FabricEventBridge#extractPlayerFromHandler(Object)} dispatches
 * to the active {@link FabricVersionAdapter} via the registry - replacing the previous
 * reflective {@code getPlayer()} / public-{@code player}-field walk.
 *
 * <p>It exercises three cases: null handler, no adapter installed, and a stub adapter
 * that returns a sentinel object. The sentinel verifies that the bridge passes the
 * incoming handler through unchanged and returns whatever the adapter resolves.
 */
class FabricEventBridgeAdapterDispatchTest {

    @BeforeEach
    void setUp() {
        FabricVersionAdapterRegistry.clearForTesting();
    }

    @AfterEach
    void tearDown() {
        FabricVersionAdapterRegistry.clearForTesting();
    }

    @Test
    void nullHandlerReturnsNullWithoutInvokingAdapter() {
        AtomicInteger calls = new AtomicInteger();
        FabricVersionAdapterRegistry.install(new StubAdapter(h -> {
            calls.incrementAndGet();
            return new Object();
        }));

        Object result = FabricEventBridge.extractPlayerFromHandler(null);

        assertNull(result, "null handler must short-circuit to null");
        assertEquals(0, calls.get(), "adapter must not be consulted for null handler");
    }

    @Test
    void noAdapterInstalledReturnsNull() {
        // Registry intentionally cleared in setUp.
        Object result = FabricEventBridge.extractPlayerFromHandler(new Object());
        assertNull(result, "missing adapter must yield null (S-006 fail-loud-but-survive)");
    }

    @Test
    void installedAdapterReceivesHandlerAndItsResultIsReturned() {
        Object handler = new Object();
        Object sentinelPlayer = new Object();
        AtomicReference<Object> seen = new AtomicReference<>();
        FabricVersionAdapterRegistry.install(new StubAdapter(h -> {
            seen.set(h);
            return sentinelPlayer;
        }));

        Object result = FabricEventBridge.extractPlayerFromHandler(handler);

        assertSame(handler, seen.get(), "adapter must receive the same handler the bridge was given");
        assertSame(sentinelPlayer, result, "bridge must return whatever the adapter resolves");
    }

    @Test
    void adapterThrowingIsCaughtAndNullReturned() {
        FabricVersionAdapterRegistry.install(new StubAdapter(h -> {
            throw new RuntimeException("simulated adapter failure");
        }));

        Object result = FabricEventBridge.extractPlayerFromHandler(new Object());

        assertNull(result, "adapter exceptions must be swallowed (S-006 fail-loud-but-survive)");
    }

    /** Minimal stub implementing only the abstract surface; delegates extract via lambda. */
    private static final class StubAdapter implements FabricVersionAdapter {
        interface Extract { Object apply(Object handler); }
        private final Extract extract;
        StubAdapter(Extract extract) { this.extract = extract; }

        @Override public String mcVersion() { return "test-stub"; }
        @Override public CompletableFuture<RTPChunkHandle> getChunkFull(RTPLevelHandle level, int cx, int cz) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public Object extractPlayerFromConnection(Object handler) { return extract.apply(handler); }
    }
}
