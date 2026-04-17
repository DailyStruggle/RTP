package io.github.dailystruggle.effectsapi.commands;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.effectsapi.LocalEffects.PotionEffect;
import io.github.dailystruggle.effectsapi.LocalEffects.enums.PotionTypeNames;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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
        PotionEffect mainEffect = new PotionEffect();
        List<PotionEffect> effects = new ArrayList<>();
        effects.add(mainEffect);
        mainEffect.setTarget(sender);
        EnumMap<PotionTypeNames, Object> data = mainEffect.getData();
        int longest = 1;
        for (Map.Entry<String, List<String>> entry : parameterValues.entrySet()) {
            String name = entry.getKey().toLowerCase();
            List<String> vals = entry.getValue();

            PotionTypeNames enumLookup = PotionTypeNames.valueOf(name.toUpperCase());
            String value = entry.getValue().get(0);
            data.put(enumLookup, value);

            //if there are more values to process later, add the copies
            longest = Math.max(longest, vals.size());
            while (effects.size() < vals.size()) {
                effects.add(new PotionEffect());
            }
            for (int i = 1; i < vals.size(); i++) {
                PotionEffect effect = effects.get(i);
                enumLookup = PotionTypeNames.valueOf(name.toUpperCase());
                value = entry.getValue().get(i);
                data.put(enumLookup, value);
                effect.setData(data);
            }
        }
        mainEffect.setData(data);

        for (PotionEffect effect : effects) {
            effect.runTask(plugin);
        }
        return true;
    }
}
