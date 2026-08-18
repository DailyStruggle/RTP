package io.github.dailystruggle.rtp.proxy.common.spi;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable view of the latest backend (and optionally proxy) heartbeats the
 * transport has observed. Assembled by the {@link NetworkTransport} -
 * <strong>never</strong> fetched by the selector - and passed by value into
 * {@link BackendSelector#choose(RtpRequest, NetworkSnapshot)} so the selector
 * stays a pure function (REQ-RTP-PROXY-COMMON-002).
 *
 * <p>Pinned by rtp-proxy-ADR-001 section 2.</p>
 *
 * @param timestampEpochMs when this snapshot was assembled, used by the
 *                         staleness filter (REQ-RTP-PROXY-COMMON-003)
 * @param backends         the most recent {@link BackendHeartbeat} per
 *                         {@code serverId}, keyed by {@code serverId}
 */
public record NetworkSnapshot(
        long timestampEpochMs,
        Map<String, BackendHeartbeat> backends
) {
    public NetworkSnapshot {
        Objects.requireNonNull(backends, "backends");
        // Preserve iteration order to keep golden-file selector tests stable.
        backends = Map.copyOf(new LinkedHashMap<>(backends));
    }

    /** Convenience accessor for a single backend row. */
    public Optional<BackendHeartbeat> backend(String serverId) {
        return Optional.ofNullable(backends.get(serverId));
    }

    /** All known backend rows. */
    public Collection<BackendHeartbeat> all() {
        return backends.values();
    }

    /** Empty snapshot with the supplied timestamp. */
    public static NetworkSnapshot empty(long timestampEpochMs) {
        return new NetworkSnapshot(timestampEpochMs, Map.of());
    }
}
