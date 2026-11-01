package io.github.dailystruggle.rtp.proxy.common.config;

/**
 * Thrown by {@link NetworkConfig#fromMap} when {@code network.yml} fails
 * fail-fast validation. The adapter is expected to catch this, log a
 * configurable WARNING (REQ-RTP-F-013), and proceed with network mode
 * disabled (REQ-RTP-NET-002 parity).
 */
public final class NetworkConfigException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NetworkConfigException(String message) {
        super(message);
    }

    public NetworkConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
