package io.github.dailystruggle.effectsapi.commands;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.effectsapi.LocalEffects.NoteEffect;
import io.github.dailystruggle.effectsapi.LocalEffects.enums.NoteTypeNames;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class NoteCommand extends GenericEffectCommand<NoteEffect> {
    public NoteCommand(Plugin plugin) {
        super(plugin);
    }

    @Override
    public String name() {
        return "note";
    }

    @Override
    public String permission() {
        return "EffectsAPI.test";
    }

    @Override
    public boolean onCommand(CommandSender sender, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        List<NoteEffect> effects = new ArrayList<>();
        NoteEffect mainEffect = new NoteEffect();
        effects.add(mainEffect);
        mainEffect.setTarget(sender);
        EnumMap<NoteTypeNames, Object> data = mainEffect.getData();
        for (Map.Entry<String, List<String>> entry : parameterValues.entrySet()) {
            List<String> vals = entry.getValue();
            String name = entry.getKey().toLowerCase();
            NoteTypeNames enumLookup = NoteTypeNames.valueOf(name.toUpperCase());
            String value = entry.getValue().get(0);
            data.put(enumLookup, value);
            mainEffect.setData(data);
            while (effects.size() < vals.size()) {
                effects.add(new NoteEffect());
            }
            for (int i = 1; i < vals.size(); i++) {
                NoteEffect effect = effects.get(i);
                enumLookup = NoteTypeNames.valueOf(name.toUpperCase());
                value = entry.getValue().get(i);
                data.put(enumLookup, value);
                effect.setData(data);
            }
        }
        for (NoteEffect effect : effects) {
            effect.runTask(plugin);
        }
        return true;
    }
}
