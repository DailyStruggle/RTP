package io.github.dailystruggle.rtp.folia.world;

import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;

/**
 * Folia-flavoured subclass of the unified non-blocking
 * {@link LocationGenerator}.
 *
 * <p>As of the 2026-04-21 ADR-015 Option B refactor, the core
 * {@link LocationGenerator} is a non-blocking state machine that releases the
 * async worker thread between every I/O-bearing stage (chunk load, reservation
 * ready, neighbour grid load, global-region-verifier). Because every scheduler
 * call is routed through {@code RTP.serverAccessor.getScheduler()}, the Folia
 * adapter's Folia-specific {@code RTPScheduler} implementation automatically
 * picks up {@code runTaskAsynchronously} invocations from the core state
 * machine. No Folia-specific generator logic remains.
 *
 * <p>This class therefore exists only as a platform-tagged alias so that
 * {@link io.github.dailystruggle.rtp.api.server.RTPServerAccessor} can
 * continue to wire a Folia-specific generator into the dependency graph without
 * having to change the existing accessor contract.
 */
public class FoliaLocationGenerator extends LocationGenerator {
}
