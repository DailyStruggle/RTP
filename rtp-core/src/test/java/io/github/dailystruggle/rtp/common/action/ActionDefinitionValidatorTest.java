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
import io.github.dailystruggle.bit.Vocabulary;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec;
import io.github.dailystruggle.rtp.common.bit.BitSeverityPolicy;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import java.io.IOException;
import java.io.Reader;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("REQ-API-F-009 action definition PBIT")
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
    void unknownSubcommandActionNameProducesLocatedFinding() {
        Map<String, Object> definition = Map.of("command", Map.of("subcommands", Map.of(
                "cancel", Map.of("run", List.of(Map.of("ACTION", "PULL_BAK"))))));

        BitReport report = validate("koth", definition, "koth.yml");

        assertFinding(report, "ACTION_UNKNOWN_ACTION_NAME",
                "actions.koth.command.subcommands.cancel.run[0].ACTION", "PULL_BACK");
    }

    @Test
    void unknownConfinementActionNameProducesLocatedFinding() {
        Map<String, Object> definition = Map.of("confinement", Map.of(
                "outsideActions", List.of(Map.of("ACTION", "PULL_BAK"))));

        BitReport report = validate("koth", definition, "koth.yml");

        assertFinding(report, "ACTION_UNKNOWN_ACTION_NAME",
                "actions.koth.confinement.outsideActions[0].ACTION", "PULL_BACK");
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
                "gate", List.of(
                        Map.of("players", ">= 2"),
                        Map.of("spatial", Map.of("distance", "1..5", "elevationDelta", "-2..2", "playerCount", ">= 1")),
                        Map.of("time", Map.of("elapsed", ">= 2m", "remaining", "1..5")),
                        Map.of("participants", Map.of("count", ">= 2"))),
                "lifecycle", Map.of("onStart", List.of(Map.of("CONSOLE", "say ready"))),
                "triggers", List.of(Map.of("type", "STEP_IN")));

        assertTrue(validate("sample", definition, "sample.yml").findings().isEmpty());
    }

    @Test
    void stepTwoValidationFindingsAreReproduced() {
        Map<String, Object> definition = Map.of(
                "gate", List.of(Map.of("scoreboard", Map.of(
                        "objective", "rtp_violatons", "matches", ">= not-a-number", "matchs", "1..2"))),
                "lifecycle", Map.of("onStart", List.of(Map.of("CONSOLE", "say [victim] [mystery]"))));

        BitReport report = validate("sample", definition, "sample.yml");

        List<String> codes = report.findings().stream().map(BitFinding::code).toList();
        assertTrue(codes.contains("ACTION_PLACEHOLDER_OUT_OF_SCOPE"), () -> "Missing out-of-scope finding: " + codes);
        assertTrue(codes.contains("ACTION_PLACEHOLDER_UNKNOWN"), () -> "Missing unknown-placeholder finding: " + codes);
        assertTrue(codes.contains("ACTION_SCOREBOARD_MANAGED_TYPO"), () -> "Missing objective typo finding: " + codes);
        assertTrue(codes.contains("ACTION_GATE_EXPRESSION_INVALID"), () -> "Missing expression finding: " + codes);
        assertTrue(codes.contains("ACTION_UNKNOWN_GATE_SUBKEY"), () -> "Missing gate-subkey finding: " + codes);
    }

    @Test
    void lifecyclePlaceholderScopeReportsErrorsAndUnknownTokensAsWarnings() {
        Map<String, Object> definition = Map.of("lifecycle", Map.of("onStart",
                List.of(Map.of("CONSOLE", "say [victim] [unrecognized_marker]"))));

        BitReport report = validate("koth", definition, "koth.yml");

        String payloadPath = "actions.koth.lifecycle.onStart[0].CONSOLE";
        assertFinding(report, "ACTION_PLACEHOLDER_OUT_OF_SCOPE", payloadPath, "", Level.ERROR);
        assertFinding(report, "ACTION_PLACEHOLDER_UNKNOWN", payloadPath, "", Level.WARNING);
    }

    @Test
    void declaredLifecycleAndForEachScopesAndConsoleFanOutAreAccepted() {
        Map<String, Object> definition = Map.of("lifecycle", Map.of(
                "onDeath", List.of(Map.of("CONSOLE", "say [victim] [winner]")),
                "onBoundaryViolation", List.of(Map.of("MESSAGE", "[violator]")),
                "onEnqueue", List.of(Map.of("MESSAGE", "[player]")),
                "onCancel", List.of(Map.of("MESSAGE", "[player] [canceller]")),
                "onWait", List.of(Map.of("MESSAGE", "[player]")),
                "onStart", List.of(Map.of("CONSOLE", "say [player] [player_name] [player_uuid] [player_1] "
                                + "[player_uuid_1] [sender_1] [cluster_2] [session_id] [target_name] "
                                + "[sender_uuid] [participants] [clusters] [123] [CLICK TO ACCEPT]"),
                        Map.of("PLAYER", "say [player] [player_name]"),
                        Map.of("FOR_EACH", List.of(Map.of("MESSAGE", "[player]"))))));

        assertTrue(validate("koth", definition, "koth.yml").findings().isEmpty());
    }

    @Test
    void commandAndConfinementActionsUseTheirDeclaredPlaceholderScopes() {
        Map<String, Object> definition = Map.of(
                "command", Map.of("subcommands", Map.of("cancel", Map.of("run",
                        List.of(Map.of("MESSAGE", "[player] [action_id]"))))),
                "confinement", Map.of("outsideActions",
                        List.of(Map.of("MESSAGE", "[player] [violator] [world]"))),
                "gate", List.of(Map.of("command", "execute if entity @a[name=[player]]")));

        assertTrue(validate("koth", definition, "koth.yml").findings().isEmpty());
    }

    @Test
    void managedScoreboardTypoReportsExactSuggestionAndError() {
        Map<String, Object> definition = Map.of("gate", List.of(Map.of("scoreboard", Map.of(
                "objective", "rtp_violatons", "matches", ">= 1"))));

        BitReport report = ActionDefinitionValidator.validate("koth", definition, "koth.yml",
                new ActionDefinitionValidator.Environment(null, Set.of(), null));

        assertFinding(report, "ACTION_SCOREBOARD_MANAGED_TYPO",
                "actions.koth.gate[0].scoreboard.objective", "rtp_violations", Level.ERROR);
    }

    @Test
    void externalScoreboardObjectiveIsWarningOnlyWhenAccessorConfirmsAbsence() {
        Map<String, Object> definition = Map.of("gate", List.of(Map.of("scoreboard", Map.of(
                "objective", "external_objective", "matches", "1..5"))));
        var environment = new ActionDefinitionValidator.Environment(
                ignored -> Optional.of(false), Set.of(), null);

        BitReport report = ActionDefinitionValidator.validate("koth", definition, "koth.yml", environment);

        assertFinding(report, "ACTION_SCOREBOARD_OBJECTIVE_MISSING",
                "actions.koth.gate[0].scoreboard.objective", "", Level.WARNING);
        assertTrue(ActionDefinitionValidator.validate("koth", definition, "koth.yml",
                ActionDefinitionValidator.Environment.NONE).findings().isEmpty());
    }

    @Test
    void invalidGateExpressionsAndUnknownSubkeysHaveLocatedFindings() {
        Map<String, Object> definition = Map.of("gate", List.of(
                Map.of("scoreboard", Map.of(
                        "objective", "rtp_session_id", "matches", ">= invalid", "matchs", "1..2")),
                Map.of("players", "many"),
                Map.of("time", Map.of("elapsed", "not-a-duration")),
                Map.of("spatial", Map.of("playerCount", "many"))));

        BitReport report = validate("koth", definition, "koth.yml");

        assertFinding(report, "ACTION_GATE_EXPRESSION_INVALID",
                "actions.koth.gate[0].scoreboard.matches", "", Level.ERROR);
        assertFinding(report, "ACTION_UNKNOWN_GATE_SUBKEY",
                "actions.koth.gate[0].scoreboard.matchs", "matches", Level.ERROR);
        assertFinding(report, "ACTION_GATE_EXPRESSION_INVALID", "actions.koth.gate[1].players", "", Level.ERROR);
        assertFinding(report, "ACTION_GATE_EXPRESSION_INVALID", "actions.koth.gate[2].time.elapsed", "", Level.ERROR);
        assertFinding(report, "ACTION_GATE_EXPRESSION_INVALID", "actions.koth.gate[3].spatial.playerCount", "", Level.ERROR);
    }

    @Test
    void missingRequiredGateSubkeyReportsExpectedFieldAndError() {
        Map<String, Object> definition = Map.of("gate", List.of(Map.of("scoreboard", Map.of("matches", "1..3"))));

        BitReport report = validate("koth", definition, "koth.yml");

        BitFinding finding = assertFinding(report, "ACTION_MISSING_GATE_SUBKEY",
                "actions.koth.gate[0].scoreboard", "objective", Level.ERROR);
        assertEquals("objective", finding.expected());

        Map<String, Object> emptyExpression = Map.of("gate", List.of(Map.of("scoreboard", Map.of(
                "objective", "rtp_session_id", "matches", ""))));
        BitFinding emptyFinding = assertFinding(validate("koth", emptyExpression, "koth.yml"),
                "ACTION_MISSING_GATE_SUBKEY", "actions.koth.gate[0].scoreboard", "matches", Level.ERROR);
        assertEquals("matches or range", emptyFinding.expected());
    }

    @Test
    void unknownPlacementRegionIncludesSuggestionAndError() {
        Map<String, Object> definition = Map.of("placement", Map.of("region", "defalt"));
        var environment = new ActionDefinitionValidator.Environment(null, Set.of("default"), null);

        BitReport report = ActionDefinitionValidator.validate("koth", definition, "koth.yml", environment);

        assertFinding(report, "ACTION_UNKNOWN_REGION", "actions.koth.placement.region", "default", Level.ERROR);
        Map<String, Object> blankRegion = Map.of("placement", Map.of("region", ""));
        assertFinding(ActionDefinitionValidator.validate("koth", blankRegion, "koth.yml", environment),
                "ACTION_UNKNOWN_REGION", "actions.koth.placement.region", "", Level.ERROR);
    }

    @Test
    void schematicResolutionIsContainedAndSupportsExtensionlessNames(@TempDir Path pluginDirectory) throws IOException {
        Path schematics = Files.createDirectories(pluginDirectory.resolve("advanced/schematics/nested"));
        Files.createFile(schematics.resolve("arena.schem"));
        Files.createFile(schematics.resolve("tower.schematic"));
        Files.createFile(schematics.resolve("direct.schematic"));
        var environment = new ActionDefinitionValidator.Environment(null, Set.of("default"), pluginDirectory);
        Map<String, Object> missing = Map.of("placement", Map.of("region", "default", "schematic", "missing"));

        BitReport report = ActionDefinitionValidator.validate("koth", missing, "koth.yml", environment);

        assertFinding(report, "ACTION_SCHEMATIC_UNRESOLVED", "actions.koth.placement.schematic", "", Level.ERROR);
        Map<String, Object> blank = Map.of("placement", Map.of("region", "default", "schematic", ""));
        assertFinding(ActionDefinitionValidator.validate("koth", blank, "koth.yml", environment),
                "ACTION_SCHEMATIC_UNRESOLVED", "actions.koth.placement.schematic", "", Level.ERROR);
        Map<String, Object> traversal = Map.of("placement", Map.of("region", "default", "schematic", "../outside.schem"));
        assertFinding(ActionDefinitionValidator.validate("koth", traversal, "koth.yml", environment),
                "ACTION_SCHEMATIC_UNRESOLVED", "actions.koth.placement.schematic", "", Level.ERROR);
        Map<String, Object> valid = Map.of("placement", Map.of("region", "default", "schematic", "nested/arena"));
        assertTrue(ActionDefinitionValidator.validate("koth", valid, "koth.yml", environment).findings().isEmpty());
        Map<String, Object> named = Map.of("placement", Map.of("region", "default", "schematic", "nested/arena.schem"));
        assertTrue(ActionDefinitionValidator.validate("koth", named, "koth.yml", environment).findings().isEmpty());
        Map<String, Object> alternate = Map.of("placement", Map.of("region", "default", "schematic", "nested/tower"));
        assertTrue(ActionDefinitionValidator.validate("koth", alternate, "koth.yml", environment).findings().isEmpty());
        Map<String, Object> explicitAlternate = Map.of("placement",
                Map.of("region", "default", "schematic", "nested/direct.schematic"));
        assertTrue(ActionDefinitionValidator.validate("koth", explicitAlternate, "koth.yml", environment)
                .findings().isEmpty());
    }

    @Test
    void unbalancedVanillaPayloadReportsError() {
        Map<String, Object> definition = Map.of("lifecycle", Map.of("onStart",
                List.of(Map.of("CONSOLE", "tellraw @a {\"text\":\"hello\""))));

        BitReport report = validate("koth", definition, "koth.yml");

        assertFinding(report, "ACTION_PAYLOAD_UNBALANCED",
                "actions.koth.lifecycle.onStart[0].CONSOLE", "", Level.ERROR);
    }

    @Test
    void invalidJsonTextComponentReportsError() {
        Map<String, Object> definition = Map.of("lifecycle", Map.of("onStart",
                List.of(Map.of("CONSOLE", "tellraw @a {\"text\":}"))));

        BitReport report = validate("koth", definition, "koth.yml");

        assertFinding(report, "ACTION_PAYLOAD_JSON_INVALID",
                "actions.koth.lifecycle.onStart[0].CONSOLE", "", Level.ERROR);
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
        Vocabulary actionTypes = new Vocabulary("command action type",
                java.util.Arrays.stream(ActionDefinition.ActionType.values()).map(Enum::name).toList());
        assertDriftFree(actionTypes,
                root.resolve("rtp-core/src/main/java/io/github/dailystruggle/rtp/common/action/ActionSessionImpl.java"),
                "cmd.type()", ActionSessionImpl.ACTION_NAMES);
        assertDriftFree(actionTypes,
                root.resolve("rtp-core/src/main/java/io/github/dailystruggle/rtp/common/action/ActionManager.java"),
                "action.type()", ActionSessionImpl.ACTION_NAMES);
        assertTrue(DriftCheck.diff(ActionDefinitionValidator.TRIGGER_TYPES,
                java.util.Arrays.stream(PhysicalTriggerSpec.TriggerType.values()).map(Enum::name).toList()).isEmpty());
        assertEquals(new HashSet<>(ActionConfigLoader.LIFECYCLE_STAGES.values()),
                ActionPlaceholderSanitizer.STAGE_TOKENS.keySet());
        String gateSource = Files.readString(root.resolve(
                "rtp-core/src/main/java/io/github/dailystruggle/rtp/common/action/GateEvaluator.java"), StandardCharsets.UTF_8);
        Set<String> gateObjectives = new HashSet<>();
        Matcher objectiveMatcher = Pattern.compile("\\\"(rtp_[a-z0-9_]+)\\\"").matcher(gateSource);
        while (objectiveMatcher.find()) gateObjectives.add(objectiveMatcher.group(1));
        assertEquals(Set.copyOf(ActionSessionImpl.MANAGED_OBJECTIVES.values()), gateObjectives);
        for (Map.Entry<String, Set<String>> gate : GateEvaluator.GATE_SUB_KEYS.entrySet()) {
            assertTrue(gateSource.contains("\"" + gate.getKey() + "\""), gate.getKey());
            for (String subKey : gate.getValue()) assertTrue(gateSource.contains("\"" + subKey + "\""), subKey);
        }
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
                    BitReport report = ActionDefinitionValidator.validate(id, root, file.toString(),
                            new ActionDefinitionValidator.Environment(null, Set.of("default"), null));
                    assertTrue(report.findings().isEmpty(), () -> file + " produced findings: " + report.findings());
                }
            }
        }
    }

    @Test
    void actionCodesAreRegisteredInTheActionNamespace() {
        List<BitCode> codes = List.of(ActionDefinitionValidator.UNKNOWN_LIFECYCLE_STAGE,
                ActionDefinitionValidator.UNKNOWN_STEP_KEY, ActionDefinitionValidator.UNKNOWN_ACTION_NAME,
                ActionDefinitionValidator.UNKNOWN_GATE_TYPE, ActionDefinitionValidator.UNKNOWN_TRIGGER_TYPE,
                ActionDefinitionValidator.PLACEHOLDER_OUT_OF_SCOPE, ActionDefinitionValidator.PLACEHOLDER_UNKNOWN,
                ActionDefinitionValidator.SCOREBOARD_MANAGED_TYPO, ActionDefinitionValidator.SCOREBOARD_OBJECTIVE_MISSING,
                ActionDefinitionValidator.GATE_EXPRESSION_INVALID, ActionDefinitionValidator.UNKNOWN_GATE_SUBKEY,
                ActionDefinitionValidator.MISSING_GATE_SUBKEY, ActionDefinitionValidator.UNKNOWN_REGION,
                ActionDefinitionValidator.SCHEMATIC_UNRESOLVED, ActionDefinitionValidator.PAYLOAD_UNBALANCED,
                ActionDefinitionValidator.PAYLOAD_JSON_INVALID);
        for (BitCode code : codes) {
            assertSame(code, RtpBitCodes.REGISTRY.byId(code.namespace(), code.id()).orElseThrow());
            assertSame(code, RtpBitCodes.REGISTRY.byKey(code.key()).orElseThrow());
            assertTrue(code.namespace().equals(RtpBitCodes.ACTION.prefix()) && RtpBitCodes.ACTION.ownsKey(code.key()));
            assertFalse(code.remediation().isBlank(), code.key() + " needs remediation text");
        }
        assertTrue(BitSeverityPolicy.isStructuralActionCode(ActionDefinitionValidator.PLACEHOLDER_OUT_OF_SCOPE.key()));
        assertTrue(BitSeverityPolicy.isStructuralActionCode(ActionDefinitionValidator.PAYLOAD_JSON_INVALID.key()));
        assertFalse(BitSeverityPolicy.isStructuralActionCode(ActionDefinitionValidator.PLACEHOLDER_UNKNOWN.key()));
        assertFalse(BitSeverityPolicy.isStructuralActionCode(ActionDefinitionValidator.SCOREBOARD_OBJECTIVE_MISSING.key()));
    }

    @Test
    void newStructuralActionErrorsFollowStrictActionsPolicy() {
        BitFinding finding = BitFinding.of(ActionDefinitionValidator.PLACEHOLDER_OUT_OF_SCOPE,
                "koth.yml", "actions.koth.lifecycle.onStart[0].MESSAGE",
                "Out-of-scope placeholder", "stage token", "");
        try {
            BitSeverityPolicy.resetDefaults();
            assertEquals(Level.WARNING, BitSeverityPolicy.effective(finding).orElseThrow());
            BitSeverityPolicy.load(true, Map.of());
            assertEquals(Level.ERROR, BitSeverityPolicy.effective(finding).orElseThrow());
        } finally {
            BitSeverityPolicy.resetDefaults();
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
        return ActionDefinitionValidator.validate(id, definition, source, ActionDefinitionValidator.Environment.NONE);
    }

    private static void assertFinding(BitReport report, String code, String path, String suggestion) {
        assertFinding(report, code, path, suggestion, null);
    }

    private static BitFinding assertFinding(BitReport report, String code, String path, String suggestion, Level severity) {
        BitFinding finding = report.findings().stream()
                .filter(value -> value.code().equals(code) && value.path().equals(path))
                .findFirst().orElse(null);
        assertNotNull(finding, () -> "Expected " + code + " in " + report.findings());
        assertEquals(path, finding.path());
        assertEquals(suggestion, finding.suggestion());
        assertEquals("koth.yml", finding.source());
        if (severity != null) assertEquals(severity, finding.severity());
        assertEquals(code, RtpBitCodes.REGISTRY.keyOf(finding.namespace(), finding.codeId()).orElse(null),
                "finding id must map back to its registered key");
        return finding;
    }

    private static void assertDriftFree(io.github.dailystruggle.bit.Vocabulary vocabulary, Path source, String expression)
            throws IOException {
        assertDriftFree(vocabulary, source, expression, null);
    }

    private static void assertDriftFree(io.github.dailystruggle.bit.Vocabulary vocabulary,
            Path source, String expression, io.github.dailystruggle.bit.Vocabulary nestedLabels) throws IOException {
        List<String> labels = new java.util.ArrayList<>(
                SwitchLabels.extract(Files.readString(source, StandardCharsets.UTF_8), expression));
        if (nestedLabels != null) labels.removeIf(nestedLabels::contains);
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
