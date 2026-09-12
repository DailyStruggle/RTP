package io.github.dailystruggle.rtp.neoforge.effects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.common.EffectFactory;
import io.github.dailystruggle.effectsapi.common.effects.CommandEffect;
import io.github.dailystruggle.effectsapi.common.spi.HandleRegistry;
import io.github.dailystruggle.rtp.neoforge.effects.local.NeoForgeHandles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeoForgeEffectsInitializerTest {

    @Test
    @DisplayName("NeoForge effects register COMMAND effect and sets HandleRegistry provider")
    void testCommandEffectRegistration() {
        EffectFactory.addEffect("COMMAND", new CommandEffect());
        NeoForgeHandles.register();

        try {
            Effect<?> effect = EffectFactory.buildEffect("COMMAND");
            assertNotNull(effect, "COMMAND effect should be resolvable by EffectFactory");
            assertTrue(effect instanceof CommandEffect, "Resolved effect should be an instance of CommandEffect");
            effect.setData("CONSOLE", "say", "hello");
        } catch (Throwable t) {
            t.printStackTrace();
            throw t;
        }

        assertTrue(HandleRegistry.hasProvider(), "NeoForge handle provider should be registered in HandleRegistry");
    }
}
