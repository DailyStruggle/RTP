package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code backlogRefillThreshold} performance knob governing L3 backlog cache refill hysteresis.
 */
class BacklogRefillThresholdTest {

    @Test
    void enumMemberExists() {
        PerformanceKeys k = PerformanceKeys.valueOf("backlogRefillThreshold");
        assertNotNull(k);
    }

    @Test
    void shippedYamlDeclaresDefaultHalf() throws IOException {
        Path yml = Path.of("..", "rtp-plugin", "src", "main", "resources", "advanced", "performance.yml");
        if (!Files.exists(yml)) {
            yml = Path.of("rtp-plugin", "src", "main", "resources", "advanced", "performance.yml");
        }
        assertTrue(Files.exists(yml), "performance.yml not found at " + yml.toAbsolutePath());
        String content = Files.readString(yml);
        assertTrue(content.contains("backlogRefillThreshold:"),
                "performance.yml must declare backlogRefillThreshold");
        assertTrue(content.contains("backlogRefillThreshold: 0.5"),
                "default backlogRefillThreshold must be 0.5");
    }

    /**
     * Mirrors the hysteresis latch in {@code Region#processBacklog}:
     * <ul>
     *   <li>active && size >= capacity      → deactivate</li>
     *   <li>!active && size < floor(t*cap)  → activate</li>
     * </ul>
     * Returns the new active state.
     */
    private static boolean nextActive(boolean active, int size, int capacity, double threshold) {
        if (active) {
            if (size >= capacity) return false;
            return true;
        } else {
            return size < (long) Math.floor(threshold * capacity);
        }
    }

    @Test
    void startsActiveAndFillsToCapacity() {
        // Fresh region: active=true. As size climbs to capacity, latch flips.
        boolean active = true;
        int cap = 100;
        for (int size = 0; size < cap; size++) {
            active = nextActive(active, size, cap, 0.5d);
            assertTrue(active, "must stay active while filling (size=" + size + ")");
        }
        // size == cap: latch deactivates.
        active = nextActive(active, cap, cap, 0.5d);
        assertFalse(active, "must deactivate once capacity reached");
    }

    @Test
    void inactiveStaysInactiveUntilThresholdCrossed() {
        // After fill, latch is inactive. Drain one slot at a time; refill must
        // not re-arm until size < 0.5 * 100 == 50.
        boolean active = false;
        int cap = 100;
        for (int size = cap; size >= 50; size--) {
            active = nextActive(active, size, cap, 0.5d);
            assertFalse(active, "must remain inactive above threshold (size=" + size + ")");
        }
        // size == 49: strictly below floor(0.5*100)=50 → re-arm.
        active = nextActive(active, 49, cap, 0.5d);
        assertTrue(active, "must re-arm once size drops strictly below threshold");
    }

    @Test
    void thresholdZeroNeverReArms() {
        // Admin opt-out: with threshold 0.0, an inactive latch never re-arms,
        // so the backlog drains fully and stays empty until something else
        // (e.g. config reload) intervenes.
        boolean active = false;
        for (int size = 50; size >= 0; size--) {
            active = nextActive(active, size, 100, 0.0d);
            assertFalse(active, "threshold 0.0 must never re-arm refill (size=" + size + ")");
        }
    }

    @Test
    void thresholdOneReArmsOnAnyDrain() {
        // threshold 1.0 reproduces the prior always-refill behaviour: as soon
        // as one slot is consumed, the latch re-arms.
        boolean active = false;
        active = nextActive(active, 99, 100, 1.0d);
        assertTrue(active, "threshold 1.0 must re-arm on any drain below capacity");
    }

    @Test
    void floorRoundingMatchesIntegerCapacity() {
        // floor(0.5 * 7) == 3. So at capacity=7, refill re-arms only when
        // size < 3, i.e. size in {0,1,2}.
        assertEquals(3L, (long) Math.floor(0.5d * 7));
        assertFalse(nextActive(false, 3, 7, 0.5d), "size==3 must not re-arm at cap=7");
        assertTrue(nextActive(false, 2, 7, 0.5d), "size==2 must re-arm at cap=7");
    }
}
