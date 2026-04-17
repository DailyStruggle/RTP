package io.github.dailystruggle.effectsapi.commands;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.effectsapi.LocalEffects.SoundEffect;
import io.github.dailystruggle.effectsapi.LocalEffects.enums.SoundTypeNames;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class SoundCommand extends GenericEffectCommand<SoundEffect> {
    public SoundCommand(Plugin plugin) {
        super(plugin);
    }

    @Override
    public String name() {
        return "sound";
    }

    @Override
    public String permission() {
        return "EffectsAPI.test";
    }

    @Override
    public boolean onCommand(CommandSender sender, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        List<SoundEffect> effects = new ArrayList<>();
        SoundEffect mainEffect = new SoundEffect();
        effects.add(mainEffect);
        mainEffect.setTarget(sender);
        EnumMap<SoundTypeNames, Object> data = mainEffect.getData();
        for (Map.Entry<String, List<String>> entry : parameterValues.entrySet()) {
            List<String> vals = entry.getValue();
            String name = entry.getKey().toLowerCase();
            SoundTypeNames enumLookup = SoundTypeNames.valueOf(name.toUpperCase());
            String value = entry.getValue().get(0);
            data.put(enumLookup, value);
            mainEffect.setData(data);
            while (effects.size() < vals.size()) {
                effects.add(new SoundEffect());
            }
            for (int i = 1; i < vals.size(); i++) {
                SoundEffect effect = effects.get(i);
                enumLookup = SoundTypeNames.valueOf(name.toUpperCase());
                value = entry.getValue().get(i);
                data.put(enumLookup, value);
                effect.setData(data);
            }
        }
        for (SoundEffect effect : effects) {
            effect.runTask(plugin);
        }
        return true;
    }
}
