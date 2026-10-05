package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.api.editor.EditorDelivery;
import io.github.dailystruggle.rtp.api.editor.EditorExtension;
import io.github.dailystruggle.rtp.api.editor.EditorFrame;
import io.github.dailystruggle.rtp.api.editor.EditorMessage;
import io.github.dailystruggle.rtp.api.editor.EditorSession;
import io.github.dailystruggle.rtp.api.editor.EditorTab;
import io.github.dailystruggle.rtp.api.editor.EditorWidget;
import io.github.dailystruggle.rtp.api.hooks.EditorExtensionRegistry;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.editor.channel.EditorChannel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Core side of addon web editor extensions (ADR-107): the registry behind
 * {@code RTPHooks.editorExtensions()}, the snapshot's {@code protocol} / {@code extensions} members,
 * the binding of each extension to its {@code <id>.} namespace on a session's {@link EditorChannel},
 * and live-state polling from the feed's async tick.
 *
 * <p>Extensions never touch the channel: core prefixes types, budgets and routes. Every extension
 * call is wrapped (exceptions and slow calls logged, one line per extension and cause per minute);
 * {@link #MAX_FAILURES} failures disable the extension for that session only.
 */
public final class EditorExtensions {

    public static final int PROTOCOL_CHANNEL = 2;
    public static final int PROTOCOL_EXTENSIONS = 1;
    static final int MAX_SNAPSHOT_BYTES = 64 * 1024;
    static final int MAX_DESCRIPTOR_BYTES = 16 * 1024;
    static final int MAX_EXTENSIONS_BYTES = 256 * 1024;
    static final int MAX_STATE_BYTES = 16 * 1024;
    static final int MAX_FRAME_SOURCE_BYTES = 64 * 1024;
    static final int MAX_FAILURES = 3;
    static final int INBOUND_PER_SECOND = 20;
    static final long SLOW_GETTER_NANOS = 50_000_000L;
    static final long SLOW_CALL_NANOS = 5_000_000L;
    static final long LOG_INTERVAL_MILLIS = 60_000L;
    static final String STATE = "state";
    static final String ERROR = "error";
    private static final Pattern ID = Pattern.compile(EditorExtensionRegistry.ID_REGEX);
    private static final Pattern LOCAL = Pattern.compile("[a-z][a-z0-9_\\-]{0,31}");
    private static final Pattern KIND = Pattern.compile("[a-zA-Z][a-zA-Z0-9]{0,31}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> RESERVED_LOCAL = Set.of(STATE, ERROR);
    /** First path segments core owns; addon files live elsewhere, normally {@code addons/<Addon>/}. */
    static final Set<String> CORE_DIRS = Set.of("regions", "worlds", "advanced", "definitions", "lang", "schematics");
    static final int MAX_LISTED_ERRORS = 20;
    /** Body keys core owns: the type and the channel header. */
    private static final Set<String> BODY_RESERVED = Set.of("type", "channel", "seq", "from", "to", "challenge");

    private static final Registry REGISTRY = new Registry();
    private static final Set<Session> SESSIONS = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> LAST_LOGGED = new LinkedHashMap<>();

    private EditorExtensions() {
    }

    /** The registry served by {@code RTPAPI.hooks().editorExtensions()}. */
    public static EditorExtensionRegistry registry() {
        return REGISTRY;
    }

    /** Unregisters every extension (plugin disable). */
    public static void unregisterAll() {
        for (String id : new ArrayList<>(REGISTRY.map.keySet())) REGISTRY.unregister(id);
    }

    // ---- registry ----

    static final class Registry implements EditorExtensionRegistry {
        private final ConcurrentSkipListMap<String, EditorExtension> map = new ConcurrentSkipListMap<>();

        @Override
        public void register(EditorExtension extension) {
            Objects.requireNonNull(extension, "extension");
            String id;
            try {
                id = extension.id();
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("editor extension id() failed: " + e.getMessage(), e);
            }
            if (id == null || !ID.matcher(id).matches()) {
                throw new IllegalArgumentException("bad editor extension id '" + id + "'; expected " + ID_REGEX);
            }
            if (RESERVED_IDS.contains(id)) throw new IllegalArgumentException("editor extension id '" + id + "' is reserved");
            try {
                descriptor(extension, id);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("editor extension '" + id + "': " + e.getMessage(), e);
            }
            if (map.putIfAbsent(id, extension) != null) {
                throw new IllegalStateException("editor extension id '" + id + "' is already registered");
            }
            RTP.log(Level.INFO, "[editor] extension '" + id + "' registered; it joins the next editor session");
        }

        @Override
        public boolean unregister(String id) {
            if (id == null || map.remove(id) == null) return false;
            for (Session s : SESSIONS) s.detach(id);
            RTP.log(Level.INFO, "[editor] extension '" + id + "' unregistered");
            return true;
        }

        @Override
        public List<EditorExtension> registered() {
            return List.copyOf(map.values());
        }

        Map<String, EditorExtension> snapshot() {
            return new LinkedHashMap<>(map);
        }
    }

    /**
     * The validated descriptor without {@code frame}, {@code snapshot} and {@code error}
     * (ADR-107 §5.2); throws {@link IllegalArgumentException} or the extension's own exception.
     */
    static Map<String, Object> descriptor(EditorExtension e, String id) {
        int version = e.version();
        if (version < 1) throw new IllegalArgumentException("version must be >= 1, was " + version);
        String name = e.displayName();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", id);
        d.put("version", version);
        d.put("name", name == null || name.isBlank() ? id : name);

        List<Object> tabs = new ArrayList<>();
        Set<String> tabIds = new HashSet<>();
        for (EditorTab t : nonNull(e.tabs())) {
            if (t == null) throw new IllegalArgumentException("null tab");
            if (!ID.matcher(t.id()).matches()) throw new IllegalArgumentException("bad tab id '" + t.id() + "'");
            if (!tabIds.add(t.id())) throw new IllegalArgumentException("duplicate tab id '" + t.id() + "'");
            if (t.title().isBlank()) throw new IllegalArgumentException("tab '" + t.id() + "' has no title");
            List<Object> widgets = new ArrayList<>();
            for (EditorWidget w : t.widgets()) {
                if (w == null) throw new IllegalArgumentException("null widget in tab '" + t.id() + "'");
                if (!KIND.matcher(w.kind()).matches()) throw new IllegalArgumentException("bad widget kind '" + w.kind() + "'");
                Map<String, Object> wm = new LinkedHashMap<>();
                wm.put("kind", w.kind());
                wm.put("props", w.props());
                widgets.add(wm);
            }
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("id", t.id());
            tm.put("title", t.title());
            if (t.icon() != null) tm.put("icon", t.icon());
            tm.put("widgets", widgets);
            tabs.add(tm);
        }
        d.put("tabs", tabs);

        Map<String, Object> push = new TreeMap<>();
        for (Map.Entry<String, EditorDelivery> p : pushTypes(e).entrySet()) {
            push.put(p.getKey(), p.getValue() == EditorDelivery.LATEST ? "latest" : "ordered");
        }
        push.put(STATE, "latest");
        d.put("push", push);
        d.put("inbound", new ArrayList<>(inboundTypes(e)));

        Set<String> files = new TreeSet<>();
        for (String p : nonNull(e.configFiles())) files.add(validatePath(p));
        d.put("files", new ArrayList<>(files));

        int bytes = utf8(toJson(d));
        if (bytes > MAX_DESCRIPTOR_BYTES) {
            throw new IllegalArgumentException("descriptor of " + bytes + " bytes exceeds " + (MAX_DESCRIPTOR_BYTES >> 10) + " KiB");
        }
        return d;
    }

    /** Validated local push types (without the reserved {@code state}). */
    static Map<String, EditorDelivery> pushTypes(EditorExtension e) {
        Map<String, EditorDelivery> out = new TreeMap<>();
        for (Map.Entry<String, EditorDelivery> p : nonNull(e.pushTypes()).entrySet()) {
            checkLocal(p.getKey(), "push type");
            if (p.getValue() == null) throw new IllegalArgumentException("push type '" + p.getKey() + "' has no delivery");
            out.put(p.getKey(), p.getValue());
        }
        return out;
    }

    static Set<String> inboundTypes(EditorExtension e) {
        Set<String> out = new TreeSet<>();
        for (String t : nonNull(e.inboundTypes())) {
            checkLocal(t, "inbound type");
            out.add(t);
        }
        return out;
    }

    private static void checkLocal(String t, String what) {
        if (t == null || !LOCAL.matcher(t).matches()) throw new IllegalArgumentException("bad " + what + " '" + t + "'");
        if (RESERVED_LOCAL.contains(t)) throw new IllegalArgumentException(what + " '" + t + "' is reserved");
    }

    /**
     * {@code path} as a normalised, relative {@code /}-separated YAML path inside the plugin folder
     * (ADR-107 §7); symlinks are checked when the file is read.
     */
    static String validatePath(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("blank config file path");
        String s = path.replace('\\', '/');
        if (s.startsWith("/") || s.indexOf(':') >= 0) {
            throw new IllegalArgumentException("config file '" + path + "' must be relative to the plugin folder");
        }
        for (String seg : s.split("/", -1)) {
            if (seg.isEmpty() || ".".equals(seg) || "..".equals(seg)) {
                throw new IllegalArgumentException("config file '" + path + "' must be a normalised relative path");
            }
            if (seg.startsWith(".")) throw new IllegalArgumentException("config file '" + path + "' has a hidden path segment");
        }
        int slash = s.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("config file '" + path + "' is in the plugin folder root, which core owns");
        }
        if (CORE_DIRS.contains(s.substring(0, slash).toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("config file '" + path + "' is in core's '" + s.substring(0, slash) + "' folder");
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".yml") && !lower.endsWith(".yaml")) {
            throw new IllegalArgumentException("config file '" + path + "' must be .yml or .yaml");
        }
        return s;
    }

    private static <T> Collection<T> nonNull(Collection<T> c) {
        return c == null ? List.of() : c;
    }

    private static <K, V> Map<K, V> nonNull(Map<K, V> m) {
        return m == null ? Map.of() : m;
    }

    // ---- snapshot ----

    /** {@code {"channel":2,"extensions":1}}. */
    public static String protocolJson() {
        return "{\"channel\":" + PROTOCOL_CHANNEL + ",\"extensions\":" + PROTOCOL_EXTENSIONS + "}";
    }

    /**
     * Snapshot members {@code "protocol":{..},"extensions":[..]} (ADR-107 §5.2), in id order. Runs
     * every extension's getters and {@link EditorExtension#snapshotJson()}; off the main thread only.
     */
    public static String payloadMembers() {
        StringBuilder arr = new StringBuilder("[");
        long total = 2;
        for (Map.Entry<String, EditorExtension> en : REGISTRY.snapshot().entrySet()) {
            String item = snapshotItem(en.getKey(), en.getValue());
            long bytes = utf8(item);
            if (total + bytes + 1 > MAX_EXTENSIONS_BYTES) {
                logLimited(en.getKey() + "|cap", Level.WARNING, "[editor] extension '" + en.getKey()
                        + "' left out of the session: all extensions together exceed " + (MAX_EXTENSIONS_BYTES >> 10) + " KiB", null);
                item = errorItem(en.getKey(), en.getValue(), "snapshot-cap");
                bytes = utf8(item);
            }
            if (arr.length() > 1) arr.append(',');
            arr.append(item);
            total += bytes + 1;
        }
        return "\"protocol\":" + protocolJson() + ",\"extensions\":" + arr.append(']');
    }

    private static String snapshotItem(String id, EditorExtension e) {
        long t0 = System.nanoTime();
        try {
            Map<String, Object> d;
            try {
                d = descriptor(e, id);
            } catch (RuntimeException | LinkageError ex) {
                logLimited(id + "|descriptor", Level.WARNING, "[editor] extension '" + id + "' descriptor invalid: " + ex, ex);
                return errorItem(id, e, "descriptor-invalid");
            }
            String frame = frameJson(id, e);
            String snap = callGet(id, null, "snapshotJson", e::snapshotJson, Long.MAX_VALUE);
            if (snap != null) {
                snap = snap.strip();
                int bytes = utf8(snap);
                if (bytes > MAX_SNAPSHOT_BYTES) {
                    logLimited(id + "|snapshot", Level.WARNING, "[editor] extension '" + id + "' snapshot of " + bytes
                            + " bytes exceeds " + (MAX_SNAPSHOT_BYTES >> 10) + " KiB; left out of the session", null);
                    return errorItem(id, e, "snapshot-cap");
                }
                if (!isJson(snap)) {
                    logLimited(id + "|snapshot", Level.WARNING, "[editor] extension '" + id + "' snapshot is not JSON", null);
                    return errorItem(id, e, "descriptor-invalid");
                }
            }
            String json = toJson(d);
            return json.substring(0, json.length() - 1) + ",\"frame\":" + frame + ",\"snapshot\":"
                    + (snap == null ? "null" : snap) + ",\"error\":null}";
        } finally {
            slow(id, "snapshot", System.nanoTime() - t0, SLOW_GETTER_NANOS);
        }
    }

    private static String errorItem(String id, EditorExtension e, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        Integer version = callGet(id, null, "version", e::version, Long.MAX_VALUE);
        m.put("version", version == null ? 0 : version);
        String name = callGet(id, null, "displayName", e::displayName, Long.MAX_VALUE);
        m.put("name", name == null || name.isBlank() ? id : name);
        m.put("error", error);
        return toJson(m);
    }

    /** {@code {"sha256":..,"js":..}} of the tier 2 frame source, or {@code null} (logged when invalid). */
    private static String frameJson(String id, EditorExtension e) {
        EditorFrame f = callGet(id, null, "frame", e::frame, Long.MAX_VALUE);
        if (f == null) return "null";
        String js;
        try (InputStream in = e.getClass().getClassLoader().getResourceAsStream(f.resource())) {
            if (in == null) throw new IOException("resource " + f.resource() + " not found");
            byte[] b = in.readNBytes(MAX_FRAME_SOURCE_BYTES + 1);
            if (b.length > MAX_FRAME_SOURCE_BYTES) throw new IOException("source exceeds " + (MAX_FRAME_SOURCE_BYTES >> 10) + " KiB");
            js = new String(b, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ex) {
            logLimited(id + "|frame", Level.WARNING, "[editor] extension '" + id + "' frame unavailable: " + ex.getMessage(), null);
            return "null";
        }
        String sha = EditorHttpTransport.computeSha256(js).toLowerCase(Locale.ROOT);
        if (f.sha256() != null && !(SHA256.matcher(f.sha256()).matches() && f.sha256().equals(sha))) {
            logLimited(id + "|frame", Level.WARNING, "[editor] extension '" + id + "' frame source does not match its sha256", null);
            return "null";
        }
        return "{\"sha256\":\"" + sha + "\",\"js\":" + EditorLoopbackJson.quote(js) + "}";
    }

    // ---- addon config files (ADR-107 §7) ----

    /** Declared path -> owning extension id; the first owner in id order wins, later claims are logged. */
    static Map<String, String> fileOwners() {
        Map<String, String> owners = new TreeMap<>();
        for (Map.Entry<String, EditorExtension> en : REGISTRY.snapshot().entrySet()) {
            String id = en.getKey();
            List<String> files = callGet(id, null, "configFiles", en.getValue()::configFiles, SLOW_GETTER_NANOS);
            if (files == null) continue;
            for (String p : files) {
                String rel;
                try {
                    rel = validatePath(p);
                } catch (IllegalArgumentException ex) {
                    logLimited(id + "|file", Level.WARNING, "[editor] extension '" + id + "' config file ignored: " + ex.getMessage(), null);
                    continue;
                }
                String prior = owners.putIfAbsent(rel, id);
                if (prior != null && !prior.equals(id)) {
                    logLimited(id + "|owner|" + rel, Level.WARNING, "[editor] extension '" + id + "' declares '" + rel
                            + "', already owned by '" + prior + "'; ignored", null);
                }
            }
        }
        return owners;
    }

    /**
     * Whether {@code rel} stays inside {@code root} once symlinks are followed; a missing file is
     * judged by its nearest existing parent.
     */
    static boolean insideRealPath(Path root, String rel) {
        try {
            Path realRoot = root.toRealPath();
            Path probe = root.resolve(rel).normalize();
            if (!probe.startsWith(root.normalize())) return false;
            while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) probe = probe.getParent();
            return probe != null && probe.toRealPath().startsWith(realRoot);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Adds every declared extension file that exists on disk to the session {@code files} map,
     * replacing a copy the folder walk picked up. Off the main thread (payload build).
     */
    public static void addConfigFiles(Path pluginPath, Map<String, String> files) {
        for (Map.Entry<String, String> en : fileOwners().entrySet()) {
            String rel = en.getKey();
            if (!insideRealPath(pluginPath, rel)) {
                logLimited(en.getValue() + "|escape|" + rel, Level.WARNING, "[editor] extension '" + en.getValue() + "' config file '"
                        + rel + "' resolves outside the plugin folder; left out of the session", null);
                continue;
            }
            Path p = pluginPath.resolve(rel);
            if (!Files.isRegularFile(p)) continue;
            try {
                files.put(rel, Files.readString(p, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException ex) {
                logLimited(en.getValue() + "|read|" + rel, Level.WARNING, "[editor] could not read extension '" + en.getValue()
                        + "' config file '" + rel + "': " + ex.getMessage(), null);
            }
        }
    }

    /**
     * Hot-Apply hook, after the structural YAML checks: runs the owning extension's
     * {@link EditorExtension#validate} for every declared file in {@code files}. Any error, a
     * symlink escape or a throwing {@code validate} fails the whole apply with every message
     * (S-004).
     *
     * @return payload path -> owning extension id, for {@link #applied(Map)}
     * @throws IllegalArgumentException listing the errors
     */
    public static Map<String, String> validateFiles(Path pluginPath, Map<String, String> files) {
        Map<String, String> owners = fileOwners();
        Map<String, EditorExtension> exts = REGISTRY.snapshot();
        Map<String, String> owned = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, String> f : files.entrySet()) {
            String rel = f.getKey().replace('\\', '/');
            String id = owners.get(rel);
            EditorExtension e = id == null ? null : exts.get(id);
            if (e == null) continue;
            if (!insideRealPath(pluginPath, rel)) {
                errors.add(rel + ": resolves outside the plugin folder");
                continue;
            }
            owned.put(f.getKey(), id);
            List<String> out;
            long t0 = System.nanoTime();
            try {
                out = e.validate(rel, f.getValue());
            } catch (RuntimeException | LinkageError ex) {
                logLimited(id + "|fail|validate", Level.WARNING, "[editor] extension '" + id + "' validate failed: " + ex, ex);
                errors.add(rel + " (" + id + "): validation failed: " + ex.getMessage());
                continue;
            } finally {
                slow(id, "validate", System.nanoTime() - t0, SLOW_GETTER_NANOS);
            }
            if (out == null) continue;
            for (String err : out) {
                if (err != null && !err.isBlank()) errors.add(rel + " (" + id + "): " + err.strip());
            }
        }
        if (!errors.isEmpty()) {
            String shown = String.join("; ", errors.subList(0, Math.min(errors.size(), MAX_LISTED_ERRORS)));
            int more = errors.size() - MAX_LISTED_ERRORS;
            throw new IllegalArgumentException("Addon validation failed: " + shown + (more > 0 ? "; and " + more + " more" : ""));
        }
        return owned;
    }

    /** Hot-Apply hook, after the atomic write: {@code applied(path)} for each owner; failures are logged only. */
    public static void applied(Map<String, String> owned) {
        Map<String, EditorExtension> exts = REGISTRY.snapshot();
        for (Map.Entry<String, String> en : owned.entrySet()) {
            EditorExtension e = exts.get(en.getValue());
            if (e == null) continue;
            String rel = en.getKey().replace('\\', '/');
            callGet(en.getValue(), null, "applied", () -> {
                e.applied(rel);
                return Boolean.TRUE;
            }, SLOW_GETTER_NANOS);
        }
    }

    // ---- sessions ----

    /**
     * Binds every registered extension to {@code ch} (before {@link EditorChannel#start()}): declares
     * its push types, routes its namespace, sets the trusted {@code hello-reply} members and calls
     * {@code sessionOpened} / {@code sessionClosed} with the channel's lifecycle.
     */
    public static void attach(EditorChannel ch) {
        Objects.requireNonNull(ch, "ch");
        Session s = new Session(ch);
        for (Map.Entry<String, EditorExtension> en : REGISTRY.snapshot().entrySet()) s.bind(en.getKey(), en.getValue());
        ch.setHelloExtras(s.helloMembers());
        ch.onOpen(s::opened);
        ch.onTrustedHello(s::pageConnected);
        ch.onClose(s::closed);
        SESSIONS.add(s);
    }

    /**
     * Pushes {@code <id>.state} for every bound extension whose {@link EditorExtension#stateJson()}
     * changed (ADR-107 §4.3); called from the live feed's async tick.
     */
    public static void pollStates() {
        for (Session s : SESSIONS) {
            if (!s.channel.isOpen()) continue;
            for (Bound b : s.bound.values()) {
                if (b.isOpen()) b.pollState();
            }
        }
    }

    /** Open sessions (tests and diagnostics). */
    static int sessionCount() {
        return SESSIONS.size();
    }

    static final class Session {
        final EditorChannel channel;
        final String id = EditorChannel.newChannelId().substring(0, 16);
        final Map<String, Bound> bound = new ConcurrentSkipListMap<>();

        Session(EditorChannel channel) {
            this.channel = channel;
        }

        void bind(String extId, EditorExtension e) {
            Map<String, EditorDelivery> pushes;
            Set<String> inbound;
            try {
                pushes = pushTypes(e);
                inbound = inboundTypes(e);
            } catch (RuntimeException | LinkageError ex) {
                logLimited(extId + "|bind", Level.WARNING, "[editor] extension '" + extId + "' not bound to the session: " + ex, ex);
                return;
            }
            Bound b = new Bound(this, extId, e, pushes, inbound);
            for (Map.Entry<String, EditorDelivery> p : pushes.entrySet()) channel.declarePush(extId + "." + p.getKey(), p.getValue());
            channel.declarePush(extId + "." + STATE, EditorDelivery.LATEST);
            channel.declarePush(extId + "." + ERROR, EditorDelivery.ORDERED);
            channel.registerNamespace(extId, b::route);
            bound.put(extId, b);
        }

        String helloMembers() {
            List<Object> list = new ArrayList<>();
            for (Bound b : bound.values()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", b.extId);
                Integer v = callGet(b.extId, null, "version", b.ext::version, Long.MAX_VALUE);
                m.put("version", v == null ? 0 : v);
                list.add(m);
            }
            return "\"protocol\":" + protocolJson() + ",\"extensions\":" + toJson(list);
        }

        void opened() {
            for (Bound b : bound.values()) {
                if (!b.detached) call(b, "sessionOpened", () -> b.ext.sessionOpened(b));
            }
        }

        /** A trusted page (re)connected: its state is unknown, so the next poll resends it. */
        void pageConnected() {
            for (Bound b : bound.values()) b.lastState = null;
        }

        void detach(String extId) {
            Bound b = bound.get(extId);
            if (b != null) b.detach();
        }

        void closed() {
            SESSIONS.remove(this);
            for (Bound b : bound.values()) b.detach();
        }
    }

    /** One extension inside one session; the {@link EditorSession} the extension sees. */
    static final class Bound implements EditorSession {
        final Session session;
        final String extId;
        final EditorExtension ext;
        final Map<String, EditorDelivery> pushes;
        final Set<String> inbound;
        final AtomicInteger failures = new AtomicInteger();
        volatile boolean failed;
        volatile boolean detached;
        volatile String lastState;
        private long rateWindowStart;
        private int rateCount;

        Bound(Session session, String extId, EditorExtension ext, Map<String, EditorDelivery> pushes, Set<String> inbound) {
            this.session = session;
            this.extId = extId;
            this.ext = ext;
            this.pushes = Map.copyOf(pushes);
            this.inbound = Set.copyOf(inbound);
        }

        @Override
        public String id() {
            return session.id;
        }

        @Override
        public boolean isOpen() {
            return !failed && !detached && session.channel.isOpen();
        }

        @Override
        public boolean push(String localType, String bodyJson) {
            return push(localType, null, bodyJson);
        }

        @Override
        public boolean push(String localType, String coalesceKey, String bodyJson) {
            if (!isOpen()) return false;
            if (localType == null || !pushes.containsKey(localType)) {
                logLimited(extId + "|push|" + localType, Level.WARNING, "[editor] extension '" + extId + "' pushed undeclared type '"
                        + localType + "'; not sent", null);
                return false;
            }
            String body = body(localType, bodyJson);
            return body != null && session.channel.sendExtension(extId, body, null, coalesceKey);
        }

        boolean reply(String to, String localType, String bodyJson) {
            if (!isOpen()) return false;
            if (localType == null || !LOCAL.matcher(localType).matches() || RESERVED_LOCAL.contains(localType)) {
                logLimited(extId + "|reply|" + localType, Level.WARNING, "[editor] extension '" + extId + "' replied with bad type '"
                        + localType + "'; not sent", null);
                return false;
            }
            String body = body(localType, bodyJson);
            return body != null && session.channel.sendExtension(extId, body, to, null);
        }

        /** {@code {"type":"<id>.<local>", ...bodyJson}}, or {@code null} (logged) when the body is unusable. */
        private String body(String localType, String bodyJson) {
            try {
                Object parsed = EditorLoopbackJson.parse(bodyJson == null ? "{}" : bodyJson);
                if (!(parsed instanceof Map<?, ?> m)) throw new IllegalArgumentException("body is not a JSON object");
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("type", extId + "." + localType);
                for (Map.Entry<?, ?> en : m.entrySet()) {
                    if (BODY_RESERVED.contains(String.valueOf(en.getKey()))) {
                        throw new IllegalArgumentException("'" + en.getKey() + "' is reserved for core");
                    }
                    out.put(String.valueOf(en.getKey()), en.getValue());
                }
                return toJson(out);
            } catch (IllegalArgumentException e) {
                logLimited(extId + "|body|" + localType, Level.WARNING, "[editor] extension '" + extId + "' message '" + localType
                        + "' not sent: " + e.getMessage(), null);
                return null;
            }
        }

        void route(EditorChannel.Inbound in) {
            if (failed || detached) return;
            String local = in.type().substring(extId.length() + 1);
            if (!inbound.contains(local)) {
                logLimited(extId + "|in|" + local, Level.WARNING, "[editor] dropped page message '" + in.type()
                        + "': not an inbound type of extension '" + extId + "'", null);
                return;
            }
            if (!acquire()) {
                logLimited(extId + "|rate", Level.WARNING, "[editor] extension '" + extId + "' inbound rate over "
                        + INBOUND_PER_SECOND + "/s; dropping page messages", null);
                session.channel.sendExtension(extId, "{\"type\":\"" + extId + "." + ERROR + "\",\"reason\":\"rate\"}", in.from(), null);
                return;
            }
            Map<String, Object> body = new LinkedHashMap<>(in.body());
            body.keySet().removeAll(BODY_RESERVED);
            Message m = new Message(local, Collections.unmodifiableMap(body), in.from(), this);
            call(this, "onMessage", () -> ext.onMessage(m));
        }

        private synchronized boolean acquire() {
            long now = System.currentTimeMillis();
            if (now - rateWindowStart >= 1_000L) {
                rateWindowStart = now;
                rateCount = 0;
            }
            return ++rateCount <= INBOUND_PER_SECOND;
        }

        void pollState() {
            String st = callGet(extId, this, "stateJson", ext::stateJson, SLOW_CALL_NANOS);
            if (st == null) return;
            st = st.strip();
            if (st.equals(lastState)) return;
            int bytes = utf8(st);
            if (bytes > MAX_STATE_BYTES) {
                logLimited(extId + "|state", Level.WARNING, "[editor] extension '" + extId + "' state of " + bytes
                        + " bytes exceeds " + (MAX_STATE_BYTES >> 10) + " KiB; not sent", null);
                return;
            }
            if (!isJson(st)) {
                logLimited(extId + "|state", Level.WARNING, "[editor] extension '" + extId + "' state is not JSON; not sent", null);
                return;
            }
            if (session.channel.sendExtension(extId, "{\"type\":\"" + extId + "." + STATE + "\",\"state\":" + st + "}", null, null)) {
                lastState = st;
            }
        }

        void fail() {
            if (failed) return;
            failed = true;
            RTP.log(Level.WARNING, "[editor] extension '" + extId + "' disabled for this editor session after "
                    + MAX_FAILURES + " failures; core and other extensions carry on");
            session.channel.sendExtension(extId, "{\"type\":\"" + extId + "." + ERROR + "\",\"reason\":\"failed\"}", null, null);
            session.channel.unregisterNamespace(extId);
        }

        void detach() {
            if (detached) return;
            detached = true;
            session.channel.unregisterNamespace(extId);
            call(this, "sessionClosed", () -> ext.sessionClosed(this));
        }
    }

    record Message(String type, Map<String, Object> body, String sender, Bound bound) implements EditorMessage {
        @Override
        public EditorSession session() {
            return bound;
        }

        @Override
        public boolean reply(String localType, String bodyJson) {
            return bound.reply(sender, localType, bodyJson);
        }
    }

    // ---- guarded calls ----

    private static void call(Bound b, String what, Runnable r) {
        callGet(b.extId, b, what, () -> {
            r.run();
            return Boolean.TRUE;
        }, SLOW_CALL_NANOS);
    }

    /** Runs an extension call; {@code null} on failure (logged, counted against {@code b} when given). */
    private static <T> T callGet(String extId, Bound b, String what, Supplier<T> call, long slowNanos) {
        long t0 = System.nanoTime();
        try {
            return call.get();
        } catch (RuntimeException | LinkageError e) {
            logLimited(extId + "|fail|" + what, Level.WARNING, "[editor] extension '" + extId + "' " + what + " failed: " + e, e);
            if (b != null && b.failures.incrementAndGet() >= MAX_FAILURES) b.fail();
            return null;
        } finally {
            slow(extId, what, System.nanoTime() - t0, slowNanos);
        }
    }

    private static void slow(String extId, String what, long nanos, long limit) {
        if (nanos > limit) {
            logLimited(extId + "|slow|" + what, Level.INFO, "[editor] extension '" + extId + "' " + what + " took "
                    + (nanos / 1_000_000L) + " ms (budget " + (limit / 1_000_000L) + " ms)", null);
        }
    }

    private static void logLimited(String key, Level level, String line, Throwable t) {
        long now = System.currentTimeMillis();
        synchronized (LAST_LOGGED) {
            Long last = LAST_LOGGED.get(key);
            if (last != null && now - last < LOG_INTERVAL_MILLIS) return;
            if (LAST_LOGGED.size() >= 512) LAST_LOGGED.clear();
            LAST_LOGGED.put(key, now);
        }
        if (t == null) RTP.log(level, line);
        else RTP.log(level, line, t);
    }

    // ---- JSON ----

    private static boolean isJson(String s) {
        try {
            EditorLoopbackJson.parse(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** JSON text of a value tree of {@code String}, {@code Number}, {@code Boolean}, {@code Map}, {@code Collection} and {@code null}. */
    static String toJson(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, int depth) {
        if (depth > 32) throw new IllegalArgumentException("JSON nesting deeper than 32");
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            sb.append(EditorLoopbackJson.quote(s));
        } else if (v instanceof Boolean b) {
            sb.append(b);
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (!Double.isFinite(d)) throw new IllegalArgumentException("non-finite number " + d);
            sb.append(d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d));
        } else if (v instanceof Number n) {
            sb.append(n);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getKey() instanceof String k)) throw new IllegalArgumentException("JSON object key is not a string");
                if (!first) sb.append(',');
                first = false;
                sb.append(EditorLoopbackJson.quote(k)).append(':');
                write(sb, e.getValue(), depth + 1);
            }
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object o : c) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o, depth + 1);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("not a JSON value: " + v.getClass().getSimpleName());
        }
    }
}
