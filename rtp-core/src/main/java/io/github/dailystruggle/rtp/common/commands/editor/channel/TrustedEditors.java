package io.github.dailystruggle.rtp.common.commands.editor.channel;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.editor.EditorLoopbackJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Browser keys the operator trusted in game (ADR-106 §5.2), by SPKI SHA-256 fingerprint.
 *
 * <p>Persisted as {@code <dataFolder>/editor/trusted-editors.json}
 * ({@code {"version":1,"trusted":[{"fingerprint":"..","added":<ms>}]}}) with a tmp-file + atomic
 * replace. A corrupt file is logged and treated as empty; the next successful trust rewrites it.
 *
 * <p>With a max age ({@code advanced/network.yml editor.trust.maxAgeDays}) an entry stops being
 * trusted that long after {@code added}; rows without {@code added} (or {@code 0}) count from load.
 */
public final class TrustedEditors {

    static final int MAX_ENTRIES = 256;
    static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
    /** {@code all}, or a fingerprint prefix long enough not to match by accident. */
    private static final Pattern SELECTOR = Pattern.compile("all|[0-9a-f]{4,64}");
    private static final Object FILE_LOCK = new Object();

    private final Path file;
    private final long maxAgeMillis;
    private final LongSupplier clock;
    private final Map<String, Long> trusted = new LinkedHashMap<>();
    private final Set<String> revoked = new LinkedHashSet<>();

    private TrustedEditors(Path file, long maxAgeMillis, LongSupplier clock) {
        this.file = file;
        this.maxAgeMillis = Math.max(0L, maxAgeMillis);
        this.clock = clock;
    }

    /** Memory only: nothing persisted (tests). */
    public static TrustedEditors inMemory() {
        return new TrustedEditors(null, 0L, System::currentTimeMillis);
    }

    /** Reads {@code file}; missing means empty, corrupt is logged and treated as empty. Off the main thread. */
    public static TrustedEditors load(Path file) {
        return load(file, 0L, System::currentTimeMillis);
    }

    /** As {@link #load(Path)}; entries older than {@code maxAgeMillis} ({@code <= 0}: never) are not trusted. */
    public static TrustedEditors load(Path file, long maxAgeMillis) {
        return load(file, maxAgeMillis, System::currentTimeMillis);
    }

    static TrustedEditors load(Path file, long maxAgeMillis, LongSupplier clock) {
        TrustedEditors t = new TrustedEditors(file, maxAgeMillis, clock);
        if (file == null || !Files.isRegularFile(file)) return t;
        long now = clock.getAsLong();
        int expired = 0;
        try {
            Object root = EditorLoopbackJson.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!(root instanceof Map<?, ?> m) || !(m.get("trusted") instanceof List<?> rows)) {
                throw new IllegalArgumentException("missing 'trusted' list");
            }
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> r) || !(r.get("fingerprint") instanceof String fp)
                        || !FINGERPRINT.matcher(fp).matches()) {
                    throw new IllegalArgumentException("malformed trusted entry");
                }
                long added = r.get("added") instanceof Number n ? n.longValue() : 0L;
                if (added <= 0L) added = now;
                if (t.expired(added, now)) {
                    expired++;
                    continue;
                }
                if (t.trusted.size() < MAX_ENTRIES) t.trusted.put(fp, added);
            }
        } catch (IOException | IllegalArgumentException e) {
            t.trusted.clear();
            RTP.log(Level.WARNING, "[editor] trusted editor list " + file + " is unreadable; treating it as empty: "
                    + e.getMessage());
            return t;
        }
        if (expired > 0) {
            RTP.log(Level.INFO, "[editor] " + expired + " trusted editor key(s) in " + file + " passed the trust max age"
                    + " and must be trusted again");
        }
        return t;
    }

    private boolean expired(long added, long now) {
        return maxAgeMillis > 0L && now - added >= maxAgeMillis;
    }

    /** Trusted and, with a max age, still inside it (an expired entry is dropped from memory). */
    public synchronized boolean isTrusted(String fingerprint) {
        if (fingerprint == null) return false;
        Long added = trusted.get(fingerprint);
        if (added == null) return false; // in-memory revocation is authoritative for this instance
        if (expired(added, clock.getAsLong())) {
            trusted.remove(fingerprint);
            return false;
        }
        return true;
    }

    /** {@code all} or 4-64 lowercase hex characters: what {@link #remove} and {@link #forget} accept. */
    public static boolean isSelector(String selector) {
        return selector != null && SELECTOR.matcher(selector).matches();
    }

    /**
     * Untrusts every fingerprint starting with {@code selector} (or all for {@code all}) and persists
     * the list when anything changed.
     *
     * @return the removed fingerprints
     * @throws IOException when the list cannot be written (the keys stay untrusted for this run)
     */
    public Set<String> remove(String selector) throws IOException {
        synchronized (FILE_LOCK) {
            synchronized (this) {
                if (file != null && Files.isRegularFile(file)) {
                    TrustedEditors disk = load(file, maxAgeMillis, clock);
                    for (Map.Entry<String, Long> entry : disk.trusted.entrySet()) {
                        if (!revoked.contains(entry.getKey())) {
                            trusted.putIfAbsent(entry.getKey(), entry.getValue());
                        }
                    }
                }
                Set<String> removed = forget(selector);
                if (!removed.isEmpty() && file != null) write();
                return removed;
            }
        }
    }

    /** As {@link #remove} in memory only: for lists that share their file with a persisted one. */
    public synchronized Set<String> forget(String selector) {
        if (!isSelector(selector)) throw new IllegalArgumentException("not a fingerprint prefix or 'all'");
        Set<String> removed = new LinkedHashSet<>();
        for (Iterator<String> it = trusted.keySet().iterator(); it.hasNext(); ) {
            String fp = it.next();
            if (selector.equals("all") || fp.startsWith(selector)) {
                removed.add(fp);
                it.remove();
            }
        }
        revoked.addAll(removed);
        return removed;
    }

    /**
     * Trusts {@code fingerprint} and persists the list. The oldest entry makes room past
     * {@link #MAX_ENTRIES}.
     *
     * @throws IOException when the list cannot be written (the key stays trusted for this run)
     */
    public void add(String fingerprint, long now) throws IOException {
        if (fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("not a key fingerprint");
        }
        synchronized (FILE_LOCK) {
            synchronized (this) {
                revoked.remove(fingerprint);
                if (file != null && Files.isRegularFile(file)) {
                    TrustedEditors disk = load(file, maxAgeMillis, clock);
                    for (Map.Entry<String, Long> entry : disk.trusted.entrySet()) {
                        if (!revoked.contains(entry.getKey())) {
                            trusted.putIfAbsent(entry.getKey(), entry.getValue());
                        }
                    }
                }
                trusted.remove(fingerprint);
                trusted.put(fingerprint, now);
                while (trusted.size() > MAX_ENTRIES) trusted.remove(trusted.keySet().iterator().next());
                if (file != null) write();
            }
        }
    }

    public synchronized Set<String> fingerprints() {
        if (file != null && Files.isRegularFile(file)) {
            TrustedEditors disk = load(file, maxAgeMillis, clock);
            for (Map.Entry<String, Long> entry : disk.trusted.entrySet()) {
                if (!revoked.contains(entry.getKey())) {
                    trusted.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        }
        return Set.copyOf(trusted.keySet());
    }

    private void write() throws IOException {
        StringBuilder sb = new StringBuilder("{\"version\":1,\"trusted\":[");
        boolean first = true;
        for (Map.Entry<String, Long> e : trusted.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"fingerprint\":\"").append(e.getKey()).append("\",\"added\":").append(e.getValue()).append('}');
        }
        sb.append("]}\n");
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(tmp, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(tmp);
            EditorKeys.restrictToOwner(tmp);
        }
        Files.writeString(tmp, sb, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        EditorKeys.restrictToOwner(file);
    }
}
