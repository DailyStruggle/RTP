package io.github.dailystruggle.rtp.api.safety;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable AND-of-conditions predicate over a block's {@code BlockData} properties —
 * the bracketed part of a safety-list token, e.g. {@code [waterlogged=true]} in
 * {@code OAK_SLAB[waterlogged=true]} (ADR-017 §1). Two condition flavours are carried:
 * <ul>
 *   <li><b>String equalities</b> ({@code key=value}) — keys and values stored lowercase
 *       (Turkish-i safe) and compared as strings against the parsed form of
 *       {@code BlockData#getAsString()}.</li>
 *   <li><b>Numeric range comparisons</b> ({@code key>=n}, {@code key<=n}, {@code key>n},
 *       {@code key<n}) — the live property value is parsed as a {@code long} and compared
 *       against the configured bound (ADR-017 amendment, "numeric range
 *       predicates"). A live value that is absent or not an integer is a <b>miss</b>,
 *       consistent with the fail-open policy in ADR-017 §4.</li>
 * </ul>
 * No typed Bukkit resolution — {@code rtp-api} stays platform-free. {@link #sourceToken()}
 * retains the original token for REQ-RTP-S-004 diagnostic logging. Deeply immutable,
 * thread-safe.
 */
public final class StatePredicate {

  /** Numeric comparison operator usable in a range predicate (ADR-017 §1 extension). */
  public enum Comparator {
    /** {@code >=} — live value greater than or equal to the bound. */
    GE(">=") {
      @Override
      boolean test(long live, long bound) {
        return live >= bound;
      }
    },
    /** {@code <=} — live value less than or equal to the bound. */
    LE("<=") {
      @Override
      boolean test(long live, long bound) {
        return live <= bound;
      }
    },
    /** {@code >} — live value strictly greater than the bound. */
    GT(">") {
      @Override
      boolean test(long live, long bound) {
        return live > bound;
      }
    },
    /** {@code <} — live value strictly less than the bound. */
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
   * Construct an immutable equality-only state predicate.
   *
   * @param properties map of property name to expected value. May not be {@code null} or
   *     empty. Keys and values are lowercased under {@link Locale#ROOT} and copied
   *     defensively into an insertion-ordered, unmodifiable map.
   * @param sourceToken the original token string as read from config, retained for
   *     diagnostics. May not be {@code null}.
   * @throws NullPointerException if any argument is {@code null} or any property key /
   *     value is {@code null}.
   * @throws IllegalArgumentException if {@code properties} is empty.
   */
  public StatePredicate(Map<String, String> properties, String sourceToken) {
    this(properties, Collections.emptyList(), sourceToken, true);
  }

  /**
   * Construct an immutable state predicate carrying both string equalities and numeric
   * range comparisons (ADR-017 §1 extension).
   *
   * @param properties map of property name to expected value (string equalities). May be
   *     empty but not {@code null}. Keys and values are lowercased under
   *     {@link Locale#ROOT}.
   * @param comparisons list of numeric range comparisons. May be empty but not
   *     {@code null}.
   * @param sourceToken the original token string as read from config. May not be
   *     {@code null}.
   * @throws NullPointerException if any argument or element is {@code null}.
   * @throws IllegalArgumentException if both {@code properties} and {@code comparisons}
   *     are empty.
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
   * Test whether every property in this predicate is satisfied by the supplied live
   * block property map.
   *
   * <p>Per ADR-017 &sect;4, if the live block does not carry a property that the
   * predicate requires, that is treated as a <strong>miss</strong> (return
   * {@code false}), not a match. This is the fail-open behaviour on an unsafe list —
   * missing properties cannot cause an over-rejection that would block teleports.</p>
   *
   * @param liveProperties map of lowercase property name to lowercase property value for
   *     the live block. May be empty. If {@code null}, returns {@code false}.
   * @return {@code true} iff every configured equality and every numeric range comparison
   *     is satisfied by {@code liveProperties}.
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
