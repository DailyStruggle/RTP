package io.github.dailystruggle.rtp.common.network;

/**
 * Producer-side abstraction for RTP trigger events (commands, player joins, external events).
 *
 * @deprecated Use {@link io.github.dailystruggle.rtp.api.network.RtpTriggerSource} directly.
 */
@Deprecated(since = "2.0.0", forRemoval = true)
@SuppressWarnings("java:S1190") // Backward-compatibility alias for promoted interface
public interface RtpTriggerSource extends io.github.dailystruggle.rtp.api.network.RtpTriggerSource {
}
