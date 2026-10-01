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
 * {@code /rtp admin setup world choice=<single|multi>}
 * Stage 1: World Topology selection.
 */
public class SetupWorldCmd extends AbstractSetupStepCmd {

    private static final Set<String> VALID_CHOICES = Set.of("single", "multi");

    public SetupWorldCmd(
            @Nullable CommandsAPICommand parent,
            SetupSessionRegistry sessionRegistry,
            SetupBookMenuBuilder bookBuilder,
            @Nullable MenuRenderer menuRenderer) {
        super(parent, sessionRegistry, bookBuilder, menuRenderer);

        addParameter("choice", new CommandParameter(CMD_PERMISSION, "world topology mode", (uuid, s) -> true) {
            @Override
            public Set<String> values() {
                return VALID_CHOICES;
            }
        });
    }

    @Override
    public String name() {
        return "world";
    }

    @Override
    public String description() {
        return "select world topology mode (single or multi)";
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, @Nullable CommandsAPICommand nextCommand) {
        String choice = SetupHandlerSupport.getFirstParam(parameterValues, "choice").toLowerCase(java.util.Locale.ROOT);
        if (choice.isEmpty()) {
            SetupHandlerSupport.sendMessage(callerId, "&cUsage: &f/rtp admin setup world choice=<single|multi>");
            return false;
        }

        if (VALID_CHOICES.contains(choice)) {
            return updateStageAndRender(callerId, session -> {
                session.setWorldChoice(choice);
                session.setCurrentStage(SetupStage.GAMEPLAY);
            });
        } else {
            SetupHandlerSupport.sendMessage(callerId, "&cInvalid world topology choice: " + choice + ". Valid: " + String.join(", ", VALID_CHOICES));
            return false;
        }
    }
}
