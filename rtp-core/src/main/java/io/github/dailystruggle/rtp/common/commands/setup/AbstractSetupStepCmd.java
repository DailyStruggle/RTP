package io.github.dailystruggle.rtp.common.commands.setup;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Common abstract base command for Setup Wizard steps.
 * Encapsulates session registry, book builder, and menu renderer dependencies,
 * standard permission checking, and stage update execution.
 */
public abstract class AbstractSetupStepCmd extends BaseRTPCmdImpl {

    public static final String CMD_PERMISSION = "rtp.admin.setup";

    protected final SetupSessionRegistry sessionRegistry;
    protected final SetupBookMenuBuilder bookBuilder;
    protected final @Nullable MenuRenderer menuRenderer;

    protected AbstractSetupStepCmd(
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
    public String permission() {
        return CMD_PERMISSION;
    }

    /**
     * Resolves the session for the caller, executes stage updater, and renders the current stage.
     *
     * @param callerId     the caller UUID
     * @param stageUpdater updater to modify the session or its stage
     * @return true if rendering succeeded
     */
    protected boolean updateStageAndRender(UUID callerId, Consumer<SetupSession> stageUpdater) {
        SetupSession session = SetupHandlerSupport.resolveSession(callerId, sessionRegistry);
        stageUpdater.accept(session);
        return SetupHandlerSupport.renderCurrentStage(callerId, session, bookBuilder, menuRenderer);
    }
}
