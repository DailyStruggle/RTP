package io.github.dailystruggle.effectsapi.bukkit.commands;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.effectsapi.common.effects.SoundEffect;
import io.github.dailystruggle.effectsapi.common.EffectFactory;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        SoundEffect mainEffect = (SoundEffect) Objects.requireNonNull(EffectFactory.buildEffect("sound"));
        effects.add(mainEffect);
        mainEffect.setTarget(sender);

        for (Map.Entry<String, List<String>> entry : parameterValues.entrySet()) {
            List<String> vals = entry.getValue();
            String name = entry.getKey().toLowerCase();
            String value = entry.getValue().get(0);
            
            mainEffect.setData(name, value);

            while (effects.size() < vals.size()) {
                effects.add((SoundEffect) Objects.requireNonNull(EffectFactory.buildEffect("sound")));
            }
            for (int i = 1; i < vals.size(); i++) {
                SoundEffect effect = effects.get(i);
                value = entry.getValue().get(i);
                effect.setData(name, value);
            }
        }
        for (SoundEffect effect : effects) {
            io.github.dailystruggle.effectsapi.bukkit.BukkitEffectsInitializer.runEffect(plugin, effect);
        }
        return true;
    }
}
