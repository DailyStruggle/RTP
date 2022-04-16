package leafcraft.rtp.bukkit.commands.commands.configure.region;

import io.github.dailystruggle.commandsapi.bukkit.localCommands.BukkitTreeCommand;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.*;

public class ConfigureRegionCmd extends BukkitTreeCommand {

    public ConfigureRegionCmd(Plugin plugin, CommandsAPICommand parent) {
        super(plugin,parent);
    }

    @Override
    public boolean onCommand(CommandSender sender, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        return false;
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