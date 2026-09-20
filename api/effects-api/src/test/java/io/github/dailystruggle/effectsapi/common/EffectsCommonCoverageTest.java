package io.github.dailystruggle.effectsapi.common;

import io.github.dailystruggle.effectsapi.bukkit.BukkitValueCoercer;
import io.github.dailystruggle.effectsapi.common.effects.GlideEffect;
import io.github.dailystruggle.effectsapi.common.effects.NoteEffect;
import io.github.dailystruggle.effectsapi.common.effects.ParticleEffect;
import io.github.dailystruggle.effectsapi.common.effects.PotionEffect;
import io.github.dailystruggle.effectsapi.common.effects.TitleEffect;
import io.github.dailystruggle.effectsapi.common.spi.EffectTarget;
import io.github.dailystruggle.effectsapi.common.spi.LocationHandle;
import io.github.dailystruggle.effectsapi.common.spi.PlayerHandle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Effects common models, enums and factory tests")
class EffectsCommonCoverageTest {

    @BeforeEach
    void setUp() {
        EffectFactory.setCoercer(new BukkitValueCoercer());
    }

    private static LocationHandle createDummyLocation() {
        return new LocationHandle() {
            @Override public int x() { return 0; }
            @Override public int y() { return 64; }
            @Override public int z() { return 0; }
            @Override public double doubleX() { return 0.5; }
            @Override public double doubleY() { return 64.0; }
            @Override public double doubleZ() { return 0.5; }
            @Override public String worldName() { return "world"; }
            @Override public Object platformLocation() { return "world:0,64,0"; }
            @Override public void playSound(Object type, float volume, float pitch) {}
            @Override public void spawnParticle(Object type, int count, double dx, double dy, double dz, double speed) {}
            @Override public void spawnFirework(Map<String, Object> data) {}
            @Override public void playNote(Object instrument, int tone) {}
        };
    }

    @Test
    void testEffectsGroupKeys() {
        for (EffectsGroupKeys key : EffectsGroupKeys.values()) {
            assertNotNull(key);
            assertEquals(key, EffectsGroupKeys.valueOf(key.name()));
        }
    }

    @Test
    void testPlayerHandleDefaults() {
        PlayerHandle handle = new PlayerHandle() {
            @Override public UUID uuid() { return UUID.randomUUID(); }
            @Override public String name() { return "player"; }
            @Override public void playSound(Object type, float volume, float pitch, double dx, double dy, double dz) {}
            @Override public void spawnParticle(Object type, int count, double dx, double dy, double dz, double speed) {}
            @Override public void applyPotionEffect(Object type, int duration, int amplifier, boolean ambient, boolean particles, boolean icon) {}
            @Override public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {}
            @Override public void playNote(Object instrument, int tone) {}
            @Override public void setGliding(boolean gliding) {}
            @Override public void spawnFirework(Map<String, Object> data) {}
            @Override public void startGlide(int relativeLift, int maxY, int landingTimeoutTicks, boolean allowFireworks, boolean placeOnShutdown, String platformMaterial) {}
        };

        assertDoesNotThrow(() -> handle.performCommand("test"));
        assertDoesNotThrow(() -> handle.dropInventory());
        assertDoesNotThrow(() -> handle.dropInventory(null));
        assertDoesNotThrow(() -> handle.dropExperience());
        assertDoesNotThrow(() -> handle.dropExperience(null));
        assertDoesNotThrow(() -> handle.kill());
        assertDoesNotThrow(() -> handle.kill(true, null));
    }

    @Test
    void testEffectFactoryRegistry() {
        EffectFactory.setCoercer(new BukkitValueCoercer());
        TitleEffect title = new TitleEffect();
        EffectFactory.addEffect("TITLE_TEST", title);

        Collection<String> names = EffectFactory.registeredNames();
        assertTrue(names.contains("TITLE_TEST"));

        Effect<?> built = EffectFactory.buildEffect("TITLE_TEST");
        assertNotNull(built);
        assertTrue(built instanceof TitleEffect);

        EffectFactory.removeEffect("TITLE_TEST");
        assertNull(EffectFactory.buildEffect("TITLE_TEST"));
    }

    @Test
    void testTitleEffectExecution() {
        EffectFactory.setCoercer(new BukkitValueCoercer());
        List<String> titlesSent = new ArrayList<>();
        PlayerHandle playerHandle = new PlayerHandle() {
            @Override public UUID uuid() { return UUID.randomUUID(); }
            @Override public String name() { return "testPlayer"; }
            @Override public void playSound(Object type, float volume, float pitch, double dx, double dy, double dz) {}
            @Override public void spawnParticle(Object type, int count, double dx, double dy, double dz, double speed) {}
            @Override public void applyPotionEffect(Object type, int duration, int amplifier, boolean ambient, boolean particles, boolean icon) {}
            @Override public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
                titlesSent.add(title + " | " + subtitle);
            }
            @Override public void playNote(Object instrument, int tone) {}
            @Override public void setGliding(boolean gliding) {}
            @Override public void spawnFirework(Map<String, Object> data) {}
            @Override public void startGlide(int relativeLift, int maxY, int landingTimeoutTicks, boolean allowFireworks, boolean placeOnShutdown, String platformMaterial) {}
        };

        LocationHandle loc = createDummyLocation();
        TitleEffect effect = new TitleEffect();
        effect.setTarget(new EffectTarget(playerHandle, loc));
        effect.data.put(TitleEffect.TitleKeys.TITLE, "Welcome");
        effect.data.put(TitleEffect.TitleKeys.SUBTITLE, "Enjoy");
        effect.run();

        assertEquals(1, titlesSent.size());
        assertEquals("Welcome | Enjoy", titlesSent.get(0));
        assertNotNull(effect.toPermission());
    }

    @Test
    void testNoteEffectExecution() {
        EffectFactory.setCoercer(new BukkitValueCoercer());
        List<String> notesPlayed = new ArrayList<>();
        PlayerHandle playerHandle = new PlayerHandle() {
            @Override public UUID uuid() { return UUID.randomUUID(); }
            @Override public String name() { return "testPlayer"; }
            @Override public void playSound(Object type, float volume, float pitch, double dx, double dy, double dz) {}
            @Override public void spawnParticle(Object type, int count, double dx, double dy, double dz, double speed) {}
            @Override public void applyPotionEffect(Object type, int duration, int amplifier, boolean ambient, boolean particles, boolean icon) {}
            @Override public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {}
            @Override public void playNote(Object instrument, int tone) {
                notesPlayed.add(instrument + ":" + tone);
            }
            @Override public void setGliding(boolean gliding) {}
            @Override public void spawnFirework(Map<String, Object> data) {}
            @Override public void startGlide(int relativeLift, int maxY, int landingTimeoutTicks, boolean allowFireworks, boolean placeOnShutdown, String platformMaterial) {}
        };

        LocationHandle loc = createDummyLocation();
        NoteEffect effect = new NoteEffect(org.bukkit.Instrument.PIANO);
        effect.setTarget(new EffectTarget(playerHandle, loc));
        effect.setData("PIANO", "5");
        effect.run();

        assertEquals(1, notesPlayed.size());
        assertEquals(org.bukkit.Instrument.PIANO + ":5", notesPlayed.get(0));
        assertNotNull(effect.toPermission());
    }

    @Test
    void testParticleEffectExecution() {
        EffectFactory.setCoercer(new BukkitValueCoercer());
        AtomicBoolean particleSpawned = new AtomicBoolean(false);
        PlayerHandle playerHandle = new PlayerHandle() {
            @Override public UUID uuid() { return UUID.randomUUID(); }
            @Override public String name() { return "particlePlayer"; }
            @Override public void playSound(Object type, float volume, float pitch, double dx, double dy, double dz) {}
            @Override public void spawnParticle(Object type, int count, double dx, double dy, double dz, double speed) {
                particleSpawned.set(true);
            }
            @Override public void applyPotionEffect(Object type, int duration, int amplifier, boolean ambient, boolean particles, boolean icon) {}
            @Override public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {}
            @Override public void playNote(Object instrument, int tone) {}
            @Override public void setGliding(boolean gliding) {}
            @Override public void spawnFirework(Map<String, Object> data) {}
            @Override public void startGlide(int relativeLift, int maxY, int landingTimeoutTicks, boolean allowFireworks, boolean placeOnShutdown, String platformMaterial) {}
        };

        LocationHandle loc = createDummyLocation();
        ParticleEffect effect = new ParticleEffect("EXPLOSION_NORMAL");
        effect.setTarget(new EffectTarget(playerHandle, loc));
        effect.setData("EXPLOSION_NORMAL", "5", "0.1", "0.2", "0.3", "1.0");
        effect.run();

        assertTrue(particleSpawned.get());
        assertNotNull(effect.toPermission());

        // Target with only location
        AtomicBoolean locParticleSpawned = new AtomicBoolean(false);
        LocationHandle locWithParticle = new LocationHandle() {
            @Override public int x() { return 0; }
            @Override public int y() { return 64; }
            @Override public int z() { return 0; }
            @Override public double doubleX() { return 0.5; }
            @Override public double doubleY() { return 64.0; }
            @Override public double doubleZ() { return 0.5; }
            @Override public String worldName() { return "world"; }
            @Override public Object platformLocation() { return "world:0,64,0"; }
            @Override public void playSound(Object type, float volume, float pitch) {}
            @Override public void spawnParticle(Object type, int count, double dx, double dy, double dz, double speed) {
                locParticleSpawned.set(true);
            }
            @Override public void spawnFirework(Map<String, Object> data) {}
            @Override public void playNote(Object instrument, int tone) {}
        };
        effect.setTarget(new EffectTarget(null, locWithParticle));
        effect.run();
        assertTrue(locParticleSpawned.get());
    }

    @Test
    void testPotionEffectExecution() {
        EffectFactory.setCoercer(new BukkitValueCoercer());
        AtomicBoolean potionApplied = new AtomicBoolean(false);
        PlayerHandle playerHandle = new PlayerHandle() {
            @Override public UUID uuid() { return UUID.randomUUID(); }
            @Override public String name() { return "potionPlayer"; }
            @Override public void playSound(Object type, float volume, float pitch, double dx, double dy, double dz) {}
            @Override public void spawnParticle(Object type, int count, double dx, double dy, double dz, double speed) {}
            @Override public void applyPotionEffect(Object type, int duration, int amplifier, boolean ambient, boolean particles, boolean icon) {
                potionApplied.set(true);
            }
            @Override public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {}
            @Override public void playNote(Object instrument, int tone) {}
            @Override public void setGliding(boolean gliding) {}
            @Override public void spawnFirework(Map<String, Object> data) {}
            @Override public void startGlide(int relativeLift, int maxY, int landingTimeoutTicks, boolean allowFireworks, boolean placeOnShutdown, String platformMaterial) {}
        };

        LocationHandle loc = createDummyLocation();
        PotionEffect effect = new PotionEffect("SPEED");
        effect.setTarget(new EffectTarget(playerHandle, loc));
        effect.setData("SPEED", "100", "2", "true", "true", "true");
        effect.run();

        assertTrue(potionApplied.get());
        assertNotNull(effect.toPermission());
    }

    @Test
    void testGlideEffectExecution() {
        EffectFactory.setCoercer(new BukkitValueCoercer());
        AtomicBoolean glideStarted = new AtomicBoolean(false);
        PlayerHandle playerHandle = new PlayerHandle() {
            @Override public UUID uuid() { return UUID.randomUUID(); }
            @Override public String name() { return "glidePlayer"; }
            @Override public void playSound(Object type, float volume, float pitch, double dx, double dy, double dz) {}
            @Override public void spawnParticle(Object type, int count, double dx, double dy, double dz, double speed) {}
            @Override public void applyPotionEffect(Object type, int duration, int amplifier, boolean ambient, boolean particles, boolean icon) {}
            @Override public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {}
            @Override public void playNote(Object instrument, int tone) {}
            @Override public void setGliding(boolean gliding) {}
            @Override public void spawnFirework(Map<String, Object> data) {}
            @Override public void startGlide(int relativeLift, int maxY, int landingTimeoutTicks, boolean allowFireworks, boolean placeOnShutdown, String platformMaterial) {
                glideStarted.set(true);
            }
        };

        LocationHandle loc = createDummyLocation();
        GlideEffect effect = new GlideEffect();
        effect.setTarget(new EffectTarget(playerHandle, loc));
        effect.setData("STONE", "50", "256", "1000", "false", "true", "*");
        effect.run();

        assertTrue(glideStarted.get());
        assertNotNull(effect.toPermission());
    }
}
