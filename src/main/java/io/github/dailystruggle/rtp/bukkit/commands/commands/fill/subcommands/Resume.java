package io.github.dailystruggle.rtp.bukkit.commands.commands.fill.subcommands;

import io.github.dailystruggle.commandsapi.bukkit.localCommands.BukkitTreeCommand;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.*;

public class Resume extends BukkitTreeCommand {
    public Resume(Plugin plugin, CommandsAPICommand parent) {
        super(plugin, parent);
    }

    @Override
    public boolean onCommand(CommandSender sender, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
//        FillCmd.apply(sender,parameterValues, MemoryRegion::isFilling, "fillRunning", MemoryRegion::resumeFill);
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
