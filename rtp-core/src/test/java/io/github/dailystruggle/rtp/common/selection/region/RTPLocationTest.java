package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RTPLocationTest {

    private static RTPCoords coords(int x, int y, int z) {
        return new RTPCoords("world", x, y, z);
    }

    @Test
    void twoArgConstructor_setsNullReservation() {
        RTPCoords c = coords(10, 64, -5);
        RTPLocation loc = new RTPLocation(c, 3);
        assertSame(c, loc.coords());
        assertEquals(3, loc.attempts());
        assertNull(loc.reservation());
    }

    @Test
    void threeArgConstructor_setsAllFields() {
        RTPCoords c = coords(0, 0, 0);
        RTPLocation loc = new RTPLocation(c, 7, null);
        assertEquals(7, loc.attempts());
        assertNull(loc.reservation());
    }

    @Test
    void recordEquality_sameValues() {
        RTPCoords c = coords(1, 2, 3);
        RTPLocation a = new RTPLocation(c, 1);
        RTPLocation b = new RTPLocation(c, 1);
        assertEquals(a, b);
    }

    @Test
    void recordEquality_differentAttempts() {
        RTPCoords c = coords(1, 2, 3);
        RTPLocation a = new RTPLocation(c, 1);
        RTPLocation b = new RTPLocation(c, 2);
        assertNotEquals(a, b);
    }

    @Test
    void recordEquality_differentCoords() {
        RTPLocation a = new RTPLocation(coords(1, 2, 3), 1);
        RTPLocation b = new RTPLocation(coords(4, 5, 6), 1);
        assertNotEquals(a, b);
    }

    @Test
    void hashCode_consistentWithEquals() {
        RTPCoords c = coords(10, 20, 30);
        RTPLocation a = new RTPLocation(c, 5);
        RTPLocation b = new RTPLocation(c, 5);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void toString_containsCoords() {
        RTPCoords c = coords(7, 8, 9);
        RTPLocation loc = new RTPLocation(c, 2);
        String s = loc.toString();
        assertNotNull(s);
        assertTrue(s.contains("RTPLocation"));
    }

    @Test
    void coords_getChunkKey_correctForOrigin() {
        RTPCoords c = coords(0, 64, 0);
        assertEquals(0L, c.getChunkKey());
    }

    @Test
    void coords_getChunkKey_nonZero() {
        RTPCoords c = coords(16, 64, 16);
        // chunk x=1, chunk z=1 → key = 1 | (1L << 32)
        long expected = 1L | (1L << 32);
        assertEquals(expected, c.getChunkKey());
    }
}
