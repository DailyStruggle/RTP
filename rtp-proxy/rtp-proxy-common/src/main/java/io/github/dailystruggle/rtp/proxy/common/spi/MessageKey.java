package io.github.dailystruggle.rtp.proxy.common.spi;

import java.util.Objects;

/**
 * Configurable-message identifier (REQ-RTP-PROXY-006, REQ-RTP-F-013).
 * Looked up in the proxy adapter's messages bundle; the SPI never carries
 * a hardcoded user-visible string.
 *
 * <p>Keys follow the dotted-namespace convention used by
 * {@code messages.yml} elsewhere in the project (e.g.
 * {@code "rtp.network.routed"}, {@code "rtp.network.failed"}).</p>
 */
public record MessageKey(String key) {
    public MessageKey {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("MessageKey must not be blank");
        }
    }
}
