package io.github.dailystruggle.rtp.proxy.common.spi;

import java.util.Optional;

/**
 * Pure-function backend chooser (rtp-proxy-ADR-001 §2,
 * rtp-proxy-ADR-004, REQ-RTP-PROXY-COMMON-002).
 *
 * <p>Contract:</p>
 * <ul>
 *   <li>{@link #choose} must perform <strong>no I/O</strong>.</li>
 *   <li>{@link #choose} must be a pure function of {@code (request, snapshot)}
 *       plus any selector-local state (e.g. {@code recentPicks}) that is
 *       supplied at construction time, not read inside the call.</li>
 *   <li>Returning {@link Optional#empty()} signals "no candidate qualified";
 *       the dispatcher decides queue-vs-fail.</li>
 * </ul>
 */
@FunctionalInterface
public interface BackendSelector {

    /**
     * Choose a backend for {@code request} given the supplied {@code snapshot}.
     *
     * @return chosen backend's {@code serverId}, or {@link Optional#empty()} when
     *         no candidate qualified
     */
    Optional<String> choose(RtpRequest request, NetworkSnapshot snapshot);
}
