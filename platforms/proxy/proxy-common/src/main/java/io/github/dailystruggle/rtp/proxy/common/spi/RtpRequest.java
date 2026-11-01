package io.github.dailystruggle.rtp.proxy.common.spi;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable description of one teleport request entering the proxy
 * dispatcher.
 *
 * <p>Pinned by rtp-proxy-ADR-001 section 1. Fields are intentionally minimal:
 * the dispatcher reads them, the selector reads them, and the transport
 * reservation primitive uses {@link #playerId()} plus the chosen
 * {@code serverId} to derive its token. Anything richer (placeholders,
 * messaging context) is resolved server-side after transfer.</p>
 *
 * @param playerId       the player whose teleport this represents (never {@code null})
 * @param triggerType    where this request came from (never {@code null})
 * @param regionKey      optional region constraint; when present, only backends whose
 *                       {@code regionsAvailable[]} contains it qualify (see ADR-004
 *                       section Candidate Filtering rule 3)
 * @param worldKey       optional world constraint; when present, only backends whose
 *                       {@code worldsLoaded[]} contains it qualify (rule 4)
 * @param serverHint     optional hard-pin to a specific backend {@code serverId}
 *                       (carried verbatim from the player's
 *                       {@code /rtp region=<server>:<region>} command, the lobby's
 *                       {@code pickMostKept()} synthesis, or any other upstream
 *                       routing decision). When present, the dispatcher must
 *                       constrain {@link BackendSelector#choose} to candidates
 *                       whose {@code serverId} matches and hard-fail when the
 *                       pinned backend is not eligible; explicit upstream intent
 *                       is never silently overridden by proxy-side load balancing.
 * @param originServerId optional originating backend id; used by some metrics for hop
 *                       avoidance but does not participate in scoring in v1
 * @param correlationId  proxy-assigned correlation id for tracing one request through
 *                       dispatcher → selector → transport → sender (never {@code null})
 */
public record RtpRequest(
        UUID playerId,
        TriggerType triggerType,
        Optional<String> regionKey,
        Optional<String> worldKey,
        Optional<String> serverHint,
        Optional<String> originServerId,
        UUID correlationId
) {
    public RtpRequest {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(triggerType, "triggerType");
        Objects.requireNonNull(regionKey, "regionKey");
        Objects.requireNonNull(worldKey, "worldKey");
        Objects.requireNonNull(serverHint, "serverHint");
        Objects.requireNonNull(originServerId, "originServerId");
        Objects.requireNonNull(correlationId, "correlationId");
    }

    /**
     * Back-compat 6-arg constructor preserving the pre-{@code serverHint}
     * call shape ({@code playerId, triggerType, regionKey, worldKey,
     * originServerId, correlationId}). Equivalent to the canonical
     * constructor with {@code serverHint = Optional.empty()}. Used by
     * callers (selector test fixtures, etc.) that have no pin to express.
     */
    public RtpRequest(UUID playerId,
                      TriggerType triggerType,
                      Optional<String> regionKey,
                      Optional<String> worldKey,
                      Optional<String> originServerId,
                      UUID correlationId) {
        this(playerId, triggerType, regionKey, worldKey,
                Optional.empty(), originServerId, correlationId);
    }
}
