package io.github.dailystruggle.rtp.bukkitplatform.commands.parameters;

import io.github.dailystruggle.rtp.bukkitplatform.commands.BukkitParameter;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public class OnlinePlayerParameter extends BukkitParameter {
    public OnlinePlayerParameter(String permission, String description, BiFunction<CommandSender, String, Boolean> isRelevant) {
        super(permission, description, isRelevant);
    }

    @Override
    public Set<String> values() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toSet());
    }
}
