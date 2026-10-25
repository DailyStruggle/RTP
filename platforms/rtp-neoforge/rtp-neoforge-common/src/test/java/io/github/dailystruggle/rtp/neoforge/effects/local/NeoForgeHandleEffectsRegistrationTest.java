package io.github.dailystruggle.rtp.neoforge.effects.local;

import io.github.dailystruggle.effectsapi.common.EffectFactory;
import io.github.dailystruggle.effectsapi.common.spi.LocationHandle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeoForgeHandleEffectsRegistrationTest {

    @Test
    @DisplayName("NeoForge registers DEATH and KILL so a DEATH effect token is not skipped as unknown")
    void registersDeathAndKill() {
        NeoForgeEffectsInitializer.registerHandleEffects();

        Collection<String> names = EffectFactory.registeredNames();
        assertTrue(names.contains("COMMAND"), "COMMAND must be registered");
        assertTrue(names.contains("DEATH"), "DEATH must be registered");
        assertTrue(names.contains("KILL"), "KILL must be registered");
    }

    @Test
    @DisplayName("RTP-70 / ADR-116: NeoForge registers the full canonical built-in effect key set")
    void registersGlideAndDrops() {
        NeoForgeEffectsInitializer.registerHandleEffects();

        Collection<String> names = EffectFactory.registeredNames();
        for (String name : EffectFactory.BUILT_IN_EFFECT_KEYS) {
            assertTrue(names.contains(name), name + " must be registered");
        }
        for (String alias : EffectFactory.BUILT_IN_EFFECT_ALIASES) {
            assertTrue(names.contains(alias), alias + " must be registered");
        }
    }

    @Test
    @DisplayName("ADR-116: NeoForgeHandles note pitch, instrument mapping, color/shape parsing, and unsupported reporting")
    void neoForgeHandlesPrimitivesAndHelpers() {
        assertEquals("minecraft:block.note_block.harp", NeoForgeHandles.noteSoundId("PIANO"));
        assertEquals("minecraft:block.note_block.pling", NeoForgeHandles.noteSoundId("PLING"));
        assertEquals("minecraft:block.note_block.basedrum", NeoForgeHandles.noteSoundId("BASS_DRUM"));
        assertEquals(1.0f, NeoForgeHandles.notePitch(12), 1e-5f);
        assertEquals(0.5f, NeoForgeHandles.notePitch(0), 1e-5f);
        assertEquals(2.0f, NeoForgeHandles.notePitch(24), 1e-5f);

        assertEquals(0xFF0000, NeoForgeHandles.parseColorRgb("RED"));
        assertEquals(0xFF8800, NeoForgeHandles.parseColorRgb("#FF8800"));
        assertEquals("STAR", NeoForgeHandles.parseFireworkShapeName("STAR"));
        assertEquals("LARGE_BALL", NeoForgeHandles.parseFireworkShapeName("BALL_LARGE"));

        EffectFactory.clearUnsupportedPrimitives();
        LocationHandle loc = NeoForgeHandles.wrapCoordinates(10.5, 64.0, -20.5, "minecraft:overworld");
        loc.spawnFirework(Map.of("NUMBER", 1, "POWER", 0, "COLOR", "RED"));
        assertTrue(EffectFactory.unsupportedPrimitives().contains("NEOFORGE:FIREWORK"),
                "Firework without bound level must report unsupported/unavailable primitive once");

        loc.playSound("minecraft:entity.player.levelup", 1.0f, 1.0f);
        assertTrue(EffectFactory.unsupportedPrimitives().contains("NEOFORGE:SOUND"),
                "Sound without bound level must report unsupported/unavailable primitive once");
        EffectFactory.clearUnsupportedPrimitives();
    }
}
