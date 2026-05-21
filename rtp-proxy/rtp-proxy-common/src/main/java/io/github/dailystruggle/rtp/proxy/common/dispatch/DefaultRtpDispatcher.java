package io.github.dailystruggle.rtp.proxy.common.dispatch;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendSelector;
import io.github.dailystruggle.rtp.proxy.common.spi.DispatchOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.QueueState;
import io.github.dailystruggle.rtp.proxy.common.spi.MessageKey;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.ProxySender;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpDispatcher;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpRequest;
import io.github.dailystruggle.rtp.proxy.common.spi.TransferOutcome;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default {@link RtpDispatcher} implementation living in
 * {@code rtp-proxy-common} so it is shared by every proxy adapter (Velocity
 * today, BungeeCord in Phase 3). Composes a {@link BackendSelector}, a
 * {@link NetworkTransport}, a {@link ProxySender}, and an {@link Executor}.
 *
 * <p>Pipeline per dispatch:</p>
 * <ol>
 *   <li>{@link NetworkTransport#readSnapshot()} (hops to the supplied executor
 *       before touching transport I/O - REQ-RTP-PROXY-COMMON-001).</li>
 *   <li>{@link BackendSelector#choose(RtpRequest, NetworkSnapshot)}.</li>
 *   <li>{@link NetworkTransport#claim(String, java.util.UUID, Duration)}.</li>
 *   <li>{@link ProxySender#sendTo(java.util.UUID, String, ReservationToken)}.</li>
 * </ol>
 *
 * <p>Every failure path produces a {@link DispatchOutcome.Failed} with a
 * configured {@link MessageKey} that the adapter resolves via
 * {@code messages.yml} (REQ-RTP-S-004, REQ-RTP-S-007, REQ-RTP-F-013). No
 * silent {@code return}.</p>
 *
 * <p>The dispatcher is constructor-injected only; it does not read any
 * vendor-specific state. Unit-testable with synthetic snapshots and a fake
 * {@link ProxySender}.</p>
 */
public final class DefaultRtpDispatcher implements RtpDispatcher {

    /** Logger name pinned to the dispatch package for adapter log routing. */
    private static final Logger LOG = Logger.getLogger(DefaultRtpDispatcher.class.getName());

    /** Default reservation TTL when the caller does not pass one. */
    public static final Duration DEFAULT_RESERVATION_TTL = Duration.ofSeconds(30);

    /** Message keys surfaced to the player on each terminal outcome. */
    public static final MessageKey MSG_ROUTED        = new MessageKey("rtp.network.routed");
    public static final MessageKey MSG_NO_BACKEND    = new MessageKey("rtp.network.no-backend");
    public static final MessageKey MSG_CLAIM_FAILED  = new MessageKey("rtp.network.claim-failed");
    public static final MessageKey MSG_UNKNOWN_TARGET = new MessageKey("rtp.network.unknown-target");
    public static final MessageKey MSG_TRANSFER_FAILED = new MessageKey("rtp.network.transfer-failed");
    public static final MessageKey MSG_DISABLED      = new MessageKey("rtp.network.disabled");
    public static final MessageKey MSG_PLAYER_GONE   = new MessageKey("rtp.network.player-gone");
    public static final MessageKey MSG_INTERNAL      = new MessageKey("rtp.network.internal-error");

    private final BackendSelector selector;
    private final NetworkTransport transport;
    private final ProxySender sender;
    private final Executor executor;
    private final Duration reservationTtl;
    private final StatusSink statusSink;

    public DefaultRtpDispatcher(BackendSelector selector,
                                NetworkTransport transport,
                                ProxySender sender,
                                Executor executor) {
        this(selector, transport, sender, executor, DEFAULT_RESERVATION_TTL, StatusSink.NO_OP);
    }

    public DefaultRtpDispatcher(BackendSelector selector,
                                NetworkTransport transport,
                                ProxySender sender,
                                Executor executor,
                                Duration reservationTtl) {
        this(selector, transport, sender, executor, reservationTtl, StatusSink.NO_OP);
    }

    /**
     * Full constructor (Slice D row D6). The {@link StatusSink} fires at
     * every terminal outcome path so the proxy can push the player's queue
     * state into the cross-server {@link io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue}
     * without the dispatcher having to know about the queue SPI.
     *
     * <p>Pass {@link StatusSink#NO_OP} to keep pre-L6 behaviour.</p>
     */
    public DefaultRtpDispatcher(BackendSelector selector,
                                NetworkTransport transport,
                                ProxySender sender,
                                Executor executor,
                                Duration reservationTtl,
                                StatusSink statusSink) {
        this.selector = Objects.requireNonNull(selector, "selector");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.reservationTtl = Objects.requireNonNull(reservationTtl, "reservationTtl");
        this.statusSink = Objects.requireNonNull(statusSink, "statusSink");
        if (reservationTtl.isZero() || reservationTtl.isNegative()) {
            throw new IllegalArgumentException("reservationTtl must be positive");
        }
    }

    /**
     * S-004-safe sink invocation: any {@link Throwable} from the sink is
     * caught and logged so a faulty queue impl cannot mask the dispatcher
     * outcome the caller is awaiting.
     */
    private void emitStatus(java.util.UUID playerId, QueueState state, java.util.Optional<String> reason) {
        try {
            statusSink.emit(playerId, state, reason);
        } catch (Throwable sinkErr) {
            LOG.log(Level.WARNING,
                    "RTP dispatch: StatusSink.emit(" + state + ") threw for player " + playerId
                            + ": " + sinkErr.getMessage(), sinkErr);
        }
    }

    @Override
    public CompletableFuture<DispatchOutcome> dispatch(RtpRequest request) {
        Objects.requireNonNull(request, "request");

        if (!sender.isConnected(request.playerId())) {
            // Player gone before we even started; nothing to surface.
            emitStatus(request.playerId(), QueueState.CANCELLED, Optional.of(MSG_PLAYER_GONE.key()));
            return CompletableFuture.completedFuture(
                    new DispatchOutcome.Failed(DispatchOutcome.Failed.Reason.PLAYER_GONE,
                            MSG_PLAYER_GONE.key()));
        }

        return transport.readSnapshot()
                .thenComposeAsync(snapshot -> claimAfterSelect(request, snapshot), executor)
                .thenCompose(this::sendAfterClaim)
                .exceptionally(err -> {
                    Throwable cause = unwrap(err);
                    LOG.log(Level.WARNING,
                            "RTP dispatch failed for player " + request.playerId()
                                    + " (correlationId=" + request.correlationId() + "): "
                                    + cause.getMessage(), cause);
                    sender.sendMessage(request.playerId(), MSG_INTERNAL, Map.of());
                    emitStatus(request.playerId(), QueueState.FAILED, Optional.of(MSG_INTERNAL.key()));
                    return new DispatchOutcome.Failed(
                            DispatchOutcome.Failed.Reason.INTERNAL, MSG_INTERNAL.key());
                });
    }

    private CompletableFuture<DispatchAttempt> claimAfterSelect(RtpRequest request,
                                                                NetworkSnapshot snapshot) {
        Optional<String> picked = selector.choose(request, snapshot);
        if (picked.isEmpty()) {
            LOG.log(Level.INFO,
                    "RTP dispatch: no eligible backend for player {0} (correlationId={1}).",
                    new Object[]{request.playerId(), request.correlationId()});
            sender.sendMessage(request.playerId(), MSG_NO_BACKEND, Map.of());
            emitStatus(request.playerId(), QueueState.FAILED, Optional.of(MSG_NO_BACKEND.key()));
            return CompletableFuture.completedFuture(
                    DispatchAttempt.failed(new DispatchOutcome.Failed(
                            DispatchOutcome.Failed.Reason.NO_BACKEND, MSG_NO_BACKEND.key())));
        }
        String serverId = picked.get();
        return transport.claim(serverId, request.playerId(), reservationTtl)
                .thenApply(token -> {
                    if (token == null) {
                        LOG.log(Level.WARNING,
                                "RTP dispatch: claim returned null token for player {0} on backend {1}.",
                                new Object[]{request.playerId(), serverId});
                        sender.sendMessage(request.playerId(), MSG_CLAIM_FAILED, Map.of());
                        emitStatus(request.playerId(), QueueState.FAILED, Optional.of(MSG_CLAIM_FAILED.key()));
                        return DispatchAttempt.failed(new DispatchOutcome.Failed(
                                DispatchOutcome.Failed.Reason.CLAIM_RACE, MSG_CLAIM_FAILED.key()));
                    }
                    // Routed: claim succeeded, transfer in flight (proposal Section 4.4).
                    emitStatus(request.playerId(), QueueState.RESERVED, Optional.of(serverId));
                    return DispatchAttempt.routed(request, token);
                });
    }

    private CompletableFuture<DispatchOutcome> sendAfterClaim(DispatchAttempt attempt) {
        if (attempt.outcome != null) {
            return CompletableFuture.completedFuture(attempt.outcome);
        }
        RtpRequest request = attempt.request;
        ReservationToken token = attempt.token;

        if (!sender.isConnected(request.playerId())) {
            LOG.log(Level.WARNING,
                    "RTP dispatch: player {0} disconnected after claim; releasing token {1}.",
                    new Object[]{request.playerId(), token.tokenId()});
            releaseQuietly(token.tokenId(), ReleaseReason.PLAYER_DISCONNECTED);
            emitStatus(request.playerId(), QueueState.CANCELLED, Optional.of(MSG_PLAYER_GONE.key()));
            return CompletableFuture.completedFuture(new DispatchOutcome.Failed(
                    DispatchOutcome.Failed.Reason.PLAYER_GONE, MSG_PLAYER_GONE.key()));
        }

        return sender.sendTo(request.playerId(), token.serverId(), token)
                .thenApply(result -> {
                    if (result == TransferOutcome.SUCCESS) {
                        sender.sendMessage(request.playerId(), MSG_ROUTED,
                                Map.of("server", token.serverId()));
                        emitStatus(request.playerId(), QueueState.COMPLETED, Optional.empty());
                        return (DispatchOutcome) new DispatchOutcome.Routed(
                                token.serverId(), token.tokenId());
                    }
                    LOG.log(Level.WARNING,
                            "RTP dispatch: transfer of player {0} to backend {1} returned {2}; "
                                    + "releasing token {3}.",
                            new Object[]{request.playerId(), token.serverId(), result, token.tokenId()});
                    releaseQuietly(token.tokenId(), releaseReasonFor(result));
                    DispatchOutcome failed = mapTransferFailure(result);
                    String reasonKey = failed instanceof DispatchOutcome.Failed f
                            ? f.messageKey() : MSG_TRANSFER_FAILED.key();
                    emitStatus(request.playerId(),
                            result == TransferOutcome.PLAYER_DISCONNECTED ? QueueState.CANCELLED : QueueState.FAILED,
                            Optional.of(reasonKey));
                    return failed;
                })
                .exceptionally(err -> {
                    Throwable cause = unwrap(err);
                    LOG.log(Level.WARNING,
                            "RTP dispatch: transfer of player " + request.playerId()
                                    + " threw " + cause.getClass().getSimpleName()
                                    + "; releasing token " + token.tokenId(),
                            cause);
                    releaseQuietly(token.tokenId(), ReleaseReason.BACKEND_REJECTED);
                    sender.sendMessage(request.playerId(), MSG_TRANSFER_FAILED, Map.of());
                    emitStatus(request.playerId(), QueueState.FAILED, Optional.of(MSG_TRANSFER_FAILED.key()));
                    return new DispatchOutcome.Failed(
                            DispatchOutcome.Failed.Reason.INTERNAL, MSG_TRANSFER_FAILED.key());
                });
    }

    private DispatchOutcome mapTransferFailure(TransferOutcome result) {
        switch (result) {
            case PLAYER_DISCONNECTED:
                return new DispatchOutcome.Failed(
                        DispatchOutcome.Failed.Reason.PLAYER_GONE, MSG_PLAYER_GONE.key());
            case TIMEOUT:
                return new DispatchOutcome.Failed(
                        DispatchOutcome.Failed.Reason.TIMEOUT, MSG_TRANSFER_FAILED.key());
            case BACKEND_REFUSED:
            case INTERNAL_ERROR:
            default:
                return new DispatchOutcome.Failed(
                        DispatchOutcome.Failed.Reason.INTERNAL, MSG_TRANSFER_FAILED.key());
        }
    }

    private static ReleaseReason releaseReasonFor(TransferOutcome result) {
        switch (result) {
            case PLAYER_DISCONNECTED:
                return ReleaseReason.PLAYER_DISCONNECTED;
            case TIMEOUT:
            case BACKEND_REFUSED:
            case INTERNAL_ERROR:
            default:
                return ReleaseReason.BACKEND_REJECTED;
        }
    }

    private void releaseQuietly(String tokenId, ReleaseReason reason) {
        try {
            transport.release(tokenId, reason);
        } catch (RuntimeException ex) {
            LOG.log(Level.FINE,
                    "RTP dispatch: best-effort release({0}, {1}) threw: {2}",
                    new Object[]{tokenId, reason, ex.getMessage()});
        }
    }

    private static Throwable unwrap(Throwable t) {
        if (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }

    /** Tagged transient holder threaded through {@link #dispatch}. */
    private static final class DispatchAttempt {
        final RtpRequest request;
        final ReservationToken token;
        final DispatchOutcome outcome;

        private DispatchAttempt(RtpRequest request, ReservationToken token, DispatchOutcome outcome) {
            this.request = request;
            this.token = token;
            this.outcome = outcome;
        }

        static DispatchAttempt routed(RtpRequest request, ReservationToken token) {
            return new DispatchAttempt(request, token, null);
        }

        static DispatchAttempt failed(DispatchOutcome outcome) {
            return new DispatchAttempt(null, null, outcome);
        }
    }
}
