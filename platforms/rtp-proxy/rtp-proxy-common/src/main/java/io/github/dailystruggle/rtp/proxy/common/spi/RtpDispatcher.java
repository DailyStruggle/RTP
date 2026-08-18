package io.github.dailystruggle.rtp.proxy.common.spi;

import java.util.concurrent.CompletableFuture;

/**
 * Single entry point invoked by trigger sources (command, join, event).
 * Owns the end-to-end "route → reserve → transfer" lifecycle.
 * rtp-proxy-ADR-001 section 1.
 *
 * <p>Pure async: implementations must not block any caller thread; the
 * returned future completes on a transport-owned executor.</p>
 */
@FunctionalInterface
public interface RtpDispatcher {
    CompletableFuture<DispatchOutcome> dispatch(RtpRequest request);
}
