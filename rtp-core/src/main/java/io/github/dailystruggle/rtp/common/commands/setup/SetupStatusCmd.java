package io.github.dailystruggle.rtp.common.commands.setup;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code /rtp admin setup status}
 * Prints setup wizard status and progress in CLI text form.
 */
public class SetupStatusCmd extends BaseRTPCmdImpl {

    public static final String PERMISSION = "rtp.admin.setup";

    private final SetupSessionRegistry sessionRegistry;

    public SetupStatusCmd(
            @Nullable CommandsAPICommand parent,
            SetupSessionRegistry sessionRegistry) {
        super(parent);
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
    }

    @Override
    public String name() {
        return "status";
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public String description() {
        return "show current setup wizard choices and status";
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, @Nullable CommandsAPICommand nextCommand) {
        UUID effectiveCaller = (callerId == null) ? SetupSession.CONSOLE_CALLER_ID : callerId;
        SetupSession session = sessionRegistry.getOrCreate(effectiveCaller);
        Map<String, List<io.github.dailystruggle.rtp.common.commands.prefab.PrefabApplier.Change>> diff =
                SetupHandlerSupport.computeDiffPreview(session);
        List<String> lines = SetupConsoleFormatter.formatStatus(session, diff);
        for (String line : lines) {
            SetupHandlerSupport.sendMessage(callerId, line);
        }
        return true;
    }
}
