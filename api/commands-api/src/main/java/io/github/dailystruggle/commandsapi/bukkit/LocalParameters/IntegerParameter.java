package io.github.dailystruggle.commandsapi.bukkit.LocalParameters;

import io.github.dailystruggle.commandsapi.bukkit.BukkitParameter;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.Vector;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public class IntegerParameter extends BukkitParameter {
    Set<String> allValues;
    public IntegerParameter(String permission, String description, BiFunction<CommandSender, String, Boolean> isRelevant, Vector<Integer> range) {
        super(permission, description,isRelevant);
        allValues = new HashSet<>();
        int maxSteps = 20;
        double span = range.lastElement() - range.firstElement();
        double step = span/maxSteps;
        for(double i = range.firstElement(); i < range.lastElement(); i = (step>1) ? i+1 : i+step) {
            allValues.add(String.valueOf((int)i));
        }
    }

    public IntegerParameter(String permission, String description, BiFunction<CommandSender, String, Boolean> isRelevant, Object... options) {
        super(permission, description, isRelevant);
        allValues = Arrays.stream(options).map(String::valueOf).collect(Collectors.toSet());
    }

    @Override
    public Set<String> values() {
        return allValues;
    }
}
