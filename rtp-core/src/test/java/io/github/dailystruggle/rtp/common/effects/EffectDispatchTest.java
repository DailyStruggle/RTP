package io.github.dailystruggle.rtp.common.effects;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigReloadLifecycle;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.tasks.tick.MiscTasks;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
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
