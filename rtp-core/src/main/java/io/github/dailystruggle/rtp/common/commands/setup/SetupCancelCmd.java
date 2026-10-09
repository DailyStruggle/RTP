package io.github.dailystruggle.rtp.common.commands.setup;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

/**
 * {@code /rtp admin setup cancel}
 * Discards the current setup session and aborts wizard flow.
 */
public class SetupCancelCmd extends BaseRTPCmdImpl {

    public static final String CMD_PERMISSION = "rtp.admin.setup";

    private final SetupSessionRegistry sessionRegistry;

    public SetupCancelCmd(
            @Nullable CommandsAPICommand parent,
            SetupSessionRegistry sessionRegistry) {
        super(parent);
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
    }

    @Override
    public String name() {
        return "cancel";
    }

    @Override
    public String permission() {
        return CMD_PERMISSION;
    }

    @Override
    public String description() {
        return "cancel setup wizard and discard draft";
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, @Nullable CommandsAPICommand nextCommand) {
        UUID effectiveCaller = (callerId == null) ? SetupSession.CONSOLE_CALLER_ID : callerId;
        var removed = sessionRegistry.remove(effectiveCaller);
        if (removed.isPresent()) {
            RTP.log(Level.INFO, "[setup] caller=" + callerId + " cancelled setup wizard session");
            SetupHandlerSupport.sendMessage(callerId, "&c[RTP Setup] Setup wizard cancelled. No configuration changes were saved.");
        } else {
            SetupHandlerSupport.sendMessage(callerId, "&7[RTP Setup] No active setup session found to cancel.");
        }
        return true;
    }
}
