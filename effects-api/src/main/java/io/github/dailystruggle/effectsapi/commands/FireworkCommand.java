package io.github.dailystruggle.effectsapi.commands;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.effectsapi.LocalEffects.FireworkEffect;
import io.github.dailystruggle.effectsapi.LocalEffects.enums.FireworkTypeNames;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class FireworkCommand extends GenericEffectCommand<FireworkEffect> {
    public FireworkCommand(Plugin plugin) {
        super(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender,
                             Map<String, List<String>> parameterValues,
                             CommandsAPICommand nextCommand) {
        if (!(sender instanceof Player)) return false;
        List<FireworkEffect> effects = new ArrayList<>();
        FireworkEffect mainEffect = new FireworkEffect();
        effects.add(mainEffect);
        mainEffect.setTarget(sender);
        EnumMap<FireworkTypeNames, Object> data = mainEffect.getData();
        for (Map.Entry<String, List<String>> entry : parameterValues.entrySet()) {
            List<String> vals = entry.getValue();
            String name = entry.getKey().toLowerCase();
            FireworkTypeNames enumLookup = FireworkTypeNames.valueOf(name.toUpperCase());
            String value = entry.getValue().get(0);
            data.put(enumLookup, value);
            mainEffect.setData(data);
            while (effects.size() < vals.size()) {
                effects.add(new FireworkEffect());
            }
            for (int i = 1; i < vals.size(); i++) {
                FireworkEffect effect = effects.get(i);
                enumLookup = FireworkTypeNames.valueOf(name.toUpperCase());
                value = entry.getValue().get(i);
                data.put(enumLookup, value);
                effect.setData(data);
            }
        }

        for (FireworkEffect effect : effects) {
            effect.runTask(plugin);
        }
        return true;
    }

    @Override
    public String name() {
        return "firework";
    }

    @Override
    public String permission() {
        return "effectsapi.test";
    }
}
