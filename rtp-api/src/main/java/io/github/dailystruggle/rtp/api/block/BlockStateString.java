package io.github.dailystruggle.rtp.api.block;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Platform-neutral parse of a Minecraft block-state string of the form
 * {@code namespace:id[key=value,...]} (e.g. {@code minecraft:oak_log[axis=y]}).
 *
 * <p>This is the structural tokenizer shared by two consumers that speak the same
 * grammar (ADR-058 Amendment 1):
 *
 * <ul>
 *   <li>{@code SafetyTokenParser} (ADR-017), which tokenizes {@code safety.yml} entries
 *       (it uses {@link #split} for the head/bracketed-body split, then applies its own
 *       predicate / comparator semantics to the body);
 *   <li>the in-house Sponge schematic decoder, whose palette entries are full block-state
 *       strings handed verbatim to each platform's native block parser
 *       ({@code Bukkit.createBlockData(String)} / Fabric {@code BlockArgumentParser}).
 * </ul>
 *
 * <p>Instances are immutable. The parse is intentionally permissive about the head
 * (it does not require a {@code namespace:}); {@link #namespace()} returns
 * {@code "minecraft"} by default when no explicit namespace is present, matching the
 * vanilla resolution rule.
 */
public final class BlockStateString {
  private final String head;
  private final String namespace;
  private final String path;
  private final Map<String, String> properties;

  private BlockStateString(String head, String namespace, String path,
                           Map<String, String> properties) {
    this.head = head;
    this.namespace = namespace;
    this.path = path;
    this.properties = Collections.unmodifiableMap(properties);
  }

  /**
   * Splits a token into its head (the text before {@code [}) and its bracketed body
   * (the text between {@code [} and the trailing {@code ]}), without interpreting the
   * body. This is the exact structural rule shared with {@code SafetyTokenParser}.
   *
   * @param token the raw token; never {@code null}
   * @return the split; {@link Split#body()} is {@code null} when no bracket is present
   * @throws IllegalArgumentException when the brackets are unbalanced or misplaced, or
   *     when the head is empty
   */
  public static Split split(String token) {
    Objects.requireNonNull(token, "token");
    String t = token.trim();
    if (t.isEmpty()) {
      throw new IllegalArgumentException("token is empty or whitespace-only");
    }
    int open = t.indexOf('[');
    int close = t.lastIndexOf(']');
    if (open < 0 && close < 0) {
      return new Split(t, null);
    }
    if (open >= 0 && close > open && close == t.length() - 1) {
      String head = t.substring(0, open);
      if (head.isEmpty()) {
        throw new IllegalArgumentException("token has no identifier before '['");
      }
      return new Split(head, t.substring(open + 1, close));
    }
    throw new IllegalArgumentException(
        "unbalanced or misplaced '[' / ']' brackets; expected 'id[key=value,...]'");
  }

  /**
   * Parses a full block-state string into its namespace, path, and ordered properties.
   * Property values are preserved verbatim (not lower-cased) so the canonical form can be
   * round-tripped to a platform parser.
   *
   * @param token the raw block-state string; never {@code null}
   * @return the parsed block state
   * @throws IllegalArgumentException on malformed brackets or a malformed {@code key=value}
   *     predicate
   */
  public static BlockStateString parse(String token) {
    Split s = split(token);
    String head = s.head();
    String namespace;
    String path;
    int colon = head.indexOf(':');
    if (colon < 0) {
      namespace = "minecraft";
      path = head;
    } else {
      namespace = head.substring(0, colon);
      path = head.substring(colon + 1);
    }

    Map<String, String> props = new LinkedHashMap<>();
    String body = s.body();
    if (body != null) {
      String trimmed = body.trim();
      if (trimmed.isEmpty()) {
        throw new IllegalArgumentException("empty '[]' body; expected 'key=value[,key=value...]'");
      }
      for (String rawPart : trimmed.split(",", -1)) {
        String part = rawPart.trim();
        int eq = part.indexOf('=');
        if (eq <= 0 || eq == part.length() - 1) {
          throw new IllegalArgumentException(
              "malformed property '" + part + "'; expected 'key=value' with non-empty sides");
        }
        String key = part.substring(0, eq).trim();
        String value = part.substring(eq + 1).trim();
        if (key.isEmpty() || value.isEmpty()) {
          throw new IllegalArgumentException(
              "malformed property '" + part + "'; expected 'key=value' with non-empty sides");
        }
        props.put(key, value);
      }
    }
    return new BlockStateString(head, namespace, path, props);
  }

  /** @return the head (everything before {@code [}), e.g. {@code minecraft:oak_log}. */
  public String head() {
    return head;
  }

  /** @return the namespace, defaulting to {@code minecraft} when none was present. */
  public String namespace() {
    return namespace;
  }

  /** @return the path (the part after {@code namespace:}), e.g. {@code oak_log}. */
  public String path() {
    return path;
  }

  /** @return the ordered, unmodifiable property map (empty when no {@code [...]} body). */
  public Map<String, String> properties() {
    return properties;
  }

  /** @return {@code true} when this is {@code minecraft:air} (any namespace form). */
  public boolean isAir() {
    return "minecraft".equals(namespace)
        && ("air".equals(path) || "cave_air".equals(path) || "void_air".equals(path));
  }

  /**
   * @return the canonical {@code namespace:path[k=v,...]} string (properties in insertion
   *     order), suitable for handing to a platform block-state parser.
   */
  public String canonical() {
    StringBuilder sb = new StringBuilder(namespace).append(':').append(path);
    if (!properties.isEmpty()) {
      sb.append('[');
      boolean first = true;
      for (Map.Entry<String, String> e : properties.entrySet()) {
        if (!first) {
          sb.append(',');
        }
        sb.append(e.getKey()).append('=').append(e.getValue());
        first = false;
      }
      sb.append(']');
    }
    return sb.toString();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof BlockStateString)) return false;
    BlockStateString that = (BlockStateString) o;
    return namespace.equals(that.namespace)
        && path.equals(that.path)
        && properties.equals(that.properties);
  }

  @Override
  public int hashCode() {
    return Objects.hash(namespace, path, properties);
  }

  @Override
  public String toString() {
    return canonical();
  }

  /** Lowercases the head for case-insensitive id comparison. */
  public String lowerCaseHead() {
    return head.toLowerCase(Locale.ROOT);
  }

  /** Structural head/body split result (see {@link #split}). */
  public static final class Split {
    private final String head;
    private final String body;

    Split(String head, String body) {
      this.head = head;
      this.body = body;
    }

    /** @return the text before {@code [}; never {@code null} or empty. */
    public String head() {
      return head;
    }

    /** @return the text between {@code [} and {@code ]}, or {@code null} when absent. */
    public String body() {
      return body;
    }
  }
}
