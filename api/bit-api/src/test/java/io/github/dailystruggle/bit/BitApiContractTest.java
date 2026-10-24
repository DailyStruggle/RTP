package io.github.dailystruggle.bit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BitApiContractTest {
    @Test
    @DisplayName("REQ-API-F-009: Vocabulary suggestions honor an explicit edit-distance limit")
    void vocabularyMatchesCaseInsensitivelyAndSuggestsNearestValue() {
        Vocabulary keys = new Vocabulary("step key", List.of("CONSOLE", "MESSAGE", "FOR_EACH"));

        assertTrue(keys.contains("console"));
        assertEquals("MESSAGE", keys.suggest("MESAGE").orElseThrow());
        assertTrue(keys.suggest("not-a-step-key").isEmpty());

        Vocabulary objectives = new Vocabulary("objective", List.of("rtp_violations"));
        assertEquals("rtp_violations", objectives.suggest("rtp_violatons", 1).orElseThrow());
        assertTrue(objectives.suggest("rtp_violatons", 0).isEmpty());
    }

    @Test
    void keyPathFormatsNestedKeysAndIndexes() {
        assertEquals("actions.koth.onStart[3].CONSOLE",
                KeyPath.root("actions").key("koth").key("onStart").index(3).key("CONSOLE").toString());
        assertThrows(IllegalArgumentException.class, () -> KeyPath.root(" "));
        assertThrows(IllegalArgumentException.class, () -> KeyPath.root("a").index(-1));
    }

    @Test
    void reportDerivesHealthAndEscapesJsonStrings() {
        BitFinding warning = new BitFinding(Level.WARNING, "CONFIG_WARNING", "actions\".yml", "a\nb",
                "message", "shape", "");
        BitReport report = BitReport.of(List.of(warning));

        assertEquals(BitHealth.DEGRADED, report.health());
        assertTrue(report.toJson().contains("actions\\\".yml"));
        assertTrue(report.toJson().contains("a\\nb"));
        assertEquals(BitHealth.FAILED, BitReport.of(List.of(new BitFinding(Level.ERROR,
                "BAD", "", "", "", "", ""))).health());
    }

    @Test
    void mapValidatorReportsUnknownKeysAndExpectedShape() {
        Vocabulary keys = new Vocabulary("step key", List.of("CONSOLE"));
        List<BitFinding> findings = MapValidator.unknownKeys(Map.of("CONSOL", "say"), keys, "koth.yml",
                KeyPath.root("actions").key("koth"), "ACTION_UNKNOWN_STEP_KEY");

        assertEquals(1, findings.size());
        assertEquals("actions.koth.CONSOL", findings.get(0).path());
        assertEquals("CONSOLE", findings.get(0).suggestion());
        assertEquals("String", MapValidator.type(1, String.class, "", KeyPath.root("x"), "TYPE").expected());
        assertEquals("an existing reference", MapValidator.reference("missing", ignored -> false, "", KeyPath.root("ref"), "REF").expected());
        assertEquals("0.0..2.0", MapValidator.range(3, 0, 2, "", KeyPath.root("range"), "RANGE").expected());
        assertEquals("mode", MapValidator.required(Map.of(), "mode", "", KeyPath.root("config"), "REQUIRED").expected());
        assertTrue(MapValidator.unknownKeys(Map.of(), keys, "", KeyPath.root("x"), "UNKNOWN").isEmpty());
    }

    @Test
    void mapValidatorAcceptsValidValuesAndHandlesMissingOrNullValues() {
        Vocabulary modes = new Vocabulary("mode", List.of("safe", "fast"));
        KeyPath path = KeyPath.root("config");

        assertTrue(MapValidator.required(Map.of("mode", "safe"), "mode", "", path, "REQUIRED") == null);
        assertTrue(MapValidator.type("text", String.class, "", path, "TYPE") == null);
        assertTrue(MapValidator.type(null, String.class, "", path, "TYPE") == null);
        assertTrue(MapValidator.oneOf("SAFE", modes, "", path, "ENUM") == null);
        assertEquals("fast", MapValidator.oneOf("fasr", modes, "", path, "ENUM").suggestion());
        assertTrue(MapValidator.range(2, 0, 2, "", path, "RANGE") == null);
        assertTrue(MapValidator.range(null, 0, 2, "", path, "RANGE") == null);
        assertTrue(MapValidator.reference("loaded", "loaded"::equals, "", path, "REF") == null);
        assertTrue(MapValidator.reference(null, ignored -> false, "", path, "REF") == null);
        assertEquals("safe, fast", MapValidator.allowedValues(modes.values()));
        assertEquals("", MapValidator.allowedValues(null));
    }

    @Test
    void registryAggregatesComponentsWithoutThrowingOrWritingGlobally() {
        BitRegistry registry = new BitRegistry();
        registry.register("good", testable(PbitResult.ok("good", 1), BitHealth.HEALTHY));
        registry.register("bad", testable(PbitResult.failed("bad", 1, "not ready", null), BitHealth.FAILED));
        registry.register("throws", new BuiltInTestable() {
            @Override public PbitResult executePbit() { throw new IllegalStateException("boom"); }
            @Override public BitHealth getHealthStatus() { return BitHealth.HEALTHY; }
        });

        BitReport report = registry.runPbit();
        assertEquals(BitHealth.FAILED, report.health());
        assertEquals(List.of("BIT_PBIT_FAILED", "BIT_PBIT_THROWN"), report.findings().stream().map(BitFinding::code).toList());
        assertEquals(BitHealth.FAILED, registry.getHealthStatus());

        List<String> output = new ArrayList<>();
        assertEquals(1, BitCli.run(registry, true, output::add));
        assertTrue(output.get(0).startsWith("{\"schema\":1"));
        assertFalse(registry.unregister("missing"));
        assertTrue(registry.unregister("good"));
        assertFalse(registry.register("bad", testable(PbitResult.ok("duplicate", 0), BitHealth.HEALTHY)));
    }

    @Test
    void countersAndSimulationUtilitiesTrackTheirContracts() {
        CbitCounter counter = new CbitCounter();
        counter.increment();
        counter.add(2);
        assertEquals(3, counter.count());
        counter.reset();
        assertEquals(0, counter.count());
        assertThrows(IllegalArgumentException.class, () -> counter.add(-1));

        GateOutcomeCounter outcomes = new GateOutcomeCounter();
        outcomes.recordOutcome(false);
        assertEquals(GateOutcomeCounter.Outcome.INSUFFICIENT, outcomes.outcome(2));
        outcomes.recordOutcome(false);
        assertEquals(GateOutcomeCounter.Outcome.NEVER_PASSED, outcomes.outcome(2));
        outcomes.recordOutcome(true);
        assertEquals(GateOutcomeCounter.Outcome.MIXED, outcomes.outcome(2));
        assertThrows(IllegalArgumentException.class, () -> outcomes.outcome(0));

        RecordingDispatcher dispatcher = new RecordingDispatcher();
        dispatcher.dispatch("say test");
        assertEquals(List.of("say test"), dispatcher.commands());
        dispatcher.clear();
        assertTrue(dispatcher.commands().isEmpty());

        SimulatedClock clock = new SimulatedClock(5);
        assertEquals(8, clock.advanceNanos(3));
        assertThrows(IllegalArgumentException.class, () -> clock.advanceNanos(-1));
    }

    @Test
    void registryReportsDegradedHealthAndCliCanRenderHumanReadableOutput() {
        BitRegistry registry = new BitRegistry();
        registry.register("optional", testable(PbitResult.ok("optional", 1), BitHealth.DEGRADED));
        assertEquals(BitHealth.DEGRADED, registry.getHealthStatus());

        List<String> output = new ArrayList<>();
        assertEquals(0, BitCli.run(registry, false, output::add));
        assertEquals("BIT health: DEGRADED", output.get(0));
        assertTrue(output.get(1).contains("BIT_HEALTH_DEGRADED optional:"));
        assertEquals(BitHealth.HEALTHY, new BitRegistry().getHealthStatus());
        assertThrows(IllegalArgumentException.class, () -> registry.register(" ", testable(
                PbitResult.ok("blank", 0), BitHealth.HEALTHY)));
        assertThrows(NullPointerException.class, () -> registry.register("null", null));
    }

    @Test
    void registryConvertsNullResultsAndNullHealthIntoFailures() {
        BitRegistry registry = new BitRegistry();
        registry.register("invalid", new BuiltInTestable() {
            @Override public PbitResult executePbit() { return null; }
            @Override public BitHealth getHealthStatus() { return null; }
        });

        assertEquals(BitHealth.FAILED, registry.getHealthStatus());
        BitReport report = registry.runPbit();
        assertEquals(BitHealth.FAILED, report.health());
        assertEquals("BIT_PBIT_THROWN", report.findings().get(0).code());
    }

    @Test
    void reportValidatesSchemaAndEscapesAllJsonControlCharacters() {
        String controls = "\"\\\b\f\n\r\t" + (char) 1;
        BitReport report = new BitReport(BitReport.CURRENT_SCHEMA, BitHealth.HEALTHY,
                List.of(new BitFinding(Level.INFO, "INFO", "", "", controls, "", "")));
        String json = report.toJson();

        assertTrue(json.contains("\\\""));
        assertTrue(json.contains("\\\\"));
        assertTrue(json.contains("\\b"));
        assertTrue(json.contains("\\f"));
        assertTrue(json.contains("\\n"));
        assertTrue(json.contains("\\r"));
        assertTrue(json.contains("\\t"));
        assertTrue(json.contains("\\u0001"));
        assertEquals(List.of(), new BitReport(1, BitHealth.HEALTHY, null).findings());
        assertThrows(IllegalArgumentException.class, () -> new BitReport(0, BitHealth.HEALTHY, List.of()));
        assertThrows(NullPointerException.class, () -> new BitFinding(null, "CODE", "", "", "", "", ""));
        assertThrows(IllegalArgumentException.class,
                () -> new BitFinding(Level.ERROR, " ", "", "", "", "", ""));
    }

    private static BuiltInTestable testable(PbitResult result, BitHealth health) {
        return new BuiltInTestable() {
            @Override public PbitResult executePbit() { return result; }
            @Override public BitHealth getHealthStatus() { return health; }
        };
    }
}
