package io.github.dailystruggle.rtp.api.safety;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable AND-predicate over {@code BlockData} properties (ADR-017).
 *
 * <p>Supports string equality ({@code key=value}) and numeric comparisons ({@code key>=n}, {@code key<=n}, etc.).
 * Property names and string values are lowercased under {@link Locale#ROOT}.
 */
public final class StatePredicate {

  /** Numeric comparison operator usable in a range predicate (ADR-017 section 1 extension). */
  public enum Comparator {
    /** {@code >=} - live value greater than or equal to the bound. */
    GE(">=") {
      @Override
      boolean test(long live, long bound) {
        return live >= bound;
      }
    },
    /** {@code <=} - live value less than or equal to the bound. */
    LE("<=") {
      @Override
      boolean test(long live, long bound) {
        return live <= bound;
      }
    },
    /** {@code >} - live value strictly greater than the bound. */
    GT(">") {
      @Override
      boolean test(long live, long bound) {
        return live > bound;
      }
    },
    /** {@code <} - live value strictly less than the bound. */
    LT("<") {
      @Override
      boolean test(long live, long bound) {
        return live < bound;
      }
    };

    private final String symbol;

    Comparator(String symbol) {
      this.symbol = symbol;
    }

    /** @return the operator's literal symbol as it appears in a token (e.g. {@code ">="}). */
    public String symbol() {
      return symbol;
    }

    abstract boolean test(long live, long bound);
  }

  /**
   * One numeric range condition: {@code key op bound}, e.g. {@code level >= 5}. Immutable.
   */
  public static final class NumericComparison {
    private final String key;
    private final Comparator op;
    private final long bound;

    /**
     * @param key the lowercase property name. Must be non-{@code null}.
     * @param op the comparison operator. Must be non-{@code null}.
     * @param bound the integer bound parsed from the token.
     */
    public NumericComparison(String key, Comparator op, long bound) {
      this.key = Objects.requireNonNull(key, "key").toLowerCase(Locale.ROOT);
      this.op = Objects.requireNonNull(op, "op");
      this.bound = bound;
    }

    /** @return the lowercase property name this comparison applies to. */
    public String key() {
      return key;
    }

    /** @return the comparison operator. */
    public Comparator op() {
      return op;
    }

    /** @return the integer bound. */
    public long bound() {
      return bound;
    }

    /**
     * Test this comparison against a live property value.
     *
     * @param liveValue the live property value as a string; may be {@code null}.
     * @return {@code true} iff {@code liveValue} parses as a {@code long} and satisfies the
     *     operator against the bound. A {@code null} or non-numeric value is a miss.
     */
    boolean matches(String liveValue) {
      if (liveValue == null) {
        return false;
      }
      long live;
      try {
        live = Long.parseLong(liveValue.trim());
      } catch (NumberFormatException e) {
        return false;
      }
      return op.test(live, bound);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof NumericComparison)) return false;
      NumericComparison that = (NumericComparison) o;
      return bound == that.bound && key.equals(that.key) && op == that.op;
    }

    @Override
    public int hashCode() {
      return Objects.hash(key, op, bound);
    }

    @Override
    public String toString() {
      return key + op.symbol() + bound;
    }
  }

  private final Map<String, String> properties;
  private final List<NumericComparison> comparisons;
  private final String sourceToken;

  /**
   * Constructs an immutable equality-only state predicate.
   *
   * @param properties  map of property names to expected values (non-null, non-empty)
   * @param sourceToken original config token for diagnostics (non-null)
   */
  public StatePredicate(Map<String, String> properties, String sourceToken) {
    this(properties, Collections.emptyList(), sourceToken, true);
  }

  /**
   * Constructs an immutable state predicate with equality and numeric range conditions (ADR-017).
   *
   * @param properties  map of property names to expected values (non-null)
   * @param comparisons list of numeric range comparisons (non-null)
   * @param sourceToken original config token for diagnostics (non-null)
   */
  public StatePredicate(Map<String, String> properties,
                        List<NumericComparison> comparisons,
                        String sourceToken) {
    this(properties, comparisons, sourceToken, false);
  }

  private StatePredicate(Map<String, String> properties,
                         List<NumericComparison> comparisons,
                         String sourceToken,
                         boolean legacyRequiresProperties) {
    Objects.requireNonNull(properties, "properties");
    Objects.requireNonNull(comparisons, "comparisons");
    Objects.requireNonNull(sourceToken, "sourceToken");
    if (legacyRequiresProperties && properties.isEmpty()) {
      throw new IllegalArgumentException("StatePredicate must have at least one property");
    }
    if (properties.isEmpty() && comparisons.isEmpty()) {
      throw new IllegalArgumentException(
          "StatePredicate must have at least one equality or comparison condition");
    }
    Map<String, String> copy = new LinkedHashMap<>(properties.size());
    for (Map.Entry<String, String> e : properties.entrySet()) {
      String k = Objects.requireNonNull(e.getKey(), "property key").toLowerCase(Locale.ROOT);
      String v = Objects.requireNonNull(e.getValue(), "property value").toLowerCase(Locale.ROOT);
      copy.put(k, v);
    }
    this.properties = Collections.unmodifiableMap(copy);
    List<NumericComparison> copyCmp = new ArrayList<>(comparisons.size());
    for (NumericComparison c : comparisons) {
      copyCmp.add(Objects.requireNonNull(c, "comparison"));
    }
    this.comparisons = Collections.unmodifiableList(copyCmp);
    this.sourceToken = sourceToken;
  }

  /**
   * @return the lowercase equality property map (unmodifiable; never {@code null}, may be
   *     empty when the predicate carries only numeric comparisons).
   */
  public Map<String, String> properties() {
    return properties;
  }

  /**
   * @return the unmodifiable list of numeric range comparisons (never {@code null}, may be
   *     empty).
   */
  public List<NumericComparison> comparisons() {
    return comparisons;
  }

  /**
   * @return the original source token as read from config, for diagnostic logging.
   */
  public String sourceToken() {
    return sourceToken;
  }

  /**
   * Tests whether every equality and comparison is satisfied by the supplied block properties.
   *
   * <p>Missing properties result in a miss ({@code false}) per ADR-017 fail-open rules.
   *
   * @param liveProperties lowercase property map for the live block, or {@code null}
   * @return {@code true} if all conditions match
   */
  public boolean matches(Map<String, String> liveProperties) {
    if (liveProperties == null || liveProperties.isEmpty()) return false;
    for (Map.Entry<String, String> e : properties.entrySet()) {
      String actual = liveProperties.get(e.getKey());
      if (actual == null) return false;
      if (!e.getValue().equals(actual)) return false;
    }
    for (NumericComparison c : comparisons) {
      if (!c.matches(liveProperties.get(c.key()))) return false;
    }
    return true;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof StatePredicate)) return false;
    StatePredicate that = (StatePredicate) o;
    return properties.equals(that.properties)
        && comparisons.equals(that.comparisons)
        && sourceToken.equals(that.sourceToken);
  }

  @Override
  public int hashCode() {
    return Objects.hash(properties, comparisons, sourceToken);
  }

  @Override
  public String toString() {
    return "StatePredicate{" + properties
        + (comparisons.isEmpty() ? "" : " " + comparisons)
        + " from='" + sourceToken + "'}";
  }
}
