package io.github.dailystruggle.rtp.common.commands.editor.channel;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.editor.EditorLoopbackJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Browser keys the operator trusted in game (ADR-106 §5.2), by SPKI SHA-256 fingerprint.
 *
 * <p>Persisted as {@code <dataFolder>/editor/trusted-editors.json}
 * ({@code {"version":1,"trusted":[{"fingerprint":"..","added":<ms>}]}}) with a tmp-file + atomic
 * replace. A corrupt file is logged and treated as empty; the next successful trust rewrites it.
 */
public final class TrustedEditors {

    static final int MAX_ENTRIES = 256;
    static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

    private final Path file;
    private final Map<String, Long> trusted = new LinkedHashMap<>();

    private TrustedEditors(Path file) {
        this.file = file;
    }

    /** Memory only: nothing persisted (tests). */
    public static TrustedEditors inMemory() {
        return new TrustedEditors(null);
    }

    /** Reads {@code file}; missing means empty, corrupt is logged and treated as empty. Off the main thread. */
    public static TrustedEditors load(Path file) {
        TrustedEditors t = new TrustedEditors(file);
        if (file == null || !Files.isRegularFile(file)) return t;
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
                if (t.trusted.size() < MAX_ENTRIES) t.trusted.put(fp, added);
            }
        } catch (IOException | IllegalArgumentException e) {
            t.trusted.clear();
            RTP.log(Level.WARNING, "[editor] trusted editor list " + file + " is unreadable; treating it as empty: "
                    + e.getMessage());
        }
        return t;
    }

    public synchronized boolean isTrusted(String fingerprint) {
        return fingerprint != null && trusted.containsKey(fingerprint);
    }

    /**
     * Trusts {@code fingerprint} and persists the list. The oldest entry makes room past
     * {@link #MAX_ENTRIES}.
     *
     * @throws IOException when the list cannot be written (the key stays trusted for this run)
     */
    public synchronized void add(String fingerprint, long now) throws IOException {
        if (fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("not a key fingerprint");
        }
        trusted.remove(fingerprint);
        trusted.put(fingerprint, now);
        while (trusted.size() > MAX_ENTRIES) trusted.remove(trusted.keySet().iterator().next());
        if (file != null) write();
    }

    public synchronized Set<String> fingerprints() {
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
        Files.writeString(tmp, sb, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
