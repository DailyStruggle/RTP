/**
 * Tier 1 runtime holder (ADR-115 seam 4): platform accessor, scheduler, economy and the
 * bootstrap log buffer, typed only by {@code rtp-api} contracts.
 *
 * <p>Depends on {@code rtp-api} and the JDK only; no other {@code rtp-core} package. Enforced by
 * {@code RTPArchitectureTest} Rule 20. The composition root ({@code common.RTP}) installs the
 * slots; domain packages read them through {@link io.github.dailystruggle.rtp.common.runtime.RTPRuntime}.
 */
package io.github.dailystruggle.rtp.common.runtime;
