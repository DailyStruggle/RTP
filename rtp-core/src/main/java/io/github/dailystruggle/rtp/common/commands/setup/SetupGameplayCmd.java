package io.github.dailystruggle.rtp.common.commands.setup;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /rtp admin setup gameplay choice=<survival|arena|skyblock|oneblock>}
 * Stage 2: Gameplay Style selection.
 */
public class SetupGameplayCmd extends AbstractSetupStepCmd {

    private static final Set<String> VALID_CHOICES = Set.of("survival", "arena", "skyblock", "oneblock");

    public SetupGameplayCmd(
            @Nullable CommandsAPICommand parent,
            SetupSessionRegistry sessionRegistry,
            SetupBookMenuBuilder bookBuilder,
            @Nullable MenuRenderer menuRenderer) {
        super(parent, sessionRegistry, bookBuilder, menuRenderer);

        addParameter("choice", new CommandParameter(CMD_PERMISSION, "gameplay style preset", (uuid, s) -> true) {
            @Override
            public Set<String> values() {
                return VALID_CHOICES;
            }
        });
    }

    @Override
    public String name() {
        return "gameplay";
    }

    @Override
    public String description() {
        return "select gameplay style preset";
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, @Nullable CommandsAPICommand nextCommand) {
        String choice = SetupHandlerSupport.getFirstParam(parameterValues, "choice").toLowerCase(java.util.Locale.ROOT);
        if (choice.isEmpty()) {
            SetupHandlerSupport.sendMessage(callerId, "&cUsage: &f/rtp admin setup gameplay choice=<survival|arena|skyblock|oneblock>");
            return false;
        }

        if (VALID_CHOICES.contains(choice)) {
            return updateStageAndRender(callerId, session -> {
                session.setGameplayChoice(choice);
                session.setCurrentStage(SetupStage.PERFORMANCE);
            });
        } else {
            SetupHandlerSupport.sendMessage(callerId, "&cInvalid gameplay choice: " + choice + ". Valid: " + String.join(", ", VALID_CHOICES));
            return false;
        }
    }
}
