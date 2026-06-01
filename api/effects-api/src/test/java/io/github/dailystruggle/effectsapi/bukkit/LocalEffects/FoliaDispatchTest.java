package io.github.dailystruggle.effectsapi.bukkit.LocalEffects;

import io.github.dailystruggle.effectsapi.EffectsAPI;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies that the Folia-aware dispatch paths in
 * {@link PotionEffect#applyOnEntityThread} and {@link FireworkEffect#run}
 * actually call into the pluggable scheduler hooks
 * ({@link PotionEffect#entityDispatcher} / {@link FireworkEffect#regionDispatcher}).
 *
 * <p>The reason these tests exist is concrete: on Folia the firework was
 * never spawning and the potion effect was throwing
 * {@code IllegalStateException: Asynchronous effect add} because the Bukkit
 * scheduler / direct mutation paths were taken instead of the Folia
 * EntityScheduler / RegionScheduler. These tests pin the production code to
 * the dispatcher seam, so a regression to "just call addPotionEffect"
 * would fail here.
 */
class FoliaDispatchTest {

    private PotionEffect.EntityDispatcher savedEntityDispatcher;
    private FireworkEffect.RegionDispatcher savedRegionDispatcher;
    private Plugin savedEffectsApiInstance;

    @BeforeEach
    void saveDefaults() {
        savedEntityDispatcher = PotionEffect.entityDispatcher;
        savedRegionDispatcher = FireworkEffect.regionDispatcher;
        // EffectsAPI.getInstance() now throws IllegalStateException (S-006)
        // when uninitialized. The production code under test calls it before
        // consulting the dispatcher seam, so seed a mock Plugin for the test
        // lifecycle and restore it in @AfterEach.
        savedEffectsApiInstance = readEffectsApiInstance();
        // Don't call EffectsAPI.init() — it registers Bukkit listeners which
        // NPE without an initialized server. Just seed the static field.
        writeEffectsApiInstance(mock(Plugin.class));
    }

    @AfterEach
    void restoreDefaults() {
        PotionEffect.entityDispatcher = savedEntityDispatcher;
        FireworkEffect.regionDispatcher = savedRegionDispatcher;
        writeEffectsApiInstance(savedEffectsApiInstance);
    }

    private static Plugin readEffectsApiInstance() {
        try {
            Field f = EffectsAPI.class.getDeclaredField("instance");
            f.setAccessible(true);
            return (Plugin) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void writeEffectsApiInstance(Plugin value) {
        try {
            Field f = EffectsAPI.class.getDeclaredField("instance");
            f.setAccessible(true);
            f.set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("PotionEffect routes addPotionEffect through the entity dispatcher (Folia path)")
    void potionEffectUsesEntityDispatcher() {
        AtomicBoolean dispatched = new AtomicBoolean(false);
        AtomicReference<Player> seenPlayer = new AtomicReference<>();
        AtomicReference<Runnable> seenTask = new AtomicReference<>();

        PotionEffect.entityDispatcher = (player, caller, task) -> {
            dispatched.set(true);
            seenPlayer.set(player);
            seenTask.set(task);
            // Simulate Folia EntityScheduler executing the consumer.
            task.run();
            return true;
        };

        Player player = mock(Player.class);
        org.bukkit.potion.PotionEffect pe =
                new org.bukkit.potion.PotionEffect(PotionEffectType.BLINDNESS, 20, 0, false, true, true);

        PotionEffect.applyOnEntityThread(player, pe);

        assertTrue(dispatched.get(),
                "PotionEffect.applyOnEntityThread must go through the EntityDispatcher seam on Folia");
        assertSame(player, seenPlayer.get(), "Dispatcher must receive the same player");
        assertNotNull(seenTask.get(), "Dispatcher must receive a non-null task");
        // The task supplied to the dispatcher must be the one that calls
        // addPotionEffect on the player — verify by running it and checking
        // the mock interaction.
        verify(player, times(1)).addPotionEffect(pe);
    }

    @Test
    @DisplayName("PotionEffect falls back to legacy path when dispatcher returns false")
    void potionEffectFallsBackWhenDispatcherDeclines() {
        AtomicBoolean dispatcherCalled = new AtomicBoolean(false);
        PotionEffect.entityDispatcher = (player, caller, task) -> {
            dispatcherCalled.set(true);
            return false; // not Folia
        };

        Player player = mock(Player.class);
        org.bukkit.potion.PotionEffect pe =
                new org.bukkit.potion.PotionEffect(PotionEffectType.BLINDNESS, 20, 0, false, true, true);

        // Bukkit static methods are not stubbable without a server; we exercise
        // only the dispatcher branch and accept that the fallback path may
        // throw downstream. The point of this test is that the dispatcher is
        // *consulted* before any other path is taken.
        try {
            PotionEffect.applyOnEntityThread(player, pe);
        } catch (Throwable ignored) {
            // Bukkit.isPrimaryThread() may throw without an initialized server;
            // that's fine — we only care that the dispatcher was queried first.
        }

        assertTrue(dispatcherCalled.get(),
                "Dispatcher seam must be consulted before any fallback path");
    }

    @Test
    @DisplayName("FireworkEffect.run routes spawn through the region dispatcher (Folia path)")
    void fireworkEffectUsesRegionDispatcher() {
        AtomicBoolean dispatched = new AtomicBoolean(false);
        AtomicReference<Location> seenLocation = new AtomicReference<>();

        // World mock not needed: we short-circuit by NOT executing the task,
        // so spawnFirework is never reached.
        Location loc = new Location(null, 0, 64, 0);

        FireworkEffect.regionDispatcher = (caller, location, task) -> {
            dispatched.set(true);
            seenLocation.set(location);
            // Simulate Folia accepting the task — but do not execute it, since
            // the Bukkit world / FireworkSafetyListener wiring isn't available
            // in unit tests. The contract under test is "the dispatcher is the
            // chosen path", not "the firework actually spawns".
            return true;
        };

        FireworkEffect effect = new FireworkEffect();
        effect.setTarget(loc);
        effect.run();

        assertTrue(dispatched.get(),
                "FireworkEffect.run must go through the RegionDispatcher seam on Folia");
        assertSame(loc, seenLocation.get(), "Dispatcher must receive the spawn location");
    }

    @Test
    @DisplayName("FireworkEffect dispatcher receives the entity location when target is an Entity")
    void fireworkEffectUnwrapsEntityTarget() {
        AtomicReference<Location> seenLocation = new AtomicReference<>();

        Location entityLoc = new Location(null, 5, 70, 5);
        org.bukkit.entity.Entity entity = mock(org.bukkit.entity.Entity.class);
        when(entity.getLocation()).thenReturn(entityLoc);

        FireworkEffect.regionDispatcher = (caller, location, task) -> {
            seenLocation.set(location);
            return true;
        };

        FireworkEffect effect = new FireworkEffect();
        effect.setTarget(entity);
        effect.run();

        assertSame(entityLoc, seenLocation.get(),
                "Entity target must be unwrapped to its Location before dispatch");
    }
}
