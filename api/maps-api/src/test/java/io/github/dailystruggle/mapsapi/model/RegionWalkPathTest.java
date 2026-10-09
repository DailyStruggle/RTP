package io.github.dailystruggle.mapsapi.model;

import io.github.dailystruggle.mapsapi.model.RegionWalkPath.StepStatus;
import io.github.dailystruggle.mapsapi.model.RegionWalkPath.WalkStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RegionWalkPath - validation, defensive copies and value semantics")
class RegionWalkPathTest {

    private static final List<WalkStep> STEPS = List.of(new WalkStep(1, 0, StepStatus.VALID, 0.5f));

    private static RegionWalkPath base() {
        return new RegionWalkPath("r", 2, 1, 0, 0, 2, 1,
                new boolean[]{true, false}, new int[]{1, 2}, new boolean[]{false, true}, STEPS);
    }

    @Test
    @DisplayName("constructor rejects invalid arguments")
    void validation() {
        boolean[] two = {true, true};
        assertThrows(NullPointerException.class,
                () -> new RegionWalkPath(null, 2, 1, two, STEPS));
        assertThrows(IllegalArgumentException.class,
                () -> new RegionWalkPath("r", 0, 1, two, STEPS));
        assertThrows(IllegalArgumentException.class,
                () -> new RegionWalkPath("r", 2, 0, two, STEPS));
        assertThrows(NullPointerException.class,
                () -> new RegionWalkPath("r", 2, 1, null, STEPS));
        assertThrows(IllegalArgumentException.class,
                () -> new RegionWalkPath("r", 3, 1, two, STEPS));
        assertThrows(NullPointerException.class,
                () -> new RegionWalkPath("r", 2, 1, two, null));
        assertThrows(NullPointerException.class,
                () -> new WalkStep(0, 0, null, 0.0f));
    }

    @Test
    @DisplayName("arrays and steps are copied on construction and on read")
    void defensiveCopies() {
        boolean[] inside = {true, false};
        int[] biome = {7, 8};
        boolean[] hazard = {true, true};
        List<WalkStep> steps = new ArrayList<>(STEPS);
        RegionWalkPath m = new RegionWalkPath("r", 2, 1, 0, 0, 2, 1, inside, biome, hazard, steps);

        inside[0] = false;
        biome[0] = 99;
        hazard[0] = false;
        steps.clear();
        m.insideDomain()[1] = true;
        m.biomeRgb()[1] = 99;
        m.hazardMask()[1] = false;

        assertArrayEquals(new boolean[]{true, false}, m.insideDomain());
        assertArrayEquals(new int[]{7, 8}, m.biomeRgb());
        assertArrayEquals(new boolean[]{true, true}, m.hazardMask());
        assertEquals(STEPS, m.steps());
        assertThrows(UnsupportedOperationException.class, () -> m.steps().clear());
    }

    @Test
    @DisplayName("convenience constructor uses grid bounds and empty terrain arrays")
    void convenienceConstructor() {
        RegionWalkPath m = new RegionWalkPath("r", 2, 1, new boolean[]{true, true}, STEPS);
        assertEquals(0, m.minX());
        assertEquals(0, m.minZ());
        assertEquals(2, m.maxX());
        assertEquals(1, m.maxZ());
        assertEquals(0, m.biomeRgb().length);
        assertEquals(0, m.hazardMask().length);
        assertEquals(2L, m.boundW());
        assertEquals(1L, m.boundH());
    }

    @Test
    @DisplayName("degenerate or inverted bounds clamp to a span of 1")
    void boundsClamp() {
        RegionWalkPath m = new RegionWalkPath("r", 1, 1, 10, 10, 5, 10,
                new boolean[]{true}, null, null, List.of());
        assertEquals(1L, m.boundW());
        assertEquals(1L, m.boundH());
    }

    @Test
    @DisplayName("equals / hashCode compare every component, arrays by content")
    void equalsAndHashCode() {
        RegionWalkPath a = base();
        RegionWalkPath b = base();
        assertEquals(a, a);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, null);
        assertNotEquals(a, "r");

        boolean[] in2 = {true, false};
        int[] bio = {1, 2};
        boolean[] hz = {false, true};
        assertNotEquals(a, new RegionWalkPath("r", 1, 2, 0, 0, 2, 1, in2, bio, hz, STEPS));
        assertNotEquals(a, new RegionWalkPath("r", 2, 2, 0, 0, 2, 1,
                new boolean[4], bio, hz, STEPS));
        assertNotEquals(a, new RegionWalkPath("r", 2, 1, 1, 0, 2, 1, in2, bio, hz, STEPS));
        assertNotEquals(a, new RegionWalkPath("r", 2, 1, 0, 1, 2, 1, in2, bio, hz, STEPS));
        assertNotEquals(a, new RegionWalkPath("r", 2, 1, 0, 0, 3, 1, in2, bio, hz, STEPS));
        assertNotEquals(a, new RegionWalkPath("r", 2, 1, 0, 0, 2, 2, in2, bio, hz, STEPS));
        assertNotEquals(a, new RegionWalkPath("x", 2, 1, 0, 0, 2, 1, in2, bio, hz, STEPS));
        assertNotEquals(a, new RegionWalkPath("r", 2, 1, 0, 0, 2, 1,
                new boolean[]{true, true}, bio, hz, STEPS));
        assertNotEquals(a, new RegionWalkPath("r", 2, 1, 0, 0, 2, 1, in2, new int[]{1, 3}, hz, STEPS));
        assertNotEquals(a, new RegionWalkPath("r", 2, 1, 0, 0, 2, 1, in2, bio,
                new boolean[]{true, true}, STEPS));
        assertNotEquals(a, new RegionWalkPath("r", 2, 1, 0, 0, 2, 1, in2, bio, hz, List.of()));
    }

    @Test
    @DisplayName("toString lists every component")
    void toStringListsComponents() {
        String s = base().toString();
        assertTrue(s.startsWith("RegionWalkPath[regionName=r"));
        assertTrue(s.contains("insideDomain=[true, false]"));
        assertTrue(s.contains("biomeRgb=[1, 2]"));
        assertTrue(s.contains("hazardMask=[false, true]"));
        assertTrue(s.contains("steps=["));
    }
}
