package io.github.dailystruggle.rtp.common.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.DriftCheck;
import io.github.dailystruggle.bit.SwitchLabels;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ActionDefinitionValidatorTest {
    @Test
    void unknownStepKeyProducesLocatedFindingAndSuggestion() {
        BitReport report = validate("koth", Map.of("lifecycle", Map.of("onStart", List.of(Map.of("CONSOL", "say hi")))), "koth.yml");

        assertFinding(report, "ACTION_UNKNOWN_STEP_KEY", "actions.koth.lifecycle.onStart[0].CONSOL", "CONSOLE");
    }

    @Test
    void unknownActionNameProducesLocatedFindingAndSuggestion() {
        BitReport report = validate("koth", Map.of("lifecycle", Map.of("onStart", List.of(Map.of("ACTION", "PULL_BAK")))), "koth.yml");

        assertFinding(report, "ACTION_UNKNOWN_ACTION_NAME", "actions.koth.lifecycle.onStart[0].ACTION", "PULL_BACK");
    }

    @Test
    void unknownGateAndTriggerTypesProduceLocatedFindings() {
        BitReport gateReport = validate("koth", Map.of("gate", List.of(Map.of("spacial", Map.of("target", "anchor")))), "koth.yml");
        assertFinding(gateReport, "ACTION_UNKNOWN_GATE_TYPE", "actions.koth.gate[0].spacial", "spatial");

        BitReport triggerReport = validate("koth", Map.of("triggers", List.of(Map.of("type", "STEP_I"))), "koth.yml");
        assertFinding(triggerReport, "ACTION_UNKNOWN_TRIGGER_TYPE", "actions.koth.triggers[0].type", "STEP_IN");
    }

    @Test
    void unknownLifecycleStageProducesLocatedFinding() {
        BitReport report = validate("koth", Map.of("lifecycle", Map.of("onStrat", List.of(Map.of("CONSOLE", "say hi")))), "koth.yml");

        assertFinding(report, "ACTION_UNKNOWN_LIFECYCLE_STAGE", "actions.koth.lifecycle.onStrat", "onStart");
    }

    @Test
    void validDefinitionHasNoFindings() {
        Map<String, Object> definition = Map.of(
                "gate", List.of(Map.of("players", ">= 2")),
                "lifecycle", Map.of("onStart", List.of(Map.of("CONSOLE", "say ready"))),
                "triggers", List.of(Map.of("type", "STEP_IN")));

        assertTrue(validate("sample", definition, "sample.yml").findings().isEmpty());
    }

    @Test
    void declaredActionAndGateValuesTrackRuntimeSwitches() throws IOException {
        Path root = repositoryRoot();
        assertDriftFree(ActionConfigLoader.STEP_ACTION_KEYS,
                root.resolve("rtp-core/src/main/java/io/github/dailystruggle/rtp/common/action/ActionConfigLoader.java"), "key");
        assertDriftFree(ActionSessionImpl.ACTION_NAMES,
                root.resolve("rtp-core/src/main/java/io/github/dailystruggle/rtp/common/action/ActionSessionImpl.java"), "actionName");
        assertDriftFree(GateEvaluator.GATE_TYPES,
                root.resolve("rtp-core/src/main/java/io/github/dailystruggle/rtp/common/action/GateEvaluator.java"), "gateType");
    }

    @Test
    void everyShippedActionDefinitionHasNoErrorFindings() throws IOException {
        Path actions = repositoryRoot().resolve("addons/LeafRTPActionAddon/src/main/resources/definitions/actions");
        assertTrue(Files.isDirectory(actions), "shipped action definitions directory must exist");
        try (var files = Files.list(actions)) {
            List<Path> definitions = files.filter(path -> path.getFileName().toString().endsWith(".yml")).sorted().toList();
            assertFalse(definitions.isEmpty(), "at least one shipped action definition is expected");
            for (Path file : definitions) {
                String id = file.getFileName().toString().replaceFirst("\\.yml$", "");
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    Map<?, ?> root = RtpYamlConfig.parse(reader).getValues(false);
                    BitReport report = validate(id, root, file.toString());
                    assertTrue(report.findings().isEmpty(), () -> file + " produced findings: " + report.findings());
                }
            }
        }
    }

    @Test
    void actionCodesAreRegisteredInTheActionNamespace() {
        List<BitCode> codes = List.of(ActionDefinitionValidator.UNKNOWN_LIFECYCLE_STAGE,
                ActionDefinitionValidator.UNKNOWN_STEP_KEY, ActionDefinitionValidator.UNKNOWN_ACTION_NAME,
                ActionDefinitionValidator.UNKNOWN_GATE_TYPE, ActionDefinitionValidator.UNKNOWN_TRIGGER_TYPE);
        for (BitCode code : codes) {
            assertSame(code, RtpBitCodes.REGISTRY.byId(code.id()).orElseThrow());
            assertSame(code, RtpBitCodes.REGISTRY.byKey(code.key()).orElseThrow());
            assertTrue(RtpBitCodes.ACTION.containsId(code.id()) && RtpBitCodes.ACTION.ownsKey(code.key()));
            assertFalse(code.remediation().isBlank(), code.key() + " needs remediation text");
        }
    }

    @Test
    void coreFindingsAreBuiltOnlyFromRegisteredCodes() throws IOException {
        Path main = repositoryRoot().resolve("rtp-core/src/main/java");
        try (var files = Files.walk(main)) {
            List<Path> offenders = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            return Files.readString(path, StandardCharsets.UTF_8).contains("new BitFinding(");
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .toList();
            assertTrue(offenders.isEmpty(), () -> "use BitFinding.of(BitCode, ...) instead of raw codes in " + offenders);
        }
    }

    private static BitReport validate(String id, Map<?, ?> definition, String source) {
        return ActionDefinitionValidator.validate(id, definition, source);
    }

    private static void assertFinding(BitReport report, String code, String path, String suggestion) {
        BitFinding finding = report.findings().stream().filter(value -> value.code().equals(code)).findFirst().orElse(null);
        assertNotNull(finding, () -> "Expected " + code + " in " + report.findings());
        assertEquals(path, finding.path());
        assertEquals(suggestion, finding.suggestion());
        assertEquals("koth.yml", finding.source());
        assertEquals(code, RtpBitCodes.REGISTRY.keyOf(finding.codeId()).orElse(null),
                "finding id must map back to its registered key");
    }

    private static void assertDriftFree(io.github.dailystruggle.bit.Vocabulary vocabulary, Path source, String expression)
            throws IOException {
        List<String> labels = SwitchLabels.extract(Files.readString(source, StandardCharsets.UTF_8), expression);
        assertFalse(labels.isEmpty(), "switch labels were not found for " + expression + " in " + source);
        assertTrue(DriftCheck.diff(vocabulary, labels).isEmpty(), () -> vocabulary.name() + " drift: "
                + DriftCheck.diff(vocabulary, labels));
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        assertNotNull(current, "could not locate repository root");
        return current;
    }
}
