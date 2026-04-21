package io.github.dailystruggle.rtp.tags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the supported-subset contract documented in {@link TinyJsonReader}.
 * Purposefully exercises the unhappy paths (null, trailing content, unknown
 * escapes, dangling quotes, malformed numbers) so that the hand-rolled reader
 * will fail loudly rather than silently accepting drifted schemas.
 */
class TinyJsonReaderTest {

  @Test
  @DisplayName("parses a canonical vanilla-shape tag body")
  void parsesCanonicalTagBody() {
    String json = "{ \"replace\": false, \"values\": [\"minecraft:oak_leaves\", \"#minecraft:wart_blocks\"] }";
    Object root = TinyJsonReader.parse(json);
    assertTrue(root instanceof Map);
    @SuppressWarnings("unchecked")
    Map<String, Object> map = (Map<String, Object>) root;
    assertEquals(Boolean.FALSE, map.get("replace"));
    @SuppressWarnings("unchecked")
    List<Object> values = (List<Object>) map.get("values");
    assertEquals(List.of("minecraft:oak_leaves", "#minecraft:wart_blocks"), values);
  }

  @Test
  @DisplayName("parses object-form entry with required flag")
  void parsesObjectFormEntry() {
    String json =
        "{\"values\":[{\"id\":\"minecraft:spruce_leaves\",\"required\":false}]}";
    @SuppressWarnings("unchecked")
    Map<String, Object> root = (Map<String, Object>) TinyJsonReader.parse(json);
    @SuppressWarnings("unchecked")
    List<Object> values = (List<Object>) root.get("values");
    assertEquals(1, values.size());
    @SuppressWarnings("unchecked")
    Map<String, Object> entry = (Map<String, Object>) values.get(0);
    assertEquals("minecraft:spruce_leaves", entry.get("id"));
    assertEquals(Boolean.FALSE, entry.get("required"));
  }

  @Test
  @DisplayName("handles whitespace and line breaks between tokens")
  void handlesWhitespace() {
    String json = "{\n  \"a\":\t1 ,\r\n  \"b\" :true\n}";
    @SuppressWarnings("unchecked")
    Map<String, Object> root = (Map<String, Object>) TinyJsonReader.parse(json);
    assertEquals(1L, root.get("a"));
    assertEquals(Boolean.TRUE, root.get("b"));
  }

  @Test
  @DisplayName("parses empty object and empty array")
  void parsesEmptyContainers() {
    assertTrue(((Map<?, ?>) TinyJsonReader.parse("{}")).isEmpty());
    assertTrue(((List<?>) TinyJsonReader.parse("[]")).isEmpty());
  }

  @Test
  @DisplayName("parses negative integer and null")
  void parsesIntegerAndNull() {
    @SuppressWarnings("unchecked")
    Map<String, Object> root =
        (Map<String, Object>) TinyJsonReader.parse("{\"n\":-42,\"z\":null}");
    assertEquals(-42L, root.get("n"));
    assertNull(root.get("z"));
    assertTrue(root.containsKey("z"));
  }

  @Test
  @DisplayName("supports the documented escape sequences in strings")
  void supportsEscapes() {
    String json = "{\"s\":\"a\\\"b\\\\c\\/d\\n\\t\\u0041\"}";
    @SuppressWarnings("unchecked")
    Map<String, Object> root = (Map<String, Object>) TinyJsonReader.parse(json);
    assertEquals("a\"b\\c/d\n\tA", root.get("s"));
  }

  @Test
  @DisplayName("rejects trailing content after a valid value")
  void rejectsTrailing() {
    assertThrows(TinyJsonReader.JsonParseException.class, () -> TinyJsonReader.parse("{} x"));
  }

  @Test
  @DisplayName("rejects unterminated string")
  void rejectsUnterminatedString() {
    assertThrows(TinyJsonReader.JsonParseException.class, () -> TinyJsonReader.parse("{\"a\":\"b"));
  }

  @Test
  @DisplayName("rejects null input")
  void rejectsNullInput() {
    assertThrows(TinyJsonReader.JsonParseException.class, () -> TinyJsonReader.parse(null));
  }

  @Test
  @DisplayName("rejects unknown escape sequence")
  void rejectsUnknownEscape() {
    assertThrows(TinyJsonReader.JsonParseException.class, () -> TinyJsonReader.parse("\"\\q\""));
  }

  @Test
  @DisplayName("rejects truncated \\u escape")
  void rejectsTruncatedUnicodeEscape() {
    assertThrows(TinyJsonReader.JsonParseException.class, () -> TinyJsonReader.parse("\"\\u12\""));
  }

  @Test
  @DisplayName("rejects floating-point numbers — outside supported subset")
  void rejectsFloat() {
    // The reader consumes 1, then sees '.' as trailing garbage.
    assertThrows(TinyJsonReader.JsonParseException.class, () -> TinyJsonReader.parse("1.5"));
  }

  @Test
  @DisplayName("rejects missing comma between object entries")
  void rejectsMissingComma() {
    assertThrows(
        TinyJsonReader.JsonParseException.class,
        () -> TinyJsonReader.parse("{\"a\":1 \"b\":2}"));
  }

  @Test
  @DisplayName("returned containers are immutable")
  void containersAreImmutable() {
    @SuppressWarnings("unchecked")
    Map<String, Object> root = (Map<String, Object>) TinyJsonReader.parse("{\"a\":[1,2]}");
    assertThrows(UnsupportedOperationException.class, () -> root.put("x", 0));
    @SuppressWarnings("unchecked")
    List<Object> arr = (List<Object>) root.get("a");
    assertThrows(UnsupportedOperationException.class, () -> arr.add(3L));
    assertFalse(arr.isEmpty());
  }
}
