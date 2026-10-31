package io.github.dailystruggle.rtp.common.effects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.ConfigReloadLifecycle;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.mock.MockPlayerRegistry;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;
import io.github.dailystruggle.rtp.common.tasks.tick.MiscTasks;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EffectDispatchTest {
    @TempDir
    Path tempDir;

    private ConfigReloadLifecycle installed;
    private MockRTPServerAccessor accessor;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
        accessor.clearWorlds();
        ActiveSelection.set(new io.github.dailystruggle.rtp.common.selection.SelectionAPI());
        new RTP();
        ActiveConfigs.get().reloadConfigs();
        MiscTasks.startup().execute(Long.MAX_VALUE);
        installed = Configs.getReloadLifecycle();
    }

    @AfterEach
    void tearDown() {
        Effect.setDefaultWarn(message -> System.err.println("[effects-api] " + message));
        Configs.setReloadLifecycle(installed);
        if (ActiveConfigs.get() != null && ActiveConfigs.get().fileDatabase != null) {
            try {
                ActiveConfigs.get().fileDatabase.close();
            } catch (Throwable ignored) {
            }
        }
        if (RTP.getInstance() != null) {
            MiscTasks.async().clear();
            MiscTasks.startup().clear();
            RTP.stop();
        }
        if (RTPRuntime.serverAccessor() instanceof MockRTPServerAccessor) {
            ((MockRTPServerAccessor) RTPRuntime.serverAccessor()).clearWorlds();
        }
        ActiveSelection.set(null);
        ActiveConfigs.set(null);
    }

    @Test
    @DisplayName("REQ-CORE-ARCH-003: effect failures are logged without stopping the group")
    void failingEffectDoesNotPreventFollowingEffects() {
        EffectDispatch.installWarnSink();
        RejectingEffect rejecting = new RejectingEffect();
        RecordingEffect following = new RecordingEffect();
        List<Effect<?>> effects = List.of(rejecting, following);

        EffectDispatch.forEachIsolated("postteleport", effects, effect ->
                effect.setTarget(effect == rejecting ? null : new Object()));

        assertTrue(following.targetSet);
        assertTrue(accessor.logMessages.stream().anyMatch(log ->
                log.contains("WARNING") && log.contains("RejectingEffect")));
    }

    @Test
    @DisplayName("REQ-CORE-ARCH-003: effect warnings use RTP logging")
    void defaultWarningSinkUsesRtpLogger() {
        EffectDispatch.installWarnSink();

        Effect.warn("effect warning test");

        assertTrue(accessor.logMessages.stream().anyMatch(log ->
                log.contains("WARNING") && log.contains("effect warning test")));
    }

    @Test
    @DisplayName("REQ-CORE-ARCH-003: stageOf extracts stage suffix and isEffectParsingEnabled reflects live ActiveConfigs")
    @SuppressWarnings("unchecked")
    void stageOfAndEffectParsingEnabledReflectLiveConfigs() {
        assertEquals("postteleport", EffectDispatch.stageOf("rtp.effect.postteleport"));
        assertEquals("custom", EffectDispatch.stageOf("custom"));
        assertEquals("", EffectDispatch.stageOf(null));

        ConfigParser<PerformanceKeys> perf =
                (ConfigParser<PerformanceKeys>) ActiveConfigs.get().getParser(PerformanceKeys.class);
        perf.set(PerformanceKeys.effectParsing, true);
        assertTrue(EffectDispatch.isEffectParsingEnabled(null));
        perf.set(PerformanceKeys.effectParsing, false);
        assertFalse(EffectDispatch.isEffectParsingEnabled(perf));
    }

    @Test
    @DisplayName("REQ-RTP-S-004: post-teleport title and command helpers format placeholders and suppress synthetic BIT mocks")
    @SuppressWarnings("unchecked")
    void postTeleportTitleAndCommandsRespectSyntheticGate() {
        MockRTPWorld world = new MockRTPWorld("world");
        accessor.addWorld(world);
        MockRTPPlayer realPlayer = new MockRTPPlayer(
                UUID.randomUUID(), "Alice", new RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(realPlayer);

        ConfigParser<PlayerMessages> msgParser =
                (ConfigParser<PlayerMessages>) ActiveConfigs.get().getParser(PlayerMessages.class);
        msgParser.set(PlayerMessages.title, "Welcome");
        msgParser.set(PlayerMessages.subtitle, "Safe arrival");
        msgParser.set(PlayerMessages.fadeIn, 10);
        msgParser.set(PlayerMessages.stay, 40);
        msgParser.set(PlayerMessages.fadeOut, 10);
        msgParser.set(PlayerMessages.actionbar, "Teleported!");

        ConfigParser<ConfigKeys> cfgParser =
                (ConfigParser<ConfigKeys>) ActiveConfigs.get().getParser(ConfigKeys.class);
        cfgParser.set(ConfigKeys.consoleCommands, List.of("/say Hello [player]"));
        cfgParser.set(ConfigKeys.playerCommands, List.of("/spawn [player]"));

        TeleportPipelineTask task = new TeleportPipelineTask(
                new GenerationContext(realPlayer, realPlayer, null),
                new io.github.dailystruggle.rtp.common.selection.region.Region(
                        "default",
                        new io.github.dailystruggle.rtp.common.selection.region.RegionSettings(
                                "default",
                                world,
                                new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square(),
                                new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor(new java.util.ArrayList<>()),
                                false, false, 0, 0, 0, 0, 0.0, 0, null, false, -1L, null)));
        try {
            EffectDispatch.PostTeleportTitle title = EffectDispatch.resolvePostTeleportTitle(task);
            assertNotNull(title);
            assertFalse(title.isEmpty());
            assertEquals("Welcome", title.title());
            assertEquals(40, title.stay());

            EffectDispatch.PostTeleportCommands cmds =
                    EffectDispatch.resolvePostTeleportCommands(task, null, true);
            assertNotNull(cmds);
            assertEquals(List.of("say Hello Alice"), cmds.consoleCommands());
            assertEquals(List.of("spawn Alice"), cmds.playerCommands());

            MockPlayerRegistry.register(realPlayer);
            assertNull(EffectDispatch.resolvePostTeleportTitle(task),
                    "Synthetic BIT mock player must not receive post-teleport titles");
            assertNull(EffectDispatch.resolvePostTeleportCommands(task, null, true),
                    "Synthetic BIT mock player must not trigger post-teleport commands");
        } finally {
            MockPlayerRegistry.unregister(realPlayer.uuid());
            task.setCancelled(true);
        }
    }

    private enum TestKey {
        VALUE
    }

    private abstract static class TestEffect extends Effect<TestKey> {
        TestEffect() {
            super(new EnumMap<>(TestKey.class));
        }

        @Override
        public String toPermission() {
            return "rtp.effect.test";
        }

        @Override
        public void setData(String... data) {}

        @Override
        public void run() {}
    }

    private static final class RejectingEffect extends TestEffect {}

    private static final class RecordingEffect extends TestEffect {
        private boolean targetSet;

        @Override
        public void setTarget(Object target) {
            targetSet = true;
        }
    }
}
