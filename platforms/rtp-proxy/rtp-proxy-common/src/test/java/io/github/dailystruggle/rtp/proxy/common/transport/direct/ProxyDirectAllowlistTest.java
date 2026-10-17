package io.github.dailystruggle.rtp.proxy.common.transport.direct;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * proxy-direct client allowlist (rtp-proxy-ADR-017): IP literals and CIDR
 * blocks only, malformed entries fail closed.
 */
class ProxyDirectAllowlistTest {

    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: null, empty, and all-blank lists disable filtering")
    void emptyListsAllowAll() throws Exception {
        assertSame(ProxyDirectAllowlist.allowAll(), ProxyDirectAllowlist.parse(null));
        assertSame(ProxyDirectAllowlist.allowAll(), ProxyDirectAllowlist.parse(List.of()));
        ProxyDirectAllowlist blank = ProxyDirectAllowlist.parse(Arrays.asList(null, "  ", ""));
        assertTrue(blank.isEmpty());
        assertEquals(0, blank.size());
        assertTrue(blank.permits(ip("203.0.113.9")));
        assertTrue(blank.permits(null));
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: IPv4 CIDR, partial-octet prefix, and bare literal match only their block")
    void ipv4Blocks() throws Exception {
        ProxyDirectAllowlist a = ProxyDirectAllowlist.parse(List.of(" 10.0.0.0/8 ", "192.168.16.0/20", "198.51.100.7"));
        assertEquals(3, a.size());
        assertFalse(a.isEmpty());
        assertTrue(a.permits(ip("10.200.3.4")));
        assertFalse(a.permits(ip("11.0.0.1")));
        assertTrue(a.permits(ip("192.168.31.255")));
        assertFalse(a.permits(ip("192.168.32.0")));
        assertTrue(a.permits(ip("198.51.100.7")));
        assertFalse(a.permits(ip("198.51.100.8")));
        assertFalse(a.permits(null));
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: IPv6 blocks never match IPv4 clients, /0 matches the whole family")
    void ipv6AndFamilies() throws Exception {
        ProxyDirectAllowlist a = ProxyDirectAllowlist.parse(List.of("fd00::/8"));
        assertTrue(a.permits(ip("fd12:3456::1")));
        assertFalse(a.permits(ip("fe80::1")));
        assertFalse(a.permits(ip("10.0.0.1")));

        ProxyDirectAllowlist anyV4 = ProxyDirectAllowlist.parse(List.of("0.0.0.0/0"));
        assertTrue(anyV4.permits(ip("203.0.113.1")));
        assertFalse(anyV4.permits(ip("::1")));
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: hostnames, bad prefixes, and invalid literals throw instead of widening access")
    void malformedEntriesFailClosed() {
        for (String bad : List.of("example.com", "localhost", "10.0.0.0/abc", "10.0.0.0/33",
                "::1/129", "/8", ":::", "1.2.3.4 # office")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ProxyDirectAllowlist.parse(List.of(bad)), bad);
        }
    }
}
