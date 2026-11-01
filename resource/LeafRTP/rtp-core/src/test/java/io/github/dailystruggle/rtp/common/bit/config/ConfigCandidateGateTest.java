package io.github.dailystruggle.rtp.common.bit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.reload.ReloadCmd;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ADR-121 section 7: reload and Hot-Apply reject a candidate with any config ERROR")
class ConfigCandidateGateTest {
    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a broken overlay is rejected and the rejection message is filled from the first error")
    void overlayWithErrorIsRejected() {
        ConfigCandidateGate.Result result = ConfigCandidateGate.evaluate(
                ConfigSnapshot.of(Map.of()).withOverlay(Map.of("config.yml", ConfigSnapshotTest.BROKEN_YAML)),
                ConfigEnvironment.HEADLESS);

        assertTrue(result.rejected());
        assertEquals("RTP_YAML_UNPARSABLE", result.firstError().code());
        assertEquals("1 RTP_YAML_UNPARSABLE config.yml -",
                ConfigCandidateGate.format("[count] [code] [source] [path]", result));
    }

    @Test
    @DisplayName("a clean candidate is accepted")
    void cleanCandidateIsAccepted() {
        ConfigCandidateGate.Result result = ConfigCandidateGate.evaluate(
                ConfigSnapshot.of(Map.of("custom/notes.yml", "a: 1\n")), ConfigEnvironment.HEADLESS);

        assertFalse(result.rejected());
    }

    @Test
    @DisplayName("Configs.reload keeps the running parsers when the on-disk candidate is rejected")
    void reloadKeepsRunningConfig() throws IOException {
        RTPTestSetup.install(tempDir.toFile());
        Configs configs = ActiveConfigs.get();
        Map<Class<?>, ?> parsersBefore = configs.configParserMap;
        Path broken = configs.pluginDirectory.toPath().resolve("definitions/regions/broken.yml");
        Files.createDirectories(broken.getParent());
        Files.writeString(broken, ConfigSnapshotTest.BROKEN_YAML, StandardCharsets.UTF_8);

        assertFalse(configs.reload());

        assertSame(parsersBefore, configs.configParserMap, "rejected candidate must not swap parsers");
        assertEquals("RTP_YAML_UNPARSABLE", configs.lastReloadRejection().orElseThrow().firstError().code());
        assertEquals(ConfigSnapshotTest.BROKEN_YAML, Files.readString(broken), "the gate writes nothing");

        Files.delete(broken);
        assertTrue(configs.reload());
        assertTrue(configs.lastReloadRejection().isEmpty());
    }

    @Test
    @DisplayName("/rtp reload sends the configurable reloadRejected message (S-007)")
    void reloadCommandReportsRejection() throws IOException {
        MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir.toFile());
        MockRTPPlayer sender = new MockRTPPlayer(UUID.randomUUID(), "reload-admin", null);
        accessor.addPlayer(sender);
        ReloadCmd reloadCmd = new ReloadCmd(null);
        Path broken = ActiveConfigs.get().pluginDirectory.toPath().resolve("definitions/regions/broken.yml");
        Files.createDirectories(broken.getParent());
        Files.writeString(broken, ConfigSnapshotTest.BROKEN_YAML, StandardCharsets.UTF_8);

        AtomicReference<Runnable> asyncReload = new AtomicReference<>();
        RTPScheduler scheduler = mock(RTPScheduler.class);
        doAnswer(invocation -> {
            asyncReload.set(invocation.getArgument(0));
            return null;
        }).when(scheduler).runTaskAsynchronously(any(Runnable.class));
        RTPRuntime.setScheduler(scheduler);

        assertTrue(reloadCmd.onCommand(sender.uuid(), new HashMap<>(), null));
        asyncReload.get().run();

        assertFalse(RTP.reloading.get());
        assertTrue(sender.sentMessages.stream().anyMatch(message -> message != null
                && message.contains("RTP_YAML_UNPARSABLE") && message.contains("definitions/regions/broken.yml")),
                () -> "messages: " + sender.sentMessages);
    }
}
