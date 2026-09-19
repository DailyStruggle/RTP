package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.server.PlayerLifecycleHook;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.CancelReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("NetworkWaitlistGuard and QuitListener tests")
class NetworkWaitlistGuardAndQuitListenerTest {

    @TempDir
    File tempDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
    }

    // -----------------------------------------------------------------------
    // NetworkWaitlistGuard
    // -----------------------------------------------------------------------

    @Test
    void guard_nonPlayerSender_allowsProceed() {
        NetworkStatusCache cache = Mockito.mock(NetworkStatusCache.class);
        NetworkWaitlistGuard guard = new NetworkWaitlistGuard(cache);
        RTPCommandSender console = Mockito.mock(RTPCommandSender.class);

        assertTrue(guard.test(console));
        Mockito.verifyNoInteractions(cache);
    }

    @Test
    void guard_playerNoCachedStatus_allowsProceed() {
        NetworkStatusCache cache = Mockito.mock(NetworkStatusCache.class);
        UUID pid = UUID.randomUUID();
        when(cache.get(pid)).thenReturn(Optional.empty());

        NetworkWaitlistGuard guard = new NetworkWaitlistGuard(cache);
        MockRTPPlayer player = new MockRTPPlayer(pid, "P1", null);

        assertTrue(guard.test(player));
        verify(cache).get(pid);
    }

    @Test
    void guard_playerTerminalStatus_allowsProceed() {
        NetworkStatusCache cache = Mockito.mock(NetworkStatusCache.class);
        UUID pid = UUID.randomUUID();
        NetworkStatusCache.QueueStatus terminal = new NetworkStatusCache.QueueStatus(
                pid, NetworkStatusCache.QueueStatus.State.COMPLETED, 0, Optional.empty(), Optional.empty(), 0L);
        when(cache.get(pid)).thenReturn(Optional.of(terminal));

        NetworkWaitlistGuard guard = new NetworkWaitlistGuard(cache);
        MockRTPPlayer player = new MockRTPPlayer(pid, "P1", null);

        assertTrue(guard.test(player));
    }

    @Test
    void guard_playerNonTerminalStatus_rejectsAndSendsMessage() {
        NetworkStatusCache cache = Mockito.mock(NetworkStatusCache.class);
        UUID pid = UUID.randomUUID();
        NetworkStatusCache.QueueStatus waitlisted = new NetworkStatusCache.QueueStatus(
                pid, NetworkStatusCache.QueueStatus.State.WAITLISTED, 5, Optional.empty(), Optional.empty(), 0L);
        when(cache.get(pid)).thenReturn(Optional.of(waitlisted));

        NetworkWaitlistGuard guard = new NetworkWaitlistGuard(cache);
        MockRTPPlayer player = new MockRTPPlayer(pid, "P1", null);
        MockRTPServerAccessor accessor = (MockRTPServerAccessor) RTP.serverAccessor;
        accessor.addPlayer(player);

        assertFalse(guard.test(player));
        assertFalse(player.sentMessages.isEmpty());
        String msg = player.sentMessages.get(0);
        assertTrue(msg.contains("5"), "Message should contain position: " + msg);
    }

    @Test
    void guard_sendMessageFails_stillRejectsCommand() {
        NetworkStatusCache cache = Mockito.mock(NetworkStatusCache.class);
        UUID pid = UUID.randomUUID();
        NetworkStatusCache.QueueStatus waitlisted = new NetworkStatusCache.QueueStatus(
                pid, NetworkStatusCache.QueueStatus.State.QUEUED, 1, Optional.empty(), Optional.empty(), 0L);
        when(cache.get(pid)).thenReturn(Optional.of(waitlisted));

        MockRTPServerAccessor accessor = Mockito.spy((MockRTPServerAccessor) RTP.serverAccessor);
        doThrow(new RuntimeException("network error")).when(accessor).sendMessage(eq(pid), any(String.class));
        RTP.serverAccessor = accessor;

        NetworkWaitlistGuard guard = new NetworkWaitlistGuard(cache);
        MockRTPPlayer player = new MockRTPPlayer(pid, "P1", null);

        assertFalse(guard.test(player), "Must reject even if message dispatch fails");
    }

    // -----------------------------------------------------------------------
    // NetworkWaitlistQuitListener
    // -----------------------------------------------------------------------

    @Test
    void quitListener_registerNullHook_doesNothing() {
        NetworkRequestQueue queue = Mockito.mock(NetworkRequestQueue.class);
        NetworkWaitlistQuitListener listener = new NetworkWaitlistQuitListener(queue);

        listener.register(null);
        listener.unregister();
    }

    @Test
    void quitListener_registerAndUnregister_managesSubscription() {
        NetworkRequestQueue queue = Mockito.mock(NetworkRequestQueue.class);
        PlayerLifecycleHook hook = Mockito.mock(PlayerLifecycleHook.class);
        AtomicBoolean closed = new AtomicBoolean(false);
        AutoCloseable subscription = () -> closed.set(true);
        when(hook.onPlayerQuit(any())).thenReturn(subscription);

        NetworkWaitlistQuitListener listener = new NetworkWaitlistQuitListener(queue);
        listener.register(hook);

        // Second register is idempotent
        listener.register(hook);
        verify(hook, Mockito.times(1)).onPlayerQuit(any());

        assertFalse(closed.get());
        listener.unregister();
        assertTrue(closed.get());

        // Second unregister is safe
        listener.unregister();
    }

    @Test
    void quitListener_onQuit_cancelsLobbyRetryAndRequestQueue() {
        NetworkRequestQueue queue = Mockito.mock(NetworkRequestQueue.class);
        LobbyDispatchRetryQueue retryQueue = Mockito.mock(LobbyDispatchRetryQueue.class);
        UUID pid = UUID.randomUUID();

        when(queue.cancel(pid, CancelReason.PLAYER_DISCONNECT))
                .thenReturn(CompletableFuture.completedFuture(null));

        NetworkWaitlistQuitListener listener = new NetworkWaitlistQuitListener(queue, retryQueue);
        listener.onQuit(pid);

        verify(retryQueue).cancel(eq(pid), eq(null));
        verify(queue).cancel(eq(pid), eq(CancelReason.PLAYER_DISCONNECT));
    }

    @Test
    void quitListener_onQuit_exceptionInRetry_doesNotBlockQueueCancel() {
        NetworkRequestQueue queue = Mockito.mock(NetworkRequestQueue.class);
        LobbyDispatchRetryQueue retryQueue = Mockito.mock(LobbyDispatchRetryQueue.class);
        UUID pid = UUID.randomUUID();

        doThrow(new RuntimeException("retry boom")).when(retryQueue).cancel(any(), any());
        when(queue.cancel(pid, CancelReason.PLAYER_DISCONNECT))
                .thenReturn(CompletableFuture.completedFuture(null));

        NetworkWaitlistQuitListener listener = new NetworkWaitlistQuitListener(queue, retryQueue);
        listener.onQuit(pid);

        verify(queue).cancel(eq(pid), eq(CancelReason.PLAYER_DISCONNECT));
    }

    @Test
    void quitListener_onQuit_queueThrowsOrFailsExceptionally_handledSafely() {
        NetworkRequestQueue queue = Mockito.mock(NetworkRequestQueue.class);
        UUID pid = UUID.randomUUID();

        // Failed future
        CompletableFuture<Void> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("queue async failure"));
        when(queue.cancel(pid, CancelReason.PLAYER_DISCONNECT)).thenReturn(failedFuture);

        NetworkWaitlistQuitListener listener = new NetworkWaitlistQuitListener(queue);
        listener.onQuit(pid);
        verify(queue).cancel(eq(pid), eq(CancelReason.PLAYER_DISCONNECT));

        // Synchronous throw
        UUID pid2 = UUID.randomUUID();
        when(queue.cancel(pid2, CancelReason.PLAYER_DISCONNECT)).thenThrow(new RuntimeException("sync failure"));
        listener.onQuit(pid2);
        verify(queue).cancel(eq(pid2), eq(CancelReason.PLAYER_DISCONNECT));
    }

    // -----------------------------------------------------------------------
    // RtpTriggerSource
    // -----------------------------------------------------------------------

    @Test
    void rtpTriggerSource_triggerRecordValidation() {
        UUID pid = UUID.randomUUID();

        assertThrows(NullPointerException.class, () -> new RtpTriggerSource.Trigger(null, RtpTriggerSource.Kind.COMMAND, "r", "w"));
        assertThrows(NullPointerException.class, () -> new RtpTriggerSource.Trigger(pid, null, "r", "w"));

        RtpTriggerSource.Trigger tCmd = RtpTriggerSource.Trigger.ofCommand(pid);
        assertEquals(pid, tCmd.playerId());
        assertEquals(RtpTriggerSource.Kind.COMMAND, tCmd.kind());
        assertNull(tCmd.regionKey());
        assertNull(tCmd.worldKey());

        RtpTriggerSource.Trigger tJoin = RtpTriggerSource.Trigger.ofJoin(pid);
        assertEquals(pid, tJoin.playerId());
        assertEquals(RtpTriggerSource.Kind.JOIN, tJoin.kind());
        assertNull(tJoin.regionKey());
        assertNull(tJoin.worldKey());

        RtpTriggerSource.Trigger tCustom = new RtpTriggerSource.Trigger(pid, RtpTriggerSource.Kind.EVENT, "customRegion", "customWorld");
        assertEquals("customRegion", tCustom.regionKey());
        assertEquals("customWorld", tCustom.worldKey());
        assertEquals(RtpTriggerSource.Kind.EVENT, tCustom.kind());
    }
}
