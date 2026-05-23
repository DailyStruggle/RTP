package io.github.dailystruggle.rtp.common.commands.prefab;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * In-memory store of pending prefab-apply confirmations. Each entry is keyed
 * by a freshly-minted opaque token and binds together the originating caller,
 * the prefab id, the per-file diff computed by {@link PrefabApplier}, and an
 * absolute expiry timestamp (caller-clock milliseconds).
 *
 * <p>Per the locked design decision (2026-05-20), there is no {@code --commit}
 * flag - the {@code /rtp admin prefab apply <id>} verb stashes a nonce here
 * and surfaces it through the chat / book confirmation menu. The user then
 * types (or clicks) {@code /rtp admin prefab confirm <id> <token>} within
 * the ~60 s TTL.
 *
 * <p>The store is process-local and not persisted - a restart drops every
 * outstanding confirmation, which is the intended behaviour (rollback is the
 * recovery path, not nonce persistence). All methods are thread-safe.
 *
 * <p>Eviction is opportunistic: every {@link #mint} and {@link #consume} call
 * sweeps expired entries first. No separate scheduler thread is needed -
 * abandoned tokens age out and are reaped on the next mutation. This matches
 * the proposal §3.4 contract.
 */
public final class PrefabNonceStore {

    /** Default TTL applied by {@link #mint(UUID, String, Map)}. */
    public static final long DEFAULT_TTL_MILLIS = 60_000L;

    /**
     * One outstanding confirmation. Immutable once {@link #mint} returns.
     */
    public record Entry(
            String token,
            UUID callerId,
            String prefabId,
            Map<String, List<PrefabApplier.Change>> perFileDiff,
            Map<String, Map<String, Object>> newTrees,
            long expiresAtMillis
    ) {
        public Entry {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(callerId, "callerId");
            Objects.requireNonNull(prefabId, "prefabId");
            Objects.requireNonNull(perFileDiff, "perFileDiff");
            Objects.requireNonNull(newTrees, "newTrees");
        }
    }

    private final Map<String, Entry> byToken = new ConcurrentHashMap<>();
    private final java.util.function.LongSupplier clock;
    private final long ttlMillis;

    /** Production constructor: system clock, 60 s TTL. */
    public PrefabNonceStore() {
        this(System::currentTimeMillis, DEFAULT_TTL_MILLIS);
    }

    /** Test constructor: injectable clock + TTL for deterministic expiry checks. */
    public PrefabNonceStore(java.util.function.LongSupplier clock, long ttlMillis) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be > 0, was " + ttlMillis);
        }
        this.ttlMillis = ttlMillis;
    }

    /**
     * Mint and stash a new confirmation. Sweeps expired entries first.
     *
     * @return the freshly-minted entry. The token is unique within the store's lifetime.
     */
    public Entry mint(UUID callerId, String prefabId,
                      Map<String, List<PrefabApplier.Change>> perFileDiff) {
        return mint(callerId, prefabId, perFileDiff, java.util.Collections.emptyMap());
    }

    /**
     * Mint a confirmation that also stashes the new YAML trees the
     * {@code confirm} verb will write to disk. The 4a single-arg overload
     * is preserved for the existing tests, which only validate the security
     * shape (token TTL, single-use, caller / prefab binding) and pass an
     * empty diff.
     */
    public Entry mint(UUID callerId, String prefabId,
                      Map<String, List<PrefabApplier.Change>> perFileDiff,
                      Map<String, Map<String, Object>> newTrees) {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(prefabId, "prefabId");
        Objects.requireNonNull(perFileDiff, "perFileDiff");
        Objects.requireNonNull(newTrees, "newTrees");
        sweep();
        String token;
        do {
            token = generateToken();
        } while (byToken.containsKey(token));
        Entry e = new Entry(token, callerId, prefabId, perFileDiff, newTrees,
                clock.getAsLong() + ttlMillis);
        byToken.put(token, e);
        return e;
    }

    /**
     * Validate and consume a token. The token is removed from the store on
     * <em>any</em> outcome other than {@code NOT_FOUND}: a wrong
     * caller, expired entry, or mismatched prefab id is single-use just as a
     * successful confirm is. This makes replay impossible.
     */
    public ConsumeResult consume(String token, UUID callerId, String prefabId) {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(prefabId, "prefabId");
        sweep();
        if (token == null || token.isEmpty()) return ConsumeResult.notFound();
        Entry e = byToken.remove(token);
        if (e == null) return ConsumeResult.notFound();
        if (clock.getAsLong() > e.expiresAtMillis) return ConsumeResult.expired();
        if (!e.callerId.equals(callerId)) return ConsumeResult.wrongCaller();
        if (!e.prefabId.equals(prefabId)) return ConsumeResult.wrongPrefab();
        return ConsumeResult.ok(e);
    }

    /**
     * Token-less variant of {@link #consume}: locate the newest non-expired
     * entry minted by {@code callerId} for {@code prefabId} and consume it.
     * Required by the menu Confirm path, which dispatches
     * {@code /rtp admin prefab confirm id=&lt;id&gt;} without surfacing the
     * opaque nonce to the player (the click is already caller-bound through
     * Bukkit's command sender — the nonce was a chat-path replay defense
     * only). Returns {@link ConsumeResult#notFound()} when no eligible entry
     * exists; identical semantics to {@link #consume} otherwise.
     */
    public ConsumeResult consumeByCaller(UUID callerId, String prefabId) {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(prefabId, "prefabId");
        sweep();
        // Iterate to find the newest (latest expiresAtMillis) matching entry.
        // ConcurrentHashMap iteration is weakly consistent — fine here since
        // the caller-bound mint -> consume sequence is single-threaded per
        // player.
        Entry newest = null;
        for (Entry e : byToken.values()) {
            if (!e.callerId.equals(callerId)) continue;
            if (!e.prefabId.equals(prefabId)) continue;
            if (newest == null || e.expiresAtMillis > newest.expiresAtMillis) {
                newest = e;
            }
        }
        if (newest == null) return ConsumeResult.notFound();
        // Remove via token to preserve the single-use invariant.
        byToken.remove(newest.token);
        if (clock.getAsLong() > newest.expiresAtMillis) return ConsumeResult.expired();
        return ConsumeResult.ok(newest);
    }

    /** Number of currently-outstanding (non-expired) entries. Test-friendly. */
    public int size() {
        sweep();
        return byToken.size();
    }

    private void sweep() {
        long now = clock.getAsLong();
        byToken.entrySet().removeIf(en -> now > en.getValue().expiresAtMillis);
    }

    private static String generateToken() {
        // 96 random bits, base-36 encoded - short enough to type on the chat
        // confirm path, wide enough to make guessing impractical within the
        // 60 s TTL. Two longs ensure no upstream PRNG seeding collision.
        long hi = ThreadLocalRandom.current().nextLong();
        long lo = ThreadLocalRandom.current().nextLong();
        return Long.toUnsignedString(hi, 36) + Long.toUnsignedString(lo, 36);
    }

    /** Outcome of {@link #consume}. */
    public static final class ConsumeResult {
        public enum Kind { OK, NOT_FOUND, EXPIRED, WRONG_CALLER, WRONG_PREFAB }

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
        static ConsumeResult expired() { return new ConsumeResult(Kind.EXPIRED, null); }
        static ConsumeResult wrongCaller() { return new ConsumeResult(Kind.WRONG_CALLER, null); }
        static ConsumeResult wrongPrefab() { return new ConsumeResult(Kind.WRONG_PREFAB, null); }
    }
}
