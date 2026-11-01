package io.github.dailystruggle.rtp.bukkitplatform.commands.parameters;

import io.github.dailystruggle.rtp.bukkitplatform.commands.BukkitParameter;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public class OfflinePlayerParameter extends BukkitParameter {
    public OfflinePlayerParameter(String permission, String description, BiFunction<CommandSender, String, Boolean> isRelevant) {
        super(permission, description, isRelevant);
    }

    @Override
    public Set<String> values() {
        return Arrays.stream(Bukkit.getOfflinePlayers()).map(OfflinePlayer::getName).filter(Objects::nonNull).collect(Collectors.toSet());
    }
}
