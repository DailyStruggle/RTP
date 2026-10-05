package io.github.dailystruggle.rtp.common.commands.editor.channel;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.editor.EditorLoopbackJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Signed two-way editor channel (ADR-106 §5), independent of its {@link ChannelTransport}.
 *
 * <p>Frames are the LuckPerms envelope {@code {"msg":"<JSON string>","signature":"<base64>"}},
 * SHA256withRSA over the UTF-8 bytes of {@code msg}. The inner message is
 * {@code {type, channel, seq, from, challenge?, to?, ...}}: {@code channel} pins it to this session,
 * {@code from} is the sender's key fingerprint, {@code seq} increases strictly per sender.
 *
 * <p>Handshake: the page sends {@code hello {publicKey, hid}} signed with that key; the reply
 * {@code hello-reply {to, hid, state, challenge, nonce?}} carries a fresh 128-bit challenge that every
 * later page message must carry, with {@code seq} from 1. An untrusted key gets a single-use 8-character
 * nonce and one operator prompt per nonce lifetime; {@code /rtp editor trust nonce=<nonce>} trusts it.
 * From untrusted keys only {@code hello} and {@code ping} are accepted; anything else needs a trusted
 * key, a valid signature, a live challenge and a higher {@code seq}, and is routed by {@code type}.
 * Forged, replayed, unknown-type and oversized frames are dropped and logged, one line per key per minute.
 *
 * <p>Caps (§5.5): {@link #MAX_FRAME_BYTES} per frame both ways; {@link #OUTBOUND_BYTES_PER_MINUTE}
 * per session, over which {@link #DROPPABLE} pushes are skipped (the next one supersedes them) while
 * replies still go out. Inbound work runs on the transport's thread; handlers must not block.
 */
public final class EditorChannel {

    public static final int MAX_FRAME_BYTES = 32 * 1024;
    public static final long OUTBOUND_BYTES_PER_MINUTE = 2L * 1024 * 1024;
    public static final long NONCE_TTL_MILLIS = 5L * 60L * 1000L;
    /** Room an envelope needs around its message: signature, header fields, worst-case {@code to}. */
    static final int ENVELOPE_OVERHEAD = 1024;
    static final int MAX_KEYS = 16;
    static final int MAX_CHALLENGES_PER_KEY = 4;
    static final long LOG_INTERVAL_MILLIS = 60_000L;
    static final String NONCE_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    static final int NONCE_LENGTH = 8;
    /** Pushes the next push replaces; skipped first when the outbound budget runs out. */
    static final Set<String> DROPPABLE = Set.of("feed", "land", "hazard-delta");
    /** Deferred first (§5.5): skipped once this share of the minute's budget is used. */
    static final String FIRST_DEFERRED = "land";
    static final double FIRST_DEFERRED_SHARE = 0.75;
    /** Fields the channel itself adds to every outbound message (hello-reply's challenge is body). */
    private static final Set<String> HEADER_KEYS = Set.of("channel", "seq", "from", "to");
    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9_\\-]{0,31}");
    /** Relay keys are short (bytesocks' key length is configurable); local ids are 32 hex. */
    private static final Pattern CHANNEL_ID = Pattern.compile("[A-Za-z0-9_\\-]{4,64}");
    private static final Pattern HELLO_ID = Pattern.compile("[A-Za-z0-9]{1,32}");
    private static final Pattern NONCE = Pattern.compile("[" + NONCE_ALPHABET + "]{" + NONCE_LENGTH + "}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<EditorChannel> OPEN = ConcurrentHashMap.newKeySet();

    /** Receives one verified message of a registered type from a trusted key. */
    @FunctionalInterface
    public interface Handler {
        void handle(Inbound in);
    }

    /** Operator-facing notices (configurable messages, REQ-RTP-F-013); any thread. */
    public interface Notifier {
        /** An untrusted browser key said hello: ask the session's operator to run the trust command. */
        void trustPrompt(String nonce, String fingerprint);

        default void opened() {
        }

        default void closed(String reason) {
        }
    }

    /** A verified page message: its type, sender fingerprint, parsed body and raw JSON. */
    public record Inbound(EditorChannel channel, String type, String from, Map<String, Object> body, String json) {
        /** Sends {@code bodyJson} (an object with its own {@code type}) to this sender only. */
        public boolean reply(String bodyJson) {
            return channel.send(bodyJson, from);
        }
    }

    public enum TrustResult { TRUSTED, ALREADY_TRUSTED, EXPIRED, UNKNOWN }

    /** One browser key seen in a hello: its live challenges (to last seq) and pending nonce. */
    private static final class Peer {
        final String fingerprint;
        final PublicKey key;
        final LinkedHashMap<String, Long> challenges = new LinkedHashMap<>();
        String nonce;
        long nonceExpiresAt;
        long lastSeen;

        Peer(String fingerprint, PublicKey key) {
            this.fingerprint = fingerprint;
            this.key = key;
        }
    }

    private final String id;
    private final EditorKeys keys;
    private final TrustedEditors trusted;
    private final ChannelTransport transport;
    private final Notifier notifier;
    private final LongSupplier clock;
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    private final Map<String, Peer> peers = new LinkedHashMap<>();
    private final Map<String, Long> lastLogged = new LinkedHashMap<>();
    private final Object lock = new Object();
    private final Object sendLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();
    private long outSeq;
    private long windowStart;
    private long windowBytes;
    private volatile String lastDropReason;
    private volatile String closeReason;

    public EditorChannel(String id, EditorKeys keys, TrustedEditors trusted, ChannelTransport transport,
                         Notifier notifier, LongSupplier clock) {
        if (id == null || !CHANNEL_ID.matcher(id).matches()) throw new IllegalArgumentException("bad channel id");
        this.id = id;
        this.keys = Objects.requireNonNull(keys, "keys");
        this.trusted = Objects.requireNonNull(trusted, "trusted");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 128-bit hex id for transports that do not assign one (loopback, in-memory). */
    public static String newChannelId() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /**
     * Opens the transport; from then on the channel takes frames and can be trusted through
     * {@link #trustAny(String)}. Never blocks: completes when the transport is open (at once for the
     * loopback and in-memory ones), exceptionally with the transport's {@link java.io.IOException}.
     */
    public CompletableFuture<EditorChannel> start() {
        return transport.start(new ChannelTransport.Listener() {
            @Override
            public void onFrame(String text) {
                EditorChannel.this.onFrame(text);
            }

            @Override
            public void onPeerOpen() {
                RTP.log(Level.FINE, "[editor] channel " + shortId() + ": page connected");
            }

            @Override
            public void onClosed(String reason) {
                closed(reason);
            }

            @Override
            public void onExpired(String reason) {
                close(reason); // signed bye first, so pages stop instead of reconnecting
            }
        }).thenApply(v -> opened());
    }

    private EditorChannel opened() {
        OPEN.add(this);
        RTP.log(Level.INFO, "[editor] channel " + shortId() + " open (" + transport.getClass().getSimpleName()
                + ", key " + keys.fingerprint().substring(0, 16) + ")");
        try {
            notifier.opened();
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING, "[editor] channel opened notice failed: " + e.getMessage(), e);
        }
        return this;
    }

    /** Routes verified, trusted messages of {@code type} to {@code handler} (one per type). */
    public EditorChannel register(String type, Handler handler) {
        if (type == null || !TYPE.matcher(type).matches()) throw new IllegalArgumentException("bad type " + type);
        if ("hello".equals(type) || "ping".equals(type)) throw new IllegalArgumentException(type + " is built in");
        handlers.put(type, Objects.requireNonNull(handler, "handler"));
        return this;
    }

    /** The snapshot's {@code channel} block: {@code {relay, id, pluginKey}}. */
    public Map<String, Object> snapshotBlock() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("relay", transport.relay());
        m.put("id", id);
        m.put("pluginKey", keys.publicKeyBase64());
        return m;
    }

    // ---- inbound ----

    void onFrame(String text) {
        if (closed.get() || text == null) return;
        if (utf8Length(text) > MAX_FRAME_BYTES) {
            drop("?", "frame over " + (MAX_FRAME_BYTES >> 10) + " KiB");
            return;
        }
        String msgText;
        byte[] signature;
        Map<String, Object> msg;
        try {
            if (!(EditorLoopbackJson.parse(text) instanceof Map<?, ?> env) || env.size() != 2
                    || !(env.get("msg") instanceof String m) || !(env.get("signature") instanceof String s)) {
                drop("?", "not a signed envelope");
                return;
            }
            msgText = m;
            signature = Base64.getDecoder().decode(s);
            msg = object(EditorLoopbackJson.parse(msgText));
        } catch (IllegalArgumentException e) {
            drop("?", "malformed frame: " + e.getMessage());
            return;
        }
        if (msg == null || !(msg.get("type") instanceof String type) || !TYPE.matcher(type).matches()) {
            drop("?", "message without a valid type");
            return;
        }
        if (!(msg.get("from") instanceof String from) || !TrustedEditors.FINGERPRINT.matcher(from).matches()) {
            drop("?", "message without a sender fingerprint");
            return;
        }
        if (from.equals(keys.fingerprint())) return; // own frame echoed by a relay
        if (!id.equals(msg.get("channel"))) {
            drop(from, "message for another channel");
            return;
        }
        byte[] signed = msgText.getBytes(StandardCharsets.UTF_8);
        Handler handler;
        synchronized (lock) {
            long now = clock.getAsLong();
            if ("hello".equals(type)) {
                hello(from, msg, signed, signature, now);
                return;
            }
            Peer p = peers.get(from);
            if (p == null) {
                drop(from, type + " before hello");
                return;
            }
            if (!EditorKeys.verify(p.key, signed, signature)) {
                drop(from, "bad signature on " + type);
                return;
            }
            Long last = msg.get("challenge") instanceof String c ? p.challenges.get(c) : null;
            if (last == null) {
                drop(from, type + " with an unknown or retired challenge");
                return;
            }
            if (!(msg.get("seq") instanceof Long seq) || seq <= last) {
                drop(from, type + " replayed or out of order");
                return;
            }
            p.challenges.put((String) msg.get("challenge"), seq);
            p.lastSeen = now;
            if ("ping".equals(type)) {
                send("{\"type\":\"pong\"}", from);
                return;
            }
            if (!trusted.isTrusted(from)) {
                drop(from, type + " from an untrusted key");
                return;
            }
            handler = handlers.get(type);
            if (handler == null) {
                drop(from, "unknown type " + type);
                return;
            }
        }
        try {
            handler.handle(new Inbound(this, type, from, msg, msgText));
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING, "[editor] channel handler for '" + type + "' failed: " + e.getMessage(), e);
        }
    }

    private void hello(String from, Map<String, Object> msg, byte[] signed, byte[] signature, long now) {
        PublicKey key;
        try {
            key = EditorKeys.decodePublicKey(msg.get("publicKey") instanceof String k ? k : null);
        } catch (IllegalArgumentException e) {
            drop(from, "hello with a bad key: " + e.getMessage());
            return;
        }
        if (!EditorKeys.fingerprint(key).equals(from)) {
            drop(from, "hello key does not match its fingerprint");
            return;
        }
        if (!EditorKeys.verify(key, signed, signature)) {
            drop(from, "bad signature on hello");
            return;
        }
        String hid = msg.get("hid") instanceof String h && HELLO_ID.matcher(h).matches() ? h : null;
        Peer p = peers.get(from);
        if (p == null) {
            if (peers.size() >= MAX_KEYS) evictOldestPeer();
            p = new Peer(from, key);
            peers.put(from, p);
        }
        p.lastSeen = now;
        String challenge = randomHex(16);
        p.challenges.put(challenge, 0L);
        while (p.challenges.size() > MAX_CHALLENGES_PER_KEY) p.challenges.remove(p.challenges.keySet().iterator().next());

        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("type", "hello-reply");
        if (hid != null) reply.put("hid", hid);
        reply.put("challenge", challenge);
        if (trusted.isTrusted(from)) {
            reply.put("state", "trusted");
        } else {
            boolean fresh = p.nonce == null || now >= p.nonceExpiresAt;
            if (fresh) {
                p.nonce = newNonce();
                p.nonceExpiresAt = now + NONCE_TTL_MILLIS;
            }
            reply.put("state", "untrusted");
            reply.put("nonce", p.nonce);
            reply.put("nonceExpiresAt", p.nonceExpiresAt);
            if (fresh) {
                RTP.log(Level.INFO, "[editor] channel " + shortId() + ": untrusted editor key " + from.substring(0, 16)
                        + " is waiting for /rtp editor trust nonce=" + p.nonce);
                try {
                    notifier.trustPrompt(p.nonce, from);
                } catch (RuntimeException e) {
                    RTP.log(Level.WARNING, "[editor] trust prompt failed: " + e.getMessage(), e);
                }
            }
        }
        send(json(reply), from);
    }

    private void evictOldestPeer() {
        Iterator<Map.Entry<String, Peer>> it = peers.entrySet().iterator();
        String oldest = null;
        long seen = Long.MAX_VALUE;
        while (it.hasNext()) {
            Map.Entry<String, Peer> e = it.next();
            if (e.getValue().lastSeen < seen) {
                seen = e.getValue().lastSeen;
                oldest = e.getKey();
            }
        }
        if (oldest != null) peers.remove(oldest);
    }

    // ---- trust ----

    /**
     * Trusts the browser key that was given {@code nonce} in this session and tells its pages.
     * A nonce is single-use and expires after {@link #NONCE_TTL_MILLIS}.
     */
    public TrustResult trust(String nonce) {
        if (nonce == null || !NONCE.matcher(nonce).matches()) return TrustResult.UNKNOWN;
        Peer match = null;
        synchronized (lock) {
            for (Peer p : peers.values()) {
                if (p.nonce != null && MessageDigest.isEqual(p.nonce.getBytes(StandardCharsets.UTF_8),
                        nonce.getBytes(StandardCharsets.UTF_8))) {
                    match = p;
                    break;
                }
            }
            if (match == null) return TrustResult.UNKNOWN;
            if (clock.getAsLong() >= match.nonceExpiresAt) return TrustResult.EXPIRED;
            match.nonce = null;
        }
        boolean already = trusted.isTrusted(match.fingerprint);
        try {
            trusted.add(match.fingerprint, clock.getAsLong());
        } catch (IOException e) {
            RTP.log(Level.WARNING, "[editor] could not persist trusted editor key " + match.fingerprint.substring(0, 16)
                    + "; it stays trusted until restart: " + e.getMessage(), e);
        }
        RTP.log(Level.INFO, "[editor] channel " + shortId() + ": editor key " + match.fingerprint.substring(0, 16) + " trusted");
        send("{\"type\":\"hello-reply\",\"state\":\"trusted\"}", match.fingerprint);
        return already ? TrustResult.ALREADY_TRUSTED : TrustResult.TRUSTED;
    }

    /** {@link #trust(String)} on every open channel; the first match decides. */
    public static TrustResult trustAny(String nonce) {
        TrustResult best = TrustResult.UNKNOWN;
        for (EditorChannel c : List.copyOf(OPEN)) {
            TrustResult r = c.trust(nonce);
            if (r == TrustResult.TRUSTED || r == TrustResult.ALREADY_TRUSTED) return r;
            if (r == TrustResult.EXPIRED) best = r;
        }
        return best;
    }

    // ---- outbound ----

    /** Broadcasts {@code bodyJson} to every page. */
    public boolean send(String bodyJson) {
        return send(bodyJson, null);
    }

    /**
     * Signs and sends one message. {@code bodyJson} is a JSON object with a {@code type} and none of
     * the header fields; {@code to} addresses one browser key or {@code null} for all.
     *
     * @return {@code false} when closed, over the frame cap, a skipped droppable push, or refused by the transport
     */
    public boolean send(String bodyJson, String to) {
        Map<String, Object> body = object(EditorLoopbackJson.parse(bodyJson));
        if (body == null || !(body.get("type") instanceof String type) || !TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("channel message needs a type");
        }
        for (String k : HEADER_KEYS) {
            if (body.containsKey(k)) throw new IllegalArgumentException("'" + k + "' is a header field");
        }
        String trimmed = bodyJson.strip();
        synchronized (sendLock) {
            if (closed.get()) return false;
            long seq = outSeq + 1;
            StringBuilder msg = new StringBuilder(trimmed.length() + 200)
                    .append(trimmed, 0, trimmed.length() - 1)
                    .append(",\"channel\":").append(EditorLoopbackJson.quote(id))
                    .append(",\"seq\":").append(seq)
                    .append(",\"from\":\"").append(keys.fingerprint()).append('"');
            if (to != null) msg.append(",\"to\":").append(EditorLoopbackJson.quote(to));
            msg.append('}');
            String m = msg.toString();
            String frame = "{\"msg\":" + EditorLoopbackJson.quote(m) + ",\"signature\":\""
                    + Base64.getEncoder().encodeToString(keys.sign(m.getBytes(StandardCharsets.UTF_8))) + "\"}";
            int bytes = utf8Length(frame);
            if (bytes > MAX_FRAME_BYTES) {
                logLimited("out|" + type, Level.WARNING, "[editor] channel " + shortId() + ": '" + type
                        + "' message of " + bytes + " bytes exceeds the " + (MAX_FRAME_BYTES >> 10) + " KiB frame cap; not sent");
                return false;
            }
            long now = clock.getAsLong();
            if (now - windowStart >= 60_000L) {
                windowStart = now;
                windowBytes = 0;
            }
            long budget = FIRST_DEFERRED.equals(type)
                    ? (long) (OUTBOUND_BYTES_PER_MINUTE * FIRST_DEFERRED_SHARE) : OUTBOUND_BYTES_PER_MINUTE;
            if (windowBytes + bytes > budget && DROPPABLE.contains(type)) {
                logLimited("budget", Level.INFO, "[editor] channel " + shortId() + ": outbound budget of "
                        + (OUTBOUND_BYTES_PER_MINUTE >> 20) + " MiB/min reached; skipping '" + type + "' pushes");
                return false;
            }
            outSeq = seq;
            windowBytes += bytes;
            return transport.send(frame);
        }
    }

    /**
     * Whether {@code bodyJson} fits one frame once wrapped, without signing it: the escaped
     * message plus {@link #ENVELOPE_OVERHEAD}.
     */
    public static boolean fitsFrame(String bodyJson) {
        long n = ENVELOPE_OVERHEAD;
        for (int i = 0; i < bodyJson.length(); i++) {
            char c = bodyJson.charAt(i);
            if (c == '"' || c == '\\') n += 2;
            else if (c < 0x20) n += 6;
            else if (c < 0x80) n += 1;
            else if (c < 0x800) n += 2;
            else n += Character.isSurrogate(c) ? 2 : 3;
            if (n > MAX_FRAME_BYTES) return false;
        }
        return true;
    }

    // ---- lifecycle ----

    /**
     * Tells the pages why ({@code bye {reason}}: {@code expired}, {@code superseded} or {@code closed}),
     * then closes the transport; idempotent, any thread.
     */
    public void close(String reason) {
        if (!closed.get()) {
            String r = reason == null ? "" : reason;
            String shortReason = r.startsWith("expired") ? "expired" : r.startsWith("superseded") ? "superseded" : "closed";
            try {
                send("{\"type\":\"bye\",\"reason\":\"" + shortReason + "\"}");
            } catch (RuntimeException e) {
                RTP.log(Level.FINE, "[editor] channel bye not sent: " + e.getMessage());
            }
        }
        transport.close(reason);
        closed(reason);
    }

    private void closed(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        closeReason = reason;
        OPEN.remove(this);
        RTP.log(Level.INFO, "[editor] channel " + shortId() + " closed: " + reason);
        try {
            notifier.closed(reason);
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING, "[editor] channel closed notice failed: " + e.getMessage(), e);
        }
    }

    /** Closes every open channel (plugin disable). */
    public static void closeAll(String reason) {
        for (EditorChannel c : List.copyOf(OPEN)) c.close(reason);
    }

    public boolean isOpen() {
        return !closed.get();
    }

    public String id() {
        return id;
    }

    public String pluginFingerprint() {
        return keys.fingerprint();
    }

    public String closeReason() {
        return closeReason;
    }

    /** Frames dropped so far (tests and diagnostics). */
    public long droppedCount() {
        return dropped.get();
    }

    public String lastDropReason() {
        return lastDropReason;
    }

    /** Browser keys that completed a hello in this session. */
    public List<String> peerFingerprints() {
        synchronized (lock) {
            return new ArrayList<>(peers.keySet());
        }
    }

    // ---- helpers ----

    private void drop(String key, String reason) {
        dropped.incrementAndGet();
        lastDropReason = reason;
        String who = key.length() > 16 ? key.substring(0, 16) : key;
        logLimited("in|" + key, Level.WARNING, "[editor] channel " + shortId() + " dropped a frame from " + who + ": " + reason);
    }

    private void logLimited(String key, Level level, String line) {
        long now = clock.getAsLong();
        synchronized (lastLogged) {
            Long last = lastLogged.get(key);
            if (last != null && now - last < LOG_INTERVAL_MILLIS) return;
            if (lastLogged.size() >= 256) lastLogged.clear();
            lastLogged.put(key, now);
        }
        RTP.log(level, line);
    }

    private String shortId() {
        return id.substring(0, 8);
    }

    @SuppressWarnings("unchecked") // EditorLoopbackJson objects are LinkedHashMap<String, Object>
    private static Map<String, Object> object(Object parsed) {
        return parsed instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private static String json(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (sb.length() > 1) sb.append(',');
            sb.append(EditorLoopbackJson.quote(e.getKey())).append(':');
            Object v = e.getValue();
            sb.append(v instanceof String s ? EditorLoopbackJson.quote(s) : String.valueOf(v));
        }
        return sb.append('}').toString();
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static String newNonce() {
        StringBuilder sb = new StringBuilder(NONCE_LENGTH);
        for (int i = 0; i < NONCE_LENGTH; i++) sb.append(NONCE_ALPHABET.charAt(RANDOM.nextInt(NONCE_ALPHABET.length())));
        return sb.toString();
    }

    static int utf8Length(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) n += 1;
            else if (c < 0x800) n += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                n += 4;
                i++;
            } else n += 3;
        }
        return n;
    }
}
