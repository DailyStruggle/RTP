package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.CancelReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NetworkQueueEntryReaperTest {

    private static final long NOW_MS = 100_000L;

    @Test
    @DisplayName("REQ-RTP-NET-008: cancels stale non-terminal entries but keeps fresh entries")
    void cancelsStaleNonTerminalEntries() {
        UUID staleId = UUID.randomUUID();
        UUID freshId = UUID.randomUUID();
        NetworkRequestQueue queue = mock(NetworkRequestQueue.class);
        when(queue.cancel(staleId, CancelReason.TTL_EXPIRED))
                .thenReturn(CompletableFuture.completedFuture(null));
        NetworkStatusCache cache = cache(
                status(staleId, NetworkStatusCache.QueueStatus.State.QUEUED, NOW_MS - 5_000L),
                status(freshId, NetworkStatusCache.QueueStatus.State.QUEUED, NOW_MS - 999L));
        NetworkQueueEntryReaper reaper = reaper(queue, cache, 1_000L);

        assertEquals(1, reaper.sweepOnce());
        verify(queue).cancel(staleId, CancelReason.TTL_EXPIRED);
        verify(queue, never()).cancel(freshId, CancelReason.TTL_EXPIRED);
    }

    @Test
    @DisplayName("REQ-RTP-NET-008: skips terminal and timestamp-less rows")
    void skipsTerminalAndTimestampLessRows() {
        NetworkRequestQueue queue = mock(NetworkRequestQueue.class);
        NetworkStatusCache cache = cache(
                status(UUID.randomUUID(), NetworkStatusCache.QueueStatus.State.CANCELLED, NOW_MS - 10_000L),
                status(UUID.randomUUID(), NetworkStatusCache.QueueStatus.State.QUEUED, 0L));

        assertEquals(0, reaper(queue, cache, 1_000L).sweepOnce());
        verifyNoInteractions(queue);
    }

    @Test
    @DisplayName("REQ-RTP-NET-008: logs cancellation failures and continues the sweep")
    void logsFailuresAndContinues() {
        UUID asynchronousFailureId = UUID.randomUUID();
        UUID synchronousFailureId = UUID.randomUUID();
        UUID successId = UUID.randomUUID();
        NetworkRequestQueue queue = mock(NetworkRequestQueue.class);
        when(queue.cancel(asynchronousFailureId, CancelReason.TTL_EXPIRED))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("async failure")));
        doThrow(new IllegalStateException("sync failure"))
                .when(queue).cancel(synchronousFailureId, CancelReason.TTL_EXPIRED);
        when(queue.cancel(successId, CancelReason.TTL_EXPIRED))
                .thenReturn(CompletableFuture.completedFuture(null));
        NetworkStatusCache cache = cache(
                status(asynchronousFailureId, NetworkStatusCache.QueueStatus.State.QUEUED, NOW_MS - 2_000L),
                status(synchronousFailureId, NetworkStatusCache.QueueStatus.State.WAITLISTED, NOW_MS - 2_000L),
                status(successId, NetworkStatusCache.QueueStatus.State.ROUTING, NOW_MS - 2_000L));

        Logger logger = Logger.getLogger("RTP");
        Level priorLevel = logger.getLevel();
        RTPServerAccessor priorAccessor = RTPRuntime.serverAccessor();
        java.util.List<LogRecord> records = new java.util.ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        RTPRuntime.setServerAccessor(null);
        try {
            assertEquals(3, reaper(queue, cache, 1_000L).sweepOnce());
        } finally {
            RTPRuntime.setServerAccessor(priorAccessor);
            logger.removeHandler(handler);
            logger.setLevel(priorLevel);
        }

        verify(queue).cancel(asynchronousFailureId, CancelReason.TTL_EXPIRED);
        verify(queue).cancel(synchronousFailureId, CancelReason.TTL_EXPIRED);
        verify(queue).cancel(successId, CancelReason.TTL_EXPIRED);
        assertTrue(records.stream().anyMatch(record -> record.getMessage().contains(asynchronousFailureId.toString())
                        && record.getMessage().contains("async failure")));
        assertTrue(records.stream().anyMatch(record -> record.getMessage().contains(synchronousFailureId.toString())
                        && record.getMessage().contains("sync failure")));
    }

    @Test
    @DisplayName("REQ-RTP-NET-008: requires a positive TTL")
    void requiresPositiveTtl() {
        NetworkRequestQueue queue = mock(NetworkRequestQueue.class);
        NetworkStatusCache cache = cache();

        assertThrows(IllegalArgumentException.class, () -> reaper(queue, cache, 0L));
        assertThrows(IllegalArgumentException.class, () -> reaper(queue, cache, -1L));
    }

    private static NetworkQueueEntryReaper reaper(
            NetworkRequestQueue queue, NetworkStatusCache cache, long ttlMs) {
        return new NetworkQueueEntryReaper(queue, cache, ttlMs, () -> NOW_MS);
    }

    private static NetworkStatusCache cache(NetworkStatusCache.QueueStatus... statuses) {
        NetworkStatusCache cache = new NetworkStatusCache(() -> List.of(statuses));
        cache.pollOnce();
        return cache;
    }

    private static NetworkStatusCache.QueueStatus status(
            UUID playerId, NetworkStatusCache.QueueStatus.State state, long updatedAtMs) {
        return new NetworkStatusCache.QueueStatus(
                playerId, state, 0, Optional.empty(), Optional.empty(), updatedAtMs);
    }
}
