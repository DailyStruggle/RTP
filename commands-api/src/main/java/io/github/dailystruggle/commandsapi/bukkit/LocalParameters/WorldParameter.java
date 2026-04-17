package io.github.dailystruggle.commandsapi.bukkit.LocalParameters;


import io.github.dailystruggle.commandsapi.bukkit.BukkitParameter;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public class WorldParameter extends BukkitParameter {
    public WorldParameter(String permission, String description, BiFunction<CommandSender, String, Boolean> isRelevant) {
        super(permission, description, isRelevant);
    }

    @Override
    public Set<String> values() {
        return Bukkit.getWorlds().stream().map(World::getName).collect(Collectors.toSet());
    }
}
