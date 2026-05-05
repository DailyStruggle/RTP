package io.github.dailystruggle.rtp.api.safety;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable AND-of-equalities predicate over a block's {@code BlockData} properties —
 * the bracketed part of a safety-list token, e.g. {@code [waterlogged=true]} in
 * {@code OAK_SLAB[waterlogged=true]} (ADR-017 §1). Keys and values are stored lowercase
 * (Turkish-i safe) and compared as strings against the parsed form of
 * {@code BlockData#getAsString()}; no typed Bukkit resolution — {@code rtp-api} stays
 * platform-free. {@link #sourceToken()} retains the original token for REQ-RTP-S-004
 * diagnostic logging. Deeply immutable, thread-safe.
 */
public final class StatePredicate {

  private final Map<String, String> properties;
  private final String sourceToken;

  /**
   * Construct an immutable state predicate.
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
    Objects.requireNonNull(properties, "properties");
    Objects.requireNonNull(sourceToken, "sourceToken");
    if (properties.isEmpty()) {
      throw new IllegalArgumentException("StatePredicate must have at least one property");
    }
    Map<String, String> copy = new LinkedHashMap<>(properties.size());
    for (Map.Entry<String, String> e : properties.entrySet()) {
      String k = Objects.requireNonNull(e.getKey(), "property key").toLowerCase(Locale.ROOT);
      String v = Objects.requireNonNull(e.getValue(), "property value").toLowerCase(Locale.ROOT);
      copy.put(k, v);
    }
    this.properties = Collections.unmodifiableMap(copy);
    this.sourceToken = sourceToken;
  }

  /**
   * @return the lowercase property map (unmodifiable, never {@code null} or empty).
   */
  public Map<String, String> properties() {
    return properties;
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
   * @return {@code true} iff every configured property is present in
   *     {@code liveProperties} with an equal value.
   */
  public boolean matches(Map<String, String> liveProperties) {
    if (liveProperties == null || liveProperties.isEmpty()) return false;
    for (Map.Entry<String, String> e : properties.entrySet()) {
      String actual = liveProperties.get(e.getKey());
      if (actual == null) return false;
      if (!e.getValue().equals(actual)) return false;
    }
    return true;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof StatePredicate)) return false;
    StatePredicate that = (StatePredicate) o;
    return properties.equals(that.properties) && sourceToken.equals(that.sourceToken);
  }

  @Override
  public int hashCode() {
    return Objects.hash(properties, sourceToken);
  }

  @Override
  public String toString() {
    return "StatePredicate{" + properties + " from='" + sourceToken + "'}";
  }
}
