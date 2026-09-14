package io.github.dailystruggle.rtp.bukkitplatform.commands.parameters;

import io.github.dailystruggle.rtp.bukkitplatform.commands.BukkitParameter;
import org.bukkit.Color;
import org.bukkit.command.CommandSender;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiFunction;

public class ColorParameter extends BukkitParameter {
    private static final Set<String> values = new HashSet<>();

    static {
        for (Field field : Color.class.getFields()) {
            if(field.getType().equals(Color.class)) {
                values.add(field.getName());
            }
        }
    }

    public ColorParameter(String permission, String description, BiFunction<CommandSender, String, Boolean> isRelevant) {
        super(permission, description, isRelevant);
    }

    @Override
    public Set<String> values() {
        return values;
    }
}
