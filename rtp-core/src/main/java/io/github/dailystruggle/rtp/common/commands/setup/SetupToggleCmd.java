package io.github.dailystruggle.rtp.common.commands.setup;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /rtp admin setup toggle <category> <key>}
 * Toggles addons and effect settings in Stage 4.
 */
public class SetupToggleCmd extends BaseRTPCmdImpl {

    public static final String PERMISSION = "rtp.admin.setup";

    private final SetupSessionRegistry sessionRegistry;
    private final SetupBookMenuBuilder bookBuilder;
    private final @Nullable MenuRenderer menuRenderer;

    public SetupToggleCmd(
            @Nullable CommandsAPICommand parent,
            SetupSessionRegistry sessionRegistry,
            SetupBookMenuBuilder bookBuilder,
            @Nullable MenuRenderer menuRenderer) {
        super(parent);
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
        this.bookBuilder = Objects.requireNonNull(bookBuilder, "bookBuilder");
        this.menuRenderer = menuRenderer;

        addParameter("key", new CommandParameter(PERMISSION, "toggle key", (u, s) -> true) {
            @Override
            public Set<String> values() {
                return Set.of("claimIntegrations", "cinematicEffects", "economyIntegration");
            }
        });
    }

    @Override
    public String name() {
        return "toggle";
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public String description() {
        return "toggle an addon or effect setting in setup";
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, @Nullable CommandsAPICommand nextCommand) {
        UUID effectiveCaller = (callerId == null) ? SetupSession.CONSOLE_CALLER_ID : callerId;
        SetupSession session = sessionRegistry.getOrCreate(effectiveCaller);

        List<String> keyArgs = parameterValues.get("key");
        String key = (keyArgs != null && !keyArgs.isEmpty()) ? keyArgs.get(0) : "";

        if (key.isEmpty()) {
            SetupHandlerSupport.sendMessage(callerId, "&cUsage: &f/rtp admin setup toggle key=<key>");
            return false;
        }

        boolean newVal = session.toggle(key);
        SetupHandlerSupport.sendMessage(callerId, "&7Toggled &f" + key + " &7to &a" + newVal);

        return SetupHandlerSupport.renderCurrentStage(callerId, session, bookBuilder, menuRenderer);
    }
}
