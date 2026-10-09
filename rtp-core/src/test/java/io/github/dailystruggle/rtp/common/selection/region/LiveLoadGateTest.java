package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.common.metrics.LiveLoadGateRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-110 LiveLoadGate pin slots and counters")
class LiveLoadGateTest {

    @Test
    @DisplayName("pin slots are bounded by kept size + in-flight pins")
    void pinSlotsBoundedByKeptCapacity() {
        LiveLoadGate g = new LiveLoadGate("r", () -> 0L);
        AtomicInteger kept = new AtomicInteger(1);
        assertTrue(g.tryAcquirePin(kept::get, 3));
        assertTrue(g.tryAcquirePin(kept::get, 3));
        assertFalse(g.tryAcquirePin(kept::get, 3), "1 kept + 2 pins fills a cap of 3");
        g.releasePin();
        assertTrue(g.tryAcquirePin(kept::get, 3));
        kept.set(3);
        g.releasePin();
        assertFalse(g.tryAcquirePin(kept::get, 3), "kept queue full: no pin regardless of in-flight count");
    }

    @Test
    @DisplayName("zero capacity or a failing size supplier never grants a pin")
    void zeroCapOrFailingSupplierDenies() {
        LiveLoadGate g = new LiveLoadGate("r", () -> 0L);
        assertFalse(g.tryAcquirePin(() -> 0, 0));
        assertFalse(g.tryAcquirePin(() -> { throw new IllegalStateException(); }, 10));
        assertEquals(0, g.pinsInFlight());
    }

    @Test
    @DisplayName("release never drops below zero")
    void releaseFloorsAtZero() {
        LiveLoadGate g = new LiveLoadGate("r", () -> 0L);
        g.releasePin();
        g.releasePin();
        assertEquals(0, g.pinsInFlight());
    }

    @Test
    @DisplayName("snapshot reflects every counter, use split by pinned/unpinned")
    void snapshotCounts() {
        LiveLoadGate g = new LiveLoadGate("r", () -> 0L);
        g.onReadResolved();
        g.onDeferredCenter();
        g.onDeferredNeighbour();
        g.onDeferredNeighbour();
        g.onPinnedLoad();
        g.onPinnedHandoff(true);
        g.onPinnedHandoff(false);
        g.onRingSkipped();
        g.onUse(false, true);
        g.onUse(false, false);
        g.onUse(true, true);
        g.onUse(true, false);
        g.onUse(true, false);
        LiveLoadGateRow r = g.snapshot();
        assertEquals("r", r.region());
        assertEquals(1, r.readResolved());
        assertEquals(1, r.deferredCenter());
        assertEquals(2, r.deferredNeighbour());
        assertEquals(1, r.pinnedLoads());
        assertEquals(1, r.pinnedKept());
        assertEquals(1, r.pinnedOverflow());
        assertEquals(1, r.ringsSkipped());
        assertEquals(1, r.usedResident());
        assertEquals(1, r.usedEvicted());
        assertEquals(1, r.usedPinnedResident());
        assertEquals(2, r.usedPinnedEvicted());
    }

    @Test
    @DisplayName("summary line carries the phase tag and current counts")
    void summaryLineCarriesTag() {
        LiveLoadGate g = new LiveLoadGate("r", () -> 0L);
        g.onUse(true, true);
        String line = g.summaryLine("phase-end");
        assertTrue(line.startsWith("[RTP][load-gate] region=r tag=phase-end "), line);
        assertTrue(line.contains("usedPinned(resident/evicted)=1/0"), line);
        assertFalse(g.summaryLine(null).contains("tag="));
    }

    @Test
    @DisplayName("flushSummaries logs every gate regardless of the rate limit")
    void flushSummariesCoversAllGates() {
        LiveLoadGate.resetAll();
        try {
            assertEquals(0, LiveLoadGate.flushSummaries("x"));
            LiveLoadGate.of("a").onReadResolved();
            LiveLoadGate.of("b");
            assertEquals(2, LiveLoadGate.flushSummaries("phase-start"));
            assertEquals(2, LiveLoadGate.flushSummaries("phase-end"), "no 60 s gap needed between flushes");
        } finally {
            LiveLoadGate.resetAll();
        }
    }

    @Test
    @DisplayName("registry returns one gate per region")
    void registryPerRegion() {
        LiveLoadGate.resetAll();
        try {
            assertSame(LiveLoadGate.of("a"), LiveLoadGate.of("a"));
            assertNotSame(LiveLoadGate.of("a"), LiveLoadGate.of("b"));
            assertEquals(2, LiveLoadGate.snapshotAll().size());
        } finally {
            LiveLoadGate.resetAll();
        }
    }
}
