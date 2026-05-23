package io.github.dailystruggle.rtp.common.commands.prefab;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * {@code /rtp admin prefab list} - print every bundled prefab's id, English
 * description, and a usage hint. Permission-gated via the shared
 * {@link PrefabCommand#PERMISSION}.
 *
 * <p>Output is plain text (no localised keys yet - Session 6 wires
 * the {@code MessagesKeys} entries through the locale TSV pipeline). The
 * English description on each row is the {@link Prefab#description()} carried
 * directly by the in-code registry, so this verb stays useful pre-locale.
 */
public class PrefabListCmd extends BaseRTPCmdImpl {

    public PrefabListCmd(@Nullable CommandsAPICommand parent) {
        super(parent);
    }

    @Override
    public String name() {
        return "list";
    }

    @Override
    public String permission() {
        return PrefabCommand.PERMISSION;
    }

    @Override
    public String description() {
        return "list the bundled config prefabs";
    }

    @Override
    public boolean onCommand(UUID callerId,
                             Map<String, List<String>> parameterValues,
                             @Nullable CommandsAPICommand nextCommand) {
        if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);
        send(callerId, "&7Bundled config prefabs:");
        for (Prefab p : PrefabRegistry.list()) {
            send(callerId, "&f  - &a" + p.id() + "&7: " + p.description());
        }
        send(callerId, "&7Use &f/rtp admin prefab apply id=<id>&7 to preview changes.");
        return true;
    }

    private static void send(UUID callerId, String msg) {
        if (callerId == null || RTP.serverAccessor == null) return;
        try {
            RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, msg);
        } catch (RuntimeException ignored) {
            // Test scaffolds without a real sender are not fatal here.
        }
    }
}
