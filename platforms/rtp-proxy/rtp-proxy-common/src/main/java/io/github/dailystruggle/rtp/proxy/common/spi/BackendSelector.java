package io.github.dailystruggle.rtp.proxy.common.spi;

import java.util.Objects;
import java.util.Optional;

/**
 * Pure-function backend chooser (rtp-proxy-ADR-001 section 2,
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

    /**
     * Choose a backend with an optional pin to a specific {@code serverId}
     * (plumbing for qualified {@code <server>=<region>} parsing;
     * not yet set by callers, but the SPI carries the parameter so the
     * downstream rollout does not require an API break).
     *
     * <p>When {@code serverIdFilter} is present, the resulting candidate set is
     * intersected with backends whose {@code serverId} equals the filter value
     * (case-sensitive, exact match) before scoring proceeds.</p>
     *
     * <p>The default implementation delegates to
     * {@link #choose(RtpRequest, NetworkSnapshot)} and discards the filter, so
     * existing selectors remain functional until they override this method.
     * Selectors that wish to honour the filter must override.</p>
     *
     * @param serverIdFilter optional pin to a specific {@code serverId}; when
     *                       absent, behaviour is identical to
     *                       {@link #choose(RtpRequest, NetworkSnapshot)}
     */
    default Optional<String> choose(RtpRequest request,
                                    NetworkSnapshot snapshot,
                                    Optional<String> serverIdFilter) {
        Objects.requireNonNull(serverIdFilter, "serverIdFilter");
        return choose(request, snapshot);
    }
}
