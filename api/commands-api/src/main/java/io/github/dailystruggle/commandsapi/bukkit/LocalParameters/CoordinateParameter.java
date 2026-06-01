package io.github.dailystruggle.commandsapi.bukkit.LocalParameters;


import io.github.dailystruggle.commandsapi.bukkit.BukkitParameter;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public class CoordinateParameter extends BukkitParameter {
    Set<String> allValues;
    public CoordinateParameter(String permission, String description, BiFunction<CommandSender, String, Boolean> isRelevant) {
        super(permission, description,isRelevant);
        allValues = new HashSet<>(Arrays.asList("~","-~","0"));
    }

    @Override
    public Set<String> relevantValues(UUID senderId) {
        return values().stream().filter(s -> this.isRelevant.apply(senderId,s)).collect(Collectors.toSet());
    }

    @Override
    public Set<String> values() {
        return allValues;
    }
}
