package io.github.dailystruggle.commandsapi.bukkit;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.UUID;
import java.util.function.BiFunction;

public abstract class BukkitParameter extends CommandParameter {
    public BukkitParameter(String permission, String description, BiFunction<CommandSender, String, Boolean> isRelevant) {
        super(permission, description, (UUID callerId, String s) -> {
            CommandSender commandSender;
            if(callerId.equals(CommandsAPI.serverId)) {
                commandSender = Bukkit.getConsoleSender();
            }
            else {
                commandSender = Bukkit.getPlayer(callerId);
                if(commandSender == null) return false;
            }
            return isRelevant.apply(commandSender,s);
        });
    }
}
