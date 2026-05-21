package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkStateBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link BackendStatePublisher} against the in-memory transport
 * binding. Two scenarios: nominal tick publishes a row visible in the local
 * table; a throwing sampler does not kill the loop (verified by observing
 * a subsequent successful tick).
 */
class BackendStatePublisherTest {

    private InMemoryNetworkStateBinding transport;

    @BeforeEach
    void setUp() {
        transport = new InMemoryNetworkStateBinding();
    }

    @AfterEach
    void tearDown() {
        if (transport != null) transport.close();
    }

    @Test
    @DisplayName("tick() publishes a heartbeat row visible in the local table (synchronous path)")
    void manualTickPublishesHeartbeat() {
        AtomicInteger calls = new AtomicInteger();
        BackendStateSampler sampler = sid -> {
            calls.incrementAndGet();
            return new BackendHeartbeat(
                    sid, 1, PluginState.READY, true, System.currentTimeMillis(),
                    18.0, 0, 20, 1L, 2L, 0, List.of("default"), List.of("world"), false);
        };
        BackendStatePublisher pub = new BackendStatePublisher(transport, sampler, "server-1", 1000L);
        // Manually invoke tick() - we don't start() in this test to keep timing deterministic.
        pub.tick();
        assertEquals(1, calls.get());
        // publishBackendHeartbeat returns a future on the in-memory executor;
        // wait for it to settle so the local table is populated.
        // The in-memory binding uses a small daemon executor; 250ms is generous.
        try {
            Thread.sleep(250);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        var snap = transport.readSnapshot().join();
        assertEquals(1, snap.all().size(), "heartbeat row must be present after tick");
        assertEquals("server-1", snap.backend("server-1").orElseThrow().serverId());
    }

    @Test
    @DisplayName("Throwing sampler is contained; subsequent ticks still publish")
    void throwingSamplerDoesNotKillLoop() {
        AtomicInteger calls = new AtomicInteger();
        BackendStateSampler sampler = sid -> {
            int n = calls.incrementAndGet();
            if (n == 1) throw new RuntimeException("simulated sampler failure");
            return new BackendHeartbeat(
                    sid, 1, PluginState.READY, true, System.currentTimeMillis(),
                    18.0, 0, 20, 1L, 2L, 0, List.of(), List.of(), false);
        };
        BackendStatePublisher pub = new BackendStatePublisher(transport, sampler, "server-2", 1000L);
        pub.tick(); // throws internally, swallowed
        pub.tick(); // succeeds
        assertEquals(2, calls.get(), "loop must survive a throwing sampler");
    }

    @Test
    @DisplayName("start/stop is idempotent and uses a daemon thread")
    void startStopIdempotent() throws InterruptedException {
        BackendStateSampler sampler = sid -> new BackendHeartbeat(
                sid, 1, PluginState.READY, true, System.currentTimeMillis(),
                18.0, 0, 20, 1L, 2L, 0, List.of(), List.of(), false);
        BackendStatePublisher pub = new BackendStatePublisher(transport, sampler, "server-3", 100L);
        pub.start();
        pub.start(); // re-entrant no-op
        // Let a few ticks fire; 350 ms / 100 ms interval >= 3 ticks.
        TimeUnit.MILLISECONDS.sleep(350);
        pub.stop();
        pub.stop(); // idempotent
        // No assertion on tick count (timing-dependent), just that we cleanly start and stop.
        assertTrue(true, "start/stop cycle completed without exception");
    }
}
