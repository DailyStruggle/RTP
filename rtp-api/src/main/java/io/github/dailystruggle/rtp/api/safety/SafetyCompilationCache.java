package io.github.dailystruggle.rtp.api.safety;

import java.lang.ref.SoftReference;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * Thread-safe cache that memoizes {@link SafetyTokenParser#parseAll(Collection)} +
 * {@link CompiledUnsafeSet#compile(Collection)} against the raw {@code Set<String>} form
 * that legacy callers pass through {@code RTPChunk.isSafe(..., Set&lt;String&gt;)}.
 *
 * <p>The hot path in {@code LocationGenerator} calls {@code chunk.isSafe(...)} once per
 * candidate column. Without caching, every call would re-parse the same token list and
 * re-compile the same {@link CompiledUnsafeSet}. This cache short-circuits that
 * recompile by keying on an immutable canonical snapshot of the raw token set.</p>
 *
 * <p>Rejected tokens produced during parsing are surfaced to a caller-supplied
 * {@link Consumer} <strong>once</strong> per distinct key, to honour REQ-RTP-S-004's
 * "never silent" rule without spamming the log on every candidate. After the first
 * warning for a given key, subsequent lookups reuse the cached result silently.</p>
 *
 * <p>Cache entries are held behind {@link SoftReference}s so the cache never pins
 * compiled sets during GC pressure, and the cache itself is {@link ConcurrentHashMap}-backed
 * for wait-free lookups. The cache is a process-wide singleton because the raw
 * {@code Set<String>} identity is determined by config load, not by any per-world or
 * per-region state.</p>
 *
 * <p>This class is immutable in its API surface (no mutable public state); only the
 * internal memoization table changes over time. All methods are thread-safe.</p>
 */
public final class SafetyCompilationCache {

  private static final ConcurrentMap<Set<String>, SoftReference<CompiledUnsafeSet>> CACHE =
      new ConcurrentHashMap<>();

  private SafetyCompilationCache() {
    // Utility class.
  }

  /**
   * Look up or compute a {@link CompiledUnsafeSet} for the given raw token set.
   *
   * <p>The lookup key is an immutable snapshot of {@code rawTokens}; callers may continue
   * to mutate their original set without invalidating the cached entry. If
   * {@code rawTokens} is {@code null} or empty, {@link CompiledUnsafeSet#EMPTY} is
   * returned without touching the cache.</p>
   *
   * @param rawTokens raw token strings as read from config; may be {@code null}.
   * @param rejectionSink optional consumer invoked <strong>once per distinct key</strong>
   *     with the human-readable form of each rejected token (REQ-RTP-S-004). May be
   *     {@code null} to suppress the warning callback entirely — the rejected list is
   *     still reachable via {@link SafetyTokenParser#parseAll(Collection)} if the caller
   *     wants to drive its own logging.
   * @return a non-null {@link CompiledUnsafeSet}.
   */
  public static CompiledUnsafeSet getOrCompile(
      Collection<String> rawTokens, Consumer<SafetyTokenParser.Rejection> rejectionSink) {
    if (rawTokens == null || rawTokens.isEmpty()) return CompiledUnsafeSet.EMPTY;

    Set<String> key = snapshot(rawTokens);
    if (key.isEmpty()) return CompiledUnsafeSet.EMPTY;

    // Fast path: cache hit.
    SoftReference<CompiledUnsafeSet> ref = CACHE.get(key);
    if (ref != null) {
      CompiledUnsafeSet cached = ref.get();
      if (cached != null) return cached;
      // Soft reference was cleared — fall through and recompile. A concurrent thread
      // may also be recomputing; the last writer wins, which is fine because the
      // compiled result is value-equal for a given key.
    }

    // Slow path: parse + compile + memoize. One-time rejection reporting happens here,
    // before we publish the entry to the cache, so each distinct key gets exactly one
    // WARN pass regardless of how many concurrent callers arrive.
    SafetyTokenParser.ParseResult result = SafetyTokenParser.parseAll(key);
    if (rejectionSink != null && result.hasRejections()) {
      for (SafetyTokenParser.Rejection r : result.rejected()) {
        try {
          rejectionSink.accept(r);
        } catch (RuntimeException ignored) {
          // Never let a misbehaving sink break the safety pipeline.
        }
      }
    }
    CompiledUnsafeSet compiled = CompiledUnsafeSet.compile(result.accepted());
    CACHE.put(key, new SoftReference<>(compiled));
    return compiled;
  }

  /**
   * Convenience overload that suppresses the rejection callback.
   *
   * @param rawTokens raw token strings as read from config; may be {@code null}.
   * @return a non-null {@link CompiledUnsafeSet}.
   */
  public static CompiledUnsafeSet getOrCompile(Collection<String> rawTokens) {
    return getOrCompile(rawTokens, null);
  }

  /**
   * Snapshot-aware variant that post-expands tag tokens in the compiled set via
   * {@link CompiledUnsafeSet#withTagsExpanded(Map)}. The returned set has no
   * tag buckets — every tag token's constituent materials have been baked into
   * the plain-material / material-state-predicate fields — so the hot path in
   * {@code RTPChunk.isSafe(...)} never needs to consult the snapshot per
   * candidate.
   *
   * <p>Cache identity: entries are keyed on the pair
   * {@code (rawTokens, System.identityHashCode(tagSnapshot))}. Callers are
   * therefore required to pass the same snapshot reference between reloads;
   * rebuilding the snapshot (see
   * {@code RTPServerAccessor.rebuildBlockTagSnapshot()}) invalidates cache
   * entries automatically because the new snapshot yields a different
   * identity hash. A {@code null} snapshot is treated as empty and keyed on
   * identity {@code 0} — distinct from an empty non-null snapshot.
   *
   * <p>When the compiled token set has no tag buckets at all, the snapshot is
   * ignored and the underlying non-snapshot entry is returned (tag-free tokens
   * do not depend on the snapshot).
   *
   * @param rawTokens raw token strings as read from config; may be {@code null}.
   * @param tagSnapshot lowercase {@code namespace:path} → upper-case material
   *     names; may be {@code null} or empty.
   * @param rejectionSink optional consumer invoked once per distinct key.
   * @return a non-null {@link CompiledUnsafeSet} with tag tokens baked into
   *     plain-material entries.
   */
  public static CompiledUnsafeSet getOrCompile(
      Collection<String> rawTokens,
      Map<String, Set<String>> tagSnapshot,
      Consumer<SafetyTokenParser.Rejection> rejectionSink) {
    CompiledUnsafeSet base = getOrCompile(rawTokens, rejectionSink);
    if (base == CompiledUnsafeSet.EMPTY) return base;
    // If there are no tag buckets, expansion is a no-op and we reuse the base.
    if (base.plainTags().isEmpty() && base.tagStatePredicates().isEmpty()) return base;
    return base.withTagsExpanded(tagSnapshot);
  }

  /**
   * Clear the entire cache. Intended for use during {@code /rtp reload} when a config
   * change may have invalidated previously-cached compilations for other reasons (e.g.
   * a platform tag-registry snapshot refresh in a future slice). Test code also calls
   * this between scenarios to prevent cross-test leakage.
   */
  public static void clear() {
    CACHE.clear();
  }

  /** @return the current number of live (non-collected) cache entries, for diagnostics. */
  public static int size() {
    int live = 0;
    for (SoftReference<CompiledUnsafeSet> r : CACHE.values()) {
      if (r != null && r.get() != null) live++;
    }
    return live;
  }

  /**
   * Produce an immutable snapshot of the caller's token set. {@code null} entries are
   * dropped; remaining entries preserve insertion order so that parser diagnostics are
   * deterministic across JVMs.
   */
  private static Set<String> snapshot(Collection<String> raw) {
    Objects.requireNonNull(raw, "raw");
    Set<String> out = new LinkedHashSet<>(raw.size());
    for (String s : raw) {
      if (s == null) continue;
      out.add(s);
    }
    return Collections.unmodifiableSet(out);
  }
}
