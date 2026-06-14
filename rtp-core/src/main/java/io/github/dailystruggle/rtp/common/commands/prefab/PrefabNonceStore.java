package io.github.dailystruggle.rtp.common.commands.prefab;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store of pending prefab-apply confirmations, keyed by
 * {@code (callerId, prefabId)}. Each entry binds together the originating
 * caller, the prefab id, the per-file diff computed by {@link PrefabApplier},
 * and the precomputed new YAML trees that {@code confirm} will write to disk.
 *
 * <p>Post-token-removal (2026-05-24, mirroring ADR-050): there is no opaque
 * token, no TTL, and no expiry. The previous design minted a UUID-shaped
 * string and surfaced it on the chat/book path; the click/chat surfaces are
 * already caller-bound through Bukkit's command sender, so the token only
 * added typing friction and produced "token expired" UX failures. The verb
 * {@code /rtp admin prefab confirm id=&lt;id&gt;} now resolves the pending
 * diff straight from {@code (callerId, prefabId)}. A repeat {@code apply}
 * for the same {@code (caller, prefab)} pair simply overwrites the prior
 * pending entry.
 *
 * <p>The store is process-local and not persisted - a restart drops every
 * outstanding confirmation. Rollback is the recovery path. All methods are
 * thread-safe.
 *
 * <p>The class name {@code PrefabNonceStore} is preserved for now to keep
 * the existing call sites and tests compiling; the "nonce" framing no
 * longer reflects the semantics. Rename is mechanical follow-up work.
 */
public final class PrefabNonceStore {

    /**
     * One outstanding confirmation. Immutable once {@link #mint} returns.
     * The {@code token} field is retained as the empty string for binary
     * compatibility with tests that still call {@code entry.token()}; new
     * call sites should ignore it.
     *
     * @param token       always the empty string; retained for call-site compatibility
     * @param callerId    UUID of the player or console that initiated the apply
     * @param prefabId    id of the prefab being confirmed
     * @param perFileDiff per-file list of changes computed by {@link PrefabApplier}
     * @param newTrees    precomputed merged YAML trees to write on confirm
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
