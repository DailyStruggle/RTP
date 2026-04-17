package io.github.dailystruggle.effectsapi.commands;

import io.github.dailystruggle.commandsapi.bukkit.localCommands.BukkitTreeCommand;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.effectsapi.EffectsAPI;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class EffectsAPIMainCommand extends BukkitTreeCommand {
    public EffectsAPIMainCommand(Plugin plugin) {
        super(EffectsAPI.getInstance(),null);
        addSubCommand(new TestCommand(plugin, this));
    }

    @Override
    public boolean onCommand(CommandSender sender, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        return true;
    }

    @Override
    public String name() {
        return "EffectsAPI";
    }

    @Override
    public String permission() {
        return "effectsapi.see";
    }

    @Override
    public String description() {
        return "generate effects by command";
    }

    @Override
    public void msgBadParameter(UUID callerId, String parameterName, String parameterValue) {

    }
}
