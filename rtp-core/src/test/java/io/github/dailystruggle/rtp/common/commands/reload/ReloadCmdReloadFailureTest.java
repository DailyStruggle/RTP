package io.github.dailystruggle.rtp.common.commands.reload;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReloadCmdReloadFailureTest {
    @TempDir
    Path tempDir;

    private MockRTPServerAccessor accessor;
    private MockRTPPlayer sender;
    private ReloadCmd reloadCmd;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
        sender = new MockRTPPlayer(UUID.randomUUID(), "reload-admin", null);
        accessor.addPlayer(sender);
        reloadCmd = new ReloadCmd(null);
    }

    @Test
    @DisplayName("REQ-RTP-F-013: reload failures are configurable and recover the reload guard")
    void thrownReloadClearsFlagAndReportsFailureToSender() {
        Configs configs = spy(ActiveConfigs.get());
        ActiveConfigs.set(configs);
        doThrow(new IllegalStateException("invalid config")).when(configs).reload();

        AtomicReference<Runnable> asyncReload = new AtomicReference<>();
        RTPScheduler scheduler = mock(RTPScheduler.class);
        doAnswer(invocation -> {
            asyncReload.set(invocation.getArgument(0));
            return null;
        }).when(scheduler).runTaskAsynchronously(any(Runnable.class));
        RTPRuntime.setScheduler(scheduler);

        RTP.reloading.set(false);
        assertTrue(reloadCmd.onCommand(sender.uuid(), new HashMap<>(), null));
        assertTrue(RTP.reloading.get());
        assertNotNull(asyncReload.get());

        assertDoesNotThrow(() -> asyncReload.get().run());

        assertFalse(RTP.reloading.get());
        assertTrue(sender.sentMessages.stream().anyMatch(message ->
                message != null && message.toLowerCase().contains("failed")));
        assertTrue(accessor.logMessages.stream().anyMatch(log ->
                log.contains("SEVERE") && log.contains("invalid config")));
    }
}
