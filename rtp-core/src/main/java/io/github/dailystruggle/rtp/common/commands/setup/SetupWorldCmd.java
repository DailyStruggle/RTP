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
 * {@code /rtp admin setup world choice=<single|multi>}
 * Stage 1: World Topology selection.
 */
public class SetupWorldCmd extends BaseRTPCmdImpl {

    public static final String PERMISSION = "rtp.admin.setup";

    private final SetupSessionRegistry sessionRegistry;
    private final SetupBookMenuBuilder bookBuilder;
    private final @Nullable MenuRenderer menuRenderer;

    public SetupWorldCmd(
            @Nullable CommandsAPICommand parent,
            SetupSessionRegistry sessionRegistry,
            SetupBookMenuBuilder bookBuilder,
            @Nullable MenuRenderer menuRenderer) {
        super(parent);
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
        this.bookBuilder = Objects.requireNonNull(bookBuilder, "bookBuilder");
        this.menuRenderer = menuRenderer;

        addParameter("choice", new CommandParameter(PERMISSION, "world topology mode", (uuid, s) -> true) {
            @Override
            public Set<String> values() {
                return Set.of("single", "multi");
            }
        });
    }

    @Override
    public String name() {
        return "world";
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public String description() {
        return "select world topology mode (single or multi)";
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, @Nullable CommandsAPICommand nextCommand) {
        UUID effectiveCaller = (callerId == null) ? SetupSession.CONSOLE_CALLER_ID : callerId;
        SetupSession session = sessionRegistry.getOrCreate(effectiveCaller);

        List<String> choiceArgs = parameterValues.get("choice");
        String choice = (choiceArgs != null && !choiceArgs.isEmpty()) ? choiceArgs.get(0).toLowerCase(Locale.ROOT) : "";

        if (choice.isEmpty()) {
            SetupHandlerSupport.sendMessage(callerId, "&cUsage: &f/rtp admin setup world choice=<single|multi>");
            return false;
        }

        if (choice.equals("single") || choice.equals("multi")) {
            session.setWorldChoice(choice);
            session.setCurrentStage(SetupStage.GAMEPLAY);
            return SetupHandlerSupport.renderCurrentStage(callerId, session, bookBuilder, menuRenderer);
        } else {
            SetupHandlerSupport.sendMessage(callerId, "&cInvalid world topology choice: " + choice + ". Valid: single, multi");
            return false;
        }
    }
}
