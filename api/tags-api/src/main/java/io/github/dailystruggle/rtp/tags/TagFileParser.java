package io.github.dailystruggle.rtp.tags;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses tag JSON into {@link TagFile}, supporting plain strings, # references,
 * and objects with {@code id} / {@code required} fields.
 *
 * <p>Malformed object entries (missing ID) are skipped, matching vanilla's
 * permissive loader. Parsing is thread-safe.
 */
public final class TagFileParser {

  private TagFileParser() {
    // Utility class.
  }

  /**
   * Parse a tag JSON source into a {@link TagFile} with the given canonical
   * identifier.
   *
   * @param namespacedId canonical tag id, e.g. {@code "minecraft:leaves"}; must
   *     not be {@code null}.
   * @param json raw JSON text; must not be {@code null}.
   * @return immutable {@link TagFile}.
   * @throws TinyJsonReader.JsonParseException if the input is malformed.
   * @throws IllegalArgumentException if the top-level value is not an object or
   *     if {@code values} is not an array.
   */
  public static TagFile parse(String namespacedId, String json) {
    Object root = TinyJsonReader.parse(json);
    if (!(root instanceof Map<?, ?> rootMap)) {
      throw new IllegalArgumentException("tag file root must be an object: " + namespacedId);
    }
    boolean replace = false;
    Object replaceObj = rootMap.get("replace");
    if (replaceObj instanceof Boolean b) {
      replace = b;
    } else if (replaceObj != null) {
      throw new IllegalArgumentException(
          "tag file 'replace' must be boolean: " + namespacedId);
    }

    List<String> values = new ArrayList<>();
    Object valuesObj = rootMap.get("values");
    if (valuesObj instanceof List<?> rawValues) {
      for (Object entry : rawValues) {
        String extracted = extractEntry(entry, namespacedId);
        if (extracted != null) values.add(extracted);
      }
    } else if (valuesObj != null) {
      throw new IllegalArgumentException(
          "tag file 'values' must be an array: " + namespacedId);
    }

    return new TagFile(namespacedId, replace, values);
  }

  /**
   * Extract a single values-array entry to its raw-string form, returning
   * {@code null} if the entry should be silently dropped (e.g. object-form
   * entry without an {@code id} field - malformed but not fatal, matching
   * vanilla's permissive loader).
   */
  private static String extractEntry(Object entry, String namespacedId) {
    if (entry instanceof String s) return s;
    if (entry instanceof Map<?, ?> obj) {
      Object id = obj.get("id");
      if (id instanceof String s) return s;
      // Malformed object-form entry; permissive drop matches vanilla.
      return null;
    }
    throw new IllegalArgumentException(
        "tag file entry must be string or object in: " + namespacedId);
  }
}
