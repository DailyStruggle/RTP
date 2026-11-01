package io.github.dailystruggle.rtp.common.network;

import java.util.Locale;
import java.util.Objects;

/**
 * Strategy for cross-server dispatch in network mode (rtp-proxy-ADR-020).
 *
 * <ul>
 *   <li>{@link #DIRECT_DB}: Backends coordinate directly over a shared store
 *       (SQL/Redis) and issue native BungeeCord {@code Connect} plugin messages.
 *       Zero proxy plugin required.</li>
 *   <li>{@link #PROXY_BROKER}: Enqueues cross-server requests into a shared
 *       request queue; proxy companion ({@code rtp-proxy-velocity} /
 *       {@code rtp-proxy-bungee}) dequeues, selects destination, claims
 *       reservation, and handles transfer.</li>
 *   <li>{@link #AUTO}: Automatically resolves to {@link #PROXY_BROKER} when
 *       proxy companions or proxy-direct transports are active, otherwise
 *       resolves to {@link #DIRECT_DB} when durable SQL/Redis transport is configured.</li>
 * </ul>
 */
public enum DispatchMode {
    DIRECT_DB,
    PROXY_BROKER,
    AUTO;

    /**
     * Parse a dispatch mode string case-insensitively, allowing hyphens and underscores.
     * Defaults to {@link #AUTO} when {@code raw} is null or unrecognised.
     */
    public static DispatchMode parse(String raw) {
        if (raw == null || raw.isBlank()) return AUTO;
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "direct_db", "directdb", "direct" -> DIRECT_DB;
            case "proxy_broker", "proxybroker", "broker", "proxy" -> PROXY_BROKER;
            case "auto" -> AUTO;
            default -> AUTO;
        };
    }

    /**
     * Resolve the effective dispatch mode based on transport type and active proxy presence.
     *
     * @param configuredMode the mode configured in network.yml
     * @param transportType  the active transport type (e.g. "sql", "redis", "proxy-direct", "plugin-message")
     * @param hasActiveProxy whether any proxy companion heartbeats are currently active
     * @return either {@link #DIRECT_DB} or {@link #PROXY_BROKER}
     */
    public static DispatchMode resolve(DispatchMode configuredMode, String transportType, boolean hasActiveProxy) {
        Objects.requireNonNull(configuredMode, "configuredMode");
        if (configuredMode == DIRECT_DB) return DIRECT_DB;
        if (configuredMode == PROXY_BROKER) return PROXY_BROKER;

        // AUTO resolution:
        String t = transportType == null ? "auto" : transportType.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (t.equals("proxy_direct") || t.equals("proxydirect") || t.equals("plugin_message") || t.equals("pluginmessage") || t.equals("proxy_cache") || t.equals("proxycache")) {
            return PROXY_BROKER;
        }

        // For durable stores (SQL / Redis / in-memory): if an active proxy companion is reporting,
        // use proxy broker; if not, use direct DB dispatch.
        if (hasActiveProxy) {
            return PROXY_BROKER;
        }
        return DIRECT_DB;
    }
}
