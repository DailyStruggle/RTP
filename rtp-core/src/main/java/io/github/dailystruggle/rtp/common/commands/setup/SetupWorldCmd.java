package io.github.dailystruggle.rtp.common.commands.setup;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * {@code /rtp admin setup world choice=<single|multi> [radius=<distance>]}
 * Stage 1: World Topology and Region Radius selection.
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
        addParameter("radius", new CommandParameter(CMD_PERMISSION, "region outer radius distance", (uuid, s) -> true) {
            @Override
            public Set<String> values() {
                return SetupRadiusCmd.PRESET_RADII;
            }
        });
    }

    @Override
    public String name() {
        return "world";
    }

    @Override
    public String description() {
        return "select world topology mode (single or multi) and region radius";
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, @Nullable CommandsAPICommand nextCommand) {
        String choice = SetupHandlerSupport.getFirstParam(parameterValues, "choice").toLowerCase(java.util.Locale.ROOT);
        String radius = SetupHandlerSupport.getFirstParam(parameterValues, "radius").trim();

        if (choice.isEmpty() && radius.isEmpty()) {
            SetupHandlerSupport.sendMessage(callerId, "&cUsage: &f/rtp admin setup world choice=<single|multi> [radius=<distance>]");
            return false;
        }

        if (!choice.isEmpty() && !VALID_CHOICES.contains(choice)) {
            RTPRuntime.log(Level.WARNING, "[setup] Rejected invalid world topology choice '" + choice + "' from caller=" + callerId);
            SetupHandlerSupport.sendMessage(callerId, "&cInvalid world topology choice: " + choice + ". Valid: " + String.join(", ", VALID_CHOICES));
            return false;
        }

        if (!radius.isEmpty() && !SetupSession.isValidRadius(radius)) {
            RTPRuntime.log(Level.WARNING, "[setup] Rejected invalid radius input '" + radius + "' from caller=" + callerId);
            SetupHandlerSupport.sendMessage(callerId,
                    "&cInvalid radius distance: '" + radius + "'. Use a positive distance such as 2.5km, 5km, 10km, 25km, 4096b, or 500c.");
            return false;
        }

        return updateStageAndRender(callerId, session -> {
            if (!radius.isEmpty()) {
                session.setRadiusChoice(radius);
            }
            if (!choice.isEmpty()) {
                session.setWorldChoice(choice);
                session.setCurrentStage(SetupStage.GAMEPLAY);
            }
        });
    }
}
