package io.github.dailystruggle.rtp.common.network.direct;

import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.transport.direct.ProxyDirectWire;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Backend-side {@link NetworkRequestQueue} for the {@code proxy-direct} tier.
 * Acts as an RPC client forwarding queue operations over {@link ProxyDirectNetworkBinding}.
 */
public final class ProxyDirectNetworkRequestQueue implements NetworkRequestQueue {

    private final ProxyDirectNetworkBinding binding;

    public ProxyDirectNetworkRequestQueue(ProxyDirectNetworkBinding binding) {
        this.binding = Objects.requireNonNull(binding, "binding");
    }

    @Override
    public CompletableFuture<EnrolOutcome> enrol(EnrolmentEnvelope envelope) {
        if (envelope == null) return CompletableFuture.completedFuture(EnrolOutcome.REJECTED);
        return flushPending(List.of(envelope));
    }

    @Override
    public CompletableFuture<EnrolOutcome> flushPending(List<EnrolmentEnvelope> batch) {
        if (batch == null || batch.isEmpty()) {
            return CompletableFuture.completedFuture(EnrolOutcome.ACCEPTED);
        }
        return CompletableFuture.supplyAsync(() -> {
            List<String> rows = new ArrayList<>(batch.size());
            for (EnrolmentEnvelope e : batch) {
                rows.add(ProxyDirectWire.encodeEnvelope(e));
            }
            String resp = binding.rpcCallListReq(ProxyDirectWire.OP_FLUSH_PENDING, rows);
            if (resp == null) {
                // No proxy answered: reject so the buffer re-enqueues (S-004).
                return EnrolOutcome.REJECTED;
            }
            try {
                return EnrolOutcome.valueOf(resp);
            } catch (IllegalArgumentException ex) {
                return EnrolOutcome.REJECTED;
            }
        });
    }

    @Override
    public CompletableFuture<List<QueueStatus>> pollStatus(List<UUID> playerIds) {
        if (playerIds == null || playerIds.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.supplyAsync(() -> {
            List<String> ids = new ArrayList<>(playerIds.size());
            for (UUID id : playerIds) {
                if (id != null) ids.add(id.toString());
            }
            List<String> rows = binding.rpcCallListBoth(ProxyDirectWire.OP_POLL_STATUS, ids);
            List<QueueStatus> out = new ArrayList<>(rows.size());
            for (String row : rows) {
                QueueStatus s = ProxyDirectWire.decodeStatus(row);
                if (s != null) out.add(s);
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<Optional<QueueEnvelope>> dequeueReady(Duration blockFor) {
        // Proxy-local: only the proxy's TransportRequestTriggerSource drains.
        return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public CompletableFuture<Optional<QueueStatus>> transition(
            UUID playerId, QueueState next, Optional<String> reason) {
        // Proxy-local: state transitions are driven by the proxy dispatcher's
        // RequestQueueStatusSink against the proxy store.
        return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public CompletableFuture<Void> cancel(UUID playerId, CancelReason reason) {
        if (playerId == null) return CompletableFuture.completedFuture(null);
        String request = playerId + String.valueOf(ProxyDirectWire.FS)
                + (reason == null ? CancelReason.EXPLICIT_REQUEST.name() : reason.name());
        return CompletableFuture.runAsync(() ->
                binding.rpcCall(ProxyDirectWire.OP_CANCEL, request));
    }
}
