package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkWaitlistNotifierTest {

    @TempDir
    File tempDir;

    private MockRTPServerAccessor serverAccessor;
    private MockRTPScheduler scheduler;

    @BeforeEach
    void setUp() {
        serverAccessor = new MockRTPServerAccessor(tempDir);
        RTP.serverAccessor = serverAccessor;
        scheduler = new MockRTPScheduler();
        RTP.scheduler = scheduler;
    }

    @AfterEach
    void tearDown() {
        RTP.serverAccessor = null;
        RTP.scheduler = null;
    }

    @Test
    @DisplayName("start and shutdown lifecycle with scheduler")
    void startAndShutdownLifecycle() {
        NetworkStatusCache cache = new NetworkStatusCache(List::of);
        NetworkWaitlistNotifier notifier = new NetworkWaitlistNotifier(cache, 1000L);

        // start when scheduler is null logs warning and returns
        RTP.scheduler = null;
        notifier.start(20L);

        RTP.scheduler = scheduler;
        notifier.start(20L);
        // idempotent start
        notifier.start(20L);

        // pulse via public pulse()
        notifier.pulse();

        // shutdown
        notifier.shutdown();
        // idempotent shutdown
        notifier.shutdown();
        assertNotNull(notifier);
    }

    @Test
    @DisplayName("emit delivers to online player, fails gracefully for offline or throwing player")
    void emitBehavior() {
        NetworkStatusCache cache = new NetworkStatusCache(List::of);
        NetworkWaitlistNotifier notifier = new NetworkWaitlistNotifier(cache, 1000L);

        UUID onlineId = UUID.randomUUID();
        UUID offlineId = UUID.randomUUID();
        UUID throwingId = UUID.randomUUID();

        List<String> messagesReceived = new ArrayList<>();
        MockRTPPlayer onlinePlayer = new MockRTPPlayer(onlineId, "onlinePlayer", null) {
            @Override public void sendMessage(String message) { messagesReceived.add(message); }
        };
        onlinePlayer.setOnline(true);

        MockRTPPlayer offlinePlayer = new MockRTPPlayer(offlineId, "offlinePlayer", null);
        offlinePlayer.setOnline(false);

        MockRTPPlayer throwingPlayer = new MockRTPPlayer(throwingId, "throwingPlayer", null) {
            @Override public void sendMessage(String message) { throw new RuntimeException("send failure"); }
        };
        throwingPlayer.setOnline(true);

        serverAccessor.addPlayer(onlinePlayer);
        serverAccessor.addPlayer(offlinePlayer);
        serverAccessor.addPlayer(throwingPlayer);

        assertTrue(notifier.emit(onlineId, "hello online"));
        assertEquals(List.of("hello online"), messagesReceived);

        assertFalse(notifier.emit(offlineId, "hello offline"));
        assertFalse(notifier.emit(throwingId, "hello throwing"));
        assertFalse(notifier.emit(UUID.randomUUID(), "nonexistent"));

        // When serverAccessor is null
        RTP.serverAccessor = null;
        assertFalse(notifier.emit(onlineId, "no server accessor"));
    }

    @Test
    @DisplayName("renderBody formats position correctly")
    void renderBodyFormatting() {
        NetworkStatusCache.QueueStatus s1 = new NetworkStatusCache.QueueStatus(
                UUID.randomUUID(), NetworkStatusCache.QueueStatus.State.WAITLISTED, 7,
                Optional.empty(), Optional.empty(), System.currentTimeMillis());
        String body1 = NetworkWaitlistNotifier.renderBody(s1);
        assertTrue(body1.contains("7"));

        NetworkStatusCache.QueueStatus s0 = new NetworkStatusCache.QueueStatus(
                UUID.randomUUID(), NetworkStatusCache.QueueStatus.State.WAITLISTED, 0,
                Optional.empty(), Optional.empty(), System.currentTimeMillis());
        String body0 = NetworkWaitlistNotifier.renderBody(s0);
        assertFalse(body0.contains("[position]"));
    }
}
