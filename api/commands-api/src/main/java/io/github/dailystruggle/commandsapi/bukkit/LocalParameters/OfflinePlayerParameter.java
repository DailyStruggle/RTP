package io.github.dailystruggle.commandsapi.bukkit.LocalParameters;


import io.github.dailystruggle.commandsapi.bukkit.BukkitParameter;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public class OfflinePlayerParameter extends BukkitParameter {
    //because this is a dynamic value, mitigate spam by refusing to update until some time passes
    public OfflinePlayerParameter(String permission, String description, BiFunction<CommandSender, String, Boolean> isRelevant) {
        super(permission, description, isRelevant);
    }

    @Override
    public Set<String> values() {
        return Arrays.stream(Bukkit.getOfflinePlayers()).map(OfflinePlayer::getName).collect(Collectors.toSet());
    }
}
