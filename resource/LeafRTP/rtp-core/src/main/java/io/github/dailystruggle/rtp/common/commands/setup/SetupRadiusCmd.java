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
 * {@code /rtp admin setup radius radius=<distance>}
 * Configures the region radius preset or custom distance via {@link io.github.dailystruggle.rtp.common.selection.region.util.DistanceParser}.
 */
public class SetupRadiusCmd extends AbstractSetupStepCmd {

    public static final Set<String> PRESET_RADII = Set.of("2.5km", "5km", "10km", "25km", "4096b", "500c");

    public SetupRadiusCmd(
            @Nullable CommandsAPICommand parent,
            SetupSessionRegistry sessionRegistry,
            SetupBookMenuBuilder bookBuilder,
            @Nullable MenuRenderer menuRenderer) {
        super(parent, sessionRegistry, bookBuilder, menuRenderer);

        CommandParameter radiusParam = new CommandParameter(CMD_PERMISSION, "region outer radius distance (e.g. 2.5km, 5km, 10km, 25km, 4096b, 500c)", (uuid, s) -> true) {
            @Override
            public Set<String> values() {
                return PRESET_RADII;
            }
        };
        addParameter("radius", radiusParam);
        addParameter("choice", radiusParam);
    }

    @Override
    public String name() {
        return "radius";
    }

    @Override
    public String description() {
        return "set region outer radius (e.g. 2.5km, 5km, 10km, 25km, 4096b, 500c)";
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, @Nullable CommandsAPICommand nextCommand) {
        String rawRadius = SetupHandlerSupport.getFirstParam(parameterValues, "radius").trim();
        if (rawRadius.isEmpty()) {
            rawRadius = SetupHandlerSupport.getFirstParam(parameterValues, "choice").trim();
        }
        if (rawRadius.isEmpty()) {
            SetupHandlerSupport.sendMessage(callerId, "&cUsage: &f/rtp admin setup radius radius=<distance> &7(e.g. 2.5km, 5km, 10km, 25km, 4096b, 500c)");
            return false;
        }

        if (!SetupSession.isValidRadius(rawRadius)) {
            RTPRuntime.log(Level.WARNING, "[setup] Rejected invalid radius input '" + rawRadius + "' from caller=" + callerId);
            SetupHandlerSupport.sendMessage(callerId,
                    "&cInvalid radius distance: '" + rawRadius + "'. Use a positive distance such as 2.5km, 5km, 10km, 25km, 4096b, or 500c.");
            return false;
        }

        String validRadius = rawRadius;
        return updateStageAndRender(callerId, session -> session.setRadiusChoice(validRadius));
    }
}
