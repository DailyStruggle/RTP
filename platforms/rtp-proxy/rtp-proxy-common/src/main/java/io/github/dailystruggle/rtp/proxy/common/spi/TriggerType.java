package io.github.dailystruggle.rtp.proxy.common.spi;

/**
 * Origin of an {@link RtpRequest}. Pinned by rtp-proxy-ADR-001 §1 and
 * REQ-RTP-PROXY-COMMON-005 (trigger source plurality).
 */
public enum TriggerType {
    /** Operator or player ran {@code /rtp ...} on a backend or on the proxy. */
    COMMAND,
    /** Player joined the network (first join or returning) and join-RTP is enabled. */
    JOIN,
    /** Programmatic trigger from another plugin or backend event. */
    EVENT
}
