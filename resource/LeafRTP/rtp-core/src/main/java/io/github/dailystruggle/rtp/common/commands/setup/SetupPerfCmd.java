package io.github.dailystruggle.rtp.common.commands.setup;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /rtp admin setup perf choice=<high|low|folia>}
 * Stage 3: Performance Profile selection.
 */
public class SetupPerfCmd extends AbstractSetupStepCmd {

    private static final Set<String> VALID_CHOICES = Set.of("high", "low", "folia");

    public SetupPerfCmd(
            @Nullable CommandsAPICommand parent,
            SetupSessionRegistry sessionRegistry,
            SetupBookMenuBuilder bookBuilder,
            @Nullable MenuRenderer menuRenderer) {
        super(parent, sessionRegistry, bookBuilder, menuRenderer);

        addParameter("choice", new CommandParameter(CMD_PERMISSION, "performance profile tier", (uuid, s) -> true) {
            @Override
            public Set<String> values() {
                return VALID_CHOICES;
            }
        });
    }

    @Override
    public String name() {
        return "perf";
    }

    @Override
    public String description() {
        return "select performance profile tier";
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, @Nullable CommandsAPICommand nextCommand) {
        String choice = SetupHandlerSupport.getFirstParam(parameterValues, "choice").toLowerCase(Locale.ROOT);
        if (choice.isEmpty()) {
            SetupHandlerSupport.sendMessage(callerId, "&cUsage: &f/rtp admin setup perf choice=<high|low|folia>");
            return false;
        }

        if (VALID_CHOICES.contains(choice)) {
            return updateStageAndRender(callerId, session -> {
                session.setPerformanceChoice(choice);
                session.setCurrentStage(SetupStage.ADDONS);
            });
        } else {
            SetupHandlerSupport.sendMessage(callerId, "&cInvalid performance choice: " + choice + ". Valid: " + String.join(", ", VALID_CHOICES));
            return false;
        }
    }
}
