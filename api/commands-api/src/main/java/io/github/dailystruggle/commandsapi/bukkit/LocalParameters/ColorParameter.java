package io.github.dailystruggle.commandsapi.bukkit.LocalParameters;


import io.github.dailystruggle.commandsapi.bukkit.BukkitParameter;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiFunction;

public class ColorParameter extends BukkitParameter {
    Set<String> allValues;
    public ColorParameter(String permission, String description, BiFunction<CommandSender, String, Boolean> isRelevant) {
        super(permission, description,isRelevant);
        allValues = new HashSet<>(Arrays.asList("FFFFFF","000000"));
    }

    @Override
    public Set<String> values() {
        return allValues;
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
