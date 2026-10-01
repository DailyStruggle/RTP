package io.github.dailystruggle.rtp.common.commands.prefab;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * {@code /rtp admin prefab list} - print bundled prefab IDs, descriptions, and usage hints.
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
        PrefabDiskIO.send(callerId, msg);
    }
}
