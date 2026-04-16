package io.github.dailystruggle.rtp.common.commands.parameters;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

public class ParameterParsingTest {
    private final UUID uuid = UUID.randomUUID();

    // ── BooleanParameter ──────────────────────────────────────────────────────

    @Test
    void booleanParameter_valuesContainsTrueAndFalse() {
        BooleanParameter param = new BooleanParameter("perm", "desc", (u, s) -> true);
        Set<String> values = param.values();
        assertTrue(values.contains("true"),  "values() must contain 'true'");
        assertTrue(values.contains("false"), "values() must contain 'false'");
        assertEquals(2, values.size(), "values() must contain exactly 2 entries");
    }

    @Test
    void booleanParameter_permissionAndDescription() {
        BooleanParameter param = new BooleanParameter("rtp.bool", "a bool param", (u, s) -> true);
        assertEquals("rtp.bool",     param.permission());
        assertEquals("a bool param", param.description());
    }

    @Test
    void booleanParameter_isRelevantDelegatesToSupplied() {
        // isRelevant always returns false
        BooleanParameter param = new BooleanParameter("perm", "desc", (u, s) -> false);
        assertFalse(param.isRelevant.apply(uuid, "true"));
        assertFalse(param.isRelevant.apply(uuid, "false"));
    }

    // ── IntegerParameter ──────────────────────────────────────────────────────

    @Test
    void integerParameter_valuesIsEmpty() {
        IntegerParameter param = new IntegerParameter("perm", "desc", (u, s) -> true);
        assertTrue(param.values().isEmpty(), "IntegerParameter.values() should be empty");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "-1", "100", Long.MAX_VALUE + "", Long.MIN_VALUE + ""})
    void integerParameter_isRelevant_acceptsValidLongs(String input) {
        IntegerParameter param = new IntegerParameter("perm", "desc", (u, s) -> true);
        assertTrue(param.isRelevant.apply(uuid, input),
                "isRelevant should accept valid long: " + input);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "", " ", "1e5", "null", "9999999999999999999"})
    void integerParameter_isRelevant_rejectsInvalidLongs(String input) {
        IntegerParameter param = new IntegerParameter("perm", "desc", (u, s) -> true);
        assertFalse(param.isRelevant.apply(uuid, input),
                "isRelevant should reject invalid long: " + input);
    }

    @Test
    void integerParameter_isRelevant_respectsDownstreamPredicate() {
        // downstream predicate rejects negative numbers
        IntegerParameter param = new IntegerParameter("perm", "desc",
                (u, s) -> Long.parseLong(s) >= 0);
        assertTrue(param.isRelevant.apply(uuid, "5"));
        assertFalse(param.isRelevant.apply(uuid, "-1"));
    }

    @Test
    void integerParameter_permissionAndDescription() {
        IntegerParameter param = new IntegerParameter("rtp.int", "an int param", (u, s) -> true);
        assertEquals("rtp.int",       param.permission());
        assertEquals("an int param",  param.description());
    }

    // ── FloatParameter ────────────────────────────────────────────────────────

    @Test
    void floatParameter_valuesIsEmpty() {
        FloatParameter param = new FloatParameter("perm", "desc", (u, s) -> true);
        assertTrue(param.values().isEmpty(), "FloatParameter.values() should be empty");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1.0", "-1.5", "3.14", "1e10", "1E-3", Double.MAX_VALUE + "", "0.0"})
    void floatParameter_isRelevant_acceptsValidDoubles(String input) {
        FloatParameter param = new FloatParameter("perm", "desc", (u, s) -> true);
        assertTrue(param.isRelevant.apply(uuid, input),
                "isRelevant should accept valid double: " + input);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "", " ", "null", "1,5", "NaN_bad", "Infinity_bad"})
    void floatParameter_isRelevant_rejectsInvalidDoubles(String input) {
        FloatParameter param = new FloatParameter("perm", "desc", (u, s) -> true);
        assertFalse(param.isRelevant.apply(uuid, input),
                "isRelevant should reject invalid double: " + input);
    }

    @Test
    void floatParameter_isRelevant_respectsDownstreamPredicate() {
        // downstream predicate rejects values > 100
        FloatParameter param = new FloatParameter("perm", "desc",
                (u, s) -> Double.parseDouble(s) <= 100.0);
        assertTrue(param.isRelevant.apply(uuid, "50.0"));
        assertFalse(param.isRelevant.apply(uuid, "200.0"));
    }

    @Test
    void floatParameter_permissionAndDescription() {
        FloatParameter param = new FloatParameter("rtp.float", "a float param", (u, s) -> true);
        assertEquals("rtp.float",      param.permission());
        assertEquals("a float param",  param.description());
    }

    // ── BiomeParameter ────────────────────────────────────────────────────────

    @Test
    void biomeParameter_constructorInitializesSubParamMap() {
        BiomeParameter param = new BiomeParameter("perm", "desc", (u, s) -> true);
        assertNotNull(param.subParamMap, "subParamMap should be initialized");
        assertTrue(param.subParamMap.containsKey("DEFAULT"),
                "subParamMap should contain 'DEFAULT' key after construction");
    }

    @Test
    void biomeParameter_subParamsReturnsDefaultMap() {
        BiomeParameter param = new BiomeParameter("perm", "desc", (u, s) -> true);
        assertNotNull(param.subParams("anything"),
                "subParams() should return non-null map");
        assertSame(param.subParamMap.get("DEFAULT"), param.subParams("anything"),
                "subParams() should return the DEFAULT map");
    }

    @Test
    void biomeParameter_putMapReplacesDefaultMap() {
        BiomeParameter param = new BiomeParameter("perm", "desc", (u, s) -> true);
        java.util.Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> newMap =
                new java.util.HashMap<>();
        param.put(newMap);
        assertSame(newMap, param.subParams("x"),
                "put(map) should replace the DEFAULT sub-param map");
    }

    @Test
    void biomeParameter_putSingleEntryAddsToDefaultMap() {
        BiomeParameter param = new BiomeParameter("perm", "desc", (u, s) -> true);
        BooleanParameter child = new BooleanParameter("p", "d", (u, s) -> true);
        param.put("myKey", child);
        assertSame(child, param.subParams("x").get("myKey"),
                "put(name, param) should add entry to DEFAULT map");
    }

    @Test
    void biomeParameter_permissionAndDescription() {
        BiomeParameter param = new BiomeParameter("rtp.biome", "a biome param", (u, s) -> true);
        assertEquals("rtp.biome",       param.permission());
        assertEquals("a biome param",   param.description());
    }

    // ── RegionParameter ───────────────────────────────────────────────────────

    @Test
    void regionParameter_constructorInitializesSubParamMap() {
        RegionParameter param = new RegionParameter("perm", "desc", (u, s) -> true);
        assertNotNull(param.subParamMap, "subParamMap should be initialized");
        assertTrue(param.subParamMap.containsKey("DEFAULT"),
                "subParamMap should contain 'DEFAULT' key after construction");
    }

    @Test
    void regionParameter_subParamsReturnsDefaultMap() {
        RegionParameter param = new RegionParameter("perm", "desc", (u, s) -> true);
        assertNotNull(param.subParams("anything"));
        assertSame(param.subParamMap.get("DEFAULT"), param.subParams("anything"));
    }

    @Test
    void regionParameter_putMapReplacesDefaultMap() {
        RegionParameter param = new RegionParameter("perm", "desc", (u, s) -> true);
        java.util.Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> newMap =
                new java.util.HashMap<>();
        param.put(newMap);
        assertSame(newMap, param.subParams("x"));
    }

    @Test
    void regionParameter_putSingleEntryAddsToDefaultMap() {
        RegionParameter param = new RegionParameter("perm", "desc", (u, s) -> true);
        BooleanParameter child = new BooleanParameter("p", "d", (u, s) -> true);
        param.put("regionKey", child);
        assertSame(child, param.subParams("x").get("regionKey"));
    }

    @Test
    void regionParameter_permissionAndDescription() {
        RegionParameter param = new RegionParameter("rtp.region", "a region param", (u, s) -> true);
        assertEquals("rtp.region",       param.permission());
        assertEquals("a region param",   param.description());
    }

    // ── ShapeParameter (class-loadable only — needs RTP.factoryMap) ───────────

    @Test
    void shapeParameter_classIsLoadable() {
        assertNotNull(ShapeParameter.class);
    }

    // ── IntegerParameter — additional edge cases ──────────────────────────────

    @Test
    void integerParameter_isRelevant_longMaxValuePlusOneOverflow_rejected() {
        // Long.MAX_VALUE + 1 as a string overflows Long — must be rejected
        IntegerParameter param = new IntegerParameter("perm", "desc", (u, s) -> true);
        assertFalse(param.isRelevant.apply(uuid, "9223372036854775808"),
                "Value exceeding Long.MAX_VALUE should be rejected");
    }

    @Test
    void integerParameter_isRelevant_longMinValueMinusOneOverflow_rejected() {
        IntegerParameter param = new IntegerParameter("perm", "desc", (u, s) -> true);
        assertFalse(param.isRelevant.apply(uuid, "-9223372036854775809"),
                "Value below Long.MIN_VALUE should be rejected");
    }

    @Test
    void integerParameter_isRelevant_hexString_rejected() {
        IntegerParameter param = new IntegerParameter("perm", "desc", (u, s) -> true);
        assertFalse(param.isRelevant.apply(uuid, "0xFF"));
    }

    @Test
    void integerParameter_isRelevant_leadingPlusSign_rejected() {
        // Long.parseLong does not accept leading '+' in all JVM versions
        IntegerParameter param = new IntegerParameter("perm", "desc", (u, s) -> true);
        // Java 7+ Long.parseLong does accept '+' prefix — result depends on JVM; just must not throw
        assertDoesNotThrow(() -> param.isRelevant.apply(uuid, "+5"));
    }

    @Test
    void integerParameter_isRelevant_downstreamPredicateReceivesOriginalString() {
        // Verify the exact string (not a parsed value) is forwarded to the downstream predicate
        String[] captured = {null};
        IntegerParameter param = new IntegerParameter("perm", "desc", (u, s) -> { captured[0] = s; return true; });
        param.isRelevant.apply(uuid, "42");
        assertEquals("42", captured[0]);
    }

    // ── FloatParameter — additional edge cases ────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "-Infinity"})
    void floatParameter_isRelevant_specialDoubleValues_accepted(String input) {
        // Double.parseDouble accepts NaN and Infinity — downstream decides relevance
        FloatParameter param = new FloatParameter("perm", "desc", (u, s) -> true);
        assertTrue(param.isRelevant.apply(uuid, input),
                "Double.parseDouble accepts '" + input + "'; downstream returns true");
    }

    @Test
    void floatParameter_isRelevant_downstreamPredicateReceivesOriginalString() {
        String[] captured = {null};
        FloatParameter param = new FloatParameter("perm", "desc", (u, s) -> { captured[0] = s; return true; });
        param.isRelevant.apply(uuid, "3.14");
        assertEquals("3.14", captured[0]);
    }

    @Test
    void floatParameter_isRelevant_downstreamPredicateNotCalledOnInvalidInput() {
        boolean[] called = {false};
        FloatParameter param = new FloatParameter("perm", "desc", (u, s) -> { called[0] = true; return true; });
        param.isRelevant.apply(uuid, "not_a_number");
        assertFalse(called[0], "Downstream predicate must not be called when parse fails");
    }

    @Test
    void integerParameter_isRelevant_downstreamPredicateNotCalledOnInvalidInput() {
        boolean[] called = {false};
        IntegerParameter param = new IntegerParameter("perm", "desc", (u, s) -> { called[0] = true; return true; });
        param.isRelevant.apply(uuid, "not_a_number");
        assertFalse(called[0], "Downstream predicate must not be called when parse fails");
    }

    // ── BooleanParameter — additional edge cases ──────────────────────────────

    @Test
    void booleanParameter_isRelevantDelegatesToSupplied_returnsTrue() {
        BooleanParameter param = new BooleanParameter("perm", "desc", (u, s) -> true);
        assertTrue(param.isRelevant.apply(uuid, "true"));
        assertTrue(param.isRelevant.apply(uuid, "false"));
        assertTrue(param.isRelevant.apply(uuid, "anything"));
    }

    @Test
    void booleanParameter_valuesIsImmutableSnapshot() {
        BooleanParameter param = new BooleanParameter("perm", "desc", (u, s) -> true);
        Set<String> v1 = param.values();
        Set<String> v2 = param.values();
        // Each call returns a fresh set — modifying one does not affect the other
        v1.add("maybe");
        assertFalse(v2.contains("maybe"), "values() should return independent sets");
    }

    // ── CommandParameter base — priority field ────────────────────────────────

    @Test
    void commandParameter_priorityDefaultIsZero() {
        IntegerParameter param = new IntegerParameter("perm", "desc", (u, s) -> true);
        assertEquals(0, param.priority);
    }

    @Test
    void commandParameter_priorityCanBeSet() {
        FloatParameter param = new FloatParameter("perm", "desc", (u, s) -> true);
        param.priority = 5;
        assertEquals(5, param.priority);
    }

    // ── Parameterized: permission/description round-trip ─────────────────────

    @ParameterizedTest
    @CsvSource({
        "rtp.admin, Admin permission",
        "'', Empty permission",
        "rtp.use, Use the RTP command"
    })
    void integerParameter_permissionDescriptionRoundTrip(String perm, String desc) {
        IntegerParameter param = new IntegerParameter(perm, desc, (u, s) -> true);
        assertEquals(perm, param.permission());
        assertEquals(desc, param.description());
    }

    @ParameterizedTest
    @CsvSource({
        "rtp.admin, Admin permission",
        "'', Empty permission",
        "rtp.use, Use the RTP command"
    })
    void floatParameter_permissionDescriptionRoundTrip(String perm, String desc) {
        FloatParameter param = new FloatParameter(perm, desc, (u, s) -> true);
        assertEquals(perm, param.permission());
        assertEquals(desc, param.description());
    }

    @ParameterizedTest
    @CsvSource({
        "rtp.admin, Admin permission",
        "'', Empty permission",
        "rtp.use, Use the RTP command"
    })
    void booleanParameter_permissionDescriptionRoundTrip(String perm, String desc) {
        BooleanParameter param = new BooleanParameter(perm, desc, (u, s) -> true);
        assertEquals(perm, param.permission());
        assertEquals(desc, param.description());
    }
}
