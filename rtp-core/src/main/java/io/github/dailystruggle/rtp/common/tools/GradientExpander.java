package io.github.dailystruggle.rtp.common.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands MiniMessage gradient/rainbow/transition tags into per-character
 * legacy {@code xrrggbb} hex color codes so that gradient text
 * renders on servers that do not bundle Adventure (e.g. pure Spigot).
 *
 * <p>On Paper/Folia the full Adventure MiniMessage path is used instead; this class is only invoked when
 * {@code MINIMESSAGE_AVAILABLE} is {@code false}.
 *
 * <p>Supported tags:
 * <ul>
 *   <li>{@code <gradient:[color1]:[color2]:...:[phase]>text</gradient>}
 *   <li>{@code <transition:[color1]:[color2]:...:[phase]>text</transition>}
 *   <li>{@code <rainbow:[!][phase]>text</rainbow>}
 * </ul>
 * Color arguments may be {@code #rrggbb} hex or any of the 16 standard
 * Minecraft named colors (e.g. {@code red}, {@code dark_blue}).
 * The optional {@code phase} argument is a float in {@code [-1, 1]}.
 */
public final class GradientExpander {

  private GradientExpander() {}

  // Matches <gradient:args>, <transition:args>, or <rainbow:args> with their
  // closing tag, capturing (1) tag-name, (2) raw args string, (3) inner text.
  private static final Pattern TAG_PATTERN = Pattern.compile(
      "<(gradient|transition|rainbow)([^>]*)>(.*?)</(gradient|transition|rainbow)>",
      Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  // Named Minecraft colors -> RGB int (0xRRGGBB).
  private static final Map<String, Integer> NAMED_COLORS = new HashMap<>();
  static {
    NAMED_COLORS.put("black",        0x000000);
    NAMED_COLORS.put("dark_blue",    0x0000AA);
    NAMED_COLORS.put("dark_green",   0x00AA00);
    NAMED_COLORS.put("dark_aqua",    0x00AAAA);
    NAMED_COLORS.put("dark_red",     0xAA0000);
    NAMED_COLORS.put("dark_purple",  0xAA00AA);
    NAMED_COLORS.put("gold",         0xFFAA00);
    NAMED_COLORS.put("gray",         0xAAAAAA);
    NAMED_COLORS.put("grey",         0xAAAAAA);
    NAMED_COLORS.put("dark_gray",    0x555555);
    NAMED_COLORS.put("dark_grey",    0x555555);
    NAMED_COLORS.put("blue",         0x5555FF);
    NAMED_COLORS.put("green",        0x55FF55);
    NAMED_COLORS.put("aqua",         0x55FFFF);
    NAMED_COLORS.put("red",          0xFF5555);
    NAMED_COLORS.put("light_purple", 0xFF55FF);
    NAMED_COLORS.put("yellow",       0xFFFF55);
    NAMED_COLORS.put("white",        0xFFFFFF);
  }

  /**
   * Expands all gradient/rainbow/transition tags in {@code text} to
   * per-character legacy hex color codes. Non-gradient content is left
   * unchanged so the rest of the message pipeline handles it.
   */
  public static String expand(String text) {
    if (text == null) return "";
    Matcher m = TAG_PATTERN.matcher(text);
    if (!m.find()) return text;

    StringBuilder sb = new StringBuilder();
    m.reset();
    int last = 0;
    while (m.find()) {
      sb.append(text, last, m.start());
      String tagName  = m.group(1).toLowerCase(Locale.ROOT);
      String rawArgs  = m.group(2); // starts with ':' if args present, else ""
      String inner    = m.group(3);
      sb.append(expandTag(tagName, rawArgs, inner));
      last = m.end();
    }
    sb.append(text, last, text.length());
    return sb.toString();
  }

  // -------------------------------------------------------------------------
  // Internal helpers
  // -------------------------------------------------------------------------

  private static String expandTag(String tagName, String rawArgs, String inner) {
    // rawArgs is everything between the tag name and '>',
    // e.g. ":#5e4fa2:#f79459:0.5" or "" for bare <rainbow>.
    // Strip leading ':' so we can split on ':'.
    String argStr = rawArgs.startsWith(":") ? rawArgs.substring(1) : rawArgs;

    switch (tagName) {
      case "gradient":
        return expandGradient(argStr, inner, false);
      case "transition":
        return expandTransition(argStr, inner);
      case "rainbow":
        return expandRainbow(argStr, inner);
      default:
        return inner;
    }
  }

  // gradient: N color stops + optional float phase at the end.
  private static String expandGradient(String argStr, String inner, boolean ignored) {
    String[] parts = argStr.isEmpty() ? new String[0] : argStr.split(":");
    float phase = 0f;
    List<int[]> stops = new ArrayList<>();

    for (String part : parts) {
      Integer rgb = parseColor(part);
      if (rgb != null) {
        stops.add(rgbComponents(rgb));
      } else {
        // Try phase float
        try {
          phase = Float.parseFloat(part);
        } catch (NumberFormatException ignored2) {
          // unrecognised arg - skip
        }
      }
    }

    // Default: white -> black
    if (stops.size() < 2) {
      stops.clear();
      stops.add(new int[]{255, 255, 255});
      stops.add(new int[]{0, 0, 0});
    }

    // Strip formatting codes from inner text to count visible characters,
    // then re-apply colors per visible character.
    char[] chars = inner.toCharArray();
    int visibleLen = countVisible(chars);
    if (visibleLen == 0) return inner;

    StringBuilder sb = new StringBuilder();
    int visIdx = 0;
    int i = 0;
    while (i < chars.length) {
      // Skip existing legacy color/format codes (X) - copy them verbatim.
      if (chars[i] == '\u00a7' && i + 1 < chars.length) {
        char code = chars[i + 1];
        if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')
            || (code >= 'A' && code <= 'F') || "klmnorKLMNOR".indexOf(code) >= 0) {
          sb.append(chars[i]).append(chars[i + 1]);
          i += 2;
          continue;
        }
      }
      // Colorize this visible character.
      float t = (visibleLen == 1) ? 0f
          : (float) visIdx / (visibleLen - 1);
      // Apply phase: shift t by phase, wrapping in [0,1].
      float shifted = (t + phase) % 1f;
      if (shifted < 0) shifted += 1f;
      int[] color = interpolateStops(stops, shifted);
      sb.append(legacyHex(color[0], color[1], color[2]));
      sb.append(chars[i]);
      visIdx++;
      i++;
    }
    return sb.toString();
  }

  // transition: same as gradient but every character gets the SAME color
  // determined by the phase (last float arg). If no phase, defaults to 0.
  private static String expandTransition(String argStr, String inner) {
    String[] parts = argStr.isEmpty() ? new String[0] : argStr.split(":");
    float phase = 0f;
    List<int[]> stops = new ArrayList<>();

    for (String part : parts) {
      Integer rgb = parseColor(part);
      if (rgb != null) {
        stops.add(rgbComponents(rgb));
      } else {
        try {
          phase = Float.parseFloat(part);
        } catch (NumberFormatException ignored) {
          // skip
        }
      }
    }

    if (stops.size() < 2) {
      stops.clear();
      stops.add(new int[]{255, 255, 255});
      stops.add(new int[]{0, 0, 0});
    }

    // Clamp phase to [0,1].
    float t = Math.max(0f, Math.min(1f, phase));
    int[] color = interpolateStops(stops, t);
    String hex = legacyHex(color[0], color[1], color[2]);

    // Apply the single color to every visible character.
    char[] chars = inner.toCharArray();
    StringBuilder sb = new StringBuilder();
    int i = 0;
    while (i < chars.length) {
      if (chars[i] == '\u00a7' && i + 1 < chars.length) {
        char code = chars[i + 1];
        if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')
            || (code >= 'A' && code <= 'F') || "klmnorKLMNOR".indexOf(code) >= 0) {
          sb.append(chars[i]).append(chars[i + 1]);
          i += 2;
          continue;
        }
      }
      sb.append(hex).append(chars[i]);
      i++;
    }
    return sb.toString();
  }

  // rainbow: HSL cycle across the visible characters, optional phase offset.
  private static String expandRainbow(String argStr, String inner) {
    boolean reverse = false;
    float phase = 0f;

    if (!argStr.isEmpty()) {
      String arg = argStr.startsWith(":") ? argStr.substring(1) : argStr;
      if (arg.startsWith("!")) {
        reverse = true;
        arg = arg.substring(1);
      }
      if (!arg.isEmpty()) {
        try {
          phase = Float.parseFloat(arg);
        } catch (NumberFormatException ignored) {
          // skip
        }
      }
    }

    char[] chars = inner.toCharArray();
    int visibleLen = countVisible(chars);
    if (visibleLen == 0) return inner;

    StringBuilder sb = new StringBuilder();
    int visIdx = 0;
    int i = 0;
    while (i < chars.length) {
      if (chars[i] == '\u00a7' && i + 1 < chars.length) {
        char code = chars[i + 1];
        if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')
            || (code >= 'A' && code <= 'F') || "klmnorKLMNOR".indexOf(code) >= 0) {
          sb.append(chars[i]).append(chars[i + 1]);
          i += 2;
          continue;
        }
      }
      float t = (visibleLen == 1) ? 0f : (float) visIdx / visibleLen;
      float hue = ((reverse ? (1f - t) : t) + phase / 360f) % 1f;
      if (hue < 0) hue += 1f;
      int[] rgb = hslToRgb(hue, 1f, 0.5f);
      sb.append(legacyHex(rgb[0], rgb[1], rgb[2]));
      sb.append(chars[i]);
      visIdx++;
      i++;
    }
    return sb.toString();
  }

  // -------------------------------------------------------------------------
  // Color utilities
  // -------------------------------------------------------------------------

  /** Parses a color argument: {@code #rrggbb} hex or a named color. */
  private static Integer parseColor(String s) {
    if (s == null || s.isEmpty()) return null;
    if (s.startsWith("#") && s.length() == 7) {
      try {
        return Integer.parseInt(s.substring(1), 16);
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return NAMED_COLORS.get(s.toLowerCase(Locale.ROOT));
  }

  private static int[] rgbComponents(int rgb) {
    return new int[]{(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF};
  }

  /**
   * Linearly interpolates across N color stops for a normalized position
   * {@code t} in [0, 1].
   */
  private static int[] interpolateStops(List<int[]> stops, float t) {
    int n = stops.size();
    if (n == 1) return stops.get(0);
    float scaled = t * (n - 1);
    int lo = (int) scaled;
    int hi = lo + 1;
    if (hi >= n) return stops.get(n - 1);
    float frac = scaled - lo;
    int[] a = stops.get(lo);
    int[] b = stops.get(hi);
    return new int[]{
        lerp(a[0], b[0], frac),
        lerp(a[1], b[1], frac),
        lerp(a[2], b[2], frac)
    };
  }

  private static int lerp(int a, int b, float t) {
    return Math.round(a + (b - a) * t);
  }

  /** Converts HSL (all in [0,1]) to an RGB int[3]. */
  private static int[] hslToRgb(float h, float s, float l) {
    float c = (1f - Math.abs(2f * l - 1f)) * s;
    float x = c * (1f - Math.abs((h * 6f) % 2f - 1f));
    float m = l - c / 2f;
    float r, g, b;
    int sector = (int) (h * 6f);
    switch (sector % 6) {
      case 0: r = c; g = x; b = 0; break;
      case 1: r = x; g = c; b = 0; break;
      case 2: r = 0; g = c; b = x; break;
      case 3: r = 0; g = x; b = c; break;
      case 4: r = x; g = 0; b = c; break;
      default: r = c; g = 0; b = x; break;
    }
    return new int[]{
        Math.round((r + m) * 255),
        Math.round((g + m) * 255),
        Math.round((b + m) * 255)
    };
  }

  /**
   * Builds the Bukkit/Spigot legacy hex color prefix {@code xRRGGBB}.
   */
  private static String legacyHex(int r, int g, int b) {
    String hex = String.format("%02x%02x%02x", r, g, b);
    StringBuilder sb = new StringBuilder("\u00a7x");
    for (char c : hex.toCharArray()) {
      sb.append('\u00a7').append(c);
    }
    return sb.toString();
  }

  /** Counts visible (non-legacy-code) characters in a char array. */
  private static int countVisible(char[] chars) {
    int count = 0;
    int i = 0;
    while (i < chars.length) {
      if (chars[i] == '\u00a7' && i + 1 < chars.length) {
        char code = chars[i + 1];
        if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')
            || (code >= 'A' && code <= 'F') || "klmnorKLMNOR".indexOf(code) >= 0) {
          i += 2;
          continue;
        }
      }
      count++;
      i++;
    }
    return count;
  }
}
