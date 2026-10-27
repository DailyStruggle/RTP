package io.github.dailystruggle.rtp.common.commands.setup;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.base.MenuRendererDiscovery;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The {@code setup} subtree under {@code /rtp admin}.
 * Provides an interactive 5-stage setup wizard for configuring worlds, gameplay templates,
 * performance profiles, and addon/effect toggles via Book Menu or Console CLI prompts.
 * (ADR-038 Contract 8 and 9).
 */
public class SetupCmd extends BaseRTPCmdImpl {

    public static final String CMD_PERMISSION = "rtp.admin.setup";

    private final SetupSessionRegistry sessionRegistry;
    private final SetupBookMenuBuilder bookBuilder;
    private final @Nullable MenuRenderer menuRenderer;

    public SetupCmd(@Nullable CommandsAPICommand parent) {
        this(parent, new SetupSessionRegistry(), new SetupBookMenuBuilder(), MenuRendererDiscovery.discoverRenderer());
    }

    public SetupCmd(
            @Nullable CommandsAPICommand parent,
            SetupSessionRegistry sessionRegistry,
            SetupBookMenuBuilder bookBuilder,
            @Nullable MenuRenderer menuRenderer) {
        super(parent);
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
        this.bookBuilder = Objects.requireNonNull(bookBuilder, "bookBuilder");
        this.menuRenderer = (menuRenderer != null) ? menuRenderer : MenuRendererDiscovery.discoverRenderer();

        addParameter("radius", new CommandParameter(CMD_PERMISSION, "region outer radius distance", (uuid, s) -> true) {
            @Override
            public Set<String> values() {
                return SetupRadiusCmd.PRESET_RADII;
            }
        });

        addSubCommand(new SetupWorldCmd(this, this.sessionRegistry, this.bookBuilder, this.menuRenderer));
        addSubCommand(new SetupRadiusCmd(this, this.sessionRegistry, this.bookBuilder, this.menuRenderer));
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

    public @Nullable MenuRenderer menuRenderer() {
        return menuRenderer;
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

        String radius = SetupHandlerSupport.getFirstParam(parameterValues, "radius").trim();
        if (!radius.isEmpty() && !SetupSession.isValidRadius(radius)) {
            RTPRuntime.log(Level.WARNING, "[setup] Rejected invalid radius input '" + radius + "' from caller=" + callerId);
            SetupHandlerSupport.sendMessage(callerId,
                    "&cInvalid radius distance: '" + radius + "'. Use a positive distance such as 2.5km, 5km, 10km, 25km, 4096b, or 500c.");
            return false;
        }

        SetupSession session = SetupHandlerSupport.resolveSession(callerId, sessionRegistry);
        if (!radius.isEmpty()) {
            session.setRadiusChoice(radius);
        }

        MenuRenderer renderer = (this.menuRenderer != null) ? this.menuRenderer : MenuRendererDiscovery.discoverRenderer();
        return SetupHandlerSupport.renderCurrentStage(callerId, session, bookBuilder, renderer);
    }
}
