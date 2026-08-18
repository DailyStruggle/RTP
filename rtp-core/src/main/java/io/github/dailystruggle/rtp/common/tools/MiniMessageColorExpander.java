package io.github.dailystruggle.rtp.common.tools;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts MiniMessage color/format markup (named colors, hex, tags, gradients)
 * into legacy {@code &}-prefixed codes for platforms without native Adventure support.
 */
public final class MiniMessageColorExpander {

  private MiniMessageColorExpander() {}

  // Named Minecraft colors -> legacy code char.
  private static final Map<String, Character> NAMED_COLORS = new HashMap<>();

  static {
    NAMED_COLORS.put("black", '0');
    NAMED_COLORS.put("dark_blue", '1');
    NAMED_COLORS.put("dark_green", '2');
    NAMED_COLORS.put("dark_aqua", '3');
    NAMED_COLORS.put("dark_red", '4');
    NAMED_COLORS.put("dark_purple", '5');
    NAMED_COLORS.put("gold", '6');
    NAMED_COLORS.put("gray", '7');
    NAMED_COLORS.put("grey", '7');
    NAMED_COLORS.put("dark_gray", '8');
    NAMED_COLORS.put("dark_grey", '8');
    NAMED_COLORS.put("blue", '9');
    NAMED_COLORS.put("green", 'a');
    NAMED_COLORS.put("aqua", 'b');
    NAMED_COLORS.put("red", 'c');
    NAMED_COLORS.put("light_purple", 'd');
    NAMED_COLORS.put("yellow", 'e');
    NAMED_COLORS.put("white", 'f');
  }

  // Formatting tag aliases -> legacy code char.
  private static final Map<String, Character> FORMATS = new HashMap<>();

  static {
    FORMATS.put("bold", 'l');
    FORMATS.put("b", 'l');
    FORMATS.put("italic", 'o');
    FORMATS.put("i", 'o');
    FORMATS.put("em", 'o');
    FORMATS.put("underlined", 'n');
    FORMATS.put("u", 'n');
    FORMATS.put("strikethrough", 'm');
    FORMATS.put("st", 'm');
    FORMATS.put("obfuscated", 'k');
    FORMATS.put("obf", 'k');
    FORMATS.put("reset", 'r');
  }

  // Known unsupported tags that should be removed rather than printed literally.
  private static final Set<String> UNSUPPORTED = new HashSet<>();

  static {
    UNSUPPORTED.add("hover");
    UNSUPPORTED.add("click");
    UNSUPPORTED.add("insertion");
    UNSUPPORTED.add("font");
    UNSUPPORTED.add("key");
    UNSUPPORTED.add("lang");
    UNSUPPORTED.add("tr");
    UNSUPPORTED.add("translate");
    UNSUPPORTED.add("nbt");
    UNSUPPORTED.add("selector");
    UNSUPPORTED.add("score");
  }

  // <tag> or </tag>; tag is a name (optionally with :args) or a bare hex color.
  private static final Pattern TAG_PATTERN = Pattern.compile(
      "<(/?)(#[0-9a-fA-F]{6}|[a-zA-Z][a-zA-Z0-9_]*(?::[^<>]*)?)>");

  /**
   * Expands gradient/rainbow/transition tags (via {@link GradientExpander}) and
   * simple MiniMessage color/format tags into legacy codes. Input that contains
   * no MiniMessage markup is returned effectively unchanged.
   */
  public static String expand(String text) {
    if (text == null || text.isEmpty()) return text;

    // Gradient/rainbow/transition first (emits per-character legacy hex).
    String pre = GradientExpander.expand(text);

    Matcher m = TAG_PATTERN.matcher(pre);
    if (!m.find()) return pre;

    StringBuilder out = new StringBuilder(pre.length());
    m.reset();
    int last = 0;
    while (m.find()) {
      out.append(pre, last, m.start());
      boolean closing = !m.group(1).isEmpty();
      String body = m.group(2);
      String replacement = replacementFor(closing, body, m.group());
      out.append(replacement);
      last = m.end();
    }
    out.append(pre, last, pre.length());
    return out.toString();
  }

  private static String replacementFor(boolean closing, String body, String original) {
    // Bare hex color: <#rrggbb>.
    if (body.startsWith("#")) {
      return closing ? "&r" : "&" + body;
    }

    String lower = body.toLowerCase(Locale.ROOT);
    String base = lower;
    String arg = null;
    int colon = lower.indexOf(':');
    if (colon >= 0) {
      base = lower.substring(0, colon);
      arg = lower.substring(colon + 1);
    }

    // <color:...> / <colour:...> / <c:...>
    if (base.equals("color") || base.equals("colour") || base.equals("c")) {
      if (closing) return "&r";
      if (arg == null || arg.isEmpty()) return "";
      if (arg.startsWith("#") && arg.length() == 7) return "&" + arg;
      Character named = NAMED_COLORS.get(arg);
      return named != null ? "&" + named : "";
    }

    // Named color tag.
    Character namedColor = NAMED_COLORS.get(base);
    if (namedColor != null) {
      return closing ? "&r" : "&" + namedColor;
    }

    // Formatting tag.
    Character fmt = FORMATS.get(base);
    if (fmt != null) {
      return closing ? "&r" : "&" + fmt;
    }

    // Known unsupported tags: drop so they don't print literally.
    if (UNSUPPORTED.contains(base)) {
      return "";
    }

    // Unrecognized: leave the original text untouched.
    return original;
  }
}
