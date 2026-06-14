package io.github.dailystruggle.effectsapi.bukkit.commands;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.effectsapi.common.effects.PotionEffect;
import io.github.dailystruggle.effectsapi.common.EffectFactory;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class PotionCommand extends GenericEffectCommand<PotionEffect> {
    public PotionCommand(Plugin plugin) {
        super(plugin);
    }

    @Override
    public String name() {
        return "potion";
    }

    @Override
    public String permission() {
        return "EffectsAPI.test";
    }

    @Override
    public boolean onCommand(CommandSender sender, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        PotionEffect mainEffect = (PotionEffect) Objects.requireNonNull(EffectFactory.buildEffect("potion"));
        List<PotionEffect> effects = new ArrayList<>();
        effects.add(mainEffect);
        mainEffect.setTarget(sender);

        for (Map.Entry<String, List<String>> entry : parameterValues.entrySet()) {
            String name = entry.getKey().toLowerCase();
            List<String> vals = entry.getValue();

            String value = entry.getValue().get(0);
            mainEffect.setData(name, value);

            while (effects.size() < vals.size()) {
                effects.add((PotionEffect) Objects.requireNonNull(EffectFactory.buildEffect("potion")));
            }
            for (int i = 1; i < vals.size(); i++) {
                PotionEffect effect = effects.get(i);
                value = entry.getValue().get(i);
                effect.setData(name, value);
            }
        }

        for (PotionEffect effect : effects) {
            io.github.dailystruggle.effectsapi.bukkit.BukkitEffectsInitializer.runEffect(plugin, effect);
        }
        return true;
    }
}
