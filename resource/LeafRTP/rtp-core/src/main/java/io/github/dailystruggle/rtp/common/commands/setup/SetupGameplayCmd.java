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
        addParameter("radius", new CommandParameter(CMD_PERMISSION, "region outer radius distance", (uuid, s) -> true) {
            @Override
            public Set<String> values() {
                return SetupRadiusCmd.PRESET_RADII;
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
        String radius = SetupHandlerSupport.getFirstParam(parameterValues, "radius").trim();
        if (choice.isEmpty() && radius.isEmpty()) {
            SetupHandlerSupport.sendMessage(callerId, "&cUsage: &f/rtp admin setup gameplay choice=<survival|arena|skyblock|oneblock> [radius=<distance>]");
            return false;
        }

        if (!choice.isEmpty() && !VALID_CHOICES.contains(choice)) {
            SetupHandlerSupport.sendMessage(callerId, "&cInvalid gameplay choice: " + choice + ". Valid: " + String.join(", ", VALID_CHOICES));
            return false;
        }

        if (!radius.isEmpty() && !SetupSession.isValidRadius(radius)) {
            io.github.dailystruggle.rtp.common.runtime.RTPRuntime.log(
                    java.util.logging.Level.WARNING,
                    "[setup] Rejected invalid radius input '" + radius + "' from caller=" + callerId);
            SetupHandlerSupport.sendMessage(callerId,
                    "&cInvalid radius distance: '" + radius + "'. Use a positive distance such as 2.5km, 5km, 10km, 25km, 4096b, or 500c.");
            return false;
        }

        return updateStageAndRender(callerId, session -> {
            if (!radius.isEmpty()) {
                session.setRadiusChoice(radius);
            }
            if (!choice.isEmpty()) {
                session.setGameplayChoice(choice);
                session.setCurrentStage(SetupStage.PERFORMANCE);
            }
        });
    }
}
