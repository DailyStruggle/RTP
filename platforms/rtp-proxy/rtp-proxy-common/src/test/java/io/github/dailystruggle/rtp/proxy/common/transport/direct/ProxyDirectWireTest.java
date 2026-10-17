package io.github.dailystruggle.rtp.proxy.common.transport.direct;

import io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.transport.direct.ProxyDirectWire.Direction;
import io.github.dailystruggle.rtp.proxy.common.transport.direct.ProxyDirectWire.FrameRejectedException;
import io.github.dailystruggle.rtp.proxy.common.transport.direct.ProxyDirectWire.RequestHeader;
import io.github.dailystruggle.rtp.proxy.common.transport.direct.ProxyDirectWire.WireContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyDirectWireTest {

    private static byte[] secret32() {
        byte[] s = new byte[32];
        for (int i = 0; i < s.length; i++) s[i] = (byte) (i + 1);
        return s;
    }

    private static final WireContext CTX = new WireContext(ProxyDirectWire.OP_HEARTBEAT, 1_000L, "n-1");

    private static DataInputStream in(ByteArrayOutputStream baos) {
        return new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: request header round-trips opcode, frame version, timestamp and a random nonce")
    void requestHeader_roundTrip() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        WireContext a = ProxyDirectWire.writeRequestHeader(dos, ProxyDirectWire.OP_REDEEM, () -> 42L);
        WireContext b = ProxyDirectWire.writeRequestHeader(dos, ProxyDirectWire.OP_REDEEM, () -> 42L);

        DataInputStream dis = in(baos);
        RequestHeader h = ProxyDirectWire.readRequestHeader(dis);
        assertEquals(ProxyDirectWire.OP_REDEEM, h.opcode());
        assertEquals(ProxyDirectWire.FRAME_VERSION, h.frameVersion());
        assertEquals(42L, h.sentAtMs());
        assertEquals(a, h.context());
        assertEquals(32, a.nonce().length());
        assertNotEquals(a.nonce(), b.nonce(), "each header carries a fresh nonce");
        assertEquals(b, ProxyDirectWire.readRequestHeader(dis).context());

        assertThrows(IllegalArgumentException.class,
                () -> ProxyDirectWire.writeRequestHeader(dos, (byte) 99));
        assertEquals(7, ProxyDirectWire.KNOWN_OPCODES.size());
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: unknown opcode is rejected by the header reader before the body")
    void requestHeader_unknownOpcodeRejected() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeByte(0x7F);
        dos.writeByte(ProxyDirectWire.FRAME_VERSION);
        FrameRejectedException ex = assertThrows(FrameRejectedException.class,
                () -> ProxyDirectWire.readRequestHeader(in(baos)));
        assertEquals(FrameRejectedException.Reason.UNKNOWN_OPCODE, ex.reason());
        assertEquals(0x7F, ex.value());
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: a v1 frame (no version byte) is rejected with an upgrade hint")
    void requestHeader_v1FrameRejected() throws Exception {
        HmacVerifier verifier = HmacVerifier.forTesting(secret32(), 1, 1);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        // v1 layout: opcode, then the signed block directly.
        dos.writeByte(ProxyDirectWire.OP_HEARTBEAT);
        dos.writeInt(1);
        dos.writeUTF(verifier.sign(1, "hb"));
        dos.writeUTF("hb");
        FrameRejectedException ex = assertThrows(FrameRejectedException.class,
                () -> ProxyDirectWire.readRequestHeader(in(baos)));
        assertEquals(FrameRejectedException.Reason.UNSUPPORTED_FRAME_VERSION, ex.reason());
        assertEquals(1, ex.value());
        assertTrue(ex.getMessage().contains("wire v1"), ex.getMessage());
        assertTrue(ex.getMessage().contains("upgrade proxy and backends together"), ex.getMessage());

        baos.reset();
        dos.writeByte(ProxyDirectWire.OP_HEARTBEAT);
        dos.writeByte(3);
        ex = assertThrows(FrameRejectedException.class, () -> ProxyDirectWire.readRequestHeader(in(baos)));
        assertEquals(3, ex.value());
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: empty nonce in a request header is rejected")
    void requestHeader_emptyNonceRejected() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeByte(ProxyDirectWire.OP_HEARTBEAT);
        dos.writeByte(ProxyDirectWire.FRAME_VERSION);
        dos.writeLong(1L);
        dos.writeUTF("");
        FrameRejectedException ex = assertThrows(FrameRejectedException.class,
                () -> ProxyDirectWire.readRequestHeader(in(baos)));
        assertEquals(FrameRejectedException.Reason.MALFORMED_HEADER, ex.reason());
    }

    @Test
    void signedPayload_roundTrip_andTamperDetection() throws Exception {
        HmacVerifier verifier = HmacVerifier.forTesting(secret32(), 1, 1);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        ProxyDirectWire.writeSignedPayload(dos, CTX, Direction.REQUEST, "hello world", verifier, 1);

        String read = ProxyDirectWire.readSignedPayload(in(baos), CTX, Direction.REQUEST, verifier);
        assertEquals("hello world", read);

        // Valid UTF framing, mismatched HMAC.
        baos.reset();
        dos.writeInt(1); // schemaVersion
        dos.writeUTF("00".repeat(32)); // wrong HMAC
        dos.writeUTF("payload");
        assertNull(ProxyDirectWire.readSignedPayload(in(baos), CTX, Direction.REQUEST, verifier));

        // A v1-style MAC over the bare payload no longer verifies.
        baos.reset();
        dos.writeInt(1);
        dos.writeUTF(verifier.sign(1, "payload"));
        dos.writeUTF("payload");
        assertNull(ProxyDirectWire.readSignedPayload(in(baos), CTX, Direction.REQUEST, verifier));
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: a signed body replayed under a different opcode is rejected")
    void signedPayload_opcodeSwapRejected() throws Exception {
        HmacVerifier verifier = HmacVerifier.forTesting(secret32(), 1, 1);
        WireContext redeem = new WireContext(ProxyDirectWire.OP_REDEEM, 5_000L, "abc");
        WireContext cancel = new WireContext(ProxyDirectWire.OP_CANCEL, 5_000L, "abc");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        ProxyDirectWire.writeSignedPayload(dos, redeem, Direction.REQUEST, "body", verifier, 1);

        assertNull(ProxyDirectWire.readSignedPayload(in(baos), cancel, Direction.REQUEST, verifier));
        assertEquals("body", ProxyDirectWire.readSignedPayload(in(baos), redeem, Direction.REQUEST, verifier));
        // Timestamp and nonce are covered too.
        assertNull(ProxyDirectWire.readSignedPayload(in(baos),
                new WireContext(ProxyDirectWire.OP_REDEEM, 5_001L, "abc"), Direction.REQUEST, verifier));
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: a response is bound to its request nonce and direction")
    void signedPayload_responseBoundToRequest() throws Exception {
        HmacVerifier verifier = HmacVerifier.forTesting(secret32(), 1, 1);
        WireContext reqA = new WireContext(ProxyDirectWire.OP_FIND_RESERVATION, 7_000L, "nonce-a");
        WireContext reqB = new WireContext(ProxyDirectWire.OP_FIND_RESERVATION, 7_000L, "nonce-b");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        ProxyDirectWire.writeSignedPayload(dos, reqA, Direction.RESPONSE, "reply", verifier, 1);

        assertEquals("reply", ProxyDirectWire.readSignedPayload(in(baos), reqA, Direction.RESPONSE, verifier));
        assertNull(ProxyDirectWire.readSignedPayload(in(baos), reqB, Direction.RESPONSE, verifier),
                "a reply to another request must not verify");
        assertNull(ProxyDirectWire.readSignedPayload(in(baos), reqA, Direction.REQUEST, verifier),
                "a reply must not verify as a request");
    }

    @Test
    void list_roundTrip_andCountBounds() throws Exception {
        HmacVerifier verifier = HmacVerifier.forTesting(secret32(), 1, 1);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        List<String> items = List.of("item1", "item2");
        ProxyDirectWire.writeList(dos, CTX, Direction.RESPONSE, items, verifier, 1);

        List<String> read = ProxyDirectWire.readList(in(baos), CTX, Direction.RESPONSE, verifier);
        assertEquals(items, read);

        // Invalid count
        baos.reset();
        dos.writeInt(-1);
        DataInputStream disNeg = in(baos);
        assertThrows(IOException.class, () -> ProxyDirectWire.readList(disNeg, CTX, Direction.RESPONSE, verifier));

        baos.reset();
        dos.writeInt(ProxyDirectWire.MAX_LIST_COUNT + 1);
        DataInputStream disBig = in(baos);
        assertThrows(IOException.class, () -> ProxyDirectWire.readList(disBig, CTX, Direction.RESPONSE, verifier));

        // Writer enforces the same cap.
        assertEquals(4096, ProxyDirectWire.MAX_LIST_COUNT);
        java.util.List<String> oversized = java.util.Collections.nCopies(ProxyDirectWire.MAX_LIST_COUNT + 1, "x");
        assertThrows(IOException.class,
                () -> ProxyDirectWire.writeList(dos, CTX, Direction.RESPONSE, oversized, verifier, 1));
    }

    /** Re-emits the signed rows of a written list in {@code order} under {@code count}. */
    private static byte[] reframe(byte[] list, int count, int... order) throws IOException {
        DataInputStream src = new DataInputStream(new ByteArrayInputStream(list));
        int n = src.readInt();
        int[] schema = new int[n];
        String[] mac = new String[n];
        String[] payload = new String[n];
        for (int i = 0; i < n; i++) {
            schema[i] = src.readInt();
            mac[i] = src.readUTF();
            payload[i] = src.readUTF();
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(count);
        for (int i : order) {
            dos.writeInt(schema[i]);
            dos.writeUTF(mac[i]);
            dos.writeUTF(payload[i]);
        }
        return baos.toByteArray();
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: reordered, dropped, duplicated rows or a tampered count fail the list")
    void list_rowReorderAndCountTamperRejected() throws Exception {
        HmacVerifier verifier = HmacVerifier.forTesting(secret32(), 1, 1);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        ProxyDirectWire.writeList(dos, CTX, Direction.RESPONSE, List.of("a", "b", "c"), verifier, 1);
        byte[] good = baos.toByteArray();

        assertEquals(List.of("a", "b", "c"), ProxyDirectWire.readList(
                new DataInputStream(new ByteArrayInputStream(reframe(good, 3, 0, 1, 2))), CTX, Direction.RESPONSE, verifier));
        int[][] tampered = {{1, 0, 2}, {0, 1}, {0, 0, 1}};
        int[] counts = {3, 2, 3};
        for (int t = 0; t < tampered.length; t++) {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(reframe(good, counts[t], tampered[t])));
            assertThrows(IOException.class, () -> ProxyDirectWire.readList(dis, CTX, Direction.RESPONSE, verifier),
                    "variant " + t + " must be rejected");
        }
        // A list signed for another request does not verify either.
        assertThrows(IOException.class, () -> ProxyDirectWire.readList(new DataInputStream(new ByteArrayInputStream(good)),
                new WireContext(ProxyDirectWire.OP_HEARTBEAT, 1_000L, "n-2"), Direction.RESPONSE, verifier));
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: freshness window is +/- MAX_CLOCK_SKEW_MS and overflow-safe")
    void isFresh_window() {
        long now = 1_700_000_000_000L;
        long skew = ProxyDirectWire.MAX_CLOCK_SKEW_MS;
        assertEquals(30_000L, skew);
        assertTrue(ProxyDirectWire.isFresh(now, now));
        assertTrue(ProxyDirectWire.isFresh(now - skew, now));
        assertTrue(ProxyDirectWire.isFresh(now + skew, now));
        assertFalse(ProxyDirectWire.isFresh(now - skew - 1, now));
        assertFalse(ProxyDirectWire.isFresh(now + skew + 1, now));
        assertFalse(ProxyDirectWire.isFresh(Long.MIN_VALUE, now));
        assertFalse(ProxyDirectWire.isFresh(Long.MAX_VALUE, now));
    }

    @Test
    @DisplayName("REQ-RTP-S-004: one HMAC-invalid row fails the whole list instead of a silent partial batch")
    void list_tamperedRow_failsWholeList() throws Exception {
        HmacVerifier verifier = HmacVerifier.forTesting(secret32(), 1, 1);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        ProxyDirectWire.writeList(dos, CTX, Direction.REQUEST, List.of("good-1", "x", "good-2"), verifier, 1);
        byte[] signed = baos.toByteArray();
        // Swap row 1 for a forged block, keep rows 0 and 2.
        DataInputStream src = new DataInputStream(new ByteArrayInputStream(signed));
        src.readInt();
        baos.reset();
        dos.writeInt(3);
        for (int i = 0; i < 3; i++) {
            int schema = src.readInt();
            String mac = src.readUTF();
            String payload = src.readUTF();
            dos.writeInt(schema);
            dos.writeUTF(i == 1 ? "00".repeat(32) : mac);
            dos.writeUTF(i == 1 ? "forged" : payload);
        }
        dos.writeByte(42); // trailing byte: proves every frame was consumed

        DataInputStream dis = in(baos);
        IOException ex = assertThrows(IOException.class,
                () -> ProxyDirectWire.readList(dis, CTX, Direction.REQUEST, verifier));
        assertTrue(ex.getMessage().contains("1 of 3"), ex.getMessage());
        assertEquals(42, dis.readByte());
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: null verifier fails closed - unsigned writes throw, every read is rejected")
    void nullVerifier_failsClosed() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        assertThrows(IllegalStateException.class,
                () -> ProxyDirectWire.writeSignedPayload(dos, CTX, Direction.REQUEST, "x", null, 1));

        // An unsigned frame (empty HMAC) is rejected by a null verifier...
        baos.reset();
        dos.writeInt(1);
        dos.writeUTF("");
        dos.writeUTF("payload");
        // ...and so is a correctly signed one: no verifier = no trust.
        ProxyDirectWire.writeSignedPayload(dos, CTX, Direction.REQUEST, "signed",
                HmacVerifier.forTesting(secret32(), 1, 1), 1);
        DataInputStream dis = in(baos);
        assertNull(ProxyDirectWire.readSignedPayload(dis, CTX, Direction.REQUEST, null));
        assertNull(ProxyDirectWire.readSignedPayload(dis, CTX, Direction.REQUEST, null),
                "frame must be consumed even when rejected");

        // A verifier also rejects the unsigned frame.
        baos.reset();
        dos.writeInt(1);
        dos.writeUTF("");
        dos.writeUTF("payload");
        assertNull(ProxyDirectWire.readSignedPayload(in(baos), CTX, Direction.REQUEST,
                HmacVerifier.forTesting(secret32(), 1, 1)));
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: token ids are redacted to an 8-char prefix for logs")
    void redactToken_prefixOnly() {
        assertEquals("12345678...", ProxyDirectWire.redactToken("1234567890abcdef"));
        assertEquals("short", ProxyDirectWire.redactToken("short"));
        assertEquals("null", ProxyDirectWire.redactToken(null));
    }

    @Test
    void tokenCodec_roundTrip_andMalformed() {
        UUID pid = UUID.randomUUID();
        ReservationToken tok = new ReservationToken("tok1", "srv1", pid, 9999L, ReservationToken.State.CLAIMED, "reg1");
        String encoded = ProxyDirectWire.encodeToken(tok);
        ReservationToken decoded = ProxyDirectWire.decodeToken(encoded);
        assertNotNull(decoded);
        assertEquals(tok.tokenId(), decoded.tokenId());
        assertEquals(tok.serverId(), decoded.serverId());
        assertEquals(tok.playerId(), decoded.playerId());
        assertEquals(tok.expiresEpochMs(), decoded.expiresEpochMs());
        assertEquals(tok.state(), decoded.state());
        assertEquals(tok.regionKey(), decoded.regionKey());

        assertNull(ProxyDirectWire.decodeToken(null));
        assertNull(ProxyDirectWire.decodeToken(""));
        assertNull(ProxyDirectWire.decodeToken("short\u0001field"));
        assertNull(ProxyDirectWire.decodeToken("bad\u0001bad\u0001not-a-uuid\u0001not-long\u0001UNKNOWN\u0001reg"));
    }

    @Test
    void envelopeCodec_roundTrip_andMalformed() {
        UUID pid = UUID.randomUUID();
        UUID cid = UUID.randomUUID();
        NetworkRequestQueue.EnrolmentEnvelope env = new NetworkRequestQueue.EnrolmentEnvelope(pid, cid, Optional.of("r1"), Optional.of("s1"), 5555L);
        String encoded = ProxyDirectWire.encodeEnvelope(env);
        NetworkRequestQueue.EnrolmentEnvelope decoded = ProxyDirectWire.decodeEnvelope(encoded);
        assertNotNull(decoded);
        assertEquals(env.playerId(), decoded.playerId());
        assertEquals(env.correlationId(), decoded.correlationId());
        assertEquals(env.regionKey(), decoded.regionKey());
        assertEquals(env.serverHint(), decoded.serverHint());
        assertEquals(env.createdAtMs(), decoded.createdAtMs());

        assertNull(ProxyDirectWire.decodeEnvelope(null));
        assertNull(ProxyDirectWire.decodeEnvelope(""));
        assertNull(ProxyDirectWire.decodeEnvelope("short\u0001data"));
        assertNull(ProxyDirectWire.decodeEnvelope("bad\u0001bad\u0001\u0001\u0001not-long"));
    }

    @Test
    void statusCodec_roundTrip_andMalformed() {
        UUID pid = UUID.randomUUID();
        NetworkRequestQueue.QueueStatus st = new NetworkRequestQueue.QueueStatus(
                pid, NetworkRequestQueue.QueueState.ROUTING, 3, Optional.of("s1"), Optional.of("r1"), 8888L);
        String encoded = ProxyDirectWire.encodeStatus(st);
        NetworkRequestQueue.QueueStatus decoded = ProxyDirectWire.decodeStatus(encoded);
        assertNotNull(decoded);
        assertEquals(st.playerId(), decoded.playerId());
        assertEquals(st.state(), decoded.state());
        assertEquals(st.positionInQueue(), decoded.positionInQueue());
        assertEquals(st.serverId(), decoded.serverId());
        assertEquals(st.regionKey(), decoded.regionKey());
        assertEquals(st.updatedAtMs(), decoded.updatedAtMs());

        assertNull(ProxyDirectWire.decodeStatus(null));
        assertNull(ProxyDirectWire.decodeStatus(""));
        assertNull(ProxyDirectWire.decodeStatus("short\u0001data"));
        assertNull(ProxyDirectWire.decodeStatus("bad\u0001UNKNOWN\u0001not-int\u0001\u0001\u0001not-long"));
    }
}
