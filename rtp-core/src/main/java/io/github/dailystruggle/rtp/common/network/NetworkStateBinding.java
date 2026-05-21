package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;

/**
 * Network-state member of {@link
 * io.github.dailystruggle.rtp.common.database.options.AbstractSQLDatabaseAccessor}
 * (decision D3 of {@code MULTI_SERVER_PLAN.md}).
 *
 * <p>Historically this interface was an opaque marker. The Phase 2e-SQL slice
 * landed an {@code api} dep from {@code rtp-core} on {@code rtp-proxy-common}
 * (per {@code rtp-proxy-ADR-011 §Module Dependencies}), so the marker now
 * carries an optional {@link #transport()} accessor returning the live
 * {@link NetworkTransport} the binding wraps. This is what
 * {@code NetworkSimulationTestJob} ({@code rtp test network}, Shape A)
 * uses to reach the running transport on this JVM without introducing a
 * new static slot on {@code RTP} and without leaking platform types
 * across module boundaries.</p>
 *
 * <p><b>Default state: {@code null}.</b> A {@code null} binding means
 * network mode is disabled (REQ-RTP-NET-002 parity, the shipping
 * default). Code paths shall treat {@code null} as "no network
 * activity" - no extra threads, no extra queries, no extra log output.
 * This is the contract that {@code ReqRtpNet002NetworkDisabledNoOpTest}
 * guards.</p>
 *
 * <p><b>{@code transport()} default:</b> returns {@code null}. Implementations
 * that wrap a live {@link NetworkTransport} (e.g. the bootstrap-installed
 * adapter in {@code NetworkModeBootstrap}) override the method. Callers
 * shall treat a {@code null} return as "no live transport on this JVM"
 * and must not synthesise one - this is the byte-identical no-op path.</p>
 *
 * <p>Refines: {@code rtp-proxy-ADR-001} (SPI shape),
 * {@code rtp-proxy-ADR-003} (in-memory binding),
 * {@code ADR-036} §D3.</p>
 *
 * @see io.github.dailystruggle.rtp.common.database.options.AbstractSQLDatabaseAccessor#getNetworkStateBinding()
 */
public interface NetworkStateBinding {
    /**
     * The live {@link NetworkTransport} this binding wraps, or {@code null}
     * if the binding does not host one. Default returns {@code null}
     * so plain marker implementations remain valid without changes.
     */
    default NetworkTransport transport() {
        return null;
    }
}
