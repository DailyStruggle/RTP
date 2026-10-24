package io.github.dailystruggle.rtp.actionaddon;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitCodeNamespace;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec;
import io.github.dailystruggle.rtp.common.action.ActionConfigLoader;
import io.github.dailystruggle.rtp.common.action.ActionManager;
import io.github.dailystruggle.rtp.common.action.ActionServices;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.common.bit.BitSeverityPolicy;
import io.github.dailystruggle.rtp.common.trigger.PhysicalTriggerManager;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** SAFE startup checks for the declarative action addon setup. */
public final class ActionSetupBit {
    private static final String OWNER = "LeafRTPActionAddon";
    private static final BitCodeNamespace NAMESPACE = RtpBitCodes.REGISTRY.reserve("ACTADDON", OWNER);

    public static final BitCode NOT_REGISTERED = NAMESPACE.register(
            1, "ACTADDON_NOT_REGISTERED", Level.WARNING, BitTier.PBIT, "action-setup", "",
            "Fix the action definition's ERROR findings so it can be registered.");
    public static final BitCode COMMAND_REGISTER_FAILED = NAMESPACE.register(
            2, "ACTADDON_COMMAND_REGISTER_FAILED", Level.ERROR, BitTier.PBIT, "action-setup", "",
            "Resolve the command registration failure and reload the action addon.");
    public static final BitCode TRIGGER_INACTIVE = NAMESPACE.register(
            3, "ACTADDON_TRIGGER_INACTIVE", Level.ERROR, BitTier.PBIT, "action-setup", "",
            "Ensure the trigger is registered for its action and start the wave timer for batch triggers.");
    public static final BitCode NAME_COLLISION = NAMESPACE.register(
            4, "ACTADDON_NAME_COLLISION", Level.WARNING, BitTier.PBIT, "action-setup", "",
            "Give each action and command a unique name and alias.");

    public static final io.github.dailystruggle.bit.BitComponent COMPONENT = new io.github.dailystruggle.bit.BitComponent(
            "action-setup", "action", BitTier.PBIT, BitSideEffect.SAFE, 5_000, ActionSetupBit::check);

    private ActionSetupBit() {}

    /** Immutable input for the pure action-setup report. */
    public record Snapshot(
            List<ActionConfigLoader.LoadedDefinition> loadedDefinitions,
            Set<String> registeredActionIds,
            List<ActionDefinition> activeDefinitions,
            List<ActionManager.RegistrationFailure> registrationFailures,
            Map<String, PhysicalTriggerSpec> registeredTriggers,
            boolean waveTimerScheduled) {
        public Snapshot {
            loadedDefinitions = loadedDefinitions == null ? List.of() : List.copyOf(loadedDefinitions);
            registeredActionIds = registeredActionIds == null ? Set.of() : registeredActionIds.stream()
                    .filter(Objects::nonNull).map(ActionSetupBit::normalize).collect(java.util.stream.Collectors.toUnmodifiableSet());
            activeDefinitions = activeDefinitions == null ? List.of() : List.copyOf(activeDefinitions);
            registrationFailures = registrationFailures == null ? List.of() : List.copyOf(registrationFailures);
            registeredTriggers = registeredTriggers == null ? Map.of() : registeredTriggers.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            entry -> normalize(entry.getKey()), Map.Entry::getValue, (first, ignored) -> first));
        }
    }

    /** Builds a report from supplied state without consulting platform services. */
    public static BitReport report(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        List<BitFinding> findings = new ArrayList<>();
        for (ActionConfigLoader.LoadedDefinition loaded : snapshot.loadedDefinitions()) {
            if (!snapshot.registeredActionIds().contains(normalize(loaded.id()))) {
                findings.add(finding(NOT_REGISTERED, Level.WARNING, loaded.source(), loaded.id(),
                        "The action definition was loaded but was not registered, usually because it has ERROR findings.",
                        "definition passes validation"));
            }
        }

        for (ActionManager.RegistrationFailure failure : snapshot.registrationFailures()) {
            if (!failure.kind().equalsIgnoreCase("command")) continue;
            findings.add(finding(COMMAND_REGISTER_FAILED, Level.ERROR, "actions/" + failure.actionId() + ".yml",
                    failure.target(), "Command registration failed for action " + failure.actionId() + ": " + failure.message(),
                    "command and aliases register successfully"));
        }

        for (ActionDefinition action : snapshot.activeDefinitions()) {
            for (PhysicalTriggerSpec declared : action.triggers()) {
                PhysicalTriggerSpec registered = snapshot.registeredTriggers().get(normalize(declared.id()));
                if (registered == null) {
                    findings.add(finding(TRIGGER_INACTIVE, Level.ERROR, "actions/" + action.id() + ".yml",
                            "triggers." + declared.id(), "Trigger is not registered for action " + action.id() + ".",
                            "trigger registered for " + action.id()));
                } else if (!registered.actionId().equalsIgnoreCase(action.id())) {
                    findings.add(finding(TRIGGER_INACTIVE, Level.ERROR, "actions/" + action.id() + ".yml",
                            "triggers." + declared.id(), "Trigger id is owned by action " + registered.actionId()
                                    + " instead of " + action.id() + ".", "trigger owned by " + action.id()));
                }
                if (declared.batchIntervalSeconds() > 0 && !snapshot.waveTimerScheduled()) {
                    findings.add(finding(TRIGGER_INACTIVE, Level.ERROR, "actions/" + action.id() + ".yml",
                            "triggers." + declared.id() + ".batchInterval",
                            "Batch trigger is configured but the wave timer is not scheduled.",
                            "wave timer scheduled"));
                }
            }
        }

        reportActionLookupCollisions(snapshot.activeDefinitions(), findings);
        reportRootCommandCollisions(snapshot.activeDefinitions(), findings);
        return BitReport.of(findings.stream()
                .map(BitSeverityPolicy::effectiveFinding)
                .flatMap(java.util.Optional::stream)
                .toList());
    }

    private static BitReport check() {
        ActionManager manager = ActionServices.actionManager();
        List<ActionDefinition> actions = new ArrayList<>();
        Set<String> registered = Set.of();
        if (manager != null) {
            registered = manager.getActionIds();
            manager.getActionIds().stream().sorted().map(manager::getAction)
                    .flatMap(java.util.Optional::stream).forEach(actions::add);
        }
        // The shared manager is owned by RTP and must not be closed here.
        @SuppressWarnings("resource")
        PhysicalTriggerManager triggers = PhysicalTriggerManager.instance();
        Map<String, PhysicalTriggerSpec> registeredTriggers = new HashMap<>();
        for (PhysicalTriggerSpec trigger : triggers.getTriggers()) {
            registeredTriggers.put(normalize(trigger.id()), trigger);
        }
        return report(new Snapshot(ActionConfigLoader.loadedDefinitions(), registered, actions,
                ActionManager.registrationFailures(), registeredTriggers, triggers.isWaveTimerScheduled()));
    }

    private static void reportActionLookupCollisions(List<ActionDefinition> actions, List<BitFinding> findings) {
        Map<String, String> owners = new HashMap<>();
        owners.put("cancel", "built-in CANCEL");
        for (ActionDefinition action : actions) {
            registerName(owners, action.id(), action.id(), "actions." + action.id(), findings);
            if (action.alias() != null && !action.alias().isBlank()) {
                registerName(owners, action.alias(), action.id(), "actions." + action.id() + ".alias", findings);
            }
        }
    }

    private static void reportRootCommandCollisions(List<ActionDefinition> actions, List<BitFinding> findings) {
        Map<String, String> owners = new HashMap<>();
        for (ActionDefinition action : actions) {
            ActionDefinition.CommandSpec command = action.command();
            if (!command.isConfigured()) continue;
            registerName(owners, command.name(), action.id(), "actions." + action.id() + ".command.name", findings);
            for (String alias : command.aliases()) {
                registerName(owners, alias, action.id(), "actions." + action.id() + ".command.aliases", findings);
            }
        }
    }

    private static void registerName(
            Map<String, String> owners, String name, String owner, String path, List<BitFinding> findings) {
        if (name == null || name.isBlank()) return;
        String key = normalize(name);
        String previous = owners.putIfAbsent(key, owner);
        if (previous != null && !previous.equalsIgnoreCase(owner)) {
            findings.add(finding(NAME_COLLISION, Level.WARNING, "action definitions", path,
                    "Name or alias '" + name + "' is shared by " + previous + " and " + owner + ".",
                    "unique action and command names"));
        }
    }

    private static BitFinding finding(BitCode code, Level severity, String source, String path,
                                      String message, String expected) {
        return BitFinding.of(code, severity, source, path, message, expected, code.remediation());
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
