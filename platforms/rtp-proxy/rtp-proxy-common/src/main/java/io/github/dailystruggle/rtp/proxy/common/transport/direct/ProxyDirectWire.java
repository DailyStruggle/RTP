package io.github.dailystruggle.rtp.proxy.common.transport.direct;

import io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared wire protocol for the {@code proxy-direct} transport tier
 * (rtp-proxy-ADR-017).
 *
 * <p>A backend opens its OWN short-lived outbound TCP connection to the proxy
 * companion - it does not ride a player's Minecraft plugin-message session - so
 * a player-empty backend can act as a stateless client of the proxy's shared
 * store. This makes {@code proxy-direct} simply "another data-management
 * transport like {@code redis}/{@code sql}": the proxy's in-memory
 * {@code NetworkTransport} + {@code NetworkRequestQueue} are the store, reached
 * over this socket instead of a Redis/JDBC server.</p>
 *
 * <p>Every connection is one RPC. Request header (wire v{@value #FRAME_VERSION}):
 * {@code [byte opcode][byte frameVersion][long sentAtMs][UTF nonce]}, then the
 * request body, then the response. A body is one signed block
 * {@code [int schemaVersion][UTF hmacHex][UTF payload]} or a count-framed list
 * {@code [int count]} + signed blocks. The opcode maps 1:1 to the SPI method invoked
 * <em>from a backend</em> (the only RPC direction; the proxy never dials a
 * backend). Proxy-local methods ({@code claim}, {@code dequeueReady},
 * {@code transition}, {@code release}, {@code reapExpired}) are never sent here -
 * they run on the proxy against its own store.</p>
 *
 * <p>Every payload is HMAC-signed (rtp-proxy-ADR-010); there is no unsigned
 * mode. The MAC covers direction, opcode, sentAtMs, nonce, row index and row
 * count (see {@link #canonical}); a response is signed with its request's
 * context, so it is bound to that request and list rows cannot be reordered,
 * dropped or duplicated. Freshness / nonce reuse are checked by the listener
 * after the MAC verifies ({@link #isFresh}, {@link ProxyDirectReplayCache}).
 * A {@code null} verifier fails closed: writers throw, readers reject
 * every payload. Verification failure yields {@code null} from
 * {@link #readSignedPayload}, never an exception - the caller drops the row
 * and logs at WARNING (REQ-RTP-S-004); {@link #readList} fails the whole list.</p>
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

    /** Every opcode a listener accepts; anything else is rejected before the body is read. */
    public static final Set<Byte> KNOWN_OPCODES = Set.of(OP_HEARTBEAT, OP_FLUSH_PENDING, OP_POLL_STATUS,
            OP_CANCEL, OP_FIND_RESERVATION, OP_REDEEM, OP_LIST_ACTIVE);

    /**
     * Request-header frame version. Independent of the config {@code schemaVersion}
     * (shared with other transports); proxy and backends must run the same frame version.
     */
    public static final int FRAME_VERSION = 2;

    /** Max |receiver clock - sentAtMs| accepted for a request (REQ-RTP-PROXY-007). */
    public static final long MAX_CLOCK_SKEW_MS = 30_000L;

    /** Max nonce length accepted in a request header (writers emit 32 hex chars). */
    public static final int MAX_NONCE_LENGTH = 64;

    /** Field delimiter inside a single multi-field payload string. */
    public static final char FS = '\u0001';

    /**
     * Upper bound on rows in one count-framed list. Sized well above any real
     * snapshot / batch (servers, queued players, active tokens per backend)
     * while capping per-connection allocation from a hostile count.
     */
    public static final int MAX_LIST_COUNT = 4096;

    /** Characters of a token id kept by {@link #redactToken}. */
    public static final int TOKEN_LOG_PREFIX = 8;

    private static final Logger LOG = Logger.getLogger(ProxyDirectWire.class.getName());
    /** Minimum gap between rejected-list WARNINGs (tamper / secret-mismatch flood guard). */
    private static final long REJECT_LOG_INTERVAL_MS = 10_000L;
    private static final AtomicLong lastRejectLogMs = new AtomicLong(Long.MIN_VALUE);
    private static final AtomicLong suppressedRejects = new AtomicLong();
    private static final SecureRandom NONCE_RANDOM = new SecureRandom();
    private static final int NONCE_BYTES = 16;

    private ProxyDirectWire() {
    }

    /** MAC direction tag: request (backend -> proxy) or response (proxy -> backend). */
    public enum Direction {
        REQUEST('Q'), RESPONSE('R');

        private final char tag;

        Direction(char tag) {
            this.tag = tag;
        }
    }

    /** Per-RPC MAC context: request and response blocks are all signed against it. */
    public record WireContext(byte opcode, long sentAtMs, String nonce) {
        public WireContext {
            Objects.requireNonNull(nonce, "nonce");
        }
    }

    /** Decoded request header; only produced for a known opcode at {@link #FRAME_VERSION}. */
    public record RequestHeader(byte opcode, int frameVersion, long sentAtMs, String nonce) {
        public WireContext context() {
            return new WireContext(opcode, sentAtMs, nonce);
        }
    }

    /** Request header refused before any body byte is read. */
    public static final class FrameRejectedException extends IOException {
        private static final long serialVersionUID = 1L;

        /** Why the header was refused. */
        public enum Reason { UNKNOWN_OPCODE, UNSUPPORTED_FRAME_VERSION, MALFORMED_HEADER }

        private final Reason reason;
        private final int value;

        public FrameRejectedException(Reason reason, int value, String message) {
            super(message);
            this.reason = reason;
            this.value = value;
        }

        public Reason reason() {
            return reason;
        }

        /** Offending opcode (unsigned) or frame version; {@code -1} when not applicable. */
        public int value() {
            return value;
        }
    }

    /** True for every opcode in {@link #KNOWN_OPCODES}. */
    public static boolean isKnownOpcode(byte opcode) {
        return KNOWN_OPCODES.contains(opcode);
    }

    /** True when {@code sentAtMs} lies within {@link #MAX_CLOCK_SKEW_MS} of {@code nowMs} (overflow-safe). */
    public static boolean isFresh(long sentAtMs, long nowMs) {
        return sentAtMs >= nowMs - MAX_CLOCK_SKEW_MS && sentAtMs <= nowMs + MAX_CLOCK_SKEW_MS;
    }

    /**
     * Log-safe form of a reservation token id: first {@value #TOKEN_LOG_PREFIX}
     * chars + ellipsis. A full id is a bearer value for redeem.
     */
    public static String redactToken(String tokenId) {
        if (tokenId == null) return "null";
        return tokenId.length() <= TOKEN_LOG_PREFIX ? tokenId : tokenId.substring(0, TOKEN_LOG_PREFIX) + "...";
    }

    // ---- request header ---------------------------------------------------

    /** Write a request header stamped with the wall clock; returns the context to sign against. */
    public static WireContext writeRequestHeader(DataOutputStream out, byte opcode) throws IOException {
        return writeRequestHeader(out, opcode, System::currentTimeMillis);
    }

    /**
     * Write {@code [opcode][frameVersion][sentAtMs][nonce]} with a fresh
     * 16-byte {@link SecureRandom} hex nonce.
     *
     * @throws IllegalArgumentException for an opcode outside {@link #KNOWN_OPCODES}
     */
    public static WireContext writeRequestHeader(DataOutputStream out, byte opcode, LongSupplier clock)
            throws IOException {
        if (!isKnownOpcode(opcode)) {
            throw new IllegalArgumentException("unknown proxy-direct opcode " + (opcode & 0xFF));
        }
        byte[] raw = new byte[NONCE_BYTES];
        NONCE_RANDOM.nextBytes(raw);
        WireContext ctx = new WireContext(opcode, clock.getAsLong(), HexFormat.of().formatHex(raw));
        out.writeByte(opcode);
        out.writeByte(FRAME_VERSION);
        out.writeLong(ctx.sentAtMs());
        out.writeUTF(ctx.nonce());
        return ctx;
    }

    /**
     * Read a request header. The opcode is checked first, then the frame
     * version, both before any further byte is consumed.
     *
     * @throws FrameRejectedException unknown opcode, frame version other than
     *                                {@link #FRAME_VERSION}, or an empty / oversized nonce
     */
    public static RequestHeader readRequestHeader(DataInputStream in) throws IOException {
        byte opcode = in.readByte();
        if (!isKnownOpcode(opcode)) {
            throw new FrameRejectedException(FrameRejectedException.Reason.UNKNOWN_OPCODE, opcode & 0xFF,
                    "unknown proxy-direct opcode " + (opcode & 0xFF));
        }
        int frameVersion = in.readUnsignedByte();
        if (frameVersion != FRAME_VERSION) {
            // v1 frames had no version byte: the schemaVersion int's high byte (0) lands here.
            int peer = frameVersion <= 1 ? 1 : frameVersion;
            throw new FrameRejectedException(FrameRejectedException.Reason.UNSUPPORTED_FRAME_VERSION, peer,
                    "peer speaks wire v" + peer + ", this node speaks v" + FRAME_VERSION
                            + "; upgrade proxy and backends together");
        }
        long sentAtMs = in.readLong();
        String nonce = in.readUTF();
        if (nonce.isEmpty() || nonce.length() > MAX_NONCE_LENGTH) {
            throw new FrameRejectedException(FrameRejectedException.Reason.MALFORMED_HEADER, -1,
                    "proxy-direct request nonce length out of range: " + nonce.length());
        }
        return new RequestHeader(opcode, frameVersion, sentAtMs, nonce);
    }

    /**
     * MAC input for one block (passed to {@code HmacVerifier.sign/verify} as the canonical payload):
     * {@code pd2|dir|opcode|sentAtMs|nonce|index|count|payload}. Single payloads use index 0, count 1.
     */
    static String canonical(Direction dir, WireContext ctx, int index, int count, String payload) {
        return "pd2|" + dir.tag + '|' + (ctx.opcode() & 0xFF) + '|' + ctx.sentAtMs() + '|' + ctx.nonce()
                + '|' + index + '|' + count + '|' + payload;
    }

    // ---- single signed payload -------------------------------------------

    /**
     * Write one schema-tagged, HMAC-signed payload string bound to {@code ctx} / {@code dir}.
     *
     * @throws IllegalStateException when {@code verifier} is null (no unsigned mode)
     */
    public static void writeSignedPayload(DataOutputStream out, WireContext ctx, Direction dir, String payload,
                                          HmacVerifier verifier, int schemaVersion) throws IOException {
        writeBlock(out, new BlockSlot(ctx, dir, 0, 1), payload, verifier, schemaVersion);
    }

    /**
     * Read one schema-tagged payload and verify its HMAC against {@code ctx} /
     * {@code dir}. Returns {@code null} on verification failure or when
     * {@code verifier} is null (fail closed: the frame is still consumed so
     * stream framing stays intact); throws only on a genuine stream/IO error.
     */
    public static String readSignedPayload(DataInputStream in, WireContext ctx, Direction dir,
                                           HmacVerifier verifier) throws IOException {
        return readBlock(in, ctx, dir, 0, 1, verifier);
    }

    /** MAC binding of one block: frame context, direction and position within its list. */
    private record BlockSlot(WireContext ctx, Direction dir, int index, int count) {}

    private static void writeBlock(DataOutputStream out, BlockSlot slot, String payload,
                                   HmacVerifier verifier, int schemaVersion) throws IOException {
        if (verifier == null) {
            throw new IllegalStateException(
                    "proxy-direct requires an HmacVerifier (network.secretEnv); refusing to send unsigned payload");
        }
        Objects.requireNonNull(slot.ctx(), "ctx");
        Objects.requireNonNull(slot.dir(), "dir");
        String p = payload == null ? "" : payload;
        out.writeInt(schemaVersion);
        out.writeUTF(verifier.sign(schemaVersion, canonical(slot.dir(), slot.ctx(), slot.index(), slot.count(), p)));
        out.writeUTF(p);
    }

    private static String readBlock(DataInputStream in, WireContext ctx, Direction dir, int index, int count,
                                    HmacVerifier verifier) throws IOException {
        int schemaVersion = in.readInt();
        String hmacHex = in.readUTF();
        String payload = in.readUTF();
        if (verifier == null || ctx == null || dir == null
                || !verifier.verify(schemaVersion, canonical(dir, ctx, index, count, payload), hmacHex)) {
            return null;
        }
        return payload;
    }

    // ---- count-framed list of signed payloads ----------------------------

    /** Write a list: a count, then one signed block per row bound to its index and the count. */
    public static void writeList(DataOutputStream out, WireContext ctx, Direction dir, List<String> payloads,
                                 HmacVerifier verifier, int schemaVersion) throws IOException {
        int count = payloads.size();
        if (count > MAX_LIST_COUNT) {
            throw new IOException("proxy-direct list too large: " + count
                    + " > " + MAX_LIST_COUNT);
        }
        out.writeInt(count);
        for (int i = 0; i < count; i++) {
            writeBlock(out, new BlockSlot(ctx, dir, i, count), payloads.get(i), verifier, schemaVersion);
        }
        out.flush();
    }

    /**
     * Read a count-framed list. Any row failing HMAC verification fails the
     * whole list with an {@link IOException} (WARNING logged, throttled): a
     * partial batch would otherwise pass as success (partial enrolment, missing
     * status rows) and hide tampering or a secret mismatch (REQ-RTP-S-004).
     * Every frame is consumed first so the error is not a framing artefact.
     */
    public static List<String> readList(DataInputStream in, WireContext ctx, Direction dir,
                                        HmacVerifier verifier) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_LIST_COUNT) {
            throw new IOException("proxy-direct list count out of range: " + count);
        }
        List<String> out = new ArrayList<>(count);
        int rejected = 0;
        for (int i = 0; i < count; i++) {
            String p = readBlock(in, ctx, dir, i, count, verifier);
            if (p != null) out.add(p);
            else rejected++;
        }
        if (rejected > 0) {
            String msg = "proxy-direct: " + rejected + " of " + count + " list row(s) failed HMAC "
                    + "verification (tampering or network.secretEnv mismatch); rejecting the list";
            logRejectedList(msg);
            throw new IOException(msg);
        }
        return out;
    }

    private static void logRejectedList(String msg) {
        long now = System.currentTimeMillis();
        long last = lastRejectLogMs.get();
        if ((last == Long.MIN_VALUE || now - last >= REJECT_LOG_INTERVAL_MS)
                && lastRejectLogMs.compareAndSet(last, now)) {
            long suppressed = suppressedRejects.getAndSet(0L);
            LOG.log(Level.WARNING, msg + (suppressed > 0 ? " (" + suppressed + " similar suppressed)" : "")
                    + " (REQ-RTP-S-004)");
        } else {
            suppressedRejects.incrementAndGet();
        }
    }

    /**
     * Split a multi-field payload on {@link #FS}, keeping trailing empty fields:
     * identical to {@code payload.split(String.valueOf(FS), -1)} without the regex dispatch.
     */
    public static String[] splitFields(String payload) {
        List<String> out = new ArrayList<>();
        int from = 0;
        int at;
        while ((at = payload.indexOf(FS, from)) >= 0) {
            out.add(payload.substring(from, at));
            from = at + 1;
        }
        out.add(payload.substring(from));
        return out.toArray(new String[0]);
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
        String[] f = splitFields(payload);
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
        String[] f = splitFields(payload);
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
        String[] f = splitFields(payload);
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
