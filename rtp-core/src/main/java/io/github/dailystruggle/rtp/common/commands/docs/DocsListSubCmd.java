package io.github.dailystruggle.rtp.common.commands.docs;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Subcommand {@code /rtp docs list} (ADR-045, ADR-104).
 * Lists all cached in-game documentation topics.
 */
public class DocsListSubCmd extends BaseRTPCmdImpl {

    public static final String PERMISSION = "rtp.admin.docs";

    public DocsListSubCmd(@Nullable CommandsAPICommand parent) {
        super(parent);
    }

    @Override
    public String name() {
        return "list";
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public String description() {
        return "list all available in-game documentation topics";
    }

    @Override
    public boolean onCommand(
            UUID callerId,
            Map<String, List<String>> parameterValues,
            @Nullable CommandsAPICommand nextCommand
    ) {
        if (nextCommand != null) {
            return true;
        }

        TreeSet<String> topics;
        try {
            topics = new TreeSet<>(DocsRegistry.getInstance().getAll().keySet());
        } catch (IllegalStateException e) {
            // S-006 fail-closed
            sendMessage(callerId, CommandMessages.docsEmpty, "&c[RTP] Documentation registry is not initialized yet.");
            return true;
        }

        if (topics.isEmpty()) {
            sendMessage(callerId, CommandMessages.docsEmpty, "&c[RTP] No documentation topics are currently registered.");
            return true;
        }

        sendMessage(callerId, null, "&8» &b&lAvailable Documentation Topics (&f" + topics.size() + "&b):");
        for (String topic : topics) {
            sendMessage(callerId, null, "  &7- &f" + topic + " &8(&b/rtp docs " + topic + "&8)");
        }
        return true;
    }

    private void sendMessage(@Nullable UUID callerId, @Nullable CommandMessages key, String fallback) {
        if (callerId == null || RTP.serverAccessor == null) return;
        try {
            if (key != null) {
                RTP.serverAccessor.sendMessage(callerId, key);
            } else {
                RTP.serverAccessor.sendMessage(callerId, fallback);
            }
        } catch (RuntimeException ignored) {
        }
    }
}
