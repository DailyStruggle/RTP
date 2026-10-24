package io.github.dailystruggle.bit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BitApiEdgeCaseTest {
    @Test
    void vocabularyHandlesNullBlankAndEmptyInputs() {
        Vocabulary unnamed = new Vocabulary(null, null);
        assertEquals("values", unnamed.name());
        assertTrue(unnamed.values().isEmpty());
        assertTrue(unnamed.suggest("x").isEmpty());
        assertFalse(unnamed.contains(null));

        Vocabulary withBlanks = new Vocabulary("v", Arrays.asList("A", null, " ", "a"));
        assertEquals(List.of("A"), withBlanks.values());
        assertTrue(withBlanks.suggest(null).isEmpty());
        assertTrue(withBlanks.suggest(" ").isEmpty());
    }

    @Test
    void driftCheckReportsUnhandledDeclaredValuesAndToleratesNulls() {
        Vocabulary declared = new Vocabulary("v", List.of("A", "B"));
        assertEquals(List.of("declared value is not handled: A", "declared value is not handled: B"),
                DriftCheck.diff(declared, null));
        assertEquals(List.of("declared value is not handled: B"),
                DriftCheck.diff(declared, Arrays.asList("a", null)));
    }

    @Test
    void switchLabelsHandlesMissingInputsUnclosedBodiesAndEscapes() {
        assertTrue(SwitchLabels.extract(null, "x").isEmpty());
        assertTrue(SwitchLabels.extract("switch (x) {", null).isEmpty());
        assertTrue(SwitchLabels.extract("switch (x) {", " ").isEmpty());
        assertTrue(SwitchLabels.extract("switch (x) { case \"A\" -> {", "x").isEmpty());

        String source = "switch (x) { /* } */ case \"Q\" -> f('}', '\\''); case B: g(\"\\\"}\"); }";
        assertEquals(List.of("Q", "B"), SwitchLabels.extract(source, "x"));
    }

    @Test
    void registryIncludesErrorMessagesAndReportsFailedHealthOnPassingPbit() {
        BitRegistry registry = new BitRegistry();
        registry.register("a", testable(PbitResult.failed("a", 1, "", new IllegalStateException("cause")),
                BitHealth.HEALTHY));
        registry.register("b", testable(PbitResult.failed("b", 1, "msg", new IllegalStateException("cause")),
                BitHealth.HEALTHY));
        registry.register("c", testable(PbitResult.failed("c", 1, "msg", new IllegalStateException()),
                BitHealth.HEALTHY));
        registry.register("d", testable(PbitResult.ok("d", 1), BitHealth.FAILED));
        registry.register("e", new BuiltInTestable() {
            @Override public PbitResult executePbit() { throw new IllegalStateException(); }
            @Override public BitHealth getHealthStatus() { throw new IllegalStateException(); }
        });

        List<BitFinding> findings = registry.runPbit().findings();
        assertEquals("cause", findings.get(0).message());
        assertEquals("msg: cause", findings.get(1).message());
        assertEquals("msg", findings.get(2).message());
        assertEquals("BIT_HEALTH_FAILED", findings.get(3).code());
        assertEquals(IllegalStateException.class.getName(), findings.get(4).message());
        assertEquals(BitHealth.FAILED, registry.getHealthStatus());
        assertFalse(registry.unregister(null));
        assertThrows(NullPointerException.class, () -> registry.register(null, testable(null, null)));
    }

    @Test
    void cliRendersHealthyRegistryAndRejectsNullArguments() {
        BitRegistry registry = new BitRegistry();
        registry.register("x", testable(PbitResult.ok("x", 0), BitHealth.HEALTHY));
        List<String> output = new ArrayList<>();
        assertEquals(0, BitCli.run(registry, false, output::add));
        assertEquals(List.of("BIT health: HEALTHY"), output);
        assertThrows(NullPointerException.class, () -> BitCli.run(null, false, output::add));
        assertThrows(NullPointerException.class, () -> BitCli.run(registry, false, null));
    }

    @Test
    void valueTypesNormalizeNullsAndRejectInvalidArguments() {
        PbitResult result = new PbitResult(true, null, -5, null, null);
        assertEquals("", result.componentName());
        assertEquals("", result.diagnosticMessage());
        assertEquals(0L, result.durationNanos());
        assertEquals("Operational", PbitResult.ok("c", 1).diagnosticMessage());

        BitFinding finding = new BitFinding(Level.INFO, "C", null, null, null, null, null);
        assertEquals("", finding.source());
        assertEquals("", finding.suggestion());
        assertThrows(IllegalArgumentException.class,
                () -> new BitFinding(Level.INFO, null, "", "", "", "", ""));

        assertThrows(NullPointerException.class, () -> new RecordingDispatcher().dispatch(null));
        assertThrows(IllegalArgumentException.class, () -> new SimulatedClock(-1));
        assertEquals(0L, new SimulatedClock().nanoTime());

        GateOutcomeCounter always = new GateOutcomeCounter();
        always.recordOutcome(true);
        assertEquals(GateOutcomeCounter.Outcome.ALWAYS_PASSED, always.outcome(1));
        assertEquals(1, always.passed());
    }

    private static BuiltInTestable testable(PbitResult result, BitHealth health) {
        return new BuiltInTestable() {
            @Override public PbitResult executePbit() { return result; }
            @Override public BitHealth getHealthStatus() { return health; }
        };
    }
}
