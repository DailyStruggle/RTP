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
 * Process-wide thread-safe memo of {@link SafetyTokenParser#parseAll(Collection)} +
 * {@link CompiledUnsafeSet#compile(Collection)}, keyed on an immutable snapshot of the
 * raw token set so {@code chunk.isSafe(...)} doesn't re-parse and re-compile per candidate.
 *
 * <p>Rejected tokens fire {@code rejectionSink} <strong>once</strong> per distinct key
 * (REQ-RTP-S-004 — audit, not spam). Entries are {@link SoftReference}-held so the cache
 * never pins compiled sets during GC pressure.
 */
public final class SafetyCompilationCache {

  private static final ConcurrentMap<Set<String>, SoftReference<CompiledUnsafeSet>> CACHE =
      new ConcurrentHashMap<>();

  private SafetyCompilationCache() {
    // Utility class.
  }

  /**
   * Look up or compute a {@link CompiledUnsafeSet} for {@code rawTokens}. Key is an
   * immutable snapshot, so callers may keep mutating their original set. {@code null} or
   * empty input returns {@link CompiledUnsafeSet#EMPTY} without touching the cache.
   * {@code rejectionSink} fires once per distinct key (REQ-RTP-S-004); pass {@code null}
   * to suppress.
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
   * Tag-snapshot-aware variant: bakes tag-bucket members into the plain-material fields via
   * {@link CompiledUnsafeSet#withTagsExpanded(Map)} so the hot path skips the snapshot
   * lookup. Caller MUST reuse the same {@code tagSnapshot} reference between reloads —
   * {@code RTPServerAccessor.rebuildBlockTagSnapshot()} produces a fresh reference, which
   * invalidates these entries by identity. Tag-free token sets bypass expansion.
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

  /** Drop all entries; called by {@code /rtp reload} and between tests. */
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
