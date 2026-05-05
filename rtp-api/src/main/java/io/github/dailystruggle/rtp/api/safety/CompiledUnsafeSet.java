package io.github.dailystruggle.rtp.api.safety;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compiled, evaluation-ready form of a safety list (ADR-017 §2). Five disjoint
 * buckets computed once at config load: plain materials, resolved tag keys,
 * per-material state predicates, per-tag state predicates, wildcard state
 * predicates. Hot eval order (ADR-017 §4): plain name → tag membership →
 * state predicates. Tag expansion is a platform concern; live tag membership
 * is supplied by the caller via {@link #isUnsafe(String, Collection, Map)}.
 * Empty membership is fail-open per ADR-017 §3. Immutable and thread-safe.
 */
public final class CompiledUnsafeSet {

  /** Empty singleton. Useful for default values and for "no safety list configured" callers. */
  public static final CompiledUnsafeSet EMPTY = new CompiledUnsafeSet(
      Collections.emptySet(),
      Collections.emptySet(),
      Collections.emptyMap(),
      Collections.emptyMap(),
      Collections.emptyList());

  private final Set<String> plainMaterials;
  private final Set<String> plainTags;
  private final Map<String, List<StatePredicate>> materialStatePredicates;
  private final Map<String, List<StatePredicate>> tagStatePredicates;
  private final List<StatePredicate> wildcardStatePredicates;

  private CompiledUnsafeSet(Set<String> plainMaterials,
                            Set<String> plainTags,
                            Map<String, List<StatePredicate>> materialStatePredicates,
                            Map<String, List<StatePredicate>> tagStatePredicates,
                            List<StatePredicate> wildcardStatePredicates) {
    this.plainMaterials = plainMaterials;
    this.plainTags = plainTags;
    this.materialStatePredicates = materialStatePredicates;
    this.tagStatePredicates = tagStatePredicates;
    this.wildcardStatePredicates = wildcardStatePredicates;
  }

  /**
   * Compile parsed tokens (from {@link SafetyTokenParser#parseAll}) into an
   * evaluation-ready structure. Bucketed by kind; duplicates coalesce; sets are
   * insertion-ordered for diagnostics. Returns {@link #EMPTY} for null/empty input.
   */
  public static CompiledUnsafeSet compile(Collection<SafetyToken> tokens) {
    if (tokens == null || tokens.isEmpty()) return EMPTY;

    Set<String> plainMaterials = new LinkedHashSet<>();
    Set<String> plainTags = new LinkedHashSet<>();
    Map<String, List<StatePredicate>> materialStates = new LinkedHashMap<>();
    Map<String, List<StatePredicate>> tagStates = new LinkedHashMap<>();
    List<StatePredicate> wildcardStates = new ArrayList<>();

    for (SafetyToken tok : tokens) {
      if (tok == null) continue;
      switch (tok.kind()) {
        case MATERIAL:
          if (tok.isWildcard()) {
            // SafetyToken.material already rejected bare '*'; defensive assert:
            if (tok.predicates().isEmpty()) continue;
            wildcardStates.addAll(tok.predicates());
          } else if (tok.isPredicated()) {
            materialStates.computeIfAbsent(tok.identifier(), k -> new ArrayList<>())
                .addAll(tok.predicates());
          } else {
            plainMaterials.add(tok.identifier());
          }
          break;
        case TAG:
          if (tok.isPredicated()) {
            tagStates.computeIfAbsent(tok.identifier(), k -> new ArrayList<>())
                .addAll(tok.predicates());
          } else {
            plainTags.add(tok.identifier());
          }
          break;
        default:
          // Unknown kind: defensive no-op. Cannot occur with the current enum.
          break;
      }
    }

    return new CompiledUnsafeSet(
        Collections.unmodifiableSet(plainMaterials),
        Collections.unmodifiableSet(plainTags),
        freezeValues(materialStates),
        freezeValues(tagStates),
        Collections.unmodifiableList(wildcardStates));
  }

  private static Map<String, List<StatePredicate>> freezeValues(
      Map<String, List<StatePredicate>> in) {
    if (in.isEmpty()) return Collections.emptyMap();
    Map<String, List<StatePredicate>> out = new LinkedHashMap<>(in.size());
    for (Map.Entry<String, List<StatePredicate>> e : in.entrySet()) {
      out.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
    }
    return Collections.unmodifiableMap(out);
  }

  /**
   * Return a new {@code CompiledUnsafeSet} with tag tokens expanded into plain
   * material entries, using the given {@code tagSnapshot} as the source of
   * truth for tag → material-name membership.
   *
   * <p>Post-expansion:
   * <ul>
   *   <li>{@link #plainTags()} is cleared (entries merged into
   *       {@link #plainMaterials()}).</li>
   *   <li>{@link #tagStatePredicates()} is cleared; each tag-scoped predicate
   *       list is replicated under every material that belongs to that tag in
   *       {@link #materialStatePredicates()}, preserving AND-semantics per
   *       ADR-017 §2.</li>
   *   <li>{@link #wildcardStatePredicates()} and
   *       {@link #materialStatePredicates()} entries already present are
   *       untouched; material-scoped predicates are concatenated when a
   *       material also appears via tag expansion.</li>
   * </ul>
   *
   * <p>Tags that are not present in the snapshot are dropped silently — the
   * caller (typically {@code SafetyCompilationCache}) is responsible for
   * surfacing rejected raw tokens before compilation; a valid tag token that
   * simply has no members in the live snapshot is not a configuration error
   * (it just contributes no constraints).
   *
   * <p>If the snapshot is {@code null} or empty and this set has no tag
   * entries, {@code this} is returned unchanged for cheap identity reuse.
   *
   * @param tagSnapshot {@code namespace:path} → upper-case material names. Keys
   *     shall match the form used by {@code SafetyToken.TAG.identifier()} and
   *     by {@code RTPServerAccessor.blockTagSnapshot()}.
   * @return a new {@code CompiledUnsafeSet} with no tag buckets, or
   *     {@code this} if no expansion was needed.
   */
  public CompiledUnsafeSet withTagsExpanded(Map<String, Set<String>> tagSnapshot) {
    boolean hasTagBuckets = !plainTags.isEmpty() || !tagStatePredicates.isEmpty();
    if (!hasTagBuckets) return this;
    if (tagSnapshot == null) tagSnapshot = Collections.emptyMap();

    Set<String> expandedPlainMaterials = new LinkedHashSet<>(plainMaterials);
    Map<String, List<StatePredicate>> expandedMatPreds = new LinkedHashMap<>();
    for (Map.Entry<String, List<StatePredicate>> e : materialStatePredicates.entrySet()) {
      expandedMatPreds.put(e.getKey(), new ArrayList<>(e.getValue()));
    }

    // Expand plain tags -> plain materials.
    for (String tagKey : plainTags) {
      Set<String> members = tagSnapshot.get(tagKey);
      if (members == null || members.isEmpty()) continue;
      expandedPlainMaterials.addAll(members);
    }

    // Expand tag state predicates -> per-material state predicates.
    for (Map.Entry<String, List<StatePredicate>> e : tagStatePredicates.entrySet()) {
      Set<String> members = tagSnapshot.get(e.getKey());
      if (members == null || members.isEmpty()) continue;
      for (String mat : members) {
        // If the material is already plain-unsafe, a state predicate adds
        // nothing — skip to keep the per-material predicate list minimal.
        if (expandedPlainMaterials.contains(mat)) continue;
        expandedMatPreds.computeIfAbsent(mat, k -> new ArrayList<>()).addAll(e.getValue());
      }
    }

    return new CompiledUnsafeSet(
        Collections.unmodifiableSet(expandedPlainMaterials),
        Collections.emptySet(),
        freezeValues(expandedMatPreds),
        Collections.emptyMap(),
        wildcardStatePredicates);
  }

  // ---------------------------------------------------------------------------
  // Accessors
  // ---------------------------------------------------------------------------

  /** @return unmodifiable set of plain upper-snake material names (no state predicate). */
  public Set<String> plainMaterials() {
    return plainMaterials;
  }

  /** @return unmodifiable set of plain {@code namespace:path} tag keys (no state predicate). */
  public Set<String> plainTags() {
    return plainTags;
  }

  /** @return unmodifiable map of material name → list of state predicates. */
  public Map<String, List<StatePredicate>> materialStatePredicates() {
    return materialStatePredicates;
  }

  /** @return unmodifiable map of {@code namespace:path} tag key → list of state predicates. */
  public Map<String, List<StatePredicate>> tagStatePredicates() {
    return tagStatePredicates;
  }

  /**
   * @return unmodifiable list of wildcard state predicates applied to every candidate.
   *     Non-empty iff any token of the form {@code *[...]} was configured.
   */
  public List<StatePredicate> wildcardStatePredicates() {
    return wildcardStatePredicates;
  }

  /**
   * @return {@code true} iff at least one wildcard state predicate exists. Platform
   *     adapters use this flag to short-circuit {@code BlockData} allocation in the hot
   *     path when no wildcard is configured (ADR-017 &sect;4).
   */
  public boolean hasWildcardStatePredicate() {
    return !wildcardStatePredicates.isEmpty();
  }

  /**
   * @return {@code true} iff this set has no plain material, no plain tag, no state
   *     predicate of any kind. An empty set always returns {@code false} from
   *     {@link #isUnsafe(String, Collection, Map)}.
   */
  public boolean isEmpty() {
    return plainMaterials.isEmpty()
        && plainTags.isEmpty()
        && materialStatePredicates.isEmpty()
        && tagStatePredicates.isEmpty()
        && wildcardStatePredicates.isEmpty();
  }

  // ---------------------------------------------------------------------------
  // Evaluation
  // ---------------------------------------------------------------------------

  /**
   * Convenience overload for the common case of a block whose tag membership is unknown
   * and which has no state properties.
   *
   * <p>Equivalent to {@link #isUnsafe(String, Collection, Map) isUnsafe(materialName,
   * Collections.emptyList(), Collections.emptyMap())}.</p>
   *
   * @param materialName upper-snake {@code Material.name()} of the candidate block.
   * @param liveProperties lowercase property map (nullable / empty allowed). Required
   *     only if a state predicate may apply.
   * @return {@code true} iff this compiled set would reject a block of the given material
   *     with the given properties.
   */
  public boolean isUnsafe(String materialName, Map<String, String> liveProperties) {
    return isUnsafe(materialName, Collections.emptyList(), liveProperties);
  }

  /**
   * Is this block unsafe? Evaluation order, first match wins (ADR-017 §4, cheapest first):
   * plain material → plain tag → material-scoped state preds → tag-scoped state preds →
   * wildcard state preds. {@code materialName} {@code null}/empty → no match.
   * {@code liveTagMembership} may be empty (Anvil off-tick has no snapshot).
   * {@code liveProperties} {@code null}/empty fail-opens any state predicate that would
   * otherwise apply.
   */
  public boolean isUnsafe(String materialName,
                          Collection<String> liveTagMembership,
                          Map<String, String> liveProperties) {
    if (materialName == null || materialName.isEmpty()) return false;

    // 1. Plain material membership — set lookup, cheapest possible.
    if (plainMaterials.contains(materialName)) return true;

    // 2. Plain tag membership.
    if (!plainTags.isEmpty() && liveTagMembership != null && !liveTagMembership.isEmpty()) {
      for (String tag : liveTagMembership) {
        if (plainTags.contains(tag)) return true;
      }
    }

    // 3. Material-scoped state predicates.
    List<StatePredicate> matPreds = materialStatePredicates.get(materialName);
    if (matPreds != null && matchesAny(matPreds, liveProperties)) return true;

    // 4. Tag-scoped state predicates.
    if (!tagStatePredicates.isEmpty()
        && liveTagMembership != null && !liveTagMembership.isEmpty()) {
      for (String tag : liveTagMembership) {
        List<StatePredicate> tagPreds = tagStatePredicates.get(tag);
        if (tagPreds != null && matchesAny(tagPreds, liveProperties)) return true;
      }
    }

    // 5. Wildcard state predicates.
    if (!wildcardStatePredicates.isEmpty()
        && matchesAny(wildcardStatePredicates, liveProperties)) {
      return true;
    }

    return false;
  }

  /**
   * Convenience: lowercase-normalize a live property map in one place so that platform
   * adapters do not each re-implement the same loop. Both keys and values are lowercased
   * under {@link Locale#ROOT}.
   *
   * @param raw raw property map as produced by parsing {@code BlockData.getAsString()}
   *     or the NBT palette {@code Properties} compound; may be {@code null}.
   * @return an unmodifiable lowercase map; never {@code null}.
   */
  public static Map<String, String> normalizeLiveProperties(Map<String, String> raw) {
    if (raw == null || raw.isEmpty()) return Collections.emptyMap();
    Map<String, String> out = new LinkedHashMap<>(raw.size());
    for (Map.Entry<String, String> e : raw.entrySet()) {
      if (e.getKey() == null || e.getValue() == null) continue;
      out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().toLowerCase(Locale.ROOT));
    }
    return Collections.unmodifiableMap(out);
  }

  private static boolean matchesAny(List<StatePredicate> preds, Map<String, String> props) {
    if (preds == null || preds.isEmpty()) return false;
    // Fail-open if no property map is available yet the caller reached this branch: the
    // predicate simply cannot match.
    if (props == null || props.isEmpty()) return false;
    for (StatePredicate p : preds) {
      if (p.matches(props)) return true;
    }
    return false;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof CompiledUnsafeSet)) return false;
    CompiledUnsafeSet that = (CompiledUnsafeSet) o;
    return plainMaterials.equals(that.plainMaterials)
        && plainTags.equals(that.plainTags)
        && materialStatePredicates.equals(that.materialStatePredicates)
        && tagStatePredicates.equals(that.tagStatePredicates)
        && wildcardStatePredicates.equals(that.wildcardStatePredicates);
  }

  @Override
  public int hashCode() {
    return Objects.hash(plainMaterials, plainTags, materialStatePredicates,
        tagStatePredicates, wildcardStatePredicates);
  }

  @Override
  public String toString() {
    return "CompiledUnsafeSet{"
        + "plainMaterials=" + plainMaterials
        + ", plainTags=" + plainTags
        + ", materialStatePredicates=" + materialStatePredicates
        + ", tagStatePredicates=" + tagStatePredicates
        + ", wildcardStatePredicates=" + wildcardStatePredicates
        + '}';
  }
}
