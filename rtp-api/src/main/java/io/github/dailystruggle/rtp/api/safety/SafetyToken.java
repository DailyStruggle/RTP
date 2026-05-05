package io.github.dailystruggle.rtp.api.safety;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Single parsed entry of a safety-list value (ADR-017 §1 grammar). Shapes:
 * MATERIAL with upper-snake {@code Material.name()} or wildcard {@code "*"};
 * TAG with lowercase {@code namespace:path} (no leading {@code #}). Predicates
 * may be empty (plain) or non-empty. Bare {@code *} is rejected at parse time.
 * No tag expansion performed here (platform adapter's responsibility, ADR-017 §2).
 * Immutable and thread-safe.
 */
public final class SafetyToken {

  /** The two top-level shapes of a safety token. */
  public enum Kind {
    /** A material identifier (upper-snake {@code Material.name()}), possibly the wildcard {@code *}. */
    MATERIAL,
    /** A Mojang-style {@code namespace:path} tag reference (no leading {@code #} on the parsed form). */
    TAG
  }

  /** Reserved wildcard material identifier (ADR-017 &sect;1). */
  public static final String WILDCARD = "*";

  private final Kind kind;
  private final String identifier;
  private final List<StatePredicate> predicates;
  private final String sourceToken;

  private SafetyToken(Kind kind, String identifier, List<StatePredicate> predicates,
                      String sourceToken) {
    this.kind = Objects.requireNonNull(kind, "kind");
    this.identifier = Objects.requireNonNull(identifier, "identifier");
    this.predicates = predicates == null || predicates.isEmpty()
        ? Collections.emptyList()
        : Collections.unmodifiableList(List.copyOf(predicates));
    this.sourceToken = Objects.requireNonNull(sourceToken, "sourceToken");
  }

  /**
   * Build a material token (possibly wildcard).
   *
   * @param identifier upper-snake material name, or {@link #WILDCARD}. Must be non-empty.
   * @param predicates optional bracket predicates; may be {@code null} or empty.
   * @param sourceToken original token string, retained for diagnostics.
   * @throws IllegalArgumentException if {@code identifier} is empty, or if it is the
   *     wildcard and {@code predicates} is empty (bare {@code *} is rejected by
   *     ADR-017 &sect;1).
   */
  public static SafetyToken material(String identifier, List<StatePredicate> predicates,
                                     String sourceToken) {
    Objects.requireNonNull(identifier, "identifier");
    if (identifier.isEmpty()) {
      throw new IllegalArgumentException("material identifier must be non-empty");
    }
    boolean wildcard = WILDCARD.equals(identifier);
    boolean noPredicates = predicates == null || predicates.isEmpty();
    if (wildcard && noPredicates) {
      throw new IllegalArgumentException(
          "bare wildcard '*' is not a valid safety token; it must carry at least one "
              + "state predicate (e.g. '*[waterlogged=true]'). See ADR-017 \u00a71.");
    }
    return new SafetyToken(Kind.MATERIAL, identifier, predicates, sourceToken);
  }

  /**
   * Build a tag token.
   *
   * @param namespaceAndPath lowercase {@code namespace:path} (no leading {@code #}).
   * @param predicates optional bracket predicates; may be {@code null} or empty.
   * @param sourceToken original token string, retained for diagnostics.
   */
  public static SafetyToken tag(String namespaceAndPath, List<StatePredicate> predicates,
                                String sourceToken) {
    Objects.requireNonNull(namespaceAndPath, "namespaceAndPath");
    if (!namespaceAndPath.contains(":")) {
      throw new IllegalArgumentException(
          "tag identifier must contain a ':' (namespace:path); got '" + namespaceAndPath + "'");
    }
    return new SafetyToken(Kind.TAG, namespaceAndPath, predicates, sourceToken);
  }

  /** @return the kind of this token ({@link Kind#MATERIAL} or {@link Kind#TAG}). */
  public Kind kind() {
    return kind;
  }

  /**
   * @return the token's identifier: upper-snake material name (or {@link #WILDCARD}) for
   *     {@link Kind#MATERIAL}, lowercase {@code namespace:path} for {@link Kind#TAG}.
   */
  public String identifier() {
    return identifier;
  }

  /** @return unmodifiable list of state predicates; empty for plain material/tag tokens. */
  public List<StatePredicate> predicates() {
    return predicates;
  }

  /** @return the original token string as read from config, for diagnostics. */
  public String sourceToken() {
    return sourceToken;
  }

  /** @return {@code true} iff this is a {@link Kind#MATERIAL} token with the wildcard identifier. */
  public boolean isWildcard() {
    return kind == Kind.MATERIAL && WILDCARD.equals(identifier);
  }

  /** @return {@code true} iff this token carries at least one state predicate. */
  public boolean isPredicated() {
    return !predicates.isEmpty();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof SafetyToken)) return false;
    SafetyToken that = (SafetyToken) o;
    return kind == that.kind
        && identifier.equals(that.identifier)
        && predicates.equals(that.predicates);
  }

  @Override
  public int hashCode() {
    return Objects.hash(kind, identifier, predicates);
  }

  @Override
  public String toString() {
    return "SafetyToken{" + kind + " " + identifier
        + (predicates.isEmpty() ? "" : predicates)
        + " from='" + sourceToken + "'}";
  }
}
