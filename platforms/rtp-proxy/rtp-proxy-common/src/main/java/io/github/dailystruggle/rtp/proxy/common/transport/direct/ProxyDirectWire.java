package io.github.dailystruggle.rtp.proxy.common.transport.direct;

import io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared wire protocol for the {@code proxy-direct} transport tier
 * (rtp-proxy-ADR-017, refined by PROPOSAL-proxy-direct-as-remote-store).
 *
 * <p>A backend opens its OWN short-lived outbound TCP connection to the proxy
 * companion - it does not ride a player's Minecraft plugin-message session - so
 * a player-empty backend can act as a stateless client of the proxy's shared
 * store. This makes {@code proxy-direct} simply "another data-management
 * transport like {@code redis}/{@code sql}": the proxy's in-memory
 * {@code NetworkTransport} + {@code NetworkRequestQueue} are the store, reached
 * over this socket instead of a Redis/JDBC server.</p>
 *
 * <p>Every connection is one RPC: {@code byte opcode}, one signed request
 * payload, then a response (a single signed payload, or a count-framed list of
 * signed payloads). The opcode maps 1:1 to the SPI method invoked
 * <em>from a backend</em> (the only RPC direction; the proxy never dials a
 * backend). Proxy-local methods ({@code claim}, {@code dequeueReady},
 * {@code transition}, {@code release}, {@code reapExpired}) are never sent here -
 * they run on the proxy against its own store.</p>
 *
 * <p>Payloads are HMAC-signed when a {@link HmacVerifier} is supplied
 * (rtp-proxy-ADR-010), unsigned otherwise. Verification failure yields
 * {@code null} from {@link #readSignedPayload}, never an exception - the caller
 * drops the row and logs at WARNING (REQ-RTP-S-004).</p>
 *
 * <p>Pure I/O glue, no threading; the proxy listener thread and the backend's
 * scheduler-driven calls own their own sockets.</p>
 */
public final class ProxyDirectWire {

    /**
     * Publish this backend's heartbeat and read back the proxy's merged
     * availability snapshot in one round trip. Request: one
     * {@code BackendHeartbeatCodec} payload. Response: count-framed snapshot
     * rows. This is region discovery and feeds {@code /rtp region=}
     * tab-completion (the historical {@code OP_PUSH}).
     */
    public static final byte OP_HEARTBEAT = 1;

    /**
     * {@code NetworkRequestQueue.flushPending(batch)}: ship a batch of
     * enrolments to the proxy's queue. Request: count-framed encoded
     * envelopes. Response: one payload = {@link NetworkRequestQueue.EnrolOutcome}
     * name.
     */
    public static final byte OP_FLUSH_PENDING = 2;

    /**
     * {@code NetworkRequestQueue.pollStatus(ids)}. Request: count-framed UUID
     * strings. Response: count-framed encoded {@link NetworkRequestQueue.QueueStatus}.
     */
    public static final byte OP_POLL_STATUS = 3;

    /**
     * {@code NetworkRequestQueue.cancel(playerId, reason)}. Request:
     * {@code playerId\u0001reason}. Response: one payload = {@code "OK"}.
     */
    public static final byte OP_CANCEL = 4;

    /**
     * {@code NetworkTransport.findReservation(playerId)}. Request: a UUID
     * string. Response: one payload = encoded token, or {@code ""} when no
     * active reservation exists for the player.
     */
    public static final byte OP_FIND_RESERVATION = 5;

    /**
     * {@code NetworkTransport.redeem(tokenId, playerId, expectedServerId)}.
     * Request: {@code tokenId\u0001playerId\u0001expectedServerId}. Response:
     * one payload = {@code RedeemOutcome} name.
     */
    public static final byte OP_REDEEM = 6;

    /**
     * {@code NetworkTransport.listActiveForServer(serverId)}. Request: a server
     * id string. Response: count-framed encoded tokens.
     */
    public static final byte OP_LIST_ACTIVE = 7;

    /** Field delimiter inside a single multi-field payload string. */
    public static final char FS = '\u0001';

    private ProxyDirectWire() {
    }

    // ---- opcode -----------------------------------------------------------

    /** Write the one-byte RPC opcode. */
    public static void writeOpcode(DataOutputStream out, byte opcode) throws IOException {
        out.writeByte(opcode);
    }

    /** Read the one-byte RPC opcode. */
    public static byte readOpcode(DataInputStream in) throws IOException {
        return in.readByte();
    }

    // ---- single signed payload -------------------------------------------

    /** Write one schema-tagged, optionally HMAC-signed payload string. */
    public static void writeSignedPayload(DataOutputStream out, String payload,
                                          HmacVerifier verifier, int schemaVersion) throws IOException {
        String p = payload == null ? "" : payload;
        out.writeInt(schemaVersion);
        out.writeUTF(verifier == null ? "" : verifier.sign(schemaVersion, p));
        out.writeUTF(p);
    }

    /**
     * Read one schema-tagged payload, verifying its HMAC when {@code verifier}
     * is non-null. Returns {@code null} on a verification failure (caller drops
     * + logs); throws only on a genuine stream/IO error.
     */
    public static String readSignedPayload(DataInputStream in, HmacVerifier verifier) throws IOException {
        int schemaVersion = in.readInt();
        String hmacHex = in.readUTF();
        String payload = in.readUTF();
        if (verifier != null && !verifier.verify(schemaVersion, payload, hmacHex)) {
            return null;
        }
        return payload;
    }

    // ---- count-framed list of signed payloads ----------------------------

    /** Write a list response/request: a count followed by one signed payload per row. */
    public static void writeList(DataOutputStream out, List<String> payloads,
                                 HmacVerifier verifier, int schemaVersion) throws IOException {
        out.writeInt(payloads.size());
        for (String p : payloads) {
            writeSignedPayload(out, p, verifier, schemaVersion);
        }
        out.flush();
    }

    /**
     * Read a count-framed list. Rows that fail HMAC verification are dropped
     * (omitted from the returned list) rather than aborting the whole read.
     */
    public static List<String> readList(DataInputStream in, HmacVerifier verifier) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 100_000) {
            throw new IOException("proxy-direct list count out of range: " + count);
        }
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String p = readSignedPayload(in, verifier);
            if (p != null) out.add(p);
        }
        return out;
    }

    // Back-compat aliases retained for the heartbeat/snapshot exchange.

    /** @deprecated use {@link #writeList}; kept for the heartbeat snapshot reply. */
    @Deprecated
    public static void writeSnapshot(DataOutputStream out, List<String> payloads,
                                     HmacVerifier verifier, int schemaVersion) throws IOException {
        writeList(out, payloads, verifier, schemaVersion);
    }

    /** @deprecated use {@link #readList}; kept for the heartbeat snapshot reply. */
    @Deprecated
    public static List<String> readSnapshot(DataInputStream in, HmacVerifier verifier) throws IOException {
        return readList(in, verifier);
    }

    // ---- structured record codecs ----------------------------------------

    /**
     * Encode a {@link ReservationToken}:
     * {@code tokenId FS serverId FS playerId FS expiresEpochMs FS state FS regionKey}.
     */
    public static String encodeToken(ReservationToken t) {
        return t.tokenId() + FS
                + t.serverId() + FS
                + t.playerId() + FS
                + t.expiresEpochMs() + FS
                + t.state().name() + FS
                + t.regionKey().orElse("");
    }

    /** Decode a token payload; {@code null}/empty -> {@code null} (no reservation). */
    public static ReservationToken decodeToken(String payload) {
        if (payload == null || payload.isEmpty()) return null;
        String[] f = payload.split(String.valueOf(FS), -1);
        if (f.length < 6) return null;
        try {
            String regionKey = f[5].isEmpty() ? null : f[5];
            return new ReservationToken(
                    f[0], f[1], UUID.fromString(f[2]),
                    Long.parseLong(f[3]),
                    ReservationToken.State.valueOf(f[4]),
                    regionKey);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Encode an {@link NetworkRequestQueue.EnrolmentEnvelope}:
     * {@code playerId FS correlationId FS regionKey FS serverHint FS createdAtMs}.
     */
    public static String encodeEnvelope(NetworkRequestQueue.EnrolmentEnvelope e) {
        return e.playerId().toString() + FS
                + e.correlationId() + FS
                + e.regionKey().orElse("") + FS
                + e.serverHint().orElse("") + FS
                + e.createdAtMs();
    }

    /** Decode an enrolment envelope; returns {@code null} when malformed. */
    public static NetworkRequestQueue.EnrolmentEnvelope decodeEnvelope(String payload) {
        if (payload == null || payload.isEmpty()) return null;
        String[] f = payload.split(String.valueOf(FS), -1);
        if (f.length < 5) return null;
        try {
            Optional<String> regionKey = f[2].isEmpty() ? Optional.empty() : Optional.of(f[2]);
            Optional<String> serverHint = f[3].isEmpty() ? Optional.empty() : Optional.of(f[3]);
            return new NetworkRequestQueue.EnrolmentEnvelope(
                    UUID.fromString(f[0]), UUID.fromString(f[1]),
                    regionKey, serverHint, Long.parseLong(f[4]));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Encode a {@link NetworkRequestQueue.QueueStatus}:
     * {@code playerId FS state FS positionInQueue FS serverId FS regionKey FS updatedAtMs}.
     */
    public static String encodeStatus(NetworkRequestQueue.QueueStatus s) {
        return s.playerId().toString() + FS
                + s.state().name() + FS
                + s.positionInQueue() + FS
                + s.serverId().orElse("") + FS
                + s.regionKey().orElse("") + FS
                + s.updatedAtMs();
    }

    /** Decode a queue-status payload; returns {@code null} when malformed. */
    public static NetworkRequestQueue.QueueStatus decodeStatus(String payload) {
        if (payload == null || payload.isEmpty()) return null;
        String[] f = payload.split(String.valueOf(FS), -1);
        if (f.length < 6) return null;
        try {
            Optional<String> serverId = f[3].isEmpty() ? Optional.empty() : Optional.of(f[3]);
            Optional<String> regionKey = f[4].isEmpty() ? Optional.empty() : Optional.of(f[4]);
            return new NetworkRequestQueue.QueueStatus(
                    UUID.fromString(f[0]),
                    NetworkRequestQueue.QueueState.valueOf(f[1]),
                    Integer.parseInt(f[2]), serverId, regionKey,
                    Long.parseLong(f[5]));
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
