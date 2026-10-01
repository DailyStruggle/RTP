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
 * The {@code setup} subtree under {@code /rtp admin}.
 * Provides an interactive 5-stage setup wizard for configuring worlds, gameplay templates,
 * performance profiles, and addon/effect toggles via Book Menu or Console CLI prompts.
 * (ADR-038 Contract 8 & 9).
 */
public class SetupCmd extends BaseRTPCmdImpl {

    public static final String CMD_PERMISSION = "rtp.admin.setup";

    private final SetupSessionRegistry sessionRegistry;
    private final SetupBookMenuBuilder bookBuilder;
    private final @Nullable MenuRenderer menuRenderer;

    public SetupCmd(@Nullable CommandsAPICommand parent) {
        this(parent, new SetupSessionRegistry(), new SetupBookMenuBuilder(), null);
    }

    public SetupCmd(
            @Nullable CommandsAPICommand parent,
            SetupSessionRegistry sessionRegistry,
            SetupBookMenuBuilder bookBuilder,
            @Nullable MenuRenderer menuRenderer) {
        super(parent);
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
        this.bookBuilder = Objects.requireNonNull(bookBuilder, "bookBuilder");
        this.menuRenderer = menuRenderer;

        addSubCommand(new SetupWorldCmd(this, this.sessionRegistry, this.bookBuilder, this.menuRenderer));
        addSubCommand(new SetupGameplayCmd(this, this.sessionRegistry, this.bookBuilder, this.menuRenderer));
        addSubCommand(new SetupPerfCmd(this, this.sessionRegistry, this.bookBuilder, this.menuRenderer));
        addSubCommand(new SetupNextCmd(this, this.sessionRegistry, this.bookBuilder, this.menuRenderer));
        addSubCommand(new SetupToggleCmd(this, this.sessionRegistry, this.bookBuilder, this.menuRenderer));
        addSubCommand(new SetupBackCmd(this, this.sessionRegistry, this.bookBuilder, this.menuRenderer));
        addSubCommand(new SetupPreviewCmd(this, this.sessionRegistry, this.bookBuilder, this.menuRenderer));
        addSubCommand(new SetupConfirmCmd(this, this.sessionRegistry));
        addSubCommand(new SetupCancelCmd(this, this.sessionRegistry));
        addSubCommand(new SetupStatusCmd(this, this.sessionRegistry));
    }

    public SetupSessionRegistry sessionRegistry() {
        return sessionRegistry;
    }

    @Override
    public String name() {
        return "setup";
    }

    @Override
    public String permission() {
        return CMD_PERMISSION;
    }

    @Override
    public String description() {
        return "interactive setup wizard for initial configuration";
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, @Nullable CommandsAPICommand nextCommand) {
        if (nextCommand != null) {
            return true;
        }

        SetupSession session = SetupHandlerSupport.resolveSession(callerId, sessionRegistry);

        return SetupHandlerSupport.renderCurrentStage(callerId, session, bookBuilder, menuRenderer);
    }
}
