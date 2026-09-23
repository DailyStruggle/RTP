package io.github.dailystruggle.helpers.stresstestrtp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage of the ticket-footprint probe's classifiers. The
 * scheduling half needs a live server and is exercised on the test rig.
 */
class TicketFootprintProbeTest {

    @Test
    @DisplayName("heap growth within 16 MiB per counted chunk stays attributable")
    void plausibleGrowthIsAttributable() {
        assertTrue(TicketFootprintProbe.heapAttributable(9L, 9L * 2L * 1024L * 1024L));
        assertTrue(TicketFootprintProbe.heapAttributable(1L,
                TicketFootprintProbe.MAX_PLAUSIBLE_BYTES_PER_CHUNK));
    }

    @Test
    @DisplayName("growth no chunk could explain is labelled unattributable, never divided per chunk")
    void implausibleGrowthIsUnattributable() {
        // The 2026-09-20 Folia run: 0 counted chunks, +4.3 GB in the window.
        assertFalse(TicketFootprintProbe.heapAttributable(0L, 4_311_744_512L));
        assertFalse(TicketFootprintProbe.heapAttributable(1L, 4_311_744_512L));
        assertFalse(TicketFootprintProbe.heapAttributable(9L,
                9L * (TicketFootprintProbe.MAX_PLAUSIBLE_BYTES_PER_CHUNK + 1L)));
    }

    @Test
    @DisplayName("no growth, or an unmeasured delta, is trivially attributable")
    void noGrowthIsAttributable() {
        assertTrue(TicketFootprintProbe.heapAttributable(0L, 0L));
        assertTrue(TicketFootprintProbe.heapAttributable(0L, TicketFootprintProbe.NO_DATA));
    }

    @Test
    @DisplayName("shape names exact odd squares and reports anything else literally")
    void shapeClassification() {
        assertEquals("", TicketFootprintProbe.classifyShape(-1L));
        assertEquals("NONE", TicketFootprintProbe.classifyShape(0L));
        assertEquals("1x1", TicketFootprintProbe.classifyShape(1L));
        assertEquals("3x3", TicketFootprintProbe.classifyShape(9L));
        assertEquals("5x5", TicketFootprintProbe.classifyShape(25L));
        assertEquals("IRREGULAR", TicketFootprintProbe.classifyShape(4L));
        assertEquals("IRREGULAR", TicketFootprintProbe.classifyShape(10L));
    }

    @Test
    @DisplayName("a collection inside the window makes every heap figure unattributable")
    void reclaimClassification() {
        assertEquals(TicketFootprintProbe.RECLAIM_GC_DURING_WINDOW,
                TicketFootprintProbe.classifyReclaim(1L, 100L, 50L));
        assertEquals(TicketFootprintProbe.RECLAIM_NET_ZERO,
                TicketFootprintProbe.classifyReclaim(0L, 0L, 0L));
        assertEquals(TicketFootprintProbe.RECLAIM_PROMPT,
                TicketFootprintProbe.classifyReclaim(0L, 100L, 40L));
        assertEquals(TicketFootprintProbe.RECLAIM_DEFERRED,
                TicketFootprintProbe.classifyReclaim(0L, 100L, 90L));
    }

    @Test
    @DisplayName("load wait cap is bounded and longer than the default settle window")
    void loadWaitCapIsBounded() {
        assertTrue(TicketFootprintProbe.MAX_LOAD_WAIT_TICKS >= 40L);
        assertTrue(TicketFootprintProbe.MAX_LOAD_WAIT_TICKS <= 20L * 60L);
    }
}
