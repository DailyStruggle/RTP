package io.github.dailystruggle.bit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("REQ-API-F-009 BIT runner and output contract")
class BitRunnerTest {
    private static final Executor DIRECT = Runnable::run;

    @Test
    void selectorsParseNamesTiersSubsystemsAndAll() {
        assertEquals(BitSelector.Kind.ALL, BitSelector.parse("all").kind());
        assertEquals(BitTier.IBIT, BitSelector.parse("tier:ibit").tier());
        assertEquals("actions", BitSelector.parse("subsystem:actions").value());
        assertEquals("probe", BitSelector.parse("probe").value());
        assertThrows(IllegalArgumentException.class, () -> BitSelector.parse("tier:unknown"));
        assertThrows(IllegalArgumentException.class, () -> BitSelector.parse(" "));
    }

    @Test
    void groupSelectorsRunOnlySafeComponentsAndNamedSelectorsMayRunAnySideEffect() {
        BitRunner runner = new BitRunner();
        BitComponent safe = component("safe", "actions", BitSideEffect.SAFE, () -> healthy());
        BitComponent world = component("world", "actions", BitSideEffect.NEEDS_WORLD, () -> healthy());
        BitComponent mutating = component("mutating", "other", BitSideEffect.MUTATING, () -> healthy());
        runner.register(safe);
        runner.register(world);
        runner.register(mutating);

        List<BitResult> group = runner.run(BitSelector.parse("subsystem:actions"), DIRECT);
        assertEquals(List.of("safe"), group.stream().map(result -> result.component().id()).toList());
        assertEquals(1, runner.run(BitSelector.parse("all"), DIRECT).size());
        assertEquals("mutating", runner.run(BitSelector.parse("mutating"), DIRECT).get(0).component().id());
    }

    @Test
    void runCachesResultsAndUnregisterRemovesMetadataAndCache() {
        BitRunner runner = new BitRunner();
        BitComponent component = component("probe", "test", BitSideEffect.SAFE, () -> healthy());
        assertTrue(runner.register(component));
        assertFalse(runner.register(component));

        BitResult result = runner.run(component.withCheck(() -> healthy()), DIRECT);

        assertEquals(BitHealth.HEALTHY, result.report().health());
        assertSame(component, result.component());
        assertEquals(result, runner.last("PROBE"));
        assertEquals(List.of(component), runner.components());
        assertTrue(runner.unregister("probe"));
        assertFalse(runner.unregister("probe"));
        assertNull(runner.last("probe"));
        assertTrue(runner.components().isEmpty());
    }

    @Test
    void thrownChecksBecomeFindingsAndDoNotEscape() {
        BitRunner runner = new BitRunner();
        BitComponent component = component("throws", "test", BitSideEffect.SAFE,
                () -> { throw new IllegalStateException("probe exploded"); });

        BitResult result = runner.run(component, DIRECT);

        assertEquals(BitHealth.FAILED, result.report().health());
        assertEquals("BIT_COMPONENT_THROWN", result.report().findings().get(0).code());
        assertTrue(result.report().findings().get(0).message().contains("probe exploded"));
    }

    @Test
    void timedOutAndRejectedExecutionsBecomeFindings() {
        BitRunner runner = new BitRunner();
        BitComponent slow = new BitComponent("slow", "test", BitTier.IBIT, BitSideEffect.SAFE, 20,
                () -> healthy());
        BitResult timedOut = runner.run(slow, ignored -> {});
        assertEquals("BIT_COMPONENT_TIMEOUT", timedOut.report().findings().get(0).code());

        Executor rejecting = ignored -> { throw new IllegalStateException("executor rejected check"); };
        BitResult rejected = runner.run(component("rejected", "test", BitSideEffect.SAFE, () -> healthy()), rejecting);
        assertEquals("BIT_COMPONENT_THROWN", rejected.report().findings().get(0).code());
        assertTrue(rejected.report().findings().get(0).message().contains("executor rejected check"));
    }

    @Test
    void unknownSelectorsAndEmptyGroupsReturnLocatedFailureResults() {
        BitRunner runner = new BitRunner();

        BitResult unknown = runner.run(BitSelector.parse("missing"), DIRECT).get(0);
        BitResult emptyGroup = runner.run(BitSelector.parse("tier:pbit"), DIRECT).get(0);

        assertEquals("BIT_COMPONENT_UNKNOWN", unknown.report().findings().get(0).code());
        assertEquals("missing", unknown.report().findings().get(0).path());
        assertEquals("BIT_COMPONENT_UNKNOWN", emptyGroup.report().findings().get(0).code());
    }

    @Test
    void aggregatePreservesExplicitHealthAndCollectsFindings() {
        BitRunner runner = new BitRunner();
        BitComponent healthy = component("healthy", "test", BitSideEffect.SAFE,
                () -> new BitReport(BitReport.CURRENT_SCHEMA, BitHealth.DEGRADED, List.of()));
        BitComponent failed = component("failed", "test", BitSideEffect.SAFE,
                () -> BitReport.of(List.of(new BitFinding(Level.ERROR, "FAIL", "", "x", "bad", "", ""))));
        List<BitResult> results = List.of(runner.run(healthy, DIRECT), runner.run(failed, DIRECT));

        BitReport report = runner.aggregate(results);

        assertEquals(BitHealth.FAILED, report.health());
        assertEquals(1, report.findings().size());
    }

    @Test
    void outputsRenderCompletedAndNotRunResultsToCallerSink() {
        BitRunner runner = new BitRunner();
        BitComponent component = component("probe", "test", BitSideEffect.SAFE, () -> healthy());
        BitResult result = runner.run(component, DIRECT);
        List<String> text = new ArrayList<>();
        List<String> json = new ArrayList<>();

        BitOutputs.text("[RTP test/status] ").write(result, text::add);
        BitOutputs.text("[RTP test/status] ").writeNotRun(component, text::add);
        BitOutputs.json("[RTP bit/test.").write(result, json::add);
        BitOutputs.json("[RTP bit/test.").writeNotRun(component, json::add);
        BitOutputs.text("[RTP test/status] ").writeEmpty("all", text::add);

        assertTrue(text.get(0).contains("health=HEALTHY"));
        assertTrue(text.get(1).contains("status=NOT_RUN"));
        assertTrue(text.get(2).contains("no components"));
        assertTrue(json.get(0).startsWith("[RTP bit/test.probe] "));
        assertTrue(json.get(0).contains("\"component\":\"probe\""));
        assertTrue(json.get(1).contains("\"status\":\"NOT_RUN\""));
        assertFalse(json.get(0).contains("[RTP test/"));
        assertNotNull(result.toJson());
    }

    private static BitComponent component(String id, String subsystem, BitSideEffect sideEffect,
                                          java.util.concurrent.Callable<BitReport> check) {
        return new BitComponent(id, subsystem, BitTier.IBIT, sideEffect, 1_000, check);
    }

    private static BitReport healthy() {
        return BitReport.of(List.of());
    }
}
