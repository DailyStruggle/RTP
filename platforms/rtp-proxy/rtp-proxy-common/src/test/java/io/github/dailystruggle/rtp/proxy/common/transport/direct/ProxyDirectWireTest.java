package io.github.dailystruggle.rtp.proxy.common.transport.direct;

import io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
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

    @Test
    void opcode_roundTrip() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        ProxyDirectWire.writeOpcode(dos, ProxyDirectWire.OP_HEARTBEAT);

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        assertEquals(ProxyDirectWire.OP_HEARTBEAT, ProxyDirectWire.readOpcode(dis));
    }

    @Test
    void signedPayload_roundTrip_andTamperDetection() throws Exception {
        HmacVerifier verifier = HmacVerifier.forTesting(secret32(), 1, 1);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        ProxyDirectWire.writeSignedPayload(dos, "hello world", verifier, 1);

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        String read = ProxyDirectWire.readSignedPayload(dis, verifier);
        assertEquals("hello world", read);

        // Tamper verification failure yields null
        baos.reset();
        ProxyDirectWire.writeSignedPayload(dos, "payload", verifier, 1);
        // Instead of breaking writeUTF framing, write a valid UTF string with a mismatched HMAC
        baos.reset();
        dos.writeInt(1); // schemaVersion
        dos.writeUTF("00".repeat(32)); // wrong HMAC
        dos.writeUTF("payload");
        dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        assertNull(ProxyDirectWire.readSignedPayload(dis, verifier));
    }

    @Test
    void list_roundTrip_andSnapshotAliases() throws Exception {
        HmacVerifier verifier = HmacVerifier.forTesting(secret32(), 1, 1);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        List<String> items = List.of("item1", "item2");
        ProxyDirectWire.writeList(dos, items, verifier, 1);

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        List<String> read = ProxyDirectWire.readList(dis, verifier);
        assertEquals(items, read);

        // Deprecated aliases
        baos.reset();
        ProxyDirectWire.writeSnapshot(dos, items, verifier, 1);
        dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        assertEquals(items, ProxyDirectWire.readSnapshot(dis, verifier));

        // Invalid count
        baos.reset();
        dos.writeInt(-1);
        DataInputStream disNeg = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        assertThrows(IOException.class, () -> ProxyDirectWire.readList(disNeg, verifier));

        baos.reset();
        dos.writeInt(ProxyDirectWire.MAX_LIST_COUNT + 1);
        DataInputStream disBig = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        assertThrows(IOException.class, () -> ProxyDirectWire.readList(disBig, verifier));

        // Writer enforces the same cap.
        assertEquals(4096, ProxyDirectWire.MAX_LIST_COUNT);
        java.util.List<String> oversized = java.util.Collections.nCopies(ProxyDirectWire.MAX_LIST_COUNT + 1, "x");
        assertThrows(IOException.class, () -> ProxyDirectWire.writeList(dos, oversized, verifier, 1));
    }

    @Test
    @DisplayName("REQ-RTP-S-004: one HMAC-invalid row fails the whole list instead of a silent partial batch")
    void list_tamperedRow_failsWholeList() throws Exception {
        HmacVerifier verifier = HmacVerifier.forTesting(secret32(), 1, 1);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(3);
        ProxyDirectWire.writeSignedPayload(dos, "good-1", verifier, 1);
        dos.writeInt(1);
        dos.writeUTF("00".repeat(32));
        dos.writeUTF("forged");
        ProxyDirectWire.writeSignedPayload(dos, "good-2", verifier, 1);
        dos.writeByte(42); // trailing byte: proves every frame was consumed

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        IOException ex = assertThrows(IOException.class, () -> ProxyDirectWire.readList(dis, verifier));
        assertTrue(ex.getMessage().contains("1 of 3"), ex.getMessage());
        assertEquals(42, dis.readByte());
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: null verifier fails closed - unsigned writes throw, every read is rejected")
    void nullVerifier_failsClosed() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        assertThrows(IllegalStateException.class,
                () -> ProxyDirectWire.writeSignedPayload(dos, "x", null, 1));

        // A legacy unsigned frame (empty HMAC) is rejected by a null verifier...
        baos.reset();
        dos.writeInt(1);
        dos.writeUTF("");
        dos.writeUTF("payload");
        // ...and so is a correctly signed one: no verifier = no trust.
        ProxyDirectWire.writeSignedPayload(dos, "signed", HmacVerifier.forTesting(secret32(), 1, 1), 1);
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        assertNull(ProxyDirectWire.readSignedPayload(dis, null));
        assertNull(ProxyDirectWire.readSignedPayload(dis, null), "frame must be consumed even when rejected");

        // A verifier also rejects the unsigned legacy frame.
        baos.reset();
        dos.writeInt(1);
        dos.writeUTF("");
        dos.writeUTF("payload");
        dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        assertNull(ProxyDirectWire.readSignedPayload(dis, HmacVerifier.forTesting(secret32(), 1, 1)));
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
