package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.bit.KeyPath;
import io.github.dailystruggle.bit.Vocabulary;
import io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Structural checks for action definitions, sharing allowed values with their runtime consumers. */
public final class ActionDefinitionValidator {
    private static final Vocabulary TRIGGER_TYPES = new Vocabulary("trigger type",
            java.util.Arrays.stream(PhysicalTriggerSpec.TriggerType.values()).map(Enum::name).toList());

    public static final BitCode UNKNOWN_LIFECYCLE_STAGE = RtpBitCodes.ACTION.register(2001,
            "ACTION_UNKNOWN_LIFECYCLE_STAGE", Level.ERROR, BitTier.PBIT, "action", "",
            "Rename the stage to one listed in expected; unknown stages never run.");
    public static final BitCode UNKNOWN_STEP_KEY = RtpBitCodes.ACTION.register(2002,
            "ACTION_UNKNOWN_STEP_KEY", Level.ERROR, BitTier.PBIT, "action", "",
            "Rename the step key to one listed in expected; unknown keys are dropped at load.");
    public static final BitCode UNKNOWN_ACTION_NAME = RtpBitCodes.ACTION.register(2003,
            "ACTION_UNKNOWN_ACTION_NAME", Level.ERROR, BitTier.PBIT, "action", "",
            "Use an ACTION: name listed in expected; unknown names fail when the step fires.");
    public static final BitCode UNKNOWN_GATE_TYPE = RtpBitCodes.ACTION.register(2004,
            "ACTION_UNKNOWN_GATE_TYPE", Level.ERROR, BitTier.PBIT, "action", "",
            "Use a gate type listed in expected; unknown gates always evaluate false.");
    public static final BitCode UNKNOWN_TRIGGER_TYPE = RtpBitCodes.ACTION.register(2005,
            "ACTION_UNKNOWN_TRIGGER_TYPE", Level.ERROR, BitTier.PBIT, "action", "",
            "Use a trigger type listed in expected; unknown types fall back to STEP_IN.");

    private ActionDefinitionValidator() {}

    public static BitReport validate(String id, Map<?, ?> definition, String source) {
        List<BitFinding> findings = new ArrayList<>();
        if (definition == null) return BitReport.of(findings);
        String actionId = id == null || id.isBlank() ? "<unknown>" : id;
        KeyPath root = KeyPath.root("actions").key(actionId);
        inspectLifecycle(value(definition, "lifecycle"), source, root.key("lifecycle"), findings);
        Object gate = value(definition, "gate");
        String gateKey = gate == null ? "gates" : "gate";
        inspectGateValue(gate == null ? value(definition, "gates") : gate, source, root.key(gateKey), findings);
        Object triggers = value(definition, "trigger");
        String triggerKey = triggers == null ? "triggers" : "trigger";
        inspectTriggers(triggers == null ? value(definition, "triggers") : triggers,
                source, root.key(triggerKey), findings);
        return BitReport.of(findings);
    }

    private static void inspectLifecycle(Object raw, String source, KeyPath path, List<BitFinding> findings) {
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
            inspectSteps(entry.getValue(), source, stagePath, findings);
        }
    }

    private static void inspectSteps(Object raw, String source, KeyPath path, List<BitFinding> findings) {
        if (!(raw instanceof List<?> steps)) return;
        for (int i = 0; i < steps.size(); i++) {
            Map<?, ?> step = asMap(steps.get(i));
            if (step == null) continue;
            KeyPath stepPath = path.index(i);
            if (hasKey(step, "gate")) {
                inspectGateValue(value(step, "gate"), source, stepPath.key("gate"), findings);
                if (hasKey(step, "run")) {
                    inspectUnknownStepEntries(step, source, stepPath, findings, true);
                    inspectActions(value(step, "run"), source, stepPath.key("run"), findings);
                } else {
                    inspectUnknownStepEntries(step, source, stepPath, findings, false);
                }
            } else {
                inspectActions(step, source, stepPath, findings);
            }
        }
    }

    private static void inspectUnknownStepEntries(
            Map<?, ?> step, String source, KeyPath path, List<BitFinding> findings, boolean hasRun) {
        for (Object rawKey : step.keySet()) {
            String key = String.valueOf(rawKey);
            if (ActionConfigLoader.STEP_STRUCTURAL_KEYS.contains(key)) continue;
            if (!ActionConfigLoader.STEP_ACTION_KEYS.contains(key)) {
                findings.add(unknown(UNKNOWN_STEP_KEY, source, path.key(key), key,
                        ActionConfigLoader.STEP_ACTION_KEYS));
            } else if (!hasRun) {
                inspectActionValue(key, value(step, key), source, path.key(key), findings);
            }
        }
    }

    private static void inspectActions(Object raw, String source, KeyPath path, List<BitFinding> findings) {
        if (raw instanceof List<?> actions) {
            for (int i = 0; i < actions.size(); i++) {
                Object action = actions.get(i);
                Map<?, ?> actionMap = asMap(action);
                if (actionMap == null) continue;
                inspectActionMap(actionMap, source, path.index(i), findings);
            }
            return;
        }
        Map<?, ?> actionMap = asMap(raw);
        if (actionMap != null) inspectActionMap(actionMap, source, path, findings);
    }

    private static void inspectActionMap(Map<?, ?> actions, String source, KeyPath path, List<BitFinding> findings) {
        for (Object rawKey : actions.keySet()) {
            String key = String.valueOf(rawKey);
            Object value = value(actions, key);
            KeyPath keyPath = path.key(key);
            if (ActionConfigLoader.STEP_ACTION_KEYS.contains(key)) {
                inspectActionValue(key, value, source, keyPath, findings);
            } else if (ActionConfigLoader.STEP_STRUCTURAL_KEYS.contains(key)) {
                if (key.equalsIgnoreCase("gate")) inspectGateValue(value, source, keyPath, findings);
                else if (key.equalsIgnoreCase("run")) inspectActions(value, source, keyPath, findings);
            } else {
                findings.add(unknown(UNKNOWN_STEP_KEY, source, keyPath, key,
                        ActionConfigLoader.STEP_ACTION_KEYS));
            }
        }
    }

    private static void inspectActionValue(
            String key, Object value, String source, KeyPath path, List<BitFinding> findings) {
        if (key.equalsIgnoreCase("ACTION")) {
            String name = value == null ? "" : value.toString().trim();
            if (!ActionSessionImpl.ACTION_NAMES.contains(name)) {
                findings.add(unknown(UNKNOWN_ACTION_NAME, source, path, name, ActionSessionImpl.ACTION_NAMES));
            }
        } else if (key.equalsIgnoreCase("FOR_EACH")) {
            inspectActions(value, source, path, findings);
        }
    }

    private static void inspectGateValue(Object raw, String source, KeyPath path, List<BitFinding> findings) {
        if (raw instanceof List<?> gates) {
            for (int i = 0; i < gates.size(); i++) inspectGateMap(gates.get(i), source, path.index(i), findings);
        } else {
            inspectGateMap(raw, source, path, findings);
        }
    }

    private static void inspectGateMap(Object raw, String source, KeyPath path, List<BitFinding> findings) {
        Map<?, ?> gates = asMap(raw);
        if (gates == null) return;
        for (Object rawKey : gates.keySet()) {
            String type = String.valueOf(rawKey);
            if (!GateEvaluator.GATE_TYPES.contains(type)) {
                findings.add(unknown(UNKNOWN_GATE_TYPE, source, path.key(type), type, GateEvaluator.GATE_TYPES));
            }
        }
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

    private static Map<?, ?> asMap(Object value) {
        if (value instanceof Map<?, ?> map) return map;
        if (value instanceof RtpYamlSection section) return section.getValues(false);
        return null;
    }
}
