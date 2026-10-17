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
}
