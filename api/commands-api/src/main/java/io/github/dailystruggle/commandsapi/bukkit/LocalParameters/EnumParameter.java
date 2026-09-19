package io.github.dailystruggle.commandsapi.bukkit.LocalParameters;

import io.github.dailystruggle.commandsapi.bukkit.BukkitParameter;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public class EnumParameter<T extends Enum<T>> extends BukkitParameter {
    Set<String> allValues;
    public EnumParameter(String permission, String description, BiFunction<CommandSender, String, Boolean> isRelevant, Class<T> enumClass) {
        super(permission, description,isRelevant);
        allValues = Arrays.stream(enumClass.getEnumConstants()).map(Enum::name).collect(Collectors.toSet());
    }

    @Override
    public Set<String> values() {
        return allValues;
    }
}
