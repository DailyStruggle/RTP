package io.github.dailystruggle.rtp.bukkit.commands.commands.fill.subcommands;

import io.github.dailystruggle.commandsapi.bukkit.localCommands.BukkitTreeCommand;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;

public class Start extends BukkitTreeCommand {
    public Start(Plugin plugin, CommandsAPICommand parent) {
        super(plugin, parent);
    }

    @Override
    public boolean onCommand(CommandSender sender, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
//        FillCmd.apply(sender,parameterValues, MemoryRegion::isFilling, "fillRunning", MemoryRegion::startFill);
        return true;
    }

    @Override
    public String name() {
        return "start";
    }

    @Override
    public String permission() {
        return "rtp.fill";
    }

    @Override
    public String description() {
        return null;
    }
}
