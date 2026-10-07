package io.github.dailystruggle.rtp.common.commands.editor.channel;

import io.github.dailystruggle.rtp.api.editor.EditorDelivery;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.editor.EditorLoopbackJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
 *
 * <p>Frame pacing (§5.5): over a transport with {@link ChannelTransport#framesPerWindow()} {@code > 0}
 * (the public relay), at most that many frames go out in any {@link ChannelTransport#FRAME_WINDOW_MILLIS}.
 * Broadcast {@link #BUNDLED} pushes are held (newest {@code feed}, newest {@code curve} per region,
 * {@code land} / {@code hazard-delta} in order, refused once a frame's worth waits) and leave as one
 * {@code bundle {items: [..]}} at most every {@link #BUNDLE_INTERVAL_MILLIS}, keeping
 * {@link #REPLY_RESERVE} frames for replies; replies over the budget wait in order. Held frames go
 * out on the next send, inbound frame or {@link #flush()}; {@code bye} is never held.
 *
 * <p>Extension types (ADR-107 §5): {@code <id>.<local>}, routed to a whole namespace
 * ({@link #registerNamespace}); undotted types stay core's. Extension pushes are declared with a
 * {@link EditorDelivery} ({@link #declarePush}) and sent through {@link #sendExtension}, which caps
 * each extension at {@link #EXTENSION_BYTES_PER_MINUTE} and all of them at {@link #EXTENSIONS_SHARE}
 * of the session budget, and skips their pushes before core's {@code land} once the session window
 * is half used ({@code latest}) or 60 % used ({@code ordered}).
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
    /**
     * Broadcast pushes a paced channel holds and sends together as one {@code bundle}. {@code land-ref}
     * (a bytebin key, never dropped) rides the next bundle instead of costing a frame of its own.
     */
    public static final Set<String> BUNDLED = Set.of("feed", "curve", "land", "land-ref", "hazard-delta");
    public static final String BUNDLE_TYPE = "bundle";
    /** Shortest gap between two bundles on a paced channel. */
    public static final long BUNDLE_INTERVAL_MILLIS = 8_000L;
    /** Frames of a paced window bundles leave to replies. */
    static final int REPLY_RESERVE = 4;
    /** Replies waiting for frame budget before the oldest is dropped. */
    static final int MAX_HELD_REPLIES = 64;
    /** Paced channels: no pong when any frame went out this recently. */
    static final long PONG_SKIP_MILLIS = 10_000L;
    /** Outbound bytes one extension may use per minute (ADR-107 §5.4). */
    public static final long EXTENSION_BYTES_PER_MINUTE = 256L * 1024;
    /** Share of {@link #OUTBOUND_BYTES_PER_MINUTE} all extensions together may use. */
    public static final double EXTENSIONS_SHARE = 0.25;
    /** Session window use above which extension pushes are skipped, so they go before core's {@code land}. */
    static final double EXTENSION_LATEST_SHARE = 0.5;
    static final double EXTENSION_ORDERED_SHARE = 0.6;
    /** Paced channels: held push characters per extension. */
    static final int EXTENSION_HELD_CHARS = 8 * 1024;
    /** Fields the channel itself adds to every outbound message (hello-reply's challenge is body). */
    private static final Set<String> HEADER_KEYS = Set.of("channel", "seq", "from", "to");
    /** Core types are undotted; extension types are {@code <id>.<local>} (ADR-107 §5.1). */
    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9_\\-]{0,31}(\\.[a-z][a-z0-9_\\-]{0,31})?");
    private static final Pattern NAMESPACE = Pattern.compile("[a-z][a-z0-9\\-]{1,23}");
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
    /** Extension namespace -> handler of all its {@code <id>.<local>} types. */
    private final Map<String, Handler> namespaces = new ConcurrentHashMap<>();
    /** Declared extension push types and their delivery. */
    private final Map<String, EditorDelivery> extensionPushes = new ConcurrentHashMap<>();
    private final List<Runnable> openListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> closeListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> trustedHelloListeners = new CopyOnWriteArrayList<>();
    /** JSON members a trusted {@code hello-reply} carries ({@code "protocol":..,"extensions":..}), or empty. */
    private volatile String helloExtras = "";
    private final Map<String, Peer> peers = new LinkedHashMap<>();
    private final Map<String, Long> lastLogged = new LinkedHashMap<>();
    private final Object lock = new Object();
    private final Object sendLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();
    private long outSeq;
    private long windowStart;
    private long windowBytes;
    /** Under {@link #sendLock}, reset with the window: bytes per extension and for all extensions. */
    private final Map<String, Long> extensionBytes = new HashMap<>();
    private long extensionsBytes;
    /** Under {@link #sendLock}: held push characters per extension. */
    private final Map<String, Long> extensionHeld = new HashMap<>();
    /** Paced channels only, under {@link #sendLock}: send times inside the frame window. */
    private final java.util.ArrayDeque<Long> sentAt = new java.util.ArrayDeque<>();
    /** Replies and non-bundled messages waiting for frame budget: {body, to, type}. */
    private final java.util.ArrayDeque<String[]> heldReplies = new java.util.ArrayDeque<>();
    /** Bundled pushes by coalescing key, in arrival order. */
    private final LinkedHashMap<String, String> heldPushes = new LinkedHashMap<>();
    private long heldPushChars;
    private long heldPushSerial;
    private long lastBundleAt = Long.MIN_VALUE / 2;
    private volatile long lastFrameAt = Long.MIN_VALUE / 2;
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
        runAll(openListeners, "open");
        return this;
    }

    /** Routes verified, trusted messages of the core {@code type} to {@code handler} (one per type). */
    public EditorChannel register(String type, Handler handler) {
        if (type == null || !TYPE.matcher(type).matches()) throw new IllegalArgumentException("bad type " + type);
        if (type.indexOf('.') >= 0) throw new IllegalArgumentException(type + " is an extension type; use registerNamespace");
        if ("hello".equals(type) || "ping".equals(type)) throw new IllegalArgumentException(type + " is built in");
        handlers.put(type, Objects.requireNonNull(handler, "handler"));
        return this;
    }

    /** Routes every verified, trusted {@code <id>.<local>} message to {@code handler}. */
    public EditorChannel registerNamespace(String id, Handler handler) {
        if (id == null || !NAMESPACE.matcher(id).matches()) throw new IllegalArgumentException("bad namespace " + id);
        namespaces.put(id, Objects.requireNonNull(handler, "handler"));
        return this;
    }

    /** Stops routing {@code id}'s messages and forgets its push types; held pushes still go out. */
    public void unregisterNamespace(String id) {
        if (id == null) return;
        namespaces.remove(id);
        extensionPushes.keySet().removeIf(t -> t.startsWith(id + "."));
    }

    /** Declares the broadcast extension type {@code type} ({@code <id>.<local>}) with its delivery class. */
    public void declarePush(String type, EditorDelivery delivery) {
        if (type == null || !TYPE.matcher(type).matches() || type.indexOf('.') < 0) {
            throw new IllegalArgumentException("bad extension type " + type);
        }
        extensionPushes.put(type, Objects.requireNonNull(delivery, "delivery"));
    }

    /** Runs {@code r} once the transport is open; any thread, never throws into the channel. */
    public void onOpen(Runnable r) {
        openListeners.add(Objects.requireNonNull(r, "r"));
    }

    /** Runs {@code r} once the channel closes, for any reason. */
    public void onClose(Runnable r) {
        closeListeners.add(Objects.requireNonNull(r, "r"));
    }

    /** Runs {@code r} each time a trusted page completes a hello or a page key gets trusted. */
    public void onTrustedHello(Runnable r) {
        trustedHelloListeners.add(Objects.requireNonNull(r, "r"));
    }

    /**
     * JSON members (no braces) added to every trusted {@code hello-reply} (ADR-107 §5.2), for example
     * {@code "protocol":{..},"extensions":[..]}; {@code null} or empty for none.
     */
    public void setHelloExtras(String members) {
        String m = members == null ? "" : members.strip();
        if (!m.isEmpty() && !(EditorLoopbackJson.parse("{" + m + "}") instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("hello extras are not JSON members");
        }
        helloExtras = m;
    }

    private String withHelloExtras(String json) {
        String m = helloExtras;
        return m.isEmpty() ? json : json.substring(0, json.length() - 1) + "," + m + "}";
    }

    private static void runAll(List<Runnable> listeners, String what) {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (RuntimeException e) {
                RTP.log(Level.WARNING, "[editor] channel " + what + " listener failed: " + e.getMessage(), e);
            }
        }
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
                // A paced page counts any verified frame as liveness: skip a pong right after other traffic
                if (transport.framesPerWindow() <= 0 || now - lastFrameAt >= PONG_SKIP_MILLIS) send("{\"type\":\"pong\"}", from);
                return;
            }
            if (!trusted.isTrusted(from)) {
                drop(from, type + " from an untrusted key");
                return;
            }
            handler = handlers.get(type);
            int dot = type.indexOf('.');
            if (handler == null && dot > 0) handler = namespaces.get(type.substring(0, dot));
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
        flush();
    }

    private void hello(String from, Map<String, Object> msg, byte[] signed, byte[] signature, long now) {
        if (!(msg.get("publicKey") instanceof String k)) {
            drop(from, "hello with a bad key: missing or not a string");
            return;
        }
        PublicKey key;
        try {
            key = EditorKeys.decodePublicKey(k);
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
        boolean isTrusted = trusted.isTrusted(from);
        if (isTrusted) {
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
        send(isTrusted ? withHelloExtras(json(reply)) : json(reply), from);
        if (isTrusted) runAll(trustedHelloListeners, "trusted hello");
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
        send(withHelloExtras("{\"type\":\"hello-reply\",\"state\":\"trusted\"}"), match.fingerprint);
        runAll(trustedHelloListeners, "trusted hello");
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

    /**
     * Drops the keys matching {@code selector} ({@link TrustedEditors#isSelector}) from every open
     * channel's in-memory trusted list, so their next message is refused; the file is the caller's.
     *
     * @return the fingerprints dropped
     */
    public static Set<String> untrustAny(String selector) {
        Set<String> removed = new java.util.LinkedHashSet<>();
        for (EditorChannel c : List.copyOf(OPEN)) removed.addAll(c.trusted.forget(selector));
        if (!removed.isEmpty()) RTP.log(Level.INFO, "[editor] " + removed.size() + " editor key(s) untrusted in open channels");
        return removed;
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
     * @return {@code false} when closed, over the frame cap, a skipped droppable push, refused by the
     * transport, or (paced channel) a push refused because a frame's worth is already held
     */
    public boolean send(String bodyJson, String to) {
        Map<String, Object> body = checkedBody(bodyJson);
        String type = (String) body.get("type");
        synchronized (sendLock) {
            return sendLocked(bodyJson.strip(), to, type, body, null);
        }
    }

    /**
     * Sends one message of extension {@code extensionId} (ADR-107 §5.3, §5.4): a reply when {@code to}
     * is set, else a push of a type declared through {@link #declarePush}. {@code coalesceKey}
     * refines a {@code latest} push's coalescing.
     *
     * @return {@code false} when closed, the type is not the extension's or not declared, a budget or
     * held-backlog cap refuses it, or any {@link #send(String, String)} refusal; refusals are logged
     * @throws IllegalArgumentException when {@code bodyJson} is not a typed message body
     */
    public boolean sendExtension(String extensionId, String bodyJson, String to, String coalesceKey) {
        Map<String, Object> body = checkedBody(bodyJson);
        String type = (String) body.get("type");
        if (extensionId == null || !type.startsWith(extensionId + ".") || type.indexOf('.') != extensionId.length()) {
            throw new IllegalArgumentException("'" + type + "' is not a type of extension " + extensionId);
        }
        String trimmed = bodyJson.strip();
        long bytes = utf8Length(trimmed);
        synchronized (sendLock) {
            if (closed.get()) return false;
            EditorDelivery delivery = to == null ? extensionPushes.get(type) : null;
            if (to == null && delivery == null) {
                logLimited("ext|undeclared|" + type, Level.WARNING, "[editor] channel " + shortId() + ": '" + type
                        + "' is not a declared push type; not sent");
                return false;
            }
            rollWindow(clock.getAsLong());
            long own = extensionBytes.getOrDefault(extensionId, 0L);
            if (own + bytes > EXTENSION_BYTES_PER_MINUTE
                    || extensionsBytes + bytes > (long) (OUTBOUND_BYTES_PER_MINUTE * EXTENSIONS_SHARE)) {
                logLimited("ext|budget|" + extensionId, Level.INFO, "[editor] channel " + shortId() + ": extension '"
                        + extensionId + "' reached its outbound share; skipping '" + type + "'");
                return false;
            }
            if (delivery != null) {
                double share = delivery == EditorDelivery.LATEST ? EXTENSION_LATEST_SHARE : EXTENSION_ORDERED_SHARE;
                if (windowBytes + bytes > (long) (OUTBOUND_BYTES_PER_MINUTE * share)) {
                    logLimited("ext|session|" + extensionId, Level.INFO, "[editor] channel " + shortId()
                            + ": session outbound budget is busy; skipping extension push '" + type + "'");
                    return false;
                }
            }
            boolean accepted = sendLocked(trimmed, to, type, body, coalesceKey == null ? "" : coalesceKey);
            if (accepted) {
                extensionBytes.merge(extensionId, bytes, Long::sum);
                extensionsBytes += bytes;
            } else {
                logLimited("ext|refused|" + type, Level.INFO, "[editor] channel " + shortId() + ": extension message '"
                        + type + "' refused (frame cap, backlog or closed)");
            }
            return accepted;
        }
    }

    private static Map<String, Object> checkedBody(String bodyJson) {
        Map<String, Object> body = object(EditorLoopbackJson.parse(bodyJson));
        if (body == null || !(body.get("type") instanceof String type) || !TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("channel message needs a type");
        }
        for (String k : HEADER_KEYS) {
            if (body.containsKey(k)) throw new IllegalArgumentException("'" + k + "' is a header field");
        }
        return body;
    }

    /** Under {@link #sendLock}; {@code coalesceKey} is non-null for extension sends only. */
    private boolean sendLocked(String trimmed, String to, String type, Map<String, Object> body, String coalesceKey) {
        int limit = transport.framesPerWindow();
        if (closed.get()) return false;
        if (limit <= 0 || "bye".equals(type)) return sendFrame(trimmed, to, type);
        boolean accepted;
        if (to == null && (BUNDLED.contains(type) || extensionPushes.containsKey(type))) {
            accepted = holdPush(trimmed, type, body, coalesceKey);
        } else {
            if (heldReplies.size() >= MAX_HELD_REPLIES) {
                String[] old = heldReplies.poll();
                logLimited("held", Level.WARNING, "[editor] channel " + shortId() + ": frame budget exhausted; dropped a held '"
                        + old[2] + "' reply");
            }
            heldReplies.add(new String[]{trimmed, to, type});
            accepted = true;
        }
        flushLocked(limit);
        return accepted;
    }

    /** Sends held replies and a due bundle as far as the frame budget allows; any thread. */
    public void flush() {
        int limit = transport.framesPerWindow();
        if (limit <= 0) return;
        synchronized (sendLock) {
            if (!closed.get()) flushLocked(limit);
        }
    }

    /** Messages a paced channel still holds (replies plus bundled pushes); tests and diagnostics. */
    public int heldCount() {
        synchronized (sendLock) {
            return heldReplies.size() + heldPushes.size();
        }
    }

    /** Under {@link #sendLock}: coalesces a bundled push, or refuses it while a frame's worth waits. */
    private boolean holdPush(String body, String type, Map<String, Object> parsed, String coalesceKey) {
        EditorDelivery delivery = extensionPushes.get(type);
        String key;
        if (delivery != null) {
            key = delivery == EditorDelivery.LATEST ? type + "|k:" + (coalesceKey == null ? "" : coalesceKey)
                    : type + "|" + (++heldPushSerial);
        } else {
            key = switch (type) {
                case "feed" -> "feed";
                case "curve" -> "curve|" + parsed.get("region");
                default -> type + "|" + (++heldPushSerial);
            };
        }
        String old = heldPushes.get(key);
        long chars = heldPushChars - (old == null ? 0 : old.length()) + body.length();
        // A replacement never grows the backlog by more than itself; new items wait for room
        if (old == null && !heldPushes.isEmpty() && chars > MAX_FRAME_BYTES - ENVELOPE_OVERHEAD) return false;
        String ext = delivery == null ? null : type.substring(0, type.indexOf('.'));
        if (ext != null) {
            long held = extensionHeld.getOrDefault(ext, 0L) - (old == null ? 0 : old.length()) + body.length();
            if (held > EXTENSION_HELD_CHARS) return false;
            extensionHeld.put(ext, held);
        }
        heldPushes.put(key, body);
        heldPushChars = chars;
        return true;
    }

    /** Under {@link #sendLock}: removes a held push and its extension backlog share. */
    private void releaseHeld(String key) {
        String body = heldPushes.remove(key);
        if (body == null) return;
        heldPushChars -= body.length();
        String type = typeOf(key);
        int dot = type.indexOf('.');
        if (dot > 0) extensionHeld.computeIfPresent(type.substring(0, dot), (k, v) -> v - body.length() <= 0 ? null : v - body.length());
    }

    /** Under {@link #sendLock}: starts a new minute of the outbound budget. */
    private void rollWindow(long now) {
        if (now - windowStart >= 60_000L) {
            windowStart = now;
            windowBytes = 0;
            extensionBytes.clear();
            extensionsBytes = 0;
        }
    }

    /** Under {@link #sendLock}: held replies first, then one bundle when due and within the reserve. */
    private void flushLocked(int limit) {
        long now = clock.getAsLong();
        while (!sentAt.isEmpty() && now - sentAt.peekFirst() >= ChannelTransport.FRAME_WINDOW_MILLIS) sentAt.pollFirst();
        while (!heldReplies.isEmpty() && sentAt.size() < limit) {
            String[] r = heldReplies.poll();
            if (sendFrame(r[0], r[1], r[2])) sentAt.addLast(now);
        }
        if (heldPushes.isEmpty() || !heldReplies.isEmpty()) return;
        if (sentAt.size() >= Math.max(1, limit - REPLY_RESERVE) || now - lastBundleAt < BUNDLE_INTERVAL_MILLIS) return;
        StringBuilder items = new StringBuilder();
        List<String> taken = new ArrayList<>();
        String single = null;
        String singleType = null;
        for (Map.Entry<String, String> e : heldPushes.entrySet()) {
            String item = e.getValue();
            String candidate = "{\"type\":\"" + BUNDLE_TYPE + "\",\"items\":[" + items + (items.length() > 0 ? "," : "") + item + "]}";
            if (!fitsFrame(candidate)) {
                if (taken.isEmpty()) {
                    // Too large to wrap: goes out on its own
                    single = item;
                    singleType = e.getKey().split("\\|", 2)[0];
                    taken.add(e.getKey());
                }
                break;
            }
            if (items.length() > 0) items.append(',');
            items.append(item);
            taken.add(e.getKey());
        }
        for (String k : taken) releaseHeld(k);
        lastBundleAt = now;
        boolean sent = single != null ? sendFrame(single, null, singleType)
                : taken.size() == 1 ? sendFrame(items.toString(), null, typeOf(taken.get(0)))
                : sendFrame("{\"type\":\"" + BUNDLE_TYPE + "\",\"items\":[" + items + "]}", null, BUNDLE_TYPE);
        if (sent) sentAt.addLast(now);
    }

    private static String typeOf(String heldKey) {
        int bar = heldKey.indexOf('|');
        return bar < 0 ? heldKey : heldKey.substring(0, bar);
    }

    /** Under {@link #sendLock}: signs one message and hands it to the transport. */
    private boolean sendFrame(String trimmed, String to, String type) {
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
        rollWindow(now);
        long budget = FIRST_DEFERRED.equals(type)
                ? (long) (OUTBOUND_BYTES_PER_MINUTE * FIRST_DEFERRED_SHARE) : OUTBOUND_BYTES_PER_MINUTE;
        if (windowBytes + bytes > budget && DROPPABLE.contains(type)) {
            logLimited("budget", Level.INFO, "[editor] channel " + shortId() + ": outbound budget of "
                    + (OUTBOUND_BYTES_PER_MINUTE >> 20) + " MiB/min reached; skipping '" + type + "' pushes");
            return false;
        }
        outSeq = seq;
        windowBytes += bytes;
        lastFrameAt = now;
        return transport.send(frame);
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
        runAll(closeListeners, "close");
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
