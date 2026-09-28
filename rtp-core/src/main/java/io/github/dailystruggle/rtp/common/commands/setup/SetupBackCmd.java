package io.github.dailystruggle.rtp.common.commands.setup;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code /rtp admin setup back}
 * Returns to the previous setup stage.
 */
public class SetupBackCmd extends BaseRTPCmdImpl {

    public static final String PERMISSION = "rtp.admin.setup";

    private final SetupSessionRegistry sessionRegistry;
    private final SetupBookMenuBuilder bookBuilder;
    private final @Nullable MenuRenderer menuRenderer;

    public SetupBackCmd(
            @Nullable CommandsAPICommand parent,
            SetupSessionRegistry sessionRegistry,
            SetupBookMenuBuilder bookBuilder,
            @Nullable MenuRenderer menuRenderer) {
        super(parent);
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
        this.bookBuilder = Objects.requireNonNull(bookBuilder, "bookBuilder");
        this.menuRenderer = menuRenderer;
    }

    @Override
    public String name() {
        return "back";
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public String description() {
        return "step back to the previous setup stage";
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, @Nullable CommandsAPICommand nextCommand) {
        UUID effectiveCaller = (callerId == null) ? SetupSession.CONSOLE_CALLER_ID : callerId;
        SetupSession session = sessionRegistry.getOrCreate(effectiveCaller);
        session.setCurrentStage(session.currentStage().previous());
        return SetupHandlerSupport.renderCurrentStage(callerId, session, bookBuilder, menuRenderer);
    }
}
