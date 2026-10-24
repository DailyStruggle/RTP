package io.github.dailystruggle.rtp.common.action;

import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.bit.KeyPath;
import io.github.dailystruggle.bit.Vocabulary;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Structural checks for action definitions, sharing allowed values with their runtime consumers. */
public final class ActionDefinitionValidator {
    private static final String ACTION_CODE_SCOPE = "action";
    private static final String ACTIONS_KEY = "actions";
    private static final String COMMAND_KEY = "command";
    private static final String EXECUTE_KEY = "execute";
    private static final String RUN_KEY = "run";
    private static final String REGION_KEY = "region";
    static final Vocabulary TRIGGER_TYPES = new Vocabulary("trigger type",
            java.util.Arrays.stream(PhysicalTriggerSpec.TriggerType.values()).map(Enum::name).toList());

    public static final BitCode UNKNOWN_LIFECYCLE_STAGE = RtpBitCodes.ACTION.register(2001,
            "ACTION_UNKNOWN_LIFECYCLE_STAGE", Level.ERROR, BitTier.PBIT, ACTION_CODE_SCOPE, "",
            "Rename the stage to one listed in expected; unknown stages never run.");
    public static final BitCode UNKNOWN_STEP_KEY = RtpBitCodes.ACTION.register(2002,
            "ACTION_UNKNOWN_STEP_KEY", Level.ERROR, BitTier.PBIT, ACTION_CODE_SCOPE, "",
            "Rename the step key to one listed in expected; unknown keys are dropped at load.");
    public static final BitCode UNKNOWN_ACTION_NAME = RtpBitCodes.ACTION.register(2003,
            "ACTION_UNKNOWN_ACTION_NAME", Level.ERROR, BitTier.PBIT, ACTION_CODE_SCOPE, "",
            "Use an ACTION: name listed in expected; unknown names are ignored during loading.");
    public static final BitCode UNKNOWN_GATE_TYPE = RtpBitCodes.ACTION.register(2004,
            "ACTION_UNKNOWN_GATE_TYPE", Level.ERROR, BitTier.PBIT, ACTION_CODE_SCOPE, "",
            "Use a gate type listed in expected; unknown gates always evaluate false.");
    public static final BitCode UNKNOWN_TRIGGER_TYPE = RtpBitCodes.ACTION.register(2005,
            "ACTION_UNKNOWN_TRIGGER_TYPE", Level.ERROR, BitTier.PBIT, ACTION_CODE_SCOPE, "",
            "Use a trigger type listed in expected; triggers with unknown types are skipped.");
    public static final BitCode PLACEHOLDER_OUT_OF_SCOPE = RtpBitCodes.ACTION.register(2006,
            "ACTION_PLACEHOLDER_OUT_OF_SCOPE", Level.ERROR, BitTier.PBIT, ACTION_CODE_SCOPE, "",
            "Use this placeholder only in the lifecycle stage or action scope that provides it.");
    public static final BitCode PLACEHOLDER_UNKNOWN = RtpBitCodes.ACTION.register(2007,
            "ACTION_PLACEHOLDER_UNKNOWN", Level.WARNING, BitTier.PBIT, ACTION_CODE_SCOPE, "",
            "Check the placeholder spelling; metadata-defined tokens are not validated as known tokens.");
    public static final BitCode SCOREBOARD_MANAGED_TYPO = RtpBitCodes.ACTION.register(2008,
            "ACTION_SCOREBOARD_MANAGED_TYPO", Level.ERROR, BitTier.PBIT, ACTION_CODE_SCOPE, "",
            "Use the suggested RTP-managed scoreboard objective or choose an external objective.");
    public static final BitCode SCOREBOARD_OBJECTIVE_MISSING = RtpBitCodes.ACTION.register(2009,
            "ACTION_SCOREBOARD_OBJECTIVE_MISSING", Level.WARNING, BitTier.PBIT, ACTION_CODE_SCOPE, "",
            "Create the scoreboard objective on the server or correct its name.");
    public static final BitCode GATE_EXPRESSION_INVALID = RtpBitCodes.ACTION.register(2010,
            "ACTION_GATE_EXPRESSION_INVALID", Level.ERROR, BitTier.PBIT, ACTION_CODE_SCOPE, "",
            "Use a numeric comparison, exact number, or inclusive min..max range accepted by GateExpressionParser.");
    public static final BitCode UNKNOWN_GATE_SUBKEY = RtpBitCodes.ACTION.register(2011,
            "ACTION_UNKNOWN_GATE_SUBKEY", Level.ERROR, BitTier.PBIT, ACTION_CODE_SCOPE, "",
            "Use a gate field listed in expected; gate field names are case-sensitive.");
    public static final BitCode MISSING_GATE_SUBKEY = RtpBitCodes.ACTION.register(2012,
            "ACTION_MISSING_GATE_SUBKEY", Level.ERROR, BitTier.PBIT, ACTION_CODE_SCOPE, "",
            "Add the required gate field or one of the listed alternatives.");
    public static final BitCode UNKNOWN_REGION = RtpBitCodes.ACTION.register(2013,
            "ACTION_UNKNOWN_REGION", Level.ERROR, BitTier.PBIT, ACTION_CODE_SCOPE, "",
            "Choose a region present in the loaded region configuration.");
    public static final BitCode SCHEMATIC_UNRESOLVED = RtpBitCodes.ACTION.register(2014,
            "ACTION_SCHEMATIC_UNRESOLVED", Level.ERROR, BitTier.PBIT, ACTION_CODE_SCOPE, "",
            "Place the schematic under advanced/schematics and reference an existing file.");
    public static final BitCode PAYLOAD_UNBALANCED = RtpBitCodes.ACTION.register(2015,
            "ACTION_PAYLOAD_UNBALANCED", Level.ERROR, BitTier.PBIT, ACTION_CODE_SCOPE, "",
            "Balance quotes, brackets and braces in the vanilla command payload.");
    public static final BitCode PAYLOAD_JSON_INVALID = RtpBitCodes.ACTION.register(2016,
            "ACTION_PAYLOAD_JSON_INVALID", Level.ERROR, BitTier.PBIT, ACTION_CODE_SCOPE, "",
            "Provide a valid JSON text component where tellraw or title expects one.");

    private static final Pattern TELLRAW_COMPONENT = Pattern.compile(
            "(?i)^\\s*+/?(?:minecraft:)?tellraw\\s++\\S++(\\s++.*+)$");
    private static final Pattern TITLE_COMPONENT = Pattern.compile(
            "(?i)^\\s*+/?(?:minecraft:)?title\\s++\\S++\\s++(?:title|subtitle|actionbar)(\\s++.*+)$");
    private static final Pattern INTEGER_TOKEN = Pattern.compile("\\d+");
    private static final Set<String> EXPRESSION_SUB_KEYS = Set.of(
            "matches", "range", "distance", "elevationDelta", "elapsed", "remaining", "playerCount", "count");
    private static final Set<String> SCALAR_EXPRESSION_GATES = Set.of("players", "participants", "queue", "participantcount");

    /** Live inputs are optional: an unavailable host/region snapshot is deliberately not treated as missing. */
    public record Environment(
            Function<String, Optional<Boolean>> objectiveLookup, Set<String> regionNames, Path pluginDirectory) {
        public static final Environment NONE = new Environment(null, Set.of(), null);

        public Environment {
            regionNames = regionNames == null ? Set.of() : Set.copyOf(regionNames);
            pluginDirectory = pluginDirectory == null ? null : pluginDirectory.toAbsolutePath().normalize();
        }

        public static Environment live() {
            RTPServerAccessor accessor = RTPRuntime.serverAccessor();
            var selection = ActiveSelection.get();
            Set<String> regions = selection == null
                    ? Set.of()
                    : new HashSet<>(selection.permRegionLookup.keySet());
            File directory = accessor == null ? null : accessor.getPluginDirectory();
            return new Environment(accessor == null ? null : accessor::hasScoreboardObjective,
                    regions, directory == null ? null : directory.toPath());
        }
    }

    private ActionDefinitionValidator() {}

    public static BitReport validate(String id, Map<?, ?> definition, String source) {
        return validate(id, definition, source, Environment.live());
    }

    public static BitReport validate(String id, Map<?, ?> definition, String source, Environment environment) {
        List<BitFinding> findings = new ArrayList<>();
        if (definition == null) return BitReport.of(findings);
        Environment inputs = environment == null ? Environment.NONE : environment;
        String actionId = id == null || id.isBlank() ? "<unknown>" : id;
        KeyPath root = KeyPath.root(ACTIONS_KEY).key(actionId);
        inspectLifecycle(value(definition, "lifecycle"), source, root.key("lifecycle"), inputs, findings);
        inspectCommand(value(definition, COMMAND_KEY), source, root.key(COMMAND_KEY), inputs, findings);
        inspectConfinement(value(definition, "confinement"), source, root.key("confinement"), inputs, findings);
        inspectGateDefinition(definition, source, root, inputs, findings);
        inspectPlacement(value(definition, "placement"), source, root.key("placement"), inputs, findings);
        inspectTriggerDefinition(definition, source, root, findings);
        return BitReport.of(findings);
    }

    private static void inspectGateDefinition(
            Map<?, ?> definition, String source, KeyPath root, Environment environment, List<BitFinding> findings) {
        Object gate = value(definition, "gate");
        if (gate != null) {
            inspectGateValue(gate, source, root.key("gate"),
                    scope(ActionPlaceholderSanitizer.PRE_GATE), environment, findings);
            return;
        }
        inspectGateValue(value(definition, "gates"), source, root.key("gates"),
                scope(ActionPlaceholderSanitizer.PRE_GATE), environment, findings);
    }

    private static void inspectTriggerDefinition(
            Map<?, ?> definition, String source, KeyPath root, List<BitFinding> findings) {
        Object triggers = value(definition, "trigger");
        if (triggers != null) {
            inspectTriggers(triggers, source, root.key("trigger"), findings);
            return;
        }
        inspectTriggers(value(definition, "triggers"), source, root.key("triggers"), findings);
    }

    private static void inspectLifecycle(
            Object raw, String source, KeyPath path, Environment environment, List<BitFinding> findings) {
        Map<?, ?> lifecycle = asMap(raw);
        if (lifecycle == null) return;
        for (Map.Entry<?, ?> entry : lifecycle.entrySet()) {
            String stage = String.valueOf(entry.getKey());
            KeyPath stagePath = path.key(stage);
            if (!ActionConfigLoader.LIFECYCLE_STAGES.contains(stage)) {
                findings.add(unknown(UNKNOWN_LIFECYCLE_STAGE, source, stagePath, stage,
                        ActionConfigLoader.LIFECYCLE_STAGES));
                continue;
            }
            inspectSteps(entry.getValue(), source, stagePath,
                    scope(ActionPlaceholderSanitizer.STAGE_TOKENS.getOrDefault(canonicalStage(stage), Set.of())),
                    environment, findings);
        }
    }

    private static void inspectCommand(
            Object raw, String source, KeyPath path, Environment environment, List<BitFinding> findings) {
        Map<?, ?> command = asMap(raw);
        if (command == null) return;
        Map<?, ?> subcommands = asMap(value(command, "subcommands"));
        if (subcommands == null) return;
        for (Map.Entry<?, ?> entry : subcommands.entrySet()) {
            String subcommandName = String.valueOf(entry.getKey());
            Map<?, ?> subcommand = asMap(entry.getValue());
            if (subcommand == null) continue;
            Object actions = value(subcommand, "run");
            String actionKey = "run";
            if (actions == null) {
                actions = value(subcommand, ACTIONS_KEY);
                actionKey = ACTIONS_KEY;
            }
            if (actions != null) {
                inspectActions(actions, source,
                        path.key("subcommands").key(subcommandName).key(actionKey),
                        scope(ActionPlaceholderSanitizer.SUBCOMMAND), environment, findings);
            }
        }
    }

    private static void inspectConfinement(
            Object raw, String source, KeyPath path, Environment environment, List<BitFinding> findings) {
        Map<?, ?> confinement = asMap(raw);
        if (confinement == null) return;
        Object actions = value(confinement, "outsideActions");
        String actionKey = "outsideActions";
        if (actions == null) {
            actions = value(confinement, "outsideAction");
            actionKey = "outsideAction";
        }
        if (actions == null) {
            actions = value(confinement, "onOutsideLimit");
            actionKey = "onOutsideLimit";
        }
        if (actions != null) {
            inspectActions(actions, source, path.key(actionKey), scope(ActionPlaceholderSanitizer.OUTSIDE_ACTION),
                    environment, findings);
        }
    }

    private static void inspectSteps(
            Object raw, String source, KeyPath path, Set<String> scope, Environment environment,
            List<BitFinding> findings) {
        if (!(raw instanceof List<?> steps)) return;
        for (int i = 0; i < steps.size(); i++) {
            Map<?, ?> step = asMap(steps.get(i));
            if (step == null) continue;
            KeyPath stepPath = path.index(i);
            if (hasKey(step, "gate")) {
                inspectGateValue(value(step, "gate"), source, stepPath.key("gate"),
                        scope(scope, ActionPlaceholderSanitizer.PRE_GATE), environment, findings);
                if (hasKey(step, "run")) {
                    inspectUnknownStepEntries(step, source, stepPath, findings, true, scope, environment);
                    inspectActions(value(step, "run"), source, stepPath.key("run"), scope, environment, findings);
                } else {
                    inspectUnknownStepEntries(step, source, stepPath, findings, false, scope, environment);
                }
            } else {
                inspectActions(step, source, stepPath, scope, environment, findings);
            }
        }
    }

    private static void inspectUnknownStepEntries(
            Map<?, ?> step, String source, KeyPath path, List<BitFinding> findings, boolean hasRun,
            Set<String> scope, Environment environment) {
        for (Object rawKey : step.keySet()) {
            String key = String.valueOf(rawKey);
            if (ActionConfigLoader.STEP_STRUCTURAL_KEYS.contains(key)) continue;
            if (!ActionConfigLoader.STEP_ACTION_KEYS.contains(key)) {
                findings.add(unknown(UNKNOWN_STEP_KEY, source, path.key(key), key,
                        ActionConfigLoader.STEP_ACTION_KEYS));
            } else if (!hasRun) {
                inspectActionValue(key, value(step, key), source, path.key(key), scope, environment, findings);
            }
        }
    }

    private static void inspectActions(
            Object raw, String source, KeyPath path, Set<String> scope, Environment environment,
            List<BitFinding> findings) {
        if (raw instanceof List<?> actions) {
            for (int i = 0; i < actions.size(); i++) {
                Object action = actions.get(i);
                Map<?, ?> actionMap = asMap(action);
                if (actionMap == null) continue;
                inspectActionMap(actionMap, source, path.index(i), scope, environment, findings);
            }
            return;
        }
        Map<?, ?> actionMap = asMap(raw);
        if (actionMap != null) inspectActionMap(actionMap, source, path, scope, environment, findings);
    }

    private static void inspectActionMap(
            Map<?, ?> actions, String source, KeyPath path, Set<String> scope, Environment environment,
            List<BitFinding> findings) {
        for (Object rawKey : actions.keySet()) {
            String key = String.valueOf(rawKey);
            Object value = value(actions, key);
            KeyPath keyPath = path.key(key);
            if (ActionConfigLoader.STEP_ACTION_KEYS.contains(key)) {
                inspectActionValue(key, value, source, keyPath, scope, environment, findings);
            } else if (ActionConfigLoader.STEP_STRUCTURAL_KEYS.contains(key)) {
                if (key.equalsIgnoreCase("gate")) {
                    inspectGateValue(value, source, keyPath, scope(scope, ActionPlaceholderSanitizer.PRE_GATE),
                            environment, findings);
                } else if (key.equalsIgnoreCase("run")) {
                    inspectActions(value, source, keyPath, scope, environment, findings);
                }
            } else {
                findings.add(unknown(UNKNOWN_STEP_KEY, source, keyPath, key,
                        ActionConfigLoader.STEP_ACTION_KEYS));
            }
        }
    }

    private static void inspectActionValue(
            String key, Object value, String source, KeyPath path, Set<String> scope,
            Environment environment, List<BitFinding> findings) {
        if (key.equalsIgnoreCase("ACTION")) {
            String name = value == null ? "" : value.toString().trim();
            if (!ActionSessionImpl.ACTION_NAMES.contains(name)) {
                findings.add(unknown(UNKNOWN_ACTION_NAME, source, path, name, ActionSessionImpl.ACTION_NAMES));
            }
        } else if (key.equalsIgnoreCase("FOR_EACH")) {
            inspectActions(value, source, path, scope(scope, ActionPlaceholderSanitizer.FOR_EACH), environment, findings);
        } else if (key.equalsIgnoreCase("CONSOLE") || key.equalsIgnoreCase("PLAYER")) {
            inspectPayloadValue(value, source, path, scope(scope, ActionPlaceholderSanitizer.FAN_OUT), findings);
        } else {
            inspectPlaceholderValue(value, source, path, scope, findings);
        }
    }

    private static void inspectGateValue(
            Object raw, String source, KeyPath path, Set<String> scope, Environment environment,
            List<BitFinding> findings) {
        if (raw instanceof List<?> gates) {
            for (int i = 0; i < gates.size(); i++) {
                inspectGateMap(gates.get(i), source, path.index(i), scope, environment, findings);
            }
        } else {
            inspectGateMap(raw, source, path, scope, environment, findings);
        }
    }

    private static void inspectGateMap(
            Object raw, String source, KeyPath path, Set<String> scope, Environment environment,
            List<BitFinding> findings) {
        Map<?, ?> gates = asMap(raw);
        if (gates == null) return;
        for (Map.Entry<?, ?> entry : gates.entrySet()) {
            String type = String.valueOf(entry.getKey());
            if (!GateEvaluator.GATE_TYPES.contains(type)) {
                findings.add(unknown(UNKNOWN_GATE_TYPE, source, path.key(type), type, GateEvaluator.GATE_TYPES));
                continue;
            }
            inspectKnownGate(entry, type, source, path, scope, environment, findings);
        }
    }

    private static void inspectKnownGate(
            Map.Entry<?, ?> entry,
            String type,
            String source,
            KeyPath path,
            Set<String> scope,
            Environment environment,
            List<BitFinding> findings) {
        String canonicalType = type.toLowerCase(Locale.ROOT);
        KeyPath gatePath = path.key(type);
        Map<?, ?> gateValue = asMap(entry.getValue());
        if (gateValue == null) {
            inspectScalarGate(canonicalType, entry.getValue(), source, gatePath, scope, findings);
            return;
        }
        inspectGateSubKeys(canonicalType, gateValue, source, gatePath, findings);
        inspectGateExpressions(canonicalType, gateValue, source, gatePath, findings);
        inspectStructuredGate(canonicalType, gateValue, source, gatePath, scope, environment, findings);
    }

    private static void inspectStructuredGate(
            String gateType,
            Map<?, ?> value,
            String source,
            KeyPath path,
            Set<String> scope,
            Environment environment,
            List<BitFinding> findings) {
        if (gateType.equals("scoreboard")) {
            inspectScoreboardObjective(value(value, "objective"), source,
                    path.key("objective"), environment, findings);
        } else if (gateType.equals(COMMAND_KEY)) {
            inspectCommandGateValue(value, source, path, scope, findings);
        }
    }

    private static void inspectCommandGateValue(
            Map<?, ?> value, String source, KeyPath path, Set<String> scope, List<BitFinding> findings) {
        Object commands = value(value, EXECUTE_KEY);
        String commandKey = EXECUTE_KEY;
        if (commands == null) {
            commands = value(value, RUN_KEY);
            commandKey = RUN_KEY;
        }
        inspectPayloadValue(commands, source, path.key(commandKey),
                scope(scope, ActionPlaceholderSanitizer.GATE_COMMAND), findings);
    }

    private static void inspectScalarGate(
            String gateType, Object value, String source, KeyPath path, Set<String> scope,
            List<BitFinding> findings) {
        if (gateType.equals(COMMAND_KEY)) {
            inspectPayloadValue(value, source, path, scope(scope, ActionPlaceholderSanitizer.GATE_COMMAND), findings);
            return;
        }
        if (!SCALAR_EXPRESSION_GATES.contains(gateType) || value == null
                || GateExpressionParser.isValid(value.toString())) return;
        findings.add(BitFinding.of(GATE_EXPRESSION_INVALID, source, path.toString(),
                "Invalid gate expression: " + value,
                "comparison, exact number, or min..max range", ""));
    }

    private static void inspectGateSubKeys(
            String gateType, Map<?, ?> values, String source, KeyPath path, List<BitFinding> findings) {
        Set<String> allowed = GateEvaluator.GATE_SUB_KEYS.getOrDefault(gateType, Set.of());
        Vocabulary vocabulary = new Vocabulary("gate sub-key", allowed.stream().sorted().toList());
        for (Object rawKey : values.keySet()) {
            String key = String.valueOf(rawKey);
            if (!allowed.contains(key)) {
                findings.add(BitFinding.of(UNKNOWN_GATE_SUBKEY, source, path.key(key).toString(),
                        "Unknown " + gateType + " gate sub-key: " + key,
                        String.join(", ", allowed.stream().sorted().toList()),
                        vocabulary.suggest(key).orElse("")));
            }
        }

        for (Set<String> requiredGroup : GateEvaluator.GATE_REQUIRED_SUB_KEYS.getOrDefault(gateType, List.of())) {
            boolean present = requiredGroup.stream().anyMatch(key -> hasRequiredValue(values.get(key), values.containsKey(key)));
            if (!present) {
                String expected = String.join(" or ", requiredGroup.stream().sorted().toList());
                String suggestion = requiredGroup.stream().sorted().findFirst().orElse("");
                findings.add(BitFinding.of(MISSING_GATE_SUBKEY, source, path.toString(),
                        "Missing required " + gateType + " gate sub-key",
                        expected, suggestion));
            }
        }
    }

    private static boolean hasRequiredValue(Object value, boolean keyPresent) {
        if (!keyPresent || value == null) return false;
        if (value instanceof String text) return !text.isBlank();
        if (value instanceof List<?> list) return !list.isEmpty();
        if (value instanceof Map<?, ?> map) return !map.isEmpty();
        return true;
    }

    private static void inspectGateExpressions(
            String gateType, Map<?, ?> values, String source, KeyPath path, List<BitFinding> findings) {
        Set<String> allowed = GateEvaluator.GATE_SUB_KEYS.getOrDefault(gateType, Set.of());
        for (String key : EXPRESSION_SUB_KEYS) {
            if (!allowed.contains(key) || !values.containsKey(key) || values.get(key) == null) continue;
            String expression = String.valueOf(values.get(key));
            if (!GateExpressionParser.isValid(expression)) {
                findings.add(BitFinding.of(GATE_EXPRESSION_INVALID, source, path.key(key).toString(),
                        "Invalid gate expression: " + expression,
                        "comparison, exact number, or min..max range", ""));
            }
        }
    }

    private static void inspectScoreboardObjective(
            Object raw, String source, KeyPath path, Environment environment, List<BitFinding> findings) {
        if (raw == null || raw.toString().isBlank()) return;
        String objective = raw.toString().trim();
        if (ActionSessionImpl.MANAGED_OBJECTIVES.contains(objective)) return;
        String suggestion = ActionSessionImpl.MANAGED_OBJECTIVES.suggest(objective, 2).orElse("");
        if (!suggestion.isEmpty()) {
            findings.add(BitFinding.of(SCOREBOARD_MANAGED_TYPO, source, path.toString(),
                    "Objective is within edit distance 2 of a managed RTP objective: " + objective,
                    String.join(", ", ActionSessionImpl.MANAGED_OBJECTIVES.values()), suggestion));
            return;
        }
        if (environment.objectiveLookup() != null
                && Optional.of(false).equals(environment.objectiveLookup().apply(objective))) {
            findings.add(BitFinding.of(SCOREBOARD_OBJECTIVE_MISSING, source, path.toString(),
                    "Scoreboard objective is absent: " + objective, "an existing scoreboard objective", ""));
        }
    }

    private static void inspectPlacement(
            Object raw, String source, KeyPath path, Environment environment, List<BitFinding> findings) {
        Map<?, ?> placement = asMap(raw);
        if (placement == null) return;
        Object regionValue = value(placement, REGION_KEY);
        if (regionValue != null && !environment.regionNames().isEmpty()) {
            String region = regionValue.toString().trim();
            if (environment.regionNames().stream().noneMatch(name -> name.equalsIgnoreCase(region))) {
                Vocabulary regions = new Vocabulary(REGION_KEY, environment.regionNames().stream().sorted().toList());
                findings.add(BitFinding.of(UNKNOWN_REGION, source, path.key(REGION_KEY).toString(),
                        "Placement region is not loaded: " + region,
                        String.join(", ", environment.regionNames().stream().sorted().toList()),
                        regions.suggest(region).orElse("")));
            }
        }

        Object schematicValue = value(placement, "schematic");
        if (schematicValue != null && environment.pluginDirectory() != null
                && !schematicResolves(environment.pluginDirectory(), schematicValue.toString())) {
            findings.add(BitFinding.of(SCHEMATIC_UNRESOLVED, source, path.key("schematic").toString(),
                    "Placement schematic cannot be resolved: " + schematicValue,
                    "an existing file under advanced/schematics", ""));
        }
    }

    private static boolean schematicResolves(Path pluginDirectory, String rawName) {
        Path root = pluginDirectory.resolve("advanced").resolve("schematics").toAbsolutePath().normalize();
        try {
            Path relative = Path.of(rawName.trim());
            Path requested = root.resolve(relative).normalize();
            if (!requested.startsWith(root)) return false;
            List<Path> candidates = new ArrayList<>();
            if (hasExtension(relative.getFileName().toString())) {
                candidates.add(requested);
            } else {
                candidates.add(Path.of(requested + ".schem"));
                candidates.add(Path.of(requested + ".schematic"));
            }
            for (Path candidate : candidates) {
                if (!Files.isRegularFile(candidate)) continue;
                if (candidate.toRealPath().startsWith(root.toRealPath())) return true;
            }
        } catch (IOException | java.nio.file.InvalidPathException ignored) {
            return false;
        }
        return false;
    }

    private static boolean hasExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 && dot < filename.length() - 1;
    }

    private static void inspectPayloadValue(
            Object raw, String source, KeyPath path, Set<String> scope, List<BitFinding> findings) {
        if (raw instanceof List<?> values) {
            for (int i = 0; i < values.size(); i++) {
                inspectPayloadValue(values.get(i), source, path.index(i), scope, findings);
            }
        } else if (raw != null) {
            inspectPayload(raw.toString(), source, path, scope, findings);
        }
    }

    private static void inspectPlaceholderValue(
            Object raw, String source, KeyPath path, Set<String> scope, List<BitFinding> findings) {
        if (raw instanceof List<?> values) {
            for (int i = 0; i < values.size(); i++) {
                inspectPlaceholderValue(values.get(i), source, path.index(i), scope, findings);
            }
        } else if (raw != null) {
            inspectPlaceholders(raw.toString(), source, path, scope, findings);
        }
    }

    private static void inspectPayload(String payload, String source, KeyPath path, Set<String> scope,
            List<BitFinding> findings) {
        inspectPlaceholders(payload, source, path, scope, findings);

        if (!isBalancedPayload(payload)) {
            findings.add(BitFinding.of(PAYLOAD_UNBALANCED, source, path.toString(),
                    "Vanilla command payload contains unbalanced quotes, brackets, or braces",
                    "balanced quotes, [], and {}", ""));
            return;
        }
        String component = jsonComponent(payload);
        if (component != null && !isValidJsonComponent(component)) {
            findings.add(BitFinding.of(PAYLOAD_JSON_INVALID, source, path.toString(),
                    "Vanilla command text component is not valid JSON", "a parseable JSON component", ""));
        }
    }

    private static void inspectPlaceholders(String payload, String source, KeyPath path, Set<String> scope,
            List<BitFinding> findings) {
        for (String token : ActionPlaceholderSanitizer.placeholders(payload)) {
            if (INTEGER_TOKEN.matcher(token).matches()) continue;
            if (!ActionPlaceholderSanitizer.inScope(token, ActionPlaceholderSanitizer.knownTokens())) {
                Vocabulary knownTokens = new Vocabulary("placeholder",
                        ActionPlaceholderSanitizer.knownTokens().stream().sorted().toList());
                findings.add(BitFinding.of(PLACEHOLDER_UNKNOWN, source, path.toString(),
                        "Unknown action placeholder: [" + token + "]",
                        String.join(", ", ActionPlaceholderSanitizer.knownTokens().stream().sorted().toList()),
                        knownTokens.suggest(token).orElse("")));
            } else if (!ActionPlaceholderSanitizer.inScope(token, scope)) {
                findings.add(BitFinding.of(PLACEHOLDER_OUT_OF_SCOPE, source, path.toString(),
                        "Action placeholder is not available in this scope: [" + token + "]",
                        String.join(", ", scope.stream().sorted().toList()), ""));
            }
        }
    }

    private static boolean isBalancedPayload(String payload) {
        Deque<Character> brackets = new ArrayDeque<>();
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < payload.length(); i++) {
            char character = payload.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') quoted = false;
                continue;
            }
            if (character == '"') {
                quoted = true;
            } else if (character == '[' || character == '{') {
                brackets.push(character);
            } else if (character == ']' || character == '}') {
                if (brackets.isEmpty()) return false;
                char opening = brackets.pop();
                if ((character == ']' && opening != '[') || (character == '}' && opening != '{')) return false;
            }
        }
        return !quoted && brackets.isEmpty();
    }

    private static String jsonComponent(String payload) {
        Matcher tellraw = TELLRAW_COMPONENT.matcher(payload);
        if (tellraw.matches()) return tellraw.group(1).trim();
        Matcher title = TITLE_COMPONENT.matcher(payload);
        return title.matches() ? title.group(1).trim() : null;
    }

    private static boolean isValidJsonComponent(String component) {
        try (JsonReader reader = new JsonReader(new StringReader(component))) {
            reader.setLenient(true);
            JsonParser.parseReader(reader);
            return reader.peek() == JsonToken.END_DOCUMENT;
        } catch (LinkageError unavailable) {
            return true;
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    private static Set<String> scope(Set<String> addition) {
        Set<String> result = new HashSet<>(ActionPlaceholderSanitizer.COMMON_TOKENS);
        for (String token : addition) result.add(token.toLowerCase(Locale.ROOT));
        return Collections.unmodifiableSet(result);
    }

    private static Set<String> scope(Set<String> current, Set<String> addition) {
        Set<String> result = new HashSet<>(current);
        for (String token : addition) result.add(token.toLowerCase(Locale.ROOT));
        return Collections.unmodifiableSet(result);
    }

    private static String canonicalStage(String stage) {
        return ActionPlaceholderSanitizer.STAGE_TOKENS.keySet().stream()
                .filter(candidate -> candidate.equalsIgnoreCase(stage))
                .findFirst()
                .orElse(stage);
    }

    private static void inspectTriggers(Object raw, String source, KeyPath path, List<BitFinding> findings) {
        if (raw instanceof List<?> triggers) {
            for (int i = 0; i < triggers.size(); i++) inspectTrigger(triggers.get(i), source, path.index(i), findings);
            return;
        }
        Map<?, ?> triggerMap = asMap(raw);
        if (triggerMap == null) return;
        boolean nested = triggerMap.values().stream().anyMatch(value -> asMap(value) != null);
        if (nested) {
            for (Map.Entry<?, ?> entry : triggerMap.entrySet()) {
                inspectTrigger(entry.getValue(), source, path.key(String.valueOf(entry.getKey())), findings);
            }
        } else {
            inspectTrigger(triggerMap, source, path, findings);
        }
    }

    private static void inspectTrigger(Object raw, String source, KeyPath path, List<BitFinding> findings) {
        Map<?, ?> trigger = asMap(raw);
        if (trigger == null || !hasKey(trigger, "type")) return;
        String type = String.valueOf(value(trigger, "type"));
        if (!TRIGGER_TYPES.contains(type)) {
            findings.add(unknown(UNKNOWN_TRIGGER_TYPE, source, path.key("type"), type, TRIGGER_TYPES));
        }
    }

    private static BitFinding unknown(BitCode code, String source, KeyPath path, String candidate, Vocabulary vocabulary) {
        return BitFinding.of(code, source, path.toString(),
                "Unknown " + vocabulary.name() + ": " + candidate,
                String.join(", ", vocabulary.values()), vocabulary.suggest(candidate).orElse(""));
    }


    private static boolean hasKey(Map<?, ?> map, String key) {
        return map.keySet().stream().anyMatch(raw -> key.equalsIgnoreCase(String.valueOf(raw)));
    }

    private static Object value(Map<?, ?> map, String key) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (key.equalsIgnoreCase(String.valueOf(entry.getKey()))) return entry.getValue();
        }
        return null;
    }

    @SuppressWarnings("java:S1168") // Null identifies malformed non-mapping input so validators can skip it.
    private static Map<?, ?> asMap(Object value) {
        if (value instanceof Map<?, ?> map) return map;
        if (value instanceof RtpYamlSection section) return section.getValues(false);
        return null;
    }
}
