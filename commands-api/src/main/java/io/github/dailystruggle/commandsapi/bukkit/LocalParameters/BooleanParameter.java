package io.github.dailystruggle.commandsapi.bukkit.LocalParameters;


import io.github.dailystruggle.commandsapi.bukkit.BukkitParameter;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiFunction;

public class BooleanParameter extends BukkitParameter {
    Set<String> allValues;
    public BooleanParameter(String permission, String description, BiFunction<CommandSender, String, Boolean> isRelevant) {
        super(permission, description,isRelevant);
        allValues = new HashSet<>(Arrays.asList("true","false"));
    }

    @Override
    public Set<String> values() {
        return allValues;
    }
}
