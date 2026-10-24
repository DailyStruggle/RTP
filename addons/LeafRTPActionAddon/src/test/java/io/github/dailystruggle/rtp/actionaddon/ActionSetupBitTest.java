package io.github.dailystruggle.rtp.actionaddon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec;
import io.github.dailystruggle.rtp.common.action.ActionConfigLoader;
import io.github.dailystruggle.rtp.common.action.ActionManager;
import io.github.dailystruggle.rtp.common.bit.BitSeverityPolicy;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("REQ-API-F-009 Action addon setup PBIT")
class ActionSetupBitTest {
    @Test
    void healthyActionWithItsTriggerHasNoFindings() {
        ActionDefinition action = action("arena", "", null,
                List.of(trigger("portal", "arena", 0)));
        ActionSetupBit.Snapshot snapshot = snapshot(
                List.of(new ActionConfigLoader.LoadedDefinition("arena", "arena.yml", Map.of())),
                Set.of("arena"), List.of(action), List.of(), List.of(trigger("portal", "arena", 0)), true);

        assertTrue(ActionSetupBit.report(snapshot).findings().isEmpty());
    }

    @Test
    void reportsRejectedDefinitionsAndCommandRegistrationFailures() {
        ActionManager.RegistrationFailure failure = new ActionManager.RegistrationFailure(
                "command", "arena", "arena-root", "duplicate command");
        ActionSetupBit.Snapshot snapshot = snapshot(
                List.of(new ActionConfigLoader.LoadedDefinition("broken", "broken.yml", Map.of())),
                Set.of(), List.of(), List.of(failure), List.of(), true);

        assertHasCode(snapshot, ActionSetupBit.NOT_REGISTERED);
        assertHasCode(snapshot, ActionSetupBit.COMMAND_REGISTER_FAILED);
    }

    @Test
    void reportsMissingAndMisownedTriggersAndUnscheduledBatchTimer() {
        ActionDefinition missing = action("missing", "", null,
                List.of(trigger("lost", "missing", 0)));
        ActionDefinition misowned = action("misowned", "", null,
                List.of(trigger("shared", "misowned", 0)));
        ActionDefinition batch = action("batch", "", null,
                List.of(trigger("wave", "batch", 10)));
        ActionSetupBit.Snapshot snapshot = snapshot(
                List.of(), Set.of("missing", "misowned", "batch"), List.of(missing, misowned, batch), List.of(),
                List.of(trigger("shared", "other", 0), trigger("wave", "batch", 10)), false);

        assertHasCode(snapshot, ActionSetupBit.TRIGGER_INACTIVE);
        assertFalse(ActionSetupBit.report(snapshot).findings().isEmpty());
    }

    @Test
    void reportsActionAliasesAndRootCommandCollisions() {
        ActionDefinition first = action("first", "shared", command("warp", List.of("go")), List.of());
        ActionDefinition second = action("second", "SHARED", command("other", List.of("GO")), List.of());
        ActionSetupBit.Snapshot snapshot = snapshot(
                List.of(), Set.of("first", "second"), List.of(first, second), List.of(), List.of(), true);

        assertHasCode(snapshot, ActionSetupBit.NAME_COLLISION);
    }

    @Test
    void reportsAliasThatShadowsCancel() {
        ActionDefinition action = action("arena", "cancel", null, List.of());
        ActionSetupBit.Snapshot snapshot = snapshot(
                List.of(), Set.of("arena"), List.of(action), List.of(), List.of(), true);

        assertHasCode(snapshot, ActionSetupBit.NAME_COLLISION);
    }

    @Test
    void appliesConfiguredSeverityOverrideToAddonFinding() {
        BitSeverityPolicy.load(false, Map.of(ActionSetupBit.NAME_COLLISION.key(), "INFO"));
        ActionDefinition first = action("first", "shared", null, List.of());
        ActionDefinition second = action("second", "SHARED", null, List.of());
        ActionSetupBit.Snapshot snapshot = snapshot(
                List.of(), Set.of("first", "second"), List.of(first, second), List.of(), List.of(), true);

        try {
            assertEquals(Level.INFO, ActionSetupBit.report(snapshot).findings().get(0).severity());
        } finally {
            BitSeverityPolicy.resetDefaults();
        }
    }

    private static ActionSetupBit.Snapshot snapshot(
            List<ActionConfigLoader.LoadedDefinition> loaded,
            Set<String> registered,
            List<ActionDefinition> actions,
            List<ActionManager.RegistrationFailure> failures,
            List<PhysicalTriggerSpec> triggers,
            boolean timerScheduled) {
        Map<String, PhysicalTriggerSpec> byId = triggers.stream()
                .collect(java.util.stream.Collectors.toMap(trigger -> trigger.id().toLowerCase(), trigger -> trigger));
        return new ActionSetupBit.Snapshot(loaded, registered, actions, failures, byId, timerScheduled);
    }

    private static ActionDefinition action(
            String id, String alias, ActionDefinition.CommandSpec command, List<PhysicalTriggerSpec> triggers) {
        return new ActionDefinition(id, alias, "", "", null, null, null,
                command, List.of(), triggers, null, null);
    }

    private static ActionDefinition.CommandSpec command(String name, List<String> aliases) {
        return new ActionDefinition.CommandSpec(name, "", "", aliases);
    }

    private static PhysicalTriggerSpec trigger(String id, String actionId, long batchInterval) {
        return new PhysicalTriggerSpec(id, PhysicalTriggerSpec.TriggerType.STEP_IN, "world",
                0, 0, 0, 1, 1, 1, actionId, 0, batchInterval);
    }

    private static void assertHasCode(ActionSetupBit.Snapshot snapshot, BitCode code) {
        BitReport report = ActionSetupBit.report(snapshot);
        assertTrue(report.findings().stream().anyMatch(finding -> finding.code().equals(code.key())),
                () -> "missing " + code.key() + " in " + report.findings());
    }
}
