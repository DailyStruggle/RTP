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
 * {@code /rtp admin setup gameplay choice=<survival|arena|skyblock|oneblock>}
 * Stage 2: Gameplay Style selection.
 */
public class SetupGameplayCmd extends BaseRTPCmdImpl {

    public static final String PERMISSION = "rtp.admin.setup";

    private final SetupSessionRegistry sessionRegistry;
    private final SetupBookMenuBuilder bookBuilder;
    private final @Nullable MenuRenderer menuRenderer;

    public SetupGameplayCmd(
            @Nullable CommandsAPICommand parent,
            SetupSessionRegistry sessionRegistry,
            SetupBookMenuBuilder bookBuilder,
            @Nullable MenuRenderer menuRenderer) {
        super(parent);
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
        this.bookBuilder = Objects.requireNonNull(bookBuilder, "bookBuilder");
        this.menuRenderer = menuRenderer;

        addParameter("choice", new CommandParameter(PERMISSION, "gameplay style preset", (uuid, s) -> true) {
            @Override
            public Set<String> values() {
                return Set.of("survival", "arena", "skyblock", "oneblock");
            }
        });
    }

    @Override
    public String name() {
        return "gameplay";
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public String description() {
        return "select gameplay style preset";
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, @Nullable CommandsAPICommand nextCommand) {
        UUID effectiveCaller = (callerId == null) ? SetupSession.CONSOLE_CALLER_ID : callerId;
        SetupSession session = sessionRegistry.getOrCreate(effectiveCaller);

        List<String> choiceArgs = parameterValues.get("choice");
        String choice = (choiceArgs != null && !choiceArgs.isEmpty()) ? choiceArgs.get(0).toLowerCase(Locale.ROOT) : "";

        if (choice.isEmpty()) {
            SetupHandlerSupport.sendMessage(callerId, "&cUsage: &f/rtp admin setup gameplay choice=<survival|arena|skyblock|oneblock>");
            return false;
        }

        if (choice.equals("survival") || choice.equals("arena") || choice.equals("skyblock") || choice.equals("oneblock")) {
            session.setGameplayChoice(choice);
            session.setCurrentStage(SetupStage.PERFORMANCE);
            return SetupHandlerSupport.renderCurrentStage(callerId, session, bookBuilder, menuRenderer);
        } else {
            SetupHandlerSupport.sendMessage(callerId, "&cInvalid gameplay choice: " + choice + ". Valid: survival, arena, skyblock, oneblock");
            return false;
        }
    }
}
