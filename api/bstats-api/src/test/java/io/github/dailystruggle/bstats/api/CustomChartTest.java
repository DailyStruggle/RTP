package io.github.dailystruggle.bstats.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Chart JSON and skip rules match the upstream {@code org.bstats.charts} serialisation. */
class CustomChartTest {

    private static Map<String, Integer> map(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        return m;
    }

    @Test
    @DisplayName("SimplePie")
    void simplePie() {
        assertEquals("{\"chartId\":\"p\",\"data\":{\"value\":\"a\\\"b\"}}", new SimplePie("p", () -> "a\"b").toJson());
        assertNull(new SimplePie("p", () -> null).toJson());
        assertNull(new SimplePie("p", () -> "").toJson());
        assertNull(new SimplePie("p", () -> { throw new IllegalStateException(); }).toJson());
        assertEquals("p", new SimplePie("p", () -> "x").getChartId());
    }

    @Test
    @DisplayName("AdvancedPie and MultiLineChart drop zero values")
    void valuesCharts() {
        assertEquals("{\"chartId\":\"a\",\"data\":{\"values\":{\"x\":2}}}",
                new AdvancedPie("a", () -> map("x", 2, "y", 0)).toJson());
        assertNull(new AdvancedPie("a", () -> map("y", 0)).toJson());
        assertNull(new AdvancedPie("a", LinkedHashMap::new).toJson());
        assertNull(new AdvancedPie("a", () -> null).toJson());
        assertEquals("{\"chartId\":\"m\",\"data\":{\"values\":{\"l1\":5,\"l2\":7}}}",
                new MultiLineChart("m", () -> map("l1", 5, "l2", 7)).toJson());
        assertNull(new MultiLineChart("m", () -> map("l1", 0)).toJson());
    }

    @Test
    @DisplayName("SingleLineChart skips zero")
    void singleLine() {
        assertEquals("{\"chartId\":\"s\",\"data\":{\"value\":4}}", new SingleLineChart("s", () -> 4).toJson());
        assertNull(new SingleLineChart("s", () -> 0).toJson());
        assertNull(new SingleLineChart("s", () -> null).toJson());
    }

    @Test
    @DisplayName("DrilldownPie nests values and drops empty outer slices")
    void drilldown() {
        Map<String, Map<String, Integer>> d = new LinkedHashMap<>();
        d.put("paper", map("<25", 1));
        d.put("empty", new LinkedHashMap<>());
        d.put("folia", map("25-50", 1, "50-100", 2));
        assertEquals("{\"chartId\":\"d\",\"data\":{\"values\":{\"paper\":{\"<25\":1},\"folia\":{\"25-50\":1,\"50-100\":2}}}}",
                new DrilldownPie("d", () -> d).toJson());
        Map<String, Map<String, Integer>> allEmpty = new LinkedHashMap<>();
        allEmpty.put("x", new LinkedHashMap<>());
        assertNull(new DrilldownPie("d", () -> allEmpty).toJson());
        assertNull(new DrilldownPie("d", () -> null).toJson());
    }

    @Test
    @DisplayName("Blank chart IDs are rejected; JSON strings are escaped")
    void validationAndEscaping() {
        assertThrows(IllegalArgumentException.class, () -> new SimplePie(" ", () -> "x"));
        assertThrows(IllegalArgumentException.class, () -> new SimplePie(null, () -> "x"));
        assertEquals("\"a\\\\b\\n\\r\\t\\u0001\"", BStatsJson.quote("a\\b\n\r\t\u0001"));
        assertEquals("\"\"", BStatsJson.quote(null));
        assertEquals("true", BStatsJson.value(Boolean.TRUE));
        assertEquals("\"\"", BStatsJson.value(null));
    }
}
