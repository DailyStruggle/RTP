package io.github.dailystruggle.rtp.api.safety;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable key/value predicate applied to a block's {@code BlockData} properties.
 *
 * <p>A {@code StatePredicate} represents the bracketed portion of a safety-list token
 * as defined by <a href="../../../../../../../../../../docs/adr/ADR-017-block-tags-and-state-predicates-in-safety-lists.md">ADR-017</a>
 * &sect;1 — e.g. the {@code waterlogged=true} in {@code OAK_SLAB[waterlogged=true]}.
 * Multiple properties combine with logical <strong>AND</strong>: a live block matches
 * iff every configured property equals (case-insensitively) the block's property of the
 * same name.</p>
 *
 * <p>Both keys and values are stored as lowercase {@link String} to avoid the
 * Turkish-i locale trap and to mirror the string form produced by
 * {@code org.bukkit.block.data.BlockData#getAsString()} (which the platform adapter
 * parses into a {@code Map<String, String>} before calling {@link #matches(Map)}). No
 * typed {@code BlockData} resolution is performed at construction time — the compiler
 * intentionally has zero Bukkit dependency so it can live in {@code rtp-api}.</p>
 *
 * <p>The original token (e.g. {@code "OAK_SLAB[waterlogged=true]"}) is retained in
 * {@link #sourceToken()} for diagnostic WARN logging of failed predicate evaluation —
 * never silent per REQ-RTP-S-004.</p>
 *
 * <p>Instances are deeply immutable and therefore thread-safe.</p>
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
