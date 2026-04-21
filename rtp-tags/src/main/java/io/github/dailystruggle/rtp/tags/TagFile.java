package io.github.dailystruggle.rtp.tags;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable DTO representing the parsed contents of a single Minecraft tag JSON
 * file (e.g. {@code data/minecraft/tags/block/leaves.json}).
 *
 * <p>The vanilla tag schema is:
 * <pre>{@code
 * {
 *   "replace": false,
 *   "values": [
 *     "minecraft:oak_leaves",
 *     "#minecraft:wart_blocks",
 *     { "id": "minecraft:spruce_leaves", "required": false }
 *   ]
 * }
 * }</pre>
 *
 * <p>Values may be plain resource-location strings, tag references prefixed with
 * {@code #}, or JSON objects with an {@code id} field and optional
 * {@code required} boolean. Non-required entries are silently dropped by the
 * parser if they cannot be resolved — matching vanilla's permissive loader
 * behaviour. This DTO preserves the raw string form of every retained entry;
 * reference expansion is deferred to {@link TagReferenceResolver}.
 *
 * <p>All fields are immutable and the class is thread-safe.
 *
 * @see TinyJsonReader for the parser that produces instances of this class.
 */
public final class TagFile {

  /** Canonical tag identifier, e.g. {@code "minecraft:leaves"}. Never {@code null}. */
  private final String namespacedId;

  /**
   * If {@code true}, this file's {@code values} list <b>replaces</b> any
   * previously-registered entries for the same {@link #namespacedId}. If
   * {@code false} (the vanilla default), the values are appended.
   */
  private final boolean replace;

  /**
   * Raw entry strings in declaration order. Tag references retain their leading
   * {@code #}; plain entries are namespaced resource locations
   * (e.g. {@code "minecraft:oak_leaves"}). Never {@code null}; may be empty.
   */
  private final List<String> values;

  public TagFile(String namespacedId, boolean replace, List<String> values) {
    this.namespacedId = Objects.requireNonNull(namespacedId, "namespacedId");
    this.replace = replace;
    this.values = List.copyOf(Objects.requireNonNull(values, "values"));
  }

  public String namespacedId() {
    return namespacedId;
  }

  public boolean replace() {
    return replace;
  }

  public List<String> values() {
    return values;
  }

  /** Convenience factory for test fixtures and for resolver internals. */
  public static TagFile of(String namespacedId, boolean replace, List<String> values) {
    return new TagFile(namespacedId, replace, values);
  }

  /** Convenience factory for an empty, non-replacing tag file. */
  public static TagFile empty(String namespacedId) {
    return new TagFile(namespacedId, false, Collections.emptyList());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof TagFile other)) return false;
    return replace == other.replace
        && namespacedId.equals(other.namespacedId)
        && values.equals(other.values);
  }

  @Override
  public int hashCode() {
    return Objects.hash(namespacedId, replace, values);
  }

  @Override
  public String toString() {
    return "TagFile{id="
        + namespacedId
        + ", replace="
        + replace
        + ", values="
        + values
        + '}';
  }
}
