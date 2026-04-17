package io.github.dailystruggle.effectsapi.commands;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.effectsapi.LocalEffects.ParticleEffect;
import io.github.dailystruggle.effectsapi.LocalEffects.enums.ParticleTypeNames;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class ParticleCommand extends GenericEffectCommand<ParticleEffect> {
    public ParticleCommand(Plugin plugin) {
        super(plugin);
    }

    @Override
    public String name() {
        return "particle";
    }

    @Override
    public String permission() {
        return "EffectsAPI.test";
    }

    @Override
    public boolean onCommand(CommandSender sender, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        List<ParticleEffect> effects = new ArrayList<>();
        ParticleEffect mainEffect = new ParticleEffect();
        effects.add(mainEffect);
        mainEffect.setTarget(sender);
        EnumMap<ParticleTypeNames, Object> data = mainEffect.getData();
        for (Map.Entry<String, List<String>> entry : parameterValues.entrySet()) {
            List<String> vals = entry.getValue();
            String name = entry.getKey().toLowerCase();
            ParticleTypeNames enumLookup = ParticleTypeNames.valueOf(name.toUpperCase());
            String value = entry.getValue().get(0);
            data.put(enumLookup, value);
            mainEffect.setData(data);
            while (effects.size() < vals.size()) {
                effects.add(new ParticleEffect());
            }
            for (int i = 1; i < vals.size(); i++) {
                ParticleEffect effect = effects.get(i);
                enumLookup = ParticleTypeNames.valueOf(name.toUpperCase());
                value = entry.getValue().get(i);
                data.put(enumLookup, value);
                effect.setData(data);
            }
        }
        for (ParticleEffect effect : effects) {
            effect.runTask(plugin);
        }
        return true;
    }
}
