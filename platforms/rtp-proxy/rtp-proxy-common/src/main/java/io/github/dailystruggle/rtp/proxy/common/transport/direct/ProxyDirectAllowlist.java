package io.github.dailystruggle.rtp.proxy.common.transport.direct;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Client-address allowlist for the {@code proxy-direct} listener. Entries are
 * IP literals or CIDR blocks ({@code 10.0.0.0/8}, {@code fd00::/8},
 * {@code 192.168.1.20}). Empty list = no address filtering (HMAC still
 * required). Hostnames are rejected: DNS-based allowlisting is spoofable and
 * would block the accept thread.
 */
public final class ProxyDirectAllowlist {

    private record Block(byte[] network, int prefix) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Block other)) return false;
            return prefix == other.prefix && java.util.Arrays.equals(network, other.network);
        }

        @Override
        public int hashCode() {
            return 31 * java.util.Arrays.hashCode(network) + Integer.hashCode(prefix);
        }

        @Override
        public String toString() {
            return "Block[network=" + java.util.Arrays.toString(network) + ", prefix=" + prefix + "]";
        }
    }

    private static final ProxyDirectAllowlist ALLOW_ALL = new ProxyDirectAllowlist(List.of());

    private final List<Block> blocks;

    private ProxyDirectAllowlist(List<Block> blocks) {
        this.blocks = blocks;
    }

    /** No filtering. */
    public static ProxyDirectAllowlist allowAll() {
        return ALLOW_ALL;
    }

    /**
     * Parse entries. Malformed entries throw so a typo fails closed (the
     * listener does not start) rather than silently widening access.
     *
     * @throws IllegalArgumentException on a hostname, bad literal, or bad prefix
     */
    public static ProxyDirectAllowlist parse(Collection<?> entries) {
        if (entries == null || entries.isEmpty()) return ALLOW_ALL;
        List<Block> out = new ArrayList<>(entries.size());
        for (Object o : entries) {
            if (o == null) continue;
            String e = String.valueOf(o).trim();
            if (e.isEmpty()) continue;
            String addr = e;
            int prefix = -1;
            int slash = e.indexOf('/');
            if (slash >= 0) {
                addr = e.substring(0, slash).trim();
                try {
                    prefix = Integer.parseInt(e.substring(slash + 1).trim());
                } catch (NumberFormatException nfe) {
                    throw new IllegalArgumentException("allowedClients: bad CIDR prefix in '" + e + "'");
                }
            }
            byte[] bytes = literal(addr, e);
            int max = bytes.length * 8;
            if (prefix < 0) prefix = max;
            if (prefix > max) {
                throw new IllegalArgumentException("allowedClients: prefix /" + prefix + " too long in '" + e + "'");
            }
            out.add(new Block(bytes, prefix));
        }
        return out.isEmpty() ? ALLOW_ALL : new ProxyDirectAllowlist(List.copyOf(out));
    }

    /** True when filtering is off or {@code addr} matches any block. */
    public boolean permits(InetAddress addr) {
        if (blocks.isEmpty()) return true;
        if (addr == null) return false;
        byte[] a = addr.getAddress();
        for (Block b : blocks) {
            if (b.network().length == a.length && matches(b.network(), a, b.prefix())) return true;
        }
        return false;
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    public int size() {
        return blocks.size();
    }

    private static boolean matches(byte[] net, byte[] a, int prefix) {
        int full = prefix / 8;
        for (int i = 0; i < full; i++) {
            if (net[i] != a[i]) return false;
        }
        int rem = prefix % 8;
        if (rem == 0) return true;
        int mask = (0xFF << (8 - rem)) & 0xFF;
        return (net[full] & mask) == (a[full] & mask);
    }

    private static byte[] literal(String addr, String entry) {
        if (addr.isEmpty() || !literalChars(addr, true) || (addr.indexOf(':') < 0 && !literalChars(addr, false))) {
            throw new IllegalArgumentException("allowedClients: '" + entry
                    + "' is not an IP literal or CIDR (hostnames are not accepted)");
        }
        try {
            // IP literal only (validated above), so no DNS lookup occurs.
            return InetAddress.getByName(addr).getAddress();
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException("allowedClients: bad address '" + entry + "'");
        }
    }

    /** Every char in {@code [0-9.]}, or {@code [0-9A-Fa-f:.]} when {@code ipv6}. */
    private static boolean literalChars(String s, boolean ipv6) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || c == '.'
                    || (ipv6 && (c == ':' || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')));
            if (!ok) return false;
        }
        return true;
    }
}
