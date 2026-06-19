package io.github.dailystruggle.effectsapi.bukkit;

import io.github.dailystruggle.effectsapi.EffectsAPI;

import java.util.function.Consumer;

/**
 * Bukkit/Folia-only entity-thread dispatch seam for potion application.
 *
 * <p>Extracted from {@code io.github.dailystruggle.effectsapi.common.effects.PotionEffect}
 * so the platform-neutral common effect links on non-Bukkit platforms
 * (Fabric/NeoForge) where {@code org.bukkit.*} is absent. The seam previously
 * lived in a static lambda on the common class whose functional-interface
 * descriptor named {@code org.bukkit.entity.Player} / {@code org.bukkit.plugin.Plugin};
 * resolving that descriptor forced eager loading of {@code org.bukkit.entity.Player}
 * the moment the common class was instantiated on Fabric, raising
 * {@link NoClassDefFoundError}.
 *
 * <p>On Folia the {@link #entityDispatcher} routes {@code addPotionEffect} onto
 * the player's EntityScheduler; on Spigot/Paper it falls back to the main-thread
 * Bukkit scheduler when called off the main thread.
 */
public final class BukkitPotionDispatch {

    private BukkitPotionDispatch() {}

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
            caller = EffectsAPI.getInstance();
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
}
