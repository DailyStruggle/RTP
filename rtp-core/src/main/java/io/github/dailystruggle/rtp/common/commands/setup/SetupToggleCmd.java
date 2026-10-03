package io.github.dailystruggle.rtp.common.commands.setup;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /rtp admin setup toggle <category> <key>}
 * Toggles addons and effect settings in Stage 4.
 */
public class SetupToggleCmd extends AbstractSetupStepCmd {

    private static final Set<String> VALID_KEYS = Set.of("claimIntegrations", "cinematicEffects", "economyIntegration");

    public SetupToggleCmd(
            @Nullable CommandsAPICommand parent,
            SetupSessionRegistry sessionRegistry,
            SetupBookMenuBuilder bookBuilder,
            @Nullable MenuRenderer menuRenderer) {
        super(parent, sessionRegistry, bookBuilder, menuRenderer);

        addParameter("key", new CommandParameter(CMD_PERMISSION, "toggle key", (u, s) -> true) {
            @Override
            public Set<String> values() {
                return VALID_KEYS;
            }
        });
    }

    @Override
    public String name() {
        return "toggle";
    }

    @Override
    public String description() {
        return "toggle an addon or effect setting in setup";
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, @Nullable CommandsAPICommand nextCommand) {
        String key = SetupHandlerSupport.getFirstParam(parameterValues, "key");

        if (key.isEmpty()) {
            SetupHandlerSupport.sendMessage(callerId, "&cUsage: &f/rtp admin setup toggle key=<key>");
            return false;
        }

        return updateStageAndRender(callerId, session -> {
            boolean newVal = session.toggle(key);
            SetupHandlerSupport.sendMessage(callerId, "&7Toggled &f" + key + " &7to &a" + newVal);
        });
    }
}
