package io.github.dailystruggle.effectsapi.bukkit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.dailystruggle.effectsapi.EffectsAPI;
import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.common.EffectFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BukkitRunEffectNoInlineFallbackTest {
    @Test
    @DisplayName("REQ-RTP-NF-002: Bukkit scheduler rejection never runs an effect inline")
    void schedulerRejectionWarnsAndDoesNotRunEffectInline() {
        Plugin plugin = mock(Plugin.class);
        Effect<?> effect = mock(Effect.class);
        List<String> warnings = new ArrayList<>();
        Effect.setDefaultWarn(warnings::add);

        try {
            BukkitEffectsInitializer.runEffect(plugin, effect);

            verify(effect, never()).run();
            assertTrue(warnings.stream().anyMatch(message ->
                    message.toLowerCase().contains("schedule")));
        } finally {
            Effect.setDefaultWarn(message -> System.err.println("[effects-api] " + message));
        }
    }

    @Test
    @DisplayName("BukkitEffectsInitializer.registerAll() registers the full common effect key set")
    void bukkitEffectsInitializerRegistersExpectedCommonKeySet() throws Exception {
        java.lang.reflect.Field intVersionField = EffectsAPI.class.getDeclaredField("intVersion");
        intVersionField.setAccessible(true);
        Integer priorVersion = (Integer) intVersionField.get(null);

        java.lang.reflect.Field registeredField =
                BukkitEffectsInitializer.class.getDeclaredField("REGISTERED");
        registeredField.setAccessible(true);
        java.util.concurrent.atomic.AtomicBoolean registeredFlag =
                (java.util.concurrent.atomic.AtomicBoolean) registeredField.get(null);
        boolean priorRegistered = registeredFlag.getAndSet(false);

        try {
            intVersionField.set(null, 20);
            BukkitEffectsInitializer.registerAll();

            Set<String> expected = Set.of(
                    "SOUND", "PARTICLE", "POTION", "TITLE", "GLIDE", "COMMAND",
                    "DROP_INVENTORY", "DROP_EXP", "DROP_EXPERIENCE", "DEATH", "KILL",
                    "FIREWORK", "NOTE"
            );
            Set<String> actual = new TreeSet<>(EffectFactory.registeredNames());
            for (String key : expected) {
                assertTrue(actual.contains(key),
                        "BukkitEffectsInitializer.registerAll() must register " + key + " (actual: " + actual + ")");
            }
        } finally {
            intVersionField.set(null, priorVersion);
            registeredFlag.set(priorRegistered);
        }
    }
}
