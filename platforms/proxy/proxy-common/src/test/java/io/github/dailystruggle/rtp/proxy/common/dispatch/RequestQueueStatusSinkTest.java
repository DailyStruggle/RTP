package io.github.dailystruggle.rtp.proxy.common.dispatch;

import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.QueueState;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestQueueStatusSinkTest {

    @Test
    void ctor_nullQueue_throws() {
        assertThrows(NullPointerException.class, () -> new RequestQueueStatusSink(null));
    }

    @Test
    void emit_happyPath() {
        NetworkRequestQueue queue = mock(NetworkRequestQueue.class);
        UUID pid = UUID.randomUUID();
        when(queue.transition(eq(pid), eq(QueueState.ROUTING), any()))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        RequestQueueStatusSink sink = new RequestQueueStatusSink(queue);
        sink.emit(pid, QueueState.ROUTING, Optional.of("test"));

        verify(queue).transition(eq(pid), eq(QueueState.ROUTING), eq(Optional.of("test")));
    }

    @Test
    void emit_exceptionalFuture_doesNotThrow() {
        NetworkRequestQueue queue = mock(NetworkRequestQueue.class);
        UUID pid = UUID.randomUUID();
        CompletableFuture<Optional<NetworkRequestQueue.QueueStatus>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("boom"));
        when(queue.transition(eq(pid), eq(QueueState.FAILED), any())).thenReturn(failed);

        RequestQueueStatusSink sink = new RequestQueueStatusSink(queue);
        sink.emit(pid, QueueState.FAILED, Optional.empty());

        verify(queue).transition(eq(pid), eq(QueueState.FAILED), eq(Optional.empty()));
    }

    @Test
    void emit_synchronousException_doesNotThrow() {
        NetworkRequestQueue queue = mock(NetworkRequestQueue.class);
        UUID pid = UUID.randomUUID();
        when(queue.transition(eq(pid), eq(QueueState.CANCELLED), any()))
                .thenThrow(new RuntimeException("sync boom"));

        RequestQueueStatusSink sink = new RequestQueueStatusSink(queue);
        sink.emit(pid, QueueState.CANCELLED, Optional.empty());

        verify(queue).transition(eq(pid), eq(QueueState.CANCELLED), eq(Optional.empty()));
    }
}
