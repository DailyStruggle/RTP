package io.github.dailystruggle.effectsapi.bukkit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.dailystruggle.effectsapi.common.Effect;
import java.util.ArrayList;
import java.util.List;
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
}
