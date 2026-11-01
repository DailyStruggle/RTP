package io.github.dailystruggle.rtp.proxy.common.transport;

import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseSink;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationTokenReaperEdgeTest {

    @Test
    void ctor_validation() {
        NetworkTransport transport = mock(NetworkTransport.class);
        assertThrows(NullPointerException.class, () -> new ReservationTokenReaper(null, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> new ReservationTokenReaper(transport, null));
        assertThrows(IllegalArgumentException.class, () -> new ReservationTokenReaper(transport, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new ReservationTokenReaper(transport, Duration.ofSeconds(-1)));
    }

    @Test
    void close_withoutStart_shutsDownScheduler() {
        NetworkTransport transport = mock(NetworkTransport.class);
        ReservationTokenReaper reaper = new ReservationTokenReaper(transport, Duration.ofSeconds(1));
        assertFalse(reaper.isRunning());
        reaper.close();
    }

    @Test
    void reapNow_reapExpiredFails_returnsZeroAndDoesNotThrow() {
        NetworkTransport transport = mock(NetworkTransport.class);
        when(transport.reapExpired(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("DB down")));

        ReservationTokenReaper reaper = new ReservationTokenReaper(transport, Duration.ofSeconds(1));
        int count = reaper.reapNow();
        assertEquals(0, count);
        assertEquals(0, reaper.reapedCount());
        reaper.close();
    }

    @Test
    void reapNow_releaseOrSinkThrows_continuesReaping() {
        NetworkTransport transport = mock(NetworkTransport.class);
        when(transport.reapExpired(any())).thenReturn(CompletableFuture.completedFuture(List.of("t1", "t2")));
        when(transport.release(eq("t1"), any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("Failed t1")));
        when(transport.release(eq("t2"), any())).thenReturn(CompletableFuture.completedFuture(null));

        ReleaseSink failingSink = (tokenId, reason) -> {
            if ("t1".equals(tokenId)) throw new RuntimeException("Sink exception");
        };

        ReservationTokenReaper reaper = new ReservationTokenReaper(transport, Duration.ofSeconds(1), Instant::now, failingSink);
        int count = reaper.reapNow();
        assertEquals(2, count);
        assertEquals(2, reaper.reapedCount());
        reaper.close();
    }

    @Test
    void start_isIdempotent() {
        NetworkTransport transport = mock(NetworkTransport.class);
        when(transport.reapExpired(any())).thenReturn(CompletableFuture.completedFuture(List.of()));

        ReservationTokenReaper reaper = new ReservationTokenReaper(transport, Duration.ofSeconds(1));
        reaper.start();
        assertTrue(reaper.isRunning());
        reaper.start(); // idempotent second start
        assertTrue(reaper.isRunning());
        reaper.close();
        assertFalse(reaper.isRunning());
    }
}
