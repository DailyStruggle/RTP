package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Polygon;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Edge branches of the snapshot curve model: naming, key detection, normalisation, run codecs and typed settings (ADR-106). */
@DisplayName("ADR-106: EditorCurveModel edge branches")
class EditorCurveModelBranchTest {

    @TempDir
    Path tempDir;

    private RTPServerAccessor previousAccessor;

    @BeforeEach
    void accessor() {
        previousAccessor = RTP.serverAccessor;
        RTP.serverAccessor = new MockRTPServerAccessor(tempDir.toFile());
    }

    @AfterEach
    void restoreAccessor() {
        RTP.serverAccessor = previousAccessor;
    }

    /** Polygon with a fixed vertex list, bypassing validation and the async mask walker. */
    private static Polygon polygon(List<int[]> vertices) {
        return new Polygon("CURVE_BRANCH_POLY") {
            @Override
            public List<int[]> getVertices() {
                return vertices;
            }
        };
    }

    private static long[] t(long start, long len, long value) {
        return new long[]{start, len, value};
    }

    // ---------------------------------------------------------------------------------------------
    // Naming and helper key detection
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("registeredName drops a .YML suffix, keeps other names, and falls back to the class name")
    void registeredName() {
        assertEquals("CURVE_NAMED", EditorCurveModel.registeredName(new Square("CURVE_NAMED.YML")));
        assertEquals("CURVE_PLAIN", EditorCurveModel.registeredName(new Square("CURVE_PLAIN")));
        Square unnamed = new Square("x");
        unnamed.name = null;
        assertEquals("Square", EditorCurveModel.registeredName(unnamed));
    }

    @Test
    @DisplayName("curveParams finds quoted and dotted keys only; vertices only for polygons whose helper reads it")
    void curveParamsDetection() {
        Square square = new Square("CURVE_KEYS");
        assertEquals(List.of(), EditorCurveModel.curveParams(square, null));
        assertEquals(List.of(), EditorCurveModel.curveParams(square, "radius"), "a key at offset 0 has no prefix");
        assertEquals(List.of(), EditorCurveModel.curveParams(square, "x'radius"), "quote before, end of input after");
        assertEquals(List.of(), EditorCurveModel.curveParams(square, "x'radius;"), "quote before, no quote after");
        assertEquals(List.of(), EditorCurveModel.curveParams(square, "p.radiusX p.radius_q"), "dotted key inside a longer word");
        assertEquals(List.of("radius"), EditorCurveModel.curveParams(square, "p.radiusX + p.radius"), "second dotted hit at end");
        assertEquals(List.of("radius"), EditorCurveModel.curveParams(square, "num(params, \"radius\", 1)"));
        List<String> both = EditorCurveModel.curveParams(square, "params['centerX'] + p.centerZ*2");
        assertTrue(both.containsAll(List.of("centerX", "centerZ")), both.toString());
        assertFalse(both.contains("vertices"), "squares never report vertices");
        assertFalse(EditorCurveModel.curveParams(square, "p.vertices").contains("vertices"));

        Polygon poly = polygon(List.of());
        assertTrue(EditorCurveModel.curveParams(poly, "params.vertices.length").contains("vertices"));
        assertFalse(EditorCurveModel.curveParams(poly, "params.radius").contains("vertices"));
    }

    @Test
    @DisplayName("params copies polygon vertices when present, skips empty or missing vertex lists and null settings")
    void paramsVerticesAndNulls() {
        int[] a = {0, 0};
        int[] b = {10, 0};
        int[] c = {0, 10};
        Map<String, Object> withVertices = EditorCurveModel.params(polygon(List.of(a, b, c)), List.of("vertices", "radius", "nope"));
        assertEquals(List.of(a, b, c), withVertices.get("vertices"));
        assertFalse(withVertices.containsKey("nope"), "unknown key has a null value and is skipped");

        assertFalse(EditorCurveModel.params(polygon(List.of()), List.of("vertices")).containsKey("vertices"));
        assertFalse(EditorCurveModel.params(polygon(null), List.of("vertices")).containsKey("vertices"));

        Square square = new Square("CURVE_PARAMS_SQ");
        square.set(GenericMemoryShapeParams.radius, 32L);
        Map<String, Object> sq = EditorCurveModel.params(square, List.of("vertices", "radius"));
        assertFalse(sq.containsKey("vertices"), "non-polygon vertices key resolves to an absent setting");
        assertEquals(32L, sq.get("radius"));
    }

    // ---------------------------------------------------------------------------------------------
    // Normalisation and numeric state
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("normalise null, floats, non-finite and huge doubles, comma decimals and padded booleans")
    void normaliseEdges() {
        assertNull(EditorCurveModel.normalise(null));
        assertEquals(3L, EditorCurveModel.normalise(3.0f));
        assertEquals(2.5, ((Number) EditorCurveModel.normalise(2.5f)).doubleValue(), 1e-9);
        assertNull(EditorCurveModel.normalise(Double.NaN));
        assertNull(EditorCurveModel.normalise(Double.POSITIVE_INFINITY));
        assertEquals(1e300, EditorCurveModel.normalise(1e300), "beyond 2^53 stays a double");
        assertEquals(7L, EditorCurveModel.normalise(7L));
        assertEquals(1.5, EditorCurveModel.normalise("1,5"));
        assertEquals(Boolean.TRUE, EditorCurveModel.normalise("  TRUE "));
        assertEquals(Boolean.FALSE, EditorCurveModel.normalise("False"));
    }

    @Test
    @DisplayName("numericState keeps finite numbers in key order and drops nulls, NaN and non-numbers")
    void numericState() {
        assertEquals(Map.of(), EditorCurveModel.numericState(null));
        Map<String, Object> state = new HashMap<>();
        state.put("b", 2.0);
        state.put("a", 1);
        state.put("nan", Double.NaN);
        state.put("text", "x");
        state.put("f", 0.25);
        Map<String, Object> out = EditorCurveModel.numericState(state);
        assertEquals(List.of("a", "b", "f"), new ArrayList<>(out.keySet()));
        assertEquals(1, out.get("a"));
        assertEquals(2L, out.get("b"));
        assertEquals(0.25, out.get("f"));
    }

    // ---------------------------------------------------------------------------------------------
    // Hazard run codecs
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("encodeHazardRuns skips negative positions and runs swallowed by the previous run")
    void encodeSkipsNegativeAndSwallowed() {
        int biome = LocationGenerator.FailTypes.biome.ordinal();
        long[] keys = {-5, 10, 11, Long.MAX_VALUE - 1};
        long[] sums = {2, 12, 14, 16}; // lengths 2, 10, 2, 2
        byte[] causes = {(byte) biome, (byte) biome, (byte) biome, (byte) biome};
        List<long[]> runs = EditorCurveModel.decodeHazardRuns(EditorCurveModel.encodeHazardRuns(keys, sums, causes));
        assertEquals(1, runs.size(), "negative start, swallowed run and overflowing end all skipped");
        assertArrayEquals(t(10, 10, biome + 1), runs.get(0));
    }

    @Test
    @DisplayName("encodeRuns round-trips through decodeHazardRuns, multi-byte varints included")
    void encodeRunsRoundTrip() {
        List<long[]> runs = List.of(t(3, 2, 1), t(300, 70_000, 5), t(1L << 40, 1, 2));
        List<long[]> back = EditorCurveModel.decodeHazardRuns(EditorCurveModel.encodeRuns(runs));
        assertEquals(runs.size(), back.size());
        for (int i = 0; i < runs.size(); i++) assertArrayEquals(runs.get(i), back.get(i));
        assertEquals(0, EditorCurveModel.encodeRuns(List.of()).length);
    }

    @Test
    @DisplayName("decodeHazardRuns rejects a truncated stream and an over-long varint")
    void decodeRejectsBadVarints() {
        assertThrows(IllegalArgumentException.class, () -> EditorCurveModel.decodeHazardRuns(new byte[]{5, 2}));
        assertThrows(IllegalArgumentException.class, () -> EditorCurveModel.decodeHazardRuns(new byte[]{(byte) 0x80}));
        byte[] tooLong = new byte[12];
        java.util.Arrays.fill(tooLong, (byte) 0x80);
        assertThrows(IllegalArgumentException.class, () -> EditorCurveModel.decodeHazardRuns(tooLong));
    }

    @Test
    @DisplayName("diffRuns: removed-only, added-only, same-start replacement and trailing removals satisfy before - remove + add == after")
    void diffRunsAllBranches() {
        List<long[]> before = List.of(t(0, 5, 1), t(10, 2, 1), t(20, 3, 2), t(40, 1, 1), t(60, 1, 1));
        List<long[]> after = List.of(t(0, 5, 1), t(15, 1, 1), t(20, 4, 2), t(40, 1, 1));
        List<long[]> add = new ArrayList<>();
        List<long[]> remove = new ArrayList<>();
        EditorCurveModel.diffRuns(before, after, add, remove);

        assertEquals(3, remove.size());
        assertArrayEquals(t(10, 2, 1), remove.get(0), "before-only run ahead of the next after run");
        assertArrayEquals(t(20, 3, 2), remove.get(1), "same start, different length");
        assertArrayEquals(t(60, 1, 1), remove.get(2), "trailing run with after exhausted");
        assertEquals(2, add.size());
        assertArrayEquals(t(15, 1, 1), add.get(0), "after-only run");
        assertArrayEquals(t(20, 4, 2), add.get(1), "replacement added alongside its removal");

        List<long[]> addOnly = new ArrayList<>();
        List<long[]> removeOnly = new ArrayList<>();
        EditorCurveModel.diffRuns(List.of(), List.of(t(1, 1, 1)), addOnly, removeOnly);
        assertEquals(1, addOnly.size());
        assertTrue(removeOnly.isEmpty());
    }

    // ---------------------------------------------------------------------------------------------
    // Typed settings
    // ---------------------------------------------------------------------------------------------

    private abstract static class StubParameter extends CommandParameter {
        private final Set<String> values;

        StubParameter(Set<String> values) {
            super("rtp.test", "stub", (u, s) -> true);
            this.values = values;
        }

        @Override
        public Set<String> values() {
            return values;
        }
    }

    static class DistanceParameter extends StubParameter {
        DistanceParameter() {
            super(Set.of());
        }
    }

    static class IntegerParameter extends StubParameter {
        IntegerParameter() {
            super(Set.of());
        }
    }

    static class FloatParameter extends StubParameter {
        FloatParameter() {
            super(Set.of());
        }
    }

    static class BooleanParameter extends StubParameter {
        BooleanParameter() {
            super(Set.of());
        }
    }

    static class EnumParameter extends StubParameter {
        EnumParameter() {
            super(Set.of());
        }
    }

    static final class AddonDistance extends DistanceParameter {
    }

    static final class OtherParameter extends StubParameter {
        OtherParameter(Set<String> values) {
            super(values);
        }
    }

    @Test
    @DisplayName("kindOf maps each known parameter class name, walks up the hierarchy, and is null otherwise")
    void kindOf() {
        assertEquals("distance", EditorCurveModel.kindOf(new DistanceParameter()));
        assertEquals("distance", EditorCurveModel.kindOf(new AddonDistance()), "add-on subclass inherits the kind");
        assertEquals("integer", EditorCurveModel.kindOf(new IntegerParameter()));
        assertEquals("number", EditorCurveModel.kindOf(new FloatParameter()));
        assertEquals("boolean", EditorCurveModel.kindOf(new BooleanParameter()));
        assertEquals("enum", EditorCurveModel.kindOf(new EnumParameter()));
        assertNull(EditorCurveModel.kindOf(new OtherParameter(Set.of())));
    }

    @Test
    @DisplayName("suggestions: failing or null values are empty; blanks dropped; numbers first with ties by text; capped")
    void suggestions() {
        CommandParameter failing = new StubParameter(Set.of()) {
            @Override
            public Set<String> values() {
                throw new IllegalStateException("not ready");
            }
        };
        assertEquals(List.of(), EditorCurveModel.suggestions(failing));
        assertEquals(List.of(), EditorCurveModel.suggestions(new OtherParameter(null)));

        Set<String> values = new LinkedHashSet<>();
        values.add("beta");
        values.add("10");
        values.add(" ");
        values.add("1.0");
        values.add("alpha");
        values.add("1");
        values.add("-2");
        values.add(null);
        assertEquals(List.of("-2", "1", "1.0", "10", "alpha", "beta"),
                EditorCurveModel.suggestions(new OtherParameter(values)));

        Set<String> many = new LinkedHashSet<>();
        for (int i = 0; i < EditorCurveModel.MAX_SUGGESTIONS + 8; i++) many.add("v" + (char) ('a' + i % 26) + i);
        List<String> capped = EditorCurveModel.suggestions(new OtherParameter(many));
        assertEquals(EditorCurveModel.MAX_SUGGESTIONS, capped.size());
    }
}
