package io.github.dailystruggle.rtp.bukkit.commands.fill.subcommands;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.bukkit.commands.BukkitBaseRTPCmd;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;

public class Resume extends BukkitBaseRTPCmd {
    public Resume(Plugin plugin, CommandsAPICommand parent) {
        super(plugin, parent);
    }

    @Override
    public boolean onCommand(CommandSender sender, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
//        FillCmdBukkit.apply(sender,parameterValues, MemoryRegion::isFilling, "fillRunning", MemoryRegion::resumeFill);
        return true;
    }

    @Override
    public String name() {
        return null;
    }

    @Override
    public String permission() {
        return null;
    }

    @Override
    public String description() {
        return null;
    }
}
