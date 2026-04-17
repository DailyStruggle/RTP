package io.github.dailystruggle.effectsapi.commands;

import io.github.dailystruggle.commandsapi.bukkit.localCommands.BukkitTreeCommand;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.effectsapi.EffectsAPI;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TestCommand extends BukkitTreeCommand {
    public TestCommand(Plugin plugin, CommandsAPICommand parent) {
        super(plugin, parent);
        addSubCommand(new FireworkCommand(plugin));
        addSubCommand(new NoteCommand(plugin));
        if(EffectsAPI.getServerIntVersion()>8) addSubCommand(new ParticleCommand(plugin));
        addSubCommand(new PotionCommand(plugin));
        addSubCommand(new SoundCommand(plugin));
    }

    @Override
    public String name() {
        return "Test";
    }

    @Override
    public String permission() {
        return "EffectsAPI.test";
    }

    @Override
    public String description() {
        return "";
    }

    @Override
    public void msgBadParameter(UUID callerId, String parameterName, String parameterValue) {

    }

    @Override
    public boolean onCommand(CommandSender sender, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        return true;
    }
}
