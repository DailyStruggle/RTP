package io.github.dailystruggle.effectsapi;

import io.github.dailystruggle.effectsapi.common.effects.DeathEffect;
import io.github.dailystruggle.effectsapi.common.effects.DropExpEffect;
import io.github.dailystruggle.effectsapi.common.effects.DropInventoryEffect;
import io.github.dailystruggle.effectsapi.common.spi.EffectTarget;
import io.github.dailystruggle.effectsapi.common.spi.LocationHandle;
import io.github.dailystruggle.effectsapi.common.spi.PlayerHandle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class DropInventoryEffectTest {

    private static final AtomicBoolean inventoryDropped = new AtomicBoolean(false);
    private static final AtomicReference<LocationHandle> invDropLoc = new AtomicReference<>();
    private static final AtomicBoolean expDropped = new AtomicBoolean(false);
    private static final AtomicReference<LocationHandle> expDropLoc = new AtomicReference<>();
    private static final AtomicBoolean playerKilled = new AtomicBoolean(false);
    private static final AtomicBoolean setBedFlag = new AtomicBoolean(false);
    private static final AtomicReference<LocationHandle> deathRespawnLoc = new AtomicReference<>();

    private static class DummyLocation implements LocationHandle {
        final double x, y, z;
        DummyLocation(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
        @Override public int x() { return (int) x; }
        @Override public int y() { return (int) y; }
        @Override public int z() { return (int) z; }
        @Override public double doubleX() { return x; }
        @Override public double doubleY() { return y; }
        @Override public double doubleZ() { return z; }
        @Override public @NotNull String worldName() { return "world"; }
        @Override public @NotNull Object platformLocation() { return this; }
        @Override public void playSound(Object type, float volume, float pitch) {}
        @Override public void spawnParticle(Object type, int count, double dx, double dy, double dz, double speed) {}
        @Override public void spawnFirework(Map<String, Object> data) {}
        @Override public void playNote(Object instrument, int tone) {}
    }

    private static class DummyPlayer implements PlayerHandle {
        final UUID uuid = UUID.randomUUID();
        final String name = "TestUser";

        @Override public @NotNull UUID uuid() { return uuid; }
        @Override public @NotNull String name() { return name; }
        @Override public void playSound(Object type, float volume, float pitch, double dx, double dy, double dz) {}
        @Override public void spawnParticle(Object type, int count, double dx, double dy, double dz, double speed) {}
        @Override public void applyPotionEffect(Object type, int duration, int amplifier, boolean ambient, boolean particles, boolean icon) {}
        @Override public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {}
        @Override public void playNote(Object instrument, int tone) {}
        @Override public void setGliding(boolean gliding) {}
        @Override public void spawnFirework(Map<String, Object> data) {}
        @Override public void startGlide(int relativeLift, int maxY, int landingTimeoutTicks, boolean allowFireworks, boolean placeOnShutdown, String platformMaterial) {}
        @Override
        public void dropInventory(@Nullable LocationHandle dropLocation) {
            inventoryDropped.set(true);
            invDropLoc.set(dropLocation);
        }
        @Override
        public void dropExperience(@Nullable LocationHandle dropLocation) {
            expDropped.set(true);
            expDropLoc.set(dropLocation);
        }
        @Override
        public void kill(boolean setBed, @Nullable LocationHandle respawnLocation) {
            playerKilled.set(true);
            setBedFlag.set(setBed);
            deathRespawnLoc.set(respawnLocation);
        }

        @Override
        public void kill() {
            kill(true, null);
        }
    }

    @BeforeEach
    void setUp() {
        inventoryDropped.set(false);
        invDropLoc.set(null);
        expDropped.set(false);
        expDropLoc.set(null);
        playerKilled.set(false);
        setBedFlag.set(false);
        deathRespawnLoc.set(null);
    }

    @Test
    @DisplayName("DropInventoryEffect drops inventory on target player")
    void testDropInventoryEffect() {
        DropInventoryEffect effect = new DropInventoryEffect();
        DummyPlayer player = new DummyPlayer();
        effect.setTarget(player);

        effect.run();

        assertTrue(inventoryDropped.get());
        assertEquals("drop_inventory", effect.toPermission());
    }

    @Test
    @DisplayName("DropInventoryEffect drops at explicit location when target is EffectTarget")
    void testDropInventoryAtLocation() {
        DropInventoryEffect effect = new DropInventoryEffect();
        DummyPlayer player = new DummyPlayer();
        DummyLocation loc = new DummyLocation(100.0, 64.0, 200.0);
        effect.setTarget(new EffectTarget(player, loc));

        effect.run();

        assertTrue(inventoryDropped.get());
        assertEquals(loc, invDropLoc.get());
    }

    @Test
    @DisplayName("DropExpEffect drops experience according to death reset")
    void testDropExpEffect() {
        DropExpEffect effect = new DropExpEffect();
        DummyPlayer player = new DummyPlayer();
        DummyLocation loc = new DummyLocation(50.0, 70.0, -50.0);
        effect.setTarget(new EffectTarget(player, loc));

        effect.run();

        assertTrue(expDropped.get());
        assertEquals(loc, expDropLoc.get());
        assertEquals("drop_exp", effect.toPermission());
    }

    @Test
    @DisplayName("DeathEffect kills target player and sets respawn location")
    void testDeathEffect() {
        DeathEffect effect = new DeathEffect();
        DummyPlayer player = new DummyPlayer();
        DummyLocation loc = new DummyLocation(200.0, 75.0, 300.0);
        effect.setTarget(new EffectTarget(player, loc));

        effect.run();

        assertTrue(playerKilled.get());
        assertTrue(setBedFlag.get());
        assertEquals(loc, deathRespawnLoc.get());
        assertEquals("death", effect.toPermission());
    }

    @Test
    @DisplayName("DeathEffect respects false bed setting token")
    void testDeathEffectWithoutBed() {
        DeathEffect effect = new DeathEffect();
        effect.setData("false");
        DummyPlayer player = new DummyPlayer();
        DummyLocation origin = new DummyLocation(10.0, 64.0, 10.0);
        DummyLocation dest = new DummyLocation(500.0, 70.0, 500.0);
        effect.setTarget(new EffectTarget(player, origin, dest));

        effect.run();

        assertTrue(playerKilled.get());
        assertFalse(setBedFlag.get());
        assertEquals(dest, deathRespawnLoc.get());
        assertEquals("death.false", effect.toPermission());
    }
}
