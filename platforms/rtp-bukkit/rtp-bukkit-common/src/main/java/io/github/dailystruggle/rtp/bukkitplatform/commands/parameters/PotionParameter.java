package io.github.dailystruggle.rtp.bukkitplatform.commands.parameters;

import io.github.dailystruggle.rtp.bukkitplatform.commands.BukkitParameter;
import org.bukkit.command.CommandSender;
import org.bukkit.potion.PotionEffectType;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public class PotionParameter extends BukkitParameter {
    public PotionParameter(String permission, String description, BiFunction<CommandSender, String, Boolean> isRelevant) {
        super(permission, description, isRelevant);
    }

    @Override
    public Set<String> values() {
        return Arrays.stream(PotionEffectType.values()).filter(Objects::nonNull).map(PotionEffectType::getName).collect(Collectors.toSet());
    }
}
