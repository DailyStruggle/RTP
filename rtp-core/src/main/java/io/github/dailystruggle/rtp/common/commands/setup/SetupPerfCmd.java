package io.github.dailystruggle.rtp.common.commands.setup;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /rtp admin setup perf choice=<high|low|folia>}
 * Stage 3: Performance Profile selection.
 */
public class SetupPerfCmd extends BaseRTPCmdImpl {

    public static final String PERMISSION = "rtp.admin.setup";

    private final SetupSessionRegistry sessionRegistry;
    private final SetupBookMenuBuilder bookBuilder;
    private final @Nullable MenuRenderer menuRenderer;

    public SetupPerfCmd(
            @Nullable CommandsAPICommand parent,
            SetupSessionRegistry sessionRegistry,
            SetupBookMenuBuilder bookBuilder,
            @Nullable MenuRenderer menuRenderer) {
        super(parent);
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
        this.bookBuilder = Objects.requireNonNull(bookBuilder, "bookBuilder");
        this.menuRenderer = menuRenderer;

        addParameter("choice", new CommandParameter(PERMISSION, "performance profile tier", (uuid, s) -> true) {
            @Override
            public Set<String> values() {
                return Set.of("high", "low", "folia");
            }
        });
    }

    @Override
    public String name() {
        return "perf";
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public String description() {
        return "select performance profile tier";
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, @Nullable CommandsAPICommand nextCommand) {
        UUID effectiveCaller = (callerId == null) ? SetupSession.CONSOLE_CALLER_ID : callerId;
        SetupSession session = sessionRegistry.getOrCreate(effectiveCaller);

        List<String> choiceArgs = parameterValues.get("choice");
        String choice = (choiceArgs != null && !choiceArgs.isEmpty()) ? choiceArgs.get(0).toLowerCase(Locale.ROOT) : "";

        if (choice.isEmpty()) {
            SetupHandlerSupport.sendMessage(callerId, "&cUsage: &f/rtp admin setup perf choice=<high|low|folia>");
            return false;
        }

        if (choice.equals("high") || choice.equals("low") || choice.equals("folia")) {
            session.setPerformanceChoice(choice);
            session.setCurrentStage(SetupStage.ADDONS);
            return SetupHandlerSupport.renderCurrentStage(callerId, session, bookBuilder, menuRenderer);
        } else {
            SetupHandlerSupport.sendMessage(callerId, "&cInvalid performance choice: " + choice + ". Valid: high, low, folia");
            return false;
        }
    }
}
