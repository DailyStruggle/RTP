package io.github.dailystruggle.rtp.proxy.common.transport.redis.resp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Standard RESP (REdis Serialization Protocol) codec.
 * Zero-dependency parser and serializer for RESP2:
 *   + Simple string
 *   - Error
 *   : Integer
 *   $ Bulk string
 *   * Array
 */
public final class RespProtocol {

    public static final byte SIMPLE_STRING = '+';
    public static final byte ERROR = '-';
    public static final byte INTEGER = ':';
    public static final byte BULK_STRING = '$';
    public static final byte ARRAY = '*';

    private static final byte[] CRLF = new byte[]{'\r', '\n'};

    private RespProtocol() {}

    /**
     * Writes a Redis command array to the output stream.
     * e.g. ["SET", "key", "val"] -> *3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$3\r\nval\r\n
     */
    public static void writeCommand(OutputStream out, String... args) throws IOException {
        writeArrayHeader(out, args.length);
        for (String arg : args) {
            writeBulkString(out, arg != null ? arg.getBytes(StandardCharsets.UTF_8) : null);
        }
        out.flush();
    }

    /**
     * Writes a Redis command array with list arguments.
     */
    public static void writeCommand(OutputStream out, List<byte[]> args) throws IOException {
        writeArrayHeader(out, args.size());
        for (byte[] arg : args) {
            writeBulkString(out, arg);
        }
        out.flush();
    }

    private static void writeArrayHeader(OutputStream out, int length) throws IOException {
        out.write(ARRAY);
        out.write(Integer.toString(length).getBytes(StandardCharsets.US_ASCII));
        out.write(CRLF);
    }

    private static void writeBulkString(OutputStream out, byte[] bytes) throws IOException {
        out.write(BULK_STRING);
        if (bytes == null) {
            out.write(new byte[]{'-', '1'});
            out.write(CRLF);
            return;
        }
        out.write(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        out.write(CRLF);
        out.write(bytes);
        out.write(CRLF);
    }

    /**
     * Reads a single RESP response object from the stream.
     * Returns:
     *   - String for simple strings
     *   - Long for integers
     *   - byte[] for bulk strings (or null for nil bulk strings)
     *   - List<Object> for arrays (or null for nil arrays)
     *   - Throws RespException for Redis error replies
     */
    public static Object readReply(InputStream in) throws IOException {
        int b = in.read();
        if (b == -1) {
            throw new EOFException("Unexpected end of Redis stream");
        }
        byte marker = (byte) b;
        switch (marker) {
            case SIMPLE_STRING:
                return readLine(in);
            case ERROR:
                String errMsg = readLine(in);
                throw new RespException(errMsg);
            case INTEGER:
                String numStr = readLine(in);
                return Long.parseLong(numStr);
            case BULK_STRING:
                int length = Integer.parseInt(readLine(in));
                if (length == -1) {
                    return null;
                }
                byte[] data = new byte[length];
                int totalRead = 0;
                while (totalRead < length) {
                    int read = in.read(data, totalRead, length - totalRead);
                    if (read == -1) {
                        throw new EOFException("Premature EOF while reading bulk string of length " + length);
                    }
                    totalRead += read;
                }
                // Consume trailing \r\n
                int cr = in.read();
                int lf = in.read();
                if (cr != '\r' || lf != '\n') {
                    throw new IOException("Malformed RESP stream: expected CRLF after bulk string");
                }
                return data;
            case ARRAY:
                int count = Integer.parseInt(readLine(in));
                if (count == -1) {
                    return null;
                }
                List<Object> list = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    list.add(readReply(in));
                }
                return Collections.unmodifiableList(list);
            default:
                throw new IOException("Unknown RESP reply type byte: " + (char) marker + " (" + marker + ")");
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(32);
        int prev = -1;
        int curr;
        while ((curr = in.read()) != -1) {
            if (prev == '\r' && curr == '\n') {
                byte[] bytes = buffer.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
            }
            buffer.write(curr);
            prev = curr;
        }
        throw new EOFException("Premature EOF while reading RESP line");
    }

    public static String toUtf8(byte[] bytes) {
        return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null;
    }

    public static byte[] toBytes(String str) {
        return str != null ? str.getBytes(StandardCharsets.UTF_8) : null;
    }
}
