package io.github.dailystruggle.rtp.tags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, hand-rolled JSON reader for Minecraft tag files.
 *
 * <p>Supports only the JSON subset used in vanilla/data-pack tags:
 * <ul>
 *   <li>Objects (string keys), arrays, scalars (string, bool, int, null).</li>
 *   <li>Standard string escape sequences.</li>
 * </ul>
 * <p>Deliberately omits floats, exponents, comments, and unquoted keys. This ensures the reader
 * remains small and auditable, avoiding third-party dependencies for a static schema.
 *
 * <p>Returns platform-free structures: {@code Map<String, Object>}, {@code List<Object>},
 * and scalar types. Thread-safe.
 */
public final class TinyJsonReader {

  /** Raised when the input is not well-formed or not within the supported subset. */
  public static final class JsonParseException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public JsonParseException(String message, int position) {
      super(message + " at position " + position);
    }
  }

  private final String src;
  private int pos;

  private TinyJsonReader(String src) {
    this.src = src;
    this.pos = 0;
  }

  /**
   * Parses the given JSON source and returns a platform-free tree. See the
   * class Javadoc for the supported subset and the return-type contract.
   *
   * @throws JsonParseException if the input is malformed or outside the
   *     supported subset.
   */
  public static Object parse(String json) {
    if (json == null) throw new JsonParseException("input is null", 0);
    TinyJsonReader r = new TinyJsonReader(json);
    r.skipWs();
    Object value = r.readValue();
    r.skipWs();
    if (r.pos != r.src.length()) {
      throw new JsonParseException("trailing content after value", r.pos);
    }
    return value;
  }

  private Object readValue() {
    skipWs();
    if (pos >= src.length()) throw new JsonParseException("unexpected end of input", pos);
    char c = src.charAt(pos);
    return switch (c) {
      case '{' -> readObject();
      case '[' -> readArray();
      case '"' -> readString();
      case 't', 'f' -> readBoolean();
      case 'n' -> readNull();
      default -> {
        if (c == '-' || (c >= '0' && c <= '9')) yield readNumber();
        else throw new JsonParseException("unexpected character '" + c + "'", pos);
      }
    };
  }

  private Map<String, Object> readObject() {
    expect('{');
    Map<String, Object> map = new LinkedHashMap<>();
    skipWs();
    if (peek() == '}') {
      pos++;
      return Collections.unmodifiableMap(map);
    }
    while (true) {
      skipWs();
      String key = readString();
      skipWs();
      expect(':');
      Object value = readValue();
      map.put(key, value);
      skipWs();
      char next = peek();
      if (next == ',') {
        pos++;
      } else if (next == '}') {
        pos++;
        return Collections.unmodifiableMap(map);
      } else {
        throw new JsonParseException("expected ',' or '}' in object", pos);
      }
    }
  }

  private List<Object> readArray() {
    expect('[');
    List<Object> list = new ArrayList<>();
    skipWs();
    if (peek() == ']') {
      pos++;
      return Collections.unmodifiableList(list);
    }
    while (true) {
      Object value = readValue();
      list.add(value);
      skipWs();
      char next = peek();
      if (next == ',') {
        pos++;
      } else if (next == ']') {
        pos++;
        return Collections.unmodifiableList(list);
      } else {
        throw new JsonParseException("expected ',' or ']' in array", pos);
      }
    }
  }

  private String readString() {
    expect('"');
    StringBuilder sb = new StringBuilder();
    while (pos < src.length()) {
      char c = src.charAt(pos++);
      if (c == '"') return sb.toString();
      if (c == '\\') {
        if (pos >= src.length()) throw new JsonParseException("dangling escape", pos);
        char esc = src.charAt(pos++);
        switch (esc) {
          case '"' -> sb.append('"');
          case '\\' -> sb.append('\\');
          case '/' -> sb.append('/');
          case 'b' -> sb.append('\b');
          case 'f' -> sb.append('\f');
          case 'n' -> sb.append('\n');
          case 'r' -> sb.append('\r');
          case 't' -> sb.append('\t');
          case 'u' -> {
            if (pos + 4 > src.length())
              throw new JsonParseException("truncated \\u escape", pos);
            int code = Integer.parseInt(src.substring(pos, pos + 4), 16);
            sb.append((char) code);
            pos += 4;
          }
          default -> throw new JsonParseException("unknown escape '\\" + esc + "'", pos);
        }
      } else {
        sb.append(c);
      }
    }
    throw new JsonParseException("unterminated string", pos);
  }

  private Boolean readBoolean() {
    if (src.startsWith("true", pos)) {
      pos += 4;
      return Boolean.TRUE;
    }
    if (src.startsWith("false", pos)) {
      pos += 5;
      return Boolean.FALSE;
    }
    throw new JsonParseException("expected boolean", pos);
  }

  private Object readNull() {
    if (src.startsWith("null", pos)) {
      pos += 4;
      return null;
    }
    throw new JsonParseException("expected null", pos);
  }

  private Long readNumber() {
    int start = pos;
    if (peek() == '-') pos++;
    while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++;
    try {
      return Long.parseLong(src.substring(start, pos));
    } catch (NumberFormatException e) {
      throw new JsonParseException("malformed integer", start);
    }
  }

  private void skipWs() {
    while (pos < src.length()) {
      char c = src.charAt(pos);
      if (c == ' ' || c == '\t' || c == '\r' || c == '\n') pos++;
      else return;
    }
  }

  private char peek() {
    if (pos >= src.length()) throw new JsonParseException("unexpected end of input", pos);
    return src.charAt(pos);
  }

  private void expect(char c) {
    if (pos >= src.length() || src.charAt(pos) != c) {
      throw new JsonParseException("expected '" + c + "'", pos);
    }
    pos++;
  }

}
