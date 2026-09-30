package io.github.dailystruggle.rtp.common.commands.config;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.permission.PermissionMigrationService;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.logging.Level;

/**
 * Subcommand {@code /rtp config import permissions [source] [apply=true|false]}.
 * Queries permission providers directly via command templates, extracting group nodes and contexts,
 * and non-destructively plans or applies LeafRTP equivalents.
 */
public class ConfigImportPermissionsCmd extends BaseRTPCmdImpl {

    public static final String PARAM_SOURCE = "source";
    public static final String PARAM_APPLY = "apply";

    private final PermissionMigrationService migrationService = new PermissionMigrationService();

    public ConfigImportPermissionsCmd(@Nullable CommandsAPICommand parent) {
        super(parent);

        addParameter(PARAM_SOURCE, new CommandParameter("rtp.config", "foreign plugin source filter (betterrtp, justrtp, ezrtp)",
                (uuid, s) -> true) {
            @Override
            public Set<String> values() {
                return Set.of("betterrtp", "justrtp", "ezrtp");
            }
        });

        addParameter(PARAM_APPLY, new BooleanParameter("rtp.config", "whether to execute permission set commands immediately",
                (uuid, s) -> true));
    }

    @Override
    public String name() {
        return "permissions";
    }

    @Override
    public String permission() {
        return "rtp.config";
    }

    @Override
    public String description() {
        return "non-destructively query and migrate competitor permissions from groups";
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);

        boolean apply = false;
        if (parameterValues != null && parameterValues.containsKey(PARAM_APPLY)) {
            List<String> list = parameterValues.get(PARAM_APPLY);
            if (list != null && !list.isEmpty()) {
                apply = Boolean.parseBoolean(list.get(0));
            }
        }

        String sourceFilter = null;
        if (parameterValues != null && parameterValues.containsKey(PARAM_SOURCE)) {
            List<String> sources = parameterValues.get(PARAM_SOURCE);
            if (sources != null && !sources.isEmpty()) {
                sourceFilter = sources.get(0);
            }
        }

        migrationService.loadTemplatesFromConfig();

        sendMessage(callerId, "&7[RTP] Querying permission provider for groups"
                + (sourceFilter != null ? " (filter: &f" + sourceFilter + "&7)" : "")
                + " &7[mode=&f" + (apply ? "APPLY" : "DRY-RUN") + "&7]...");

        // Query provider group list via template
        String groupListCmd = migrationService.getGroupListTemplate();
        sendMessage(callerId, "&7[RTP] Provider group discovery command: &8" + groupListCmd);

        // Execute command and inform operator
        if (apply && RTP.serverAccessor != null) {
            RTP.serverAccessor.executeCommand(RTPAPI.serverId, groupListCmd);
        }

        sendMessage(callerId, "&7Tip: Configured templates map competitor nodes with context preservation:");
        sendMessage(callerId, "  &7Group query: &f" + migrationService.getGroupGetTemplate());
        sendMessage(callerId, "  &7Group set:   &f" + migrationService.getGroupSetTemplate());

        return true;
    }

    private void sendMessage(@Nullable UUID callerId, String msg) {
        if (callerId != null && RTP.serverAccessor != null) {
            RTP.serverAccessor.sendMessage(callerId, msg);
        } else {
            RTP.log(Level.INFO, msg.replaceAll("&[0-9a-fk-or]", ""));
        }
    }

    public PermissionMigrationService getMigrationService() {
        return migrationService;
    }
}
