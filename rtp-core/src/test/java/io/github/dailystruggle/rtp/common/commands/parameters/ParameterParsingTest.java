package io.github.dailystruggle.rtp.common.commands.parameters;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
}
