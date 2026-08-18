package io.github.dailystruggle.rtp.proxy.common.dispatch;

import io.github.dailystruggle.rtp.proxy.common.selector.RegionAwareSelector;
import io.github.dailystruggle.rtp.proxy.common.selector.ServerRegion;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendSelector;
import io.github.dailystruggle.rtp.proxy.common.spi.DispatchOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.QueueState;
import io.github.dailystruggle.rtp.proxy.common.spi.MessageKey;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist;
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
 * {@code rtp-proxy-common} so it is shared by every proxy adapter. Composes a {@link BackendSelector}, a
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
    /**
     * Surfaced when the request was parked on the shared cross-proxy
     * {@link NetworkWaitlist} instead of being immediately routed. The
     * lobby-side notifier renders the player's queue position
     * against this key.
     */
    public static final MessageKey MSG_QUEUED        = new MessageKey("rtp.network.queued");

    private final BackendSelector selector;
    private final NetworkTransport transport;
    private final ProxySender sender;
    private final Executor executor;
    private final Duration reservationTtl;
    private final StatusSink statusSink;
    /** Optional shared waitlist; when non-null the no-backend path enrols instead of failing. */
    private final NetworkWaitlist waitlist;
    /** Stable id of the proxy node hosting this dispatcher; persisted in {@link NetworkWaitlist.WaitEnvelope}. */
    private final String proxyServerId;
    /**
     * Optional region-aware fallback selector. When non-null AND the
     * incoming {@link RtpRequest#regionKey()} is empty (i.e. a bare
     * {@code /rtp} that no lobby pre-decided), the dispatcher runs this
     * selector first to pick a {@code (serverId, regionKey)} pair using
     * the same scoring table operators tune for the lobby-side
     * {@code PeerRegionRegistry}. The picked region is folded into a
     * rewritten {@link RtpRequest} which then flows through the existing
     * {@link BackendSelector#choose(RtpRequest, NetworkSnapshot, Optional)}
     * + claim path (with the picked {@code serverId} acting as the
     * filter), so downstream code paths are unchanged. {@code null}
     * preserves pre-change behaviour: regionless requests fall straight
     * through to {@link BackendSelector#choose(RtpRequest, NetworkSnapshot)}.
     */
    private final RegionAwareSelector regionAwareSelector;

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
     * Full constructor. The {@link StatusSink} fires at
     * every terminal outcome path so the proxy can push the player's queue
     * state into the cross-server {@link io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue}
     * without the dispatcher having to know about the queue SPI.
     *
     * <p>Pass {@link StatusSink#NO_OP} to disable status sink callbacks.</p>
     */
    public DefaultRtpDispatcher(BackendSelector selector,
                                NetworkTransport transport,
                                ProxySender sender,
                                Executor executor,
                                Duration reservationTtl,
                                StatusSink statusSink) {
        this(selector, transport, sender, executor, reservationTtl, statusSink, null, "proxy");
    }

    /**
     * Extended constructor: in addition to the inputs above, accept an
     * optional shared {@link NetworkWaitlist}. When {@code waitlist} is
     * non-null and {@link BackendSelector#choose} returns empty (no
     * qualifying backend), the dispatcher parks the envelope on the
     * waitlist and resolves with {@link DispatchOutcome.Queued} +
     * {@link QueueState#WAITLISTED}, instead of the terminal
     * {@link DispatchOutcome.Failed} the no-waitlist path produces. The
     * proxy-side {@link NetworkWaitlistDrainer} reinjects parked
     * envelopes when capacity appears.
     *
     * @param waitlist      shared cross-proxy waitlist, or {@code null}
     *                      to keep no-waitlist behaviour
     * @param proxyServerId stable id of this proxy node; persisted in
     *                      {@link NetworkWaitlist.WaitEnvelope#originServerId()}
     */
    public DefaultRtpDispatcher(BackendSelector selector,
                                NetworkTransport transport,
                                ProxySender sender,
                                Executor executor,
                                Duration reservationTtl,
                                StatusSink statusSink,
                                NetworkWaitlist waitlist,
                                String proxyServerId) {
        this(selector, transport, sender, executor, reservationTtl, statusSink,
                waitlist, proxyServerId, null);
    }

    /**
     * Full constructor that also accepts an optional
     * {@link RegionAwareSelector} for proxy-side load-balanced fallback on
     * bare {@code /rtp} (no {@code regionKey} hint, e.g. proxy-driven
     * join-time RTP without a lobby pre-decision). When non-null, the
     * dispatcher picks a {@code (serverId, regionKey)} pair using the
     * operator-tuned scoring table from {@code network.yml} before the
     * existing {@link BackendSelector} claim path runs.
     *
     * @param regionAwareSelector pair-wise selector, or {@code null} to
     *                            skip the region-aware fallback step
     */
    public DefaultRtpDispatcher(BackendSelector selector,
                                NetworkTransport transport,
                                ProxySender sender,
                                Executor executor,
                                Duration reservationTtl,
                                StatusSink statusSink,
                                NetworkWaitlist waitlist,
                                String proxyServerId,
                                RegionAwareSelector regionAwareSelector) {
        this.selector = Objects.requireNonNull(selector, "selector");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.reservationTtl = Objects.requireNonNull(reservationTtl, "reservationTtl");
        this.statusSink = Objects.requireNonNull(statusSink, "statusSink");
        this.waitlist = waitlist; // optional; null disables the waitlist branch
        this.proxyServerId = Objects.requireNonNull(proxyServerId, "proxyServerId");
        this.regionAwareSelector = regionAwareSelector; // optional; null disables the region-aware fallback
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
        // State-transition log: every dispatcher state emission (QUEUED ->
        // WAITLISTED -> ROUTING -> RESERVED -> TRANSFERRING -> COMPLETED /
        // FAILED). One line per transition; the sink itself is the
        // transport-side mirror that the lobby's NetworkStatusCache reads.
        LOG.log(Level.FINE,
                "[NETWORK][state][proxy] emit: playerId=" + playerId
                        + " state=" + state
                        + (reason != null && reason.isPresent() ? " reason=" + reason.get() : ""));
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
        // Hard-pin gate (2026-05-23 fix): when the upstream router carried
        // a `serverHint` (player typed `region=<server>:<region>`, or the
        // lobby's pickMostKept() synthesised one), the dispatcher MUST
        // constrain the BackendSelector to that server and hard-fail if it
        // is not eligible. Without this gate, every cross-server /rtp was
        // load-balanced even when the player named a specific backend (the
        // hint never reached the selector at all). The region-aware
        // fallback is intentionally bypassed: explicit upstream intent is
        // never overridden by a proxy-side region pick.
        if (request.serverHint().isPresent()) {
            String pinnedServerId = request.serverHint().get();
            Optional<String> picked = selector.choose(request, snapshot,
                    Optional.of(pinnedServerId));
            if (picked.isEmpty()) {
                // Pinned backend not eligible (killSwitched, offline, does
                // not advertise the requested region, etc.). The player
                // explicitly named this backend, so we MUST NOT silently
                // load-balance them elsewhere. Hard-fail with NO_BACKEND so
                // they see the localized "that region is not available"
                // message and can retry with a different target.
                LOG.log(Level.INFO,
                        "[NETWORK][dispatch] serverHint pin not eligible: serverId={0}"
                                + " regionKey={1} player={2} (correlationId={3});"
                                + " hard-failing rather than load-balancing.",
                        new Object[]{pinnedServerId, request.regionKey().orElse("-"),
                                request.playerId(), request.correlationId()});
                sender.sendMessage(request.playerId(), MSG_NO_BACKEND, Map.of());
                emitStatus(request.playerId(), QueueState.FAILED,
                        Optional.of(MSG_NO_BACKEND.key()));
                return CompletableFuture.completedFuture(
                        DispatchAttempt.failed(new DispatchOutcome.Failed(
                                DispatchOutcome.Failed.Reason.NO_BACKEND,
                                MSG_NO_BACKEND.key())));
            }
            LOG.log(Level.INFO,
                    "[NETWORK][dispatch] serverHint honoured: serverId={0} regionKey={1}"
                            + " player={2} (correlationId={3})",
                    new Object[]{picked.get(), request.regionKey().orElse("-"),
                            request.playerId(), request.correlationId()});
            return continueAfterPick(request, picked);
        }

        // Region-aware fallback (proxy-side load-balanced region pick).
        // Active iff (a) an operator-tuned RegionAwareSelector is wired AND
        // (b) the incoming request did NOT pre-pin a region. A pre-pinned
        // region is honoured verbatim: explicit player intent (or a lobby's
        // pickMostKept() result) always wins over the proxy-side pick.
        RtpRequest effectiveRequest = request;
        if (regionAwareSelector != null && request.regionKey().isEmpty()) {
            Optional<ServerRegion> pair =
                    regionAwareSelector.choose(snapshot, request, /*excludedServerId=*/null);
            if (pair.isPresent()) {
                // Rewrite the request with the chosen regionKey so the
                // downstream BackendSelector treats this exactly like the
                // "player explicitly typed region=..." path. We do NOT
                // overwrite playerId / correlationId / triggerType /
                // worldKey / serverHint / originServerId - those are
                // caller invariants. (serverHint stays empty here: this
                // branch only runs when no upstream pin was supplied.)
                effectiveRequest = new RtpRequest(
                        request.playerId(),
                        request.triggerType(),
                        Optional.of(pair.get().regionKey()),
                        request.worldKey(),
                        request.serverHint(),
                        request.originServerId(),
                        request.correlationId());
                LOG.log(Level.INFO,
                        "[NETWORK][dispatch] regionAwareSelector picked serverId={0} regionKey={1}"
                                + " for player {2} (correlationId={3})",
                        new Object[]{pair.get().serverId(), pair.get().regionKey(),
                                request.playerId(), request.correlationId()});
                // Pin the BackendSelector candidate set to the chosen serverId
                // so a same-snapshot disagreement between the two selectors
                // cannot route the player to a different backend than the one
                // whose region we just chose.
                Optional<String> picked = selector.choose(effectiveRequest, snapshot,
                        Optional.of(pair.get().serverId()));
                return continueAfterPick(effectiveRequest, picked);
            }
            // No region-aware pick (every backend disqualified). Fall through
            // to the plain BackendSelector; it will likely also return empty
            // and trigger the waitlist branch below - the desired terminal
            // for "no backend is currently serving anything".
        }
        Optional<String> picked = selector.choose(effectiveRequest, snapshot);
        return continueAfterPick(effectiveRequest, picked);
    }

    /**
     * Shared tail of {@link #claimAfterSelect}: given a (possibly rewritten)
     * request and the {@link BackendSelector#choose} result, drive the
     * waitlist / no-backend / claim path. Factored out so the region-aware
     * fallback can reuse it without duplicating the claim and waitlist
     * branching.
     */
    private CompletableFuture<DispatchAttempt> continueAfterPick(RtpRequest request,
                                                                 Optional<String> picked) {
        if (picked.isEmpty()) {
            // Park on the shared waitlist if one is configured, so
            // the player gets Queued + WAITLISTED rather than a terminal
            // FAILED. The proxy-side NetworkWaitlistDrainer will reinject
            // when capacity appears. See rtp-proxy-ADR-015.
            if (waitlist != null) {
                return parkOnWaitlist(request);
            }
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
        // Carry the request's region constraint onto the reservation so the
        // destination backend's JoinTriggerSource can re-attach it as
        // `rtp region=<regionKey>` on arrival (cross-server
        // /rtp region=<server>:<region> support).
        return transport.claim(serverId, request.playerId(), reservationTtl, request.regionKey())
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

        // Phase B trace (2026-05-23): claim succeeded, player still
        // connected, about to ask the proxy adapter (Velocity / BungeeCord)
        // to actually transfer the player. Logged at FINE so devstack
        // repros can confirm the dispatcher reached the transfer step
        // (vs. returning early on sender.isConnected, claim failure, or
        // never being entered at all) without spamming production consoles.
        LOG.log(Level.FINE,
                "[NETWORK][trace] DefaultRtpDispatcher.sendAfterClaim: invoking sender.sendTo(player={0}, serverId={1}, token={2}) correlationId={3}",
                new Object[]{request.playerId(), token.serverId(), token.tokenId(), request.correlationId()});
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

    /**
     * Park the envelope on the shared cross-proxy waitlist. The
     * dispatcher emits {@link QueueState#WAITLISTED} via the status sink
     * and surfaces {@link DispatchOutcome.Queued} to the caller so trigger
     * sources can update the player UX. Failures from the waitlist
     * binding (REJECTED_FULL, REJECTED_CLOSED, transport throw) fall
     * back to the NO_BACKEND outcome so the player never gets
     * a silent swallow.
     */
    private CompletableFuture<DispatchAttempt> parkOnWaitlist(RtpRequest request) {
        // Preserve serverHint into the parked envelope (2026-05-23 fix):
        // if the dispatcher could not place a pinned request right now and
        // parked it on the waitlist, the drainer must see the hint when it
        // re-dispatches later. Without this, the parked entry silently
        // load-balances on re-injection.
        NetworkWaitlist.WaitEnvelope envelope = new NetworkWaitlist.WaitEnvelope(
                request.playerId(),
                request.correlationId(),
                request.regionKey(),
                request.serverHint(),
                request.originServerId().orElse(proxyServerId),
                System.currentTimeMillis());
        return waitlist.enrol(envelope)
                .thenApply(outcome -> {
                    if (outcome == NetworkWaitlist.EnrolOutcome.ACCEPTED
                            || outcome == NetworkWaitlist.EnrolOutcome.REJECTED_DUPLICATE) {
                        // REJECTED_DUPLICATE is benign here: the player
                        // already has a parked entry from a prior request,
                        // so the waitlist + command-lock UX is already in
                        // effect. Surface the same Queued + WAITLISTED
                        // signal so the trigger source converges on one
                        // pending request per UUID.
                        LOG.log(Level.INFO,
                                "RTP dispatch: parked player {0} on cross-proxy waitlist "
                                        + "(correlationId={1}, outcome={2}).",
                                new Object[]{request.playerId(), request.correlationId(), outcome});
                        sender.sendMessage(request.playerId(), MSG_QUEUED, Map.of());
                        emitStatus(request.playerId(), QueueState.WAITLISTED,
                                Optional.of(MSG_QUEUED.key()));
                        return DispatchAttempt.failed(new DispatchOutcome.Queued(0));
                    }
                    // REJECTED_FULL or REJECTED_CLOSED: fall back to the
                    // terminal NO_BACKEND outcome, so the
                    // player still gets a meaningful S-004 message.
                    LOG.log(Level.WARNING,
                            "RTP dispatch: waitlist refused enrol for player {0} ({1}); "
                                    + "falling back to NO_BACKEND.",
                            new Object[]{request.playerId(), outcome});
                    sender.sendMessage(request.playerId(), MSG_NO_BACKEND, Map.of());
                    emitStatus(request.playerId(), QueueState.FAILED,
                            Optional.of(MSG_NO_BACKEND.key()));
                    return DispatchAttempt.failed(new DispatchOutcome.Failed(
                            DispatchOutcome.Failed.Reason.NO_BACKEND, MSG_NO_BACKEND.key()));
                })
                .exceptionally(err -> {
                    Throwable cause = unwrap(err);
                    LOG.log(Level.WARNING,
                            "RTP dispatch: waitlist.enrol threw for player " + request.playerId()
                                    + " (correlationId=" + request.correlationId() + "); "
                                    + "falling back to NO_BACKEND.",
                            cause);
                    sender.sendMessage(request.playerId(), MSG_NO_BACKEND, Map.of());
                    emitStatus(request.playerId(), QueueState.FAILED,
                            Optional.of(MSG_NO_BACKEND.key()));
                    return DispatchAttempt.failed(new DispatchOutcome.Failed(
                            DispatchOutcome.Failed.Reason.NO_BACKEND, MSG_NO_BACKEND.key()));
                });
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
