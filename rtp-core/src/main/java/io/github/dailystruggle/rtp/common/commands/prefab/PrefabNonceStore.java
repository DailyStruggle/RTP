package io.github.dailystruggle.rtp.common.commands.prefab;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store of pending prefab-apply confirmations keyed by {@code (callerId, prefabId)}.
 * Holds diffs and precomputed trees ready for disk commit. Thread-safe, in-memory only.
 */
public final class PrefabNonceStore {

    /**
     * One outstanding confirmation descriptor.
     *
     * @param token       retained empty string for binary compatibility
     * @param callerId    UUID of player or console initiating the apply
     * @param prefabId    identifier of the prefab being confirmed
     * @param perFileDiff per-file list of changes
     * @param newTrees    precomputed merged trees to write on confirm
     */
    public record Entry(
            String token,
            UUID callerId,
            String prefabId,
            Map<String, List<PrefabApplier.Change>> perFileDiff,
            Map<String, Map<String, Object>> newTrees
    ) {
        public Entry {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(callerId, "callerId");
            Objects.requireNonNull(prefabId, "prefabId");
            Objects.requireNonNull(perFileDiff, "perFileDiff");
            Objects.requireNonNull(newTrees, "newTrees");
        }
    }

    private record Key(UUID callerId, String prefabId) { }

    private final Map<Key, Entry> byCaller = new ConcurrentHashMap<>();

    /** Production constructor. */
    public PrefabNonceStore() {
    }

    /**
     * Stash a pending confirmation keyed on {@code (callerId, prefabId)}.
     * Overwrites any prior pending entry for the same pair.
     *
     * <p>Method name {@code mint} is preserved for call-site compatibility;
     * no token is generated.
     */
    public Entry mint(UUID callerId, String prefabId,
                      Map<String, List<PrefabApplier.Change>> perFileDiff) {
        return mint(callerId, prefabId, perFileDiff, java.util.Collections.emptyMap());
    }

    /**
     * Stash a pending confirmation that also carries the new YAML trees the
     * {@code confirm} verb will write to disk.
     */
    public Entry mint(UUID callerId, String prefabId,
                      Map<String, List<PrefabApplier.Change>> perFileDiff,
                      Map<String, Map<String, Object>> newTrees) {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(prefabId, "prefabId");
        Objects.requireNonNull(perFileDiff, "perFileDiff");
        Objects.requireNonNull(newTrees, "newTrees");
        Entry e = new Entry("", callerId, prefabId, perFileDiff, newTrees);
        byCaller.put(new Key(callerId, prefabId), e);
        return e;
    }

    /**
     * Resolve and consume the pending confirmation for
     * {@code (callerId, prefabId)}. Single-use: the entry is removed on any
     * outcome other than {@code NOT_FOUND}.
     */
    public ConsumeResult consumeByCaller(UUID callerId, String prefabId) {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(prefabId, "prefabId");
        Entry e = byCaller.remove(new Key(callerId, prefabId));
        if (e == null) return ConsumeResult.notFound();
        return ConsumeResult.ok(e);
    }

    /** Number of currently-outstanding entries. Test-friendly. */
    public int size() {
        return byCaller.size();
    }

    /** Outcome of {@link #consumeByCaller}. */
    public static final class ConsumeResult {
        public enum Kind { OK, NOT_FOUND }

        private final Kind kind;
        private final Entry entry;

        private ConsumeResult(Kind kind, Entry entry) {
            this.kind = kind;
            this.entry = entry;
        }

        public Kind kind() { return kind; }
        public Entry entry() { return entry; }
        public boolean ok() { return kind == Kind.OK; }

        static ConsumeResult ok(Entry e) { return new ConsumeResult(Kind.OK, e); }
        static ConsumeResult notFound() { return new ConsumeResult(Kind.NOT_FOUND, null); }
    }
}
