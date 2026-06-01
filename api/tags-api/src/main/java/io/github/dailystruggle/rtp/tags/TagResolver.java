package io.github.dailystruggle.rtp.tags;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves {@link TagSource}s into {@code Map<String, Set<String>>} for RTP.
 *
 * <p>Resolution:
 * <ol>
 *   <li>Collect files; merge by {@code namespacedId} (replace vs append).</li>
 *   <li>Fixed-point expansion of {@code #} tag references; cycle-protected.</li>
 *   <li>Normalise materials (upper-case local-id, remove namespace).</li>
 *   <li>Key map by canonical {@code namespace:path} identifier.</li>
 * </ol>
 *
 * <p>The result is a union across all sources. Thread-safe and immutable.
 */
public final class TagResolver {

  private TagResolver() {
    // Utility class.
  }

  /**
   * Resolve the given sources into an immutable
   * {@code Map<String, Set<String>>} keyed by ADR-017 canonical tag-token form.
   *
   * @param sources tag sources in priority order (earlier = lower priority);
   *     must not be {@code null}. An empty list returns an empty map.
   * @return immutable snapshot; never {@code null}.
   */
  public static Map<String, Set<String>> resolve(List<TagSource> sources) {
    Objects.requireNonNull(sources, "sources");
    if (sources.isEmpty()) return Collections.emptyMap();

    // 1. Collect all tag files in source order.
    Map<String, List<String>> merged = new LinkedHashMap<>();
    for (TagSource src : sources) {
      if (src == null) continue;
      for (TagFile file : src.loadBlockTags()) {
        if (file == null) continue;
        List<String> acc =
            file.replace()
                ? new ArrayList<>()
                : merged.computeIfAbsent(file.namespacedId(), k -> new ArrayList<>());
        if (file.replace()) merged.put(file.namespacedId(), acc);
        acc.addAll(file.values());
      }
    }

    // 2. Fixed-point expansion of #-prefixed references.
    Map<String, Set<String>> expanded = new HashMap<>();
    for (String tagId : merged.keySet()) {
      expand(tagId, merged, expanded, new ArrayDeque<>());
    }

    // 3. Key by ADR-017 canonical tag-token form and normalise materials.
    Map<String, Set<String>> out = new LinkedHashMap<>();
    for (Map.Entry<String, Set<String>> e : expanded.entrySet()) {
      Set<String> normalised = new TreeSet<>();
      for (String raw : e.getValue()) {
        String canonical = canonicaliseMaterial(raw);
        if (canonical != null) normalised.add(canonical);
      }
      out.put(e.getKey(), Collections.unmodifiableSet(normalised));
    }
    return Collections.unmodifiableMap(out);
  }

  /**
   * Recursive expansion with cycle protection. {@code path} records the chain
   * of tag ids currently being expanded; if the target is already on the path,
   * we return the currently-accumulated set for it (which may be empty) to
   * break the cycle without corrupting other expansions.
   */
  private static Set<String> expand(
      String tagId,
      Map<String, List<String>> merged,
      Map<String, Set<String>> cache,
      ArrayDeque<String> path) {
    Set<String> cached = cache.get(tagId);
    if (cached != null) return cached;
    if (path.contains(tagId)) {
      // Cycle: return an empty set for now; the outer caller will still get
      // the rest of its entries. The cycle-breaking set is intentionally not
      // cached so subsequent unrelated expansions still converge correctly.
      return Collections.emptySet();
    }

    path.push(tagId);
    Set<String> out = new LinkedHashSet<>();
    List<String> raw = merged.get(tagId);
    if (raw != null) {
      for (String entry : raw) {
        if (entry == null || entry.isEmpty()) continue;
        if (entry.charAt(0) == '#') {
          String ref = normaliseReference(entry.substring(1));
          Set<String> resolved = expand(ref, merged, cache, path);
          out.addAll(resolved);
        } else {
          out.add(entry);
        }
      }
    }
    path.pop();
    Set<String> immutable = Collections.unmodifiableSet(out);
    cache.put(tagId, immutable);
    return immutable;
  }

  /**
   * Ensure a reference has a namespace, defaulting to {@code "minecraft"} for
   * bare ids — matching vanilla loader behaviour.
   */
  private static String normaliseReference(String ref) {
    return ref.indexOf(':') >= 0 ? ref : "minecraft:" + ref;
  }

  /**
   * Canonicalise a material entry to the upper-case local-id form used by
   * Bukkit's {@code Material.name()} and by
   * {@code RTPServerAccessor.materials()}. Drops the namespace; returns
   * {@code null} if the entry is malformed (empty, no local id).
   */
  static String canonicaliseMaterial(String raw) {
    if (raw == null) return null;
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) return null;
    int colon = trimmed.indexOf(':');
    String local = (colon >= 0) ? trimmed.substring(colon + 1) : trimmed;
    if (local.isEmpty()) return null;
    return local.toUpperCase(Locale.ROOT);
  }
}
