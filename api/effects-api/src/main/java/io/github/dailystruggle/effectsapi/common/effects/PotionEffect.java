package io.github.dailystruggle.effectsapi.common.effects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.common.spi.HandleRegistry;
import io.github.dailystruggle.effectsapi.common.spi.PlayerHandle;

import java.util.EnumMap;

/**
 * Common potion effect implementation.
 *
 * <p>Platform-neutral: potion application is funneled through
 * {@link PlayerHandle#applyPotionEffect} so this class links on every platform
 * (Bukkit/Paper/Folia, Fabric, NeoForge). The Bukkit/Folia-specific
 * entity-thread dispatch seam previously embedded here lived in a static lambda
 * whose functional-interface descriptor named {@code org.bukkit.*} types; that
 * forced eager linking of {@code org.bukkit.entity.Player} when this class was
 * merely instantiated on Fabric (via {@code FabricEffectsInitializer}), raising
 * {@link NoClassDefFoundError}. That seam now lives in
 * {@code io.github.dailystruggle.effectsapi.bukkit.BukkitPotionDispatch}.
 */
public class PotionEffect extends Effect<PotionEffect.PotionKeys> {
    public enum PotionKeys { TYPE, DURATION, AMPLIFIER, AMBIENT, PARTICLES, ICON }

    public PotionEffect(Object defaultPotion) {
        super(new EnumMap<>(PotionKeys.class));
        data.put(PotionKeys.TYPE, defaultPotion);
        data.put(PotionKeys.DURATION, 1);
        data.put(PotionKeys.AMPLIFIER, 1);
        data.put(PotionKeys.AMBIENT, false);
        data.put(PotionKeys.PARTICLES, true);
        data.put(PotionKeys.ICON, true);
        this.defaults = data.clone();
    }

    @Override
    public void run() {
        int duration = 0, amplifier = 0;
        boolean ambient = false, particles = true, icon = true;

        Object o = data.get(PotionKeys.DURATION);
        if (o instanceof Number) duration = ((Number) o).intValue();
        o = data.get(PotionKeys.AMPLIFIER);
        if (o instanceof Number) amplifier = ((Number) o).intValue();
        o = data.get(PotionKeys.AMBIENT);
        if (o instanceof Boolean) ambient = (Boolean) o;
        o = data.get(PotionKeys.PARTICLES);
        if (o instanceof Boolean) particles = (Boolean) o;
        o = data.get(PotionKeys.ICON);
        if (o instanceof Boolean) icon = (Boolean) o;

        Object type = data.get(PotionKeys.TYPE);
        PlayerHandle ph = HandleRegistry.wrapPlayer(target);
        if (ph != null) {
            ph.applyPotionEffect(type, duration, amplifier, ambient, particles, icon);
        }
    }

    @Override
    public void setData(String... data) {
        applyByType(PotionKeys.values(), data);
    }

    @Override
    public String toPermission() {
        return data.get(PotionKeys.TYPE).toString().replaceAll("\\.*", "") +
               data.get(PotionKeys.DURATION).toString().replaceAll("\\.*", "") +
               data.get(PotionKeys.AMPLIFIER).toString().replaceAll("\\.*", "");
    }
}
