package io.github.dailystruggle.rtp.proxy.common.spi;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable description of one teleport request entering the proxy
 * dispatcher.
 *
 * <p>Pinned by rtp-proxy-ADR-001 §1. Fields are intentionally minimal:
 * the dispatcher reads them, the selector reads them, and the transport
 * reservation primitive uses {@link #playerId()} plus the chosen
 * {@code serverId} to derive its token. Anything richer (placeholders,
 * messaging context) is resolved server-side after transfer.</p>
 *
 * @param playerId       the player whose teleport this represents (never {@code null})
 * @param triggerType    where this request came from (never {@code null})
 * @param regionKey      optional region constraint; when present, only backends whose
 *                       {@code regionsAvailable[]} contains it qualify (see ADR-004
 *                       §Candidate Filtering rule 3)
 * @param worldKey       optional world constraint; when present, only backends whose
 *                       {@code worldsLoaded[]} contains it qualify (rule 4)
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
        Optional<String> originServerId,
        UUID correlationId
) {
    public RtpRequest {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(triggerType, "triggerType");
        Objects.requireNonNull(regionKey, "regionKey");
        Objects.requireNonNull(worldKey, "worldKey");
        Objects.requireNonNull(originServerId, "originServerId");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
