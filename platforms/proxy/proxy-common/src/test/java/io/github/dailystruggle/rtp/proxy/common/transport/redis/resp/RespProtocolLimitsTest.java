package io.github.dailystruggle.rtp.proxy.common.transport.redis.resp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bounds on the RESP reader (line length, nesting depth, total element
 * budget) and {@link RespEndpoint} URL parsing. Hostile / corrupt Redis
 * streams must fail with an {@link IOException}, never an OOM or
 * {@link StackOverflowError}.
 */
@DisplayName("REQ-RTP-PROXY-007: RESP reader limits and endpoint parsing")
class RespProtocolLimitsTest {

    private static Object read(String s) throws IOException {
        return read(s.getBytes(StandardCharsets.US_ASCII));
    }

    private static Object read(byte[] b) throws IOException {
        return RespProtocol.readReply(new ByteArrayInputStream(b));
    }

    @Test
    void lineAtCapAccepted_overCapRejected() throws IOException {
        String ok = "+" + "a".repeat(RespProtocol.MAX_LINE_LENGTH) + "\r\n";
        assertEquals(RespProtocol.MAX_LINE_LENGTH, ((String) read(ok)).length());

        String over = "+" + "a".repeat(RespProtocol.MAX_LINE_LENGTH + 1) + "\r\n";
        IOException ex = assertThrows(IOException.class, () -> read(over));
        assertTrue(ex.getMessage().contains("line exceeds"));
    }

    @Test
    void unterminatedHugeLineRejectedBeforeEof() {
        // No CRLF at all: must trip the cap, not buffer until EOF.
        byte[] b = new byte[RespProtocol.MAX_LINE_LENGTH * 2];
        b[0] = '-';
        java.util.Arrays.fill(b, 1, b.length, (byte) 'x');
        IOException ex = assertThrows(IOException.class, () -> read(b));
        assertTrue(ex.getMessage().contains("line exceeds"));
    }

    @Test
    void lengthHeaderOverflowRejected() {
        assertThrows(IOException.class, () -> read("$" + "9".repeat(RespProtocol.MAX_LINE_LENGTH + 1) + "\r\n"));
        assertThrows(IOException.class, () -> read("$notanumber\r\n"));
        assertThrows(IOException.class, () -> read(":12x\r\n"));
    }

    @Test
    void nestingDepthBounded() throws IOException {
        String atLimit = "*1\r\n".repeat(RespProtocol.MAX_NESTING_DEPTH) + ":1\r\n";
        Object v = read(atLimit);
        for (int i = 0; i < RespProtocol.MAX_NESTING_DEPTH; i++) {
            v = ((List<?>) v).get(0);
        }
        assertEquals(1L, v);

        String tooDeep = "*1\r\n".repeat(RespProtocol.MAX_NESTING_DEPTH + 1) + ":1\r\n";
        IOException ex = assertThrows(IOException.class, () -> read(tooDeep));
        assertTrue(ex.getMessage().contains("nesting"));

        // A 100k-deep stream would blow the stack without the depth guard.
        String bomb = "*1\r\n".repeat(100_000);
        assertThrows(IOException.class, () -> read(bomb));
    }

    @Test
    void totalElementBudgetBounded() {
        // Two sibling arrays, each within MAX_ARRAY_ELEMENT_COUNT, together over budget.
        int half = RespProtocol.MAX_TOTAL_ELEMENTS / 2 + 1;
        String s = "*2\r\n*" + half + "\r\n" + "*" + half + "\r\n";
        IOException ex = assertThrows(IOException.class, () -> read(s));
        assertTrue(ex.getMessage().contains("total elements"));
    }

    @Test
    void normalRepliesUnaffected() throws IOException {
        assertEquals("OK", read("+OK\r\n"));
        assertEquals(42L, read(":42\r\n"));
        assertNull(read("$-1\r\n"));
        assertEquals(List.of(1L, 2L), read("*2\r\n:1\r\n:2\r\n"));
        assertThrows(RespException.class, () -> read("-ERR boom\r\n"));
    }

    @Test
    void endpointParsing() {
        RespEndpoint plain = RespEndpoint.parse("redis.local", 6379);
        assertEquals("redis.local", plain.host());
        assertFalse(plain.tls());
        assertNull(plain.username());

        RespEndpoint tls = RespEndpoint.parse("rediss://acl@redis.local:6380", 6379);
        assertTrue(tls.tls());
        assertEquals("acl", tls.username());
        assertEquals(6380, tls.port());
        assertEquals(tls, RespEndpoint.parse(tls.toUri(), 1));

        RespEndpoint v6 = RespEndpoint.parse("redis://[::1]", 6379);
        assertEquals("::1", v6.host());
        assertEquals(6379, v6.port());
        assertEquals("redis://[::1]:6379", v6.toUri());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RespEndpoint.parse("rediss://u:secret@h:1", 6379));
        assertFalse(ex.getMessage().contains("secret"));
        assertFalse(new RespEndpoint("h", 1, true, "u").toString().contains("secret"));
    }
}
