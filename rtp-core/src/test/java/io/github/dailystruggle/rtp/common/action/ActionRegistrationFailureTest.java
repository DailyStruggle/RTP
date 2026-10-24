package io.github.dailystruggle.rtp.common.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("REQ-API-F-009 Action registration failures are retained until successful retry")
class ActionRegistrationFailureTest {
    @AfterEach
    void clearGlobalState() {
        ActionManager.setTriggerRegistrar(null);
        ActionManager.setCommandRegistrar(null);
        new ActionManager().clearDefinitions();
    }

    @Test
    void triggerFailureIsRecordedAndSuccessfulRetryClearsIt() {
        ActionManager manager = new ActionManager();
        ActionDefinition action = actionWithTrigger();
        ActionManager.setTriggerRegistrar(trigger -> {
            throw new IllegalStateException("trigger registry unavailable");
        });

        manager.registerAction(action);

        assertEquals("trigger registry unavailable", ActionManager.registrationFailures().get(0).message());
        ActionManager.setTriggerRegistrar(trigger -> {});
        manager.registerAction(action);
        assertTrue(ActionManager.registrationFailures().isEmpty());
    }

    @Test
    void explicitFailureCanBeClearedCaseInsensitivelyAndDefinitionResetClearsFailures() {
        ActionManager.recordRegistrationFailure("COMMAND", "Arena", "ArenaRoot", "duplicate");

        assertEquals(List.of(new ActionManager.RegistrationFailure("command", "arena", "arenaroot", "duplicate")),
                ActionManager.registrationFailures());
        ActionManager.clearRegistrationFailure("Command", "ARENA", "ARENAROOT");
        assertTrue(ActionManager.registrationFailures().isEmpty());

        ActionManager.recordRegistrationFailure("command", "arena", "arena-root", "duplicate");
        new ActionManager().clearDefinitions();
        assertTrue(ActionManager.registrationFailures().isEmpty());
    }

    private static ActionDefinition actionWithTrigger() {
        PhysicalTriggerSpec trigger = new PhysicalTriggerSpec("portal", PhysicalTriggerSpec.TriggerType.PORTAL,
                "world", 0, 0, 0, 1, 1, 1, "arena", 0);
        return new ActionDefinition("arena", "", "", "", null, null, null,
                null, List.of(), List.of(trigger), null, null);
    }
}
