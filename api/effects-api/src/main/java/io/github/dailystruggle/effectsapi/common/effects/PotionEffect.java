package io.github.dailystruggle.effectsapi.common.effects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.common.spi.HandleRegistry;
import io.github.dailystruggle.effectsapi.common.spi.PlayerHandle;

import java.util.EnumMap;
import java.util.function.Consumer;

/**
 * Common potion effect implementation.
 */
public class PotionEffect extends Effect<PotionEffect.PotionKeys> {
    public enum PotionKeys { TYPE, DURATION, AMPLIFIER, AMBIENT, PARTICLES, ICON }

    @FunctionalInterface
    public interface EntityDispatcher {
        boolean dispatch(org.bukkit.entity.Player player, org.bukkit.plugin.Plugin caller, Runnable task);
    }

    public static volatile EntityDispatcher entityDispatcher = (player, caller, task) -> {
        if (!isFolia()) return false;
        try {
            Object scheduler = player.getClass().getMethod("getScheduler").invoke(player);
            scheduler.getClass()
                    .getMethod("run", org.bukkit.plugin.Plugin.class, Consumer.class, Runnable.class)
                    .invoke(scheduler, caller, null, task);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    };

    private static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregionscheduling.RegionScheduler");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static void applyOnEntityThread(org.bukkit.entity.Player player, org.bukkit.potion.PotionEffect pe) {
        org.bukkit.plugin.Plugin caller;
        try {
            caller = io.github.dailystruggle.effectsapi.EffectsAPI.getInstance();
        } catch (IllegalStateException pre) {
            caller = null;
        }

        if (entityDispatcher.dispatch(player, caller, () -> player.addPotionEffect(pe))) {
            return;
        }

        if (!org.bukkit.Bukkit.isPrimaryThread()) {
            org.bukkit.Bukkit.getScheduler().runTask(caller, () -> player.addPotionEffect(pe));
            return;
        }
        player.addPotionEffect(pe);
    }

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
