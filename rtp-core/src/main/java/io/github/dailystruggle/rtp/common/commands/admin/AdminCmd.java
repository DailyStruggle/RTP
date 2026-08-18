package io.github.dailystruggle.rtp.common.commands.admin;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.jetbrains.annotations.Nullable;

/**
 * Top-level {@code /rtp admin} command.
 * Bare invocation delegates to injected admin-panel opener (gates on {@code rtp.menu.admin}).
 * Subcommands manage prefabs (gates on {@code rtp.admin.prefab}).
 * Rejects with {@code menuInvalid} when no opener is wired (REQ-RTP-S-004, S-007).
 */
public class AdminCmd extends BaseRTPCmdImpl {

    /** Permission key for the bare {@code /rtp admin} form. Mirrors {@code MenuRedeemSubcommand.ADMIN_MENU_PERMISSION}. */
    public static final String PERMISSION = "rtp.menu.admin";

    private final @Nullable Consumer<UUID> openAdminPanel;

    /**
     * Production constructor with an injected admin-panel opener.
     *
     * @param parent          the parent command tree (the {@code /rtp} root), or {@code null}.
     * @param openAdminPanel  callback invoked from the bare form to open the admin panel
     *                        for the given caller, or {@code null} to disable the bare form.
     */
    public AdminCmd(@Nullable CommandsAPICommand parent,
                    @Nullable Consumer<UUID> openAdminPanel) {
        super(parent);
        this.openAdminPanel = openAdminPanel;
    }

    /**
     * Convenience constructor with no opener; bare form rejects until one is wired.
     *
     * @param parent the parent command tree (the {@code /rtp} root), or {@code null}
     */
    public AdminCmd(@Nullable CommandsAPICommand parent) {
        this(parent, null);
    }

    @Override
    public String name() {
        return "admin";
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public String description() {
        return "open the admin panel; subcommands manage prefabs";
    }

    @Override
    public boolean onCommand(UUID callerId,
                             Map<String, List<String>> parameterValues,
                             @Nullable CommandsAPICommand nextCommand) {
        if (nextCommand != null) {
            return nextCommand.onCommand(callerId, parameterValues, null);
        }
        if (openAdminPanel == null) {
            RTP.log(Level.WARNING,
                    "/rtp admin invoked by " + callerId
                            + " with no admin-panel opener wired; rejecting");
            rejectMenuInvalid(callerId);
            return false;
        }
        try {
            openAdminPanel.accept(callerId);
            return true;
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "/rtp admin opener threw for " + callerId + ": " + e.getMessage(), e);
            rejectMenuInvalid(callerId);
            return false;
        }
    }

    private static void rejectMenuInvalid(UUID callerId) {
        if (callerId == null || RTP.serverAccessor == null) return;
        try {
            RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, CommandMessages.menuInvalid);
        } catch (RuntimeException ignored) {
            // serverAccessor failures (test scaffolds without a sender) are not fatal.
        }
    }
}
