package io.github.dailystruggle.rtp.proxy.common.transport.redis.resp;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Redis connection target: host, port, TLS flag, optional ACL username.
 *
 * <p>Parsed from either a bare host or a URL: {@code redis://[user@]host[:port]}
 * (plain) or {@code rediss://[user@]host[:port]} (TLS). Passwords in the URL
 * are rejected - they would leak into logs and error messages; supply them via
 * the separate password / password-env channel. The URL form lets TLS and
 * the username travel through call sites that only carry a host string.</p>
 */
public record RespEndpoint(String host, int port, boolean tls, String username) {

    public RespEndpoint {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("redis host is empty");
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("redis port out of range: " + port);
        username = username == null || username.isEmpty() ? null : username;
    }

    /**
     * @param hostOrUrl   bare host or {@code redis://} / {@code rediss://} URL
     * @param defaultPort used when no port is given in the URL (or for bare hosts)
     * @throws IllegalArgumentException on a malformed URL or an embedded password
     */
    public static RespEndpoint parse(String hostOrUrl, int defaultPort) {
        if (hostOrUrl == null) throw new IllegalArgumentException("redis host is empty");
        String s = hostOrUrl.trim();
        String lower = s.toLowerCase(Locale.ROOT);
        boolean tls;
        if (lower.startsWith("rediss://")) {
            tls = true;
        } else if (lower.startsWith("redis://")) {
            tls = false;
        } else {
            return new RespEndpoint(s, defaultPort, false, null);
        }
        URI uri;
        try {
            uri = new URI(s);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("malformed redis URL (" + e.getReason() + ")");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) throw new IllegalArgumentException("redis URL has no host");
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        String user = null;
        String info = uri.getRawUserInfo();
        if (info != null && !info.isEmpty()) {
            if (info.indexOf(':') >= 0) {
                throw new IllegalArgumentException(
                        "redis URL must not embed a password; use password / passwordEnv instead");
            }
            user = uri.getUserInfo();
        }
        int port = uri.getPort() > 0 ? uri.getPort() : defaultPort;
        return new RespEndpoint(host, port, tls, user);
    }

    /** URL form without any secret; round-trips through {@link #parse}. */
    public String toUri() {
        String h = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return (tls ? "rediss://" : "redis://") + (username != null ? username + "@" : "") + h + ":" + port;
    }

    /** Log-safe description. */
    @Override
    public String toString() {
        return host + ":" + port + (tls ? " (tls)" : "") + (username != null ? " user=" + username : "");
    }
}
