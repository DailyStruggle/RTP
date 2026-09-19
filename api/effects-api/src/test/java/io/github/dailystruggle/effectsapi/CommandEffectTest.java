package io.github.dailystruggle.effectsapi;

import io.github.dailystruggle.effectsapi.common.EffectFactory;
import io.github.dailystruggle.effectsapi.common.effects.CommandEffect;
import io.github.dailystruggle.effectsapi.common.spi.HandleProvider;
import io.github.dailystruggle.effectsapi.common.spi.HandleRegistry;
import io.github.dailystruggle.effectsapi.common.spi.LocationHandle;
import io.github.dailystruggle.effectsapi.common.spi.PlayerHandle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CommandEffectTest {

    private final List<String> consoleCommandsRun = new ArrayList<>();
    private final List<String> playerCommandsRun = new ArrayList<>();

    private final UUID testUuid = UUID.randomUUID();

    private final PlayerHandle testPlayer = new PlayerHandle() {
        @Override public @NotNull UUID uuid() { return testUuid; }
        @Override public @NotNull String name() { return "TestPlayer"; }
        @Override public void playSound(Object type, float volume, float pitch, double dx, double dy, double dz) {}
        @Override public void spawnParticle(Object type, int count, double dx, double dy, double dz, double speed) {}
        @Override public void applyPotionEffect(Object type, int duration, int amplifier, boolean ambient, boolean particles, boolean icon) {}
        @Override public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {}
        @Override public void playNote(Object instrument, int tone) {}
        @Override public void setGliding(boolean gliding) {}
        @Override public void spawnFirework(Map<String, Object> data) {}
        @Override public void startGlide(int relativeLift, int maxY, int landingTimeoutTicks, boolean allowFireworks, boolean placeOnShutdown, String platformMaterial) {}
        @Override public void performCommand(@NotNull String command) {
            playerCommandsRun.add(command);
        }
    };

    @BeforeEach
    void setup() {
        consoleCommandsRun.clear();
        playerCommandsRun.clear();

        EffectFactory.setCoercer(new io.github.dailystruggle.effectsapi.bukkit.BukkitValueCoercer());

        HandleRegistry.setProvider(new HandleProvider() {
            @Override
            public @Nullable PlayerHandle wrapPlayer(@NotNull Object player) {
                if (player instanceof PlayerHandle ph) return ph;
                return null;
            }

            @Override
            public @Nullable LocationHandle wrapLocation(@NotNull Object location) {
                return null;
            }

            @Override
            public void dispatchConsoleCommand(@NotNull String command) {
                consoleCommandsRun.add(command);
            }

            @Override
            public void dispatchPlayerCommand(@NotNull PlayerHandle player, @NotNull String command) {
                player.performCommand(command);
            }
        });

        EffectFactory.addEffect("COMMAND", new CommandEffect());
    }

    @Test
    void testCommandParseAndRunConsole() {
        CommandEffect effect = (CommandEffect) EffectFactory.buildEffect("COMMAND");
        if (effect == null) {
            fail("EffectFactory.buildEffect(\"COMMAND\") returned null");
        }
        effect.setData("CONSOLE", "say", "Hello", "[player]!");
        effect.setTarget(testPlayer);
        effect.run();

        assertEquals(1, consoleCommandsRun.size());
        assertEquals("say Hello TestPlayer!", consoleCommandsRun.get(0));
        assertTrue(playerCommandsRun.isEmpty());
    }

    @Test
    void testCommandParseAndRunPlayer() {
        CommandEffect effect = (CommandEffect) EffectFactory.buildEffect("COMMAND");
        assertNotNull(effect);
        effect.setData("PLAYER", "spawn");
        effect.setTarget(testPlayer);
        effect.run();

        assertTrue(consoleCommandsRun.isEmpty());
        assertEquals(1, playerCommandsRun.size());
        assertEquals("spawn", playerCommandsRun.get(0));
    }

    @Test
    void testCommandWithQuotesAndEscapes() {
        CommandEffect effect = (CommandEffect) EffectFactory.buildEffect("COMMAND");
        assertNotNull(effect);
        effect.setData("CONSOLE", "\"broadcast", "Player\\", "[player]\\", "joined\"");
        effect.setTarget(testPlayer);
        effect.run();

        assertEquals(1, consoleCommandsRun.size());
        assertEquals("broadcast Player TestPlayer joined", consoleCommandsRun.get(0));
    }

    @Test
    void testPlaceholderUuidAndPlayer() {
        CommandEffect effect = new CommandEffect();
        effect.setData("CONSOLE", "give", "{uuid}", "diamond", "1");
        effect.setTarget(testPlayer);
        effect.run();

        assertEquals(1, consoleCommandsRun.size());
        assertEquals("give " + testUuid + " diamond 1", consoleCommandsRun.get(0));
    }

    @Test
    void testToPermission() {
        CommandEffect effect = new CommandEffect();
        effect.setData("CONSOLE", "say", "hello", "world");
        String perm = effect.toPermission();
        assertEquals("console.say\\ hello\\ world", perm);
    }
}
