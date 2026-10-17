package io.github.dailystruggle.rtp.proxy.common.transport.redis.resp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RespProtocolTest {

    @Test
    @DisplayName("Write command encodes RESP2 array correctly")
    void testWriteCommand() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RespProtocol.writeCommand(out, "SET", "foo", "bar");
        String encoded = out.toString(StandardCharsets.UTF_8);
        assertEquals("*3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n", encoded);
    }

    @Test
    @DisplayName("Read simple string")
    void testReadSimpleString() throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream("+OK\r\n".getBytes(StandardCharsets.UTF_8));
        Object rep = RespProtocol.readReply(in);
        assertEquals("OK", rep);
    }

    @Test
    @DisplayName("Read error throws RespException")
    void testReadError() {
        ByteArrayInputStream in = new ByteArrayInputStream("-ERR unknown command 'FOO'\r\n".getBytes(StandardCharsets.UTF_8));
        RespException ex = assertThrows(RespException.class, () -> RespProtocol.readReply(in));
        assertEquals("ERR unknown command 'FOO'", ex.getMessage());
        assertFalse(ex.isNoScript());
    }

    @Test
    @DisplayName("Read NOSCRIPT error sets isNoScript to true")
    void testReadNoScriptError() {
        ByteArrayInputStream in = new ByteArrayInputStream("-NOSCRIPT No matching script. Please use EVAL.\r\n".getBytes(StandardCharsets.UTF_8));
        RespException ex = assertThrows(RespException.class, () -> RespProtocol.readReply(in));
        assertTrue(ex.isNoScript());
    }

    @Test
    @DisplayName("Read integer")
    void testReadInteger() throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(":1000\r\n".getBytes(StandardCharsets.UTF_8));
        Object rep = RespProtocol.readReply(in);
        assertEquals(1000L, rep);
    }

    @Test
    @DisplayName("Read bulk string")
    void testReadBulkString() throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream("$6\r\nfoobar\r\n".getBytes(StandardCharsets.UTF_8));
        Object rep = RespProtocol.readReply(in);
        assertInstanceOf(byte[].class, rep);
        assertEquals("foobar", RespProtocol.toUtf8((byte[]) rep));
    }

    @Test
    @DisplayName("Read nil bulk string returns null")
    void testReadNilBulkString() throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream("$-1\r\n".getBytes(StandardCharsets.UTF_8));
        Object rep = RespProtocol.readReply(in);
        assertNull(rep);
    }

    @Test
    @DisplayName("Read array")
    void testReadArray() throws IOException {
        String input = "*2\r\n$3\r\nfoo\r\n$3\r\nbar\r\n";
        ByteArrayInputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        Object rep = RespProtocol.readReply(in);
        assertInstanceOf(List.class, rep);
        List<?> list = (List<?>) rep;
        assertEquals(2, list.size());
        assertEquals("foo", RespProtocol.toUtf8((byte[]) list.get(0)));
        assertEquals("bar", RespProtocol.toUtf8((byte[]) list.get(1)));
    }

    @Test
    @DisplayName("Read nested array")
    void testReadNestedArray() throws IOException {
        String input = "*2\r\n$1\r\n0\r\n*2\r\n$4\r\nkey1\r\n$4\r\nkey2\r\n";
        ByteArrayInputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        Object rep = RespProtocol.readReply(in);
        assertInstanceOf(List.class, rep);
        List<?> list = (List<?>) rep;
        assertEquals(2, list.size());
        assertEquals("0", RespProtocol.toUtf8((byte[]) list.get(0)));
        List<?> subList = (List<?>) list.get(1);
        assertEquals(2, subList.size());
        assertEquals("key1", RespProtocol.toUtf8((byte[]) subList.get(0)));
        assertEquals("key2", RespProtocol.toUtf8((byte[]) subList.get(1)));
    }
}
