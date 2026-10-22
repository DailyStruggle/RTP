package io.github.dailystruggle.rtp.neoforge.effects.local;

import io.github.dailystruggle.effectsapi.common.EffectFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;

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
    @DisplayName("RTP-70: NeoForge registers GLIDE, DROP_INVENTORY and DROP_EXP / DROP_EXPERIENCE")
    void registersGlideAndDrops() {
        NeoForgeEffectsInitializer.registerHandleEffects();

        Collection<String> names = EffectFactory.registeredNames();
        for (String name : new String[] {"GLIDE", "DROP_INVENTORY", "DROP_EXP", "DROP_EXPERIENCE"}) {
            assertTrue(names.contains(name), name + " must be registered");
        }
    }
}
