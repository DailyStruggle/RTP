package io.github.dailystruggle.mapsapi.model;

import io.github.dailystruggle.mapsapi.model.SelectionHeatmap.SelectionPoint;
import io.github.dailystruggle.mapsapi.model.SelectionHeatmap.SelectionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SelectionHeatmap - validation, defensive copies and value semantics")
class SelectionHeatmapTest {

    private static final List<SelectionPoint> POINTS =
            List.of(new SelectionPoint(1, 0, 2, SelectionType.ARRIVAL));

    private static SelectionHeatmap of(String name, int w, int h, int minX, int minZ, int maxX, int maxZ,
                                       boolean[] inside, int[] biome, double[] density, double max,
                                       List<SelectionPoint> points) {
        return new SelectionHeatmap(name, w, h, minX, minZ, maxX, maxZ, inside, biome, density, max, points);
    }

    private static SelectionHeatmap base() {
        return of("r", 2, 1, 0, 0, 2, 1, new boolean[]{true, false}, new int[]{1, 2},
                new double[]{0.5, 1.5}, 1.5, POINTS);
    }

    @Test
    @DisplayName("constructor rejects invalid arguments")
    void validation() {
        boolean[] in = {true, true};
        double[] d = {0, 0};
        assertThrows(NullPointerException.class, () -> of(null, 2, 1, 0, 0, 1, 1, in, null, d, 0, POINTS));
        assertThrows(IllegalArgumentException.class, () -> of("r", 0, 1, 0, 0, 1, 1, in, null, d, 0, POINTS));
        assertThrows(IllegalArgumentException.class, () -> of("r", 2, 0, 0, 0, 1, 1, in, null, d, 0, POINTS));
        assertThrows(NullPointerException.class, () -> of("r", 2, 1, 0, 0, 1, 1, null, null, d, 0, POINTS));
        assertThrows(IllegalArgumentException.class,
                () -> of("r", 2, 1, 0, 0, 1, 1, new boolean[3], null, d, 0, POINTS));
        assertThrows(NullPointerException.class, () -> of("r", 2, 1, 0, 0, 1, 1, in, null, null, 0, POINTS));
        assertThrows(IllegalArgumentException.class,
                () -> of("r", 2, 1, 0, 0, 1, 1, in, null, new double[3], 0, POINTS));
        assertThrows(NullPointerException.class, () -> of("r", 2, 1, 0, 0, 1, 1, in, null, d, 0, null));
        assertThrows(NullPointerException.class, () -> new SelectionPoint(0, 0, 1, null));
    }

    @Test
    @DisplayName("arrays and points are copied on construction and on read")
    void defensiveCopies() {
        boolean[] inside = {true, false};
        int[] biome = {7, 8};
        double[] density = {1.0, 2.0};
        List<SelectionPoint> points = new ArrayList<>(POINTS);
        SelectionHeatmap m = of("r", 2, 1, 0, 0, 2, 1, inside, biome, density, 2.0, points);

        inside[0] = false;
        biome[0] = 99;
        density[0] = 99.0;
        points.clear();
        m.insideDomain()[1] = true;
        m.biomeRgb()[1] = 99;
        m.densityGrid()[1] = 99.0;

        assertArrayEquals(new boolean[]{true, false}, m.insideDomain());
        assertArrayEquals(new int[]{7, 8}, m.biomeRgb());
        assertArrayEquals(new double[]{1.0, 2.0}, m.densityGrid());
        assertEquals(POINTS, m.points());
    }

    @Test
    @DisplayName("null terrain becomes empty; inverted bounds clamp to a span of 1")
    void nullTerrainAndBoundsClamp() {
        SelectionHeatmap m = of("r", 1, 1, 10, 10, 5, 10, new boolean[]{true}, null,
                new double[1], 0.0, List.of());
        assertEquals(0, m.biomeRgb().length);
        assertEquals(1L, m.boundW());
        assertEquals(1L, m.boundH());
        assertEquals(2L, base().boundW());
    }

    @Test
    @DisplayName("equals / hashCode compare every component, arrays by content")
    void equalsAndHashCode() {
        SelectionHeatmap a = base();
        assertEquals(a, a);
        assertEquals(a, base());
        assertEquals(a.hashCode(), base().hashCode());
        assertNotEquals(a, null);
        assertNotEquals(a, "r");

        boolean[] in = {true, false};
        int[] bio = {1, 2};
        double[] d = {0.5, 1.5};
        assertNotEquals(a, of("r", 1, 2, 0, 0, 2, 1, in, bio, d, 1.5, POINTS));
        assertNotEquals(a, of("r", 2, 2, 0, 0, 2, 1, new boolean[4], bio, new double[4], 1.5, POINTS));
        assertNotEquals(a, of("r", 2, 1, 1, 0, 2, 1, in, bio, d, 1.5, POINTS));
        assertNotEquals(a, of("r", 2, 1, 0, 1, 2, 1, in, bio, d, 1.5, POINTS));
        assertNotEquals(a, of("r", 2, 1, 0, 0, 3, 1, in, bio, d, 1.5, POINTS));
        assertNotEquals(a, of("r", 2, 1, 0, 0, 2, 2, in, bio, d, 1.5, POINTS));
        assertNotEquals(a, of("r", 2, 1, 0, 0, 2, 1, in, bio, d, 9.0, POINTS));
        assertNotEquals(a, of("x", 2, 1, 0, 0, 2, 1, in, bio, d, 1.5, POINTS));
        assertNotEquals(a, of("r", 2, 1, 0, 0, 2, 1, new boolean[]{true, true}, bio, d, 1.5, POINTS));
        assertNotEquals(a, of("r", 2, 1, 0, 0, 2, 1, in, new int[]{1, 3}, d, 1.5, POINTS));
        assertNotEquals(a, of("r", 2, 1, 0, 0, 2, 1, in, bio, new double[]{0.5, 2.5}, 1.5, POINTS));
        assertNotEquals(a, of("r", 2, 1, 0, 0, 2, 1, in, bio, d, 1.5, List.of()));
    }

    @Test
    @DisplayName("toString lists every component")
    void toStringListsComponents() {
        String s = base().toString();
        assertTrue(s.startsWith("SelectionHeatmap[regionName=r"));
        assertTrue(s.contains("densityGrid=[0.5, 1.5]"));
        assertTrue(s.contains("maxDensity=1.5"));
        assertTrue(s.contains("points=["));
    }
}
