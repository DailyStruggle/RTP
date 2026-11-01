package io.github.dailystruggle.menu.api;

import io.github.dailystruggle.server.api.BookMenuSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Dual-surface WCAG 2.1 relative-luminance contrast validator and palette normalizer for
 * written-book menus ({@code ADR-035}, {@code ADR-044}, {@code ADR-063}, {@code ADR-115}).
 *
 * <p>Minecraft written books render across two opposite-luminance backgrounds:
 *
 * <ol>
 *   <li><b>Parchment Page Surface ({@code #F4E8C1}, relative luminance {@code ~0.818}):</b>
 *       Requires dark foreground codes ({@code &0}, {@code &1}, {@code &2}, {@code &3}, {@code &4},
 *       {@code &5}, {@code &8}) achieving {@code >= 2.2:1} contrast ({@code >= 4.5:1} WCAG AA for
 *       primary text). Pale codes ({@code &e} yellow, {@code &6} gold, {@code &f} white, {@code &a}
 *       bright green, {@code &b} aqua, {@code &7} light gray) wash out on parchment and are
 *       prohibited on page text.
 *   <li><b>Hover Tooltip Box ({@code #100010}, relative luminance {@code ~0.004}):</b> Minecraft
 *       renders {@code SHOW_TEXT} hover tooltips inside a dark purple-black box. Dark parchment
 *       codes ({@code &0} black {@code 1.00:1}, {@code &1} dark blue {@code 1.54:1}, {@code &8}
 *       dark gray {@code 2.75:1}) become unreadable against {@code #100010}. Hover tooltips require
 *       light/vibrant codes ({@code &f}, {@code &7}, {@code &e}, {@code &6}, {@code &a}, {@code
 *       &b}, {@code &c}, {@code &d}) achieving {@code >= 3.0:1} contrast against {@code #100010}.
 * </ol>
 *
 * @governedBy ADR-035
 * @governedBy ADR-044
 * @governedBy ADR-063
 * @governedBy ADR-115
 * @trace REQ-SLEUTH-UI-001
 */
public final class BookContrastValidator {

  /** Reference RGB of the vanilla written-book parchment background ({@code #F4E8C1}). */
  public static final int PARCHMENT_BG_RGB = 0xF4E8C1;

  /** Reference RGB of the vanilla hover tooltip background box ({@code #100010}). */
  public static final int HOVER_TOOLTIP_BG_RGB = 0x100010;

  /** Minimum baseline contrast ratio allowed for any accent color on parchment ({@code 2.2:1}). */
  public static final double MIN_PARCHMENT_CONTRAST_RATIO = 2.2;

  /** WCAG 2.1 AA contrast ratio for primary dark text on parchment ({@code 4.5:1}). */
  public static final double WCAG_AA_PARCHMENT_CONTRAST_RATIO = 4.5;

  /** Minimum contrast ratio allowed for any text color inside a dark hover tooltip ({@code 3.0:1}). */
  public static final double MIN_HOVER_CONTRAST_RATIO = 3.0;

  /** Legacy color codes that wash out on parchment ({@code #F4E8C1}) and are forbidden in page text. */
  public static final Set<Character> FORBIDDEN_PARCHMENT_CODES =
      Set.of('e', '6', 'f', 'a', 'b', 'd', '7', '9', 'c');

  /** Legacy color codes that are too dark inside a {@code #100010} hover tooltip box. */
  public static final Set<Character> FORBIDDEN_HOVER_CODES = Set.of('0', '1', '8', '4');

  /** Standard Minecraft legacy color code RGB table ({@code 0..9, a..f}). */
  public static final Map<Character, Integer> LEGACY_COLOR_RGB =
      Map.ofEntries(
          Map.entry('0', 0x000000), // Black
          Map.entry('1', 0x0000AA), // Dark Blue
          Map.entry('2', 0x00AA00), // Dark Green
          Map.entry('3', 0x00AAAA), // Dark Aqua
          Map.entry('4', 0xAA0000), // Dark Red
          Map.entry('5', 0xAA00AA), // Dark Purple
          Map.entry('6', 0xFFAA00), // Gold
          Map.entry('7', 0xAAAAAA), // Gray
          Map.entry('8', 0x555555), // Dark Gray
          Map.entry('9', 0x5555FF), // Blue
          Map.entry('a', 0x55FF55), // Green
          Map.entry('b', 0x55FFFF), // Aqua
          Map.entry('c', 0xFF5555), // Red
          Map.entry('d', 0xFF55FF), // Light Purple
          Map.entry('e', 0xFFFF55), // Yellow
          Map.entry('f', 0xFFFFFF) // White
          );

  private BookContrastValidator() {}

  /**
   * Computes WCAG 2.1 relative luminance {@code L in [0.0, 1.0]} for a 24-bit {@code 0xRRGGBB}
   * color.
   *
   * @param rgb 24-bit sRGB color
   * @return relative luminance in {@code [0.0, 1.0]}
   */
  public static double relativeLuminance(int rgb) {
    double r = ((rgb >> 16) & 0xFF) / 255.0;
    double g = ((rgb >> 8) & 0xFF) / 255.0;
    double b = (rgb & 0xFF) / 255.0;

    double rLin = (r <= 0.03928) ? (r / 12.92) : Math.pow((r + 0.055) / 1.055, 2.4);
    double gLin = (g <= 0.03928) ? (g / 12.92) : Math.pow((g + 0.055) / 1.055, 2.4);
    double bLin = (b <= 0.03928) ? (b / 12.92) : Math.pow((b + 0.055) / 1.055, 2.4);

    return 0.2126 * rLin + 0.7152 * gLin + 0.0722 * bLin;
  }

  /**
   * Computes WCAG 2.1 contrast ratio {@code (L_max + 0.05) / (L_min + 0.05)} between two RGB
   * colors.
   *
   * @param rgbA first 24-bit sRGB color
   * @param rgbB second 24-bit sRGB color
   * @return contrast ratio in {@code [1.0, 21.0]}
   */
  public static double contrastRatioRgb(int rgbA, int rgbB) {
    return contrastRatioLuminance(relativeLuminance(rgbA), relativeLuminance(rgbB));
  }

  /**
   * Computes WCAG 2.1 contrast ratio from two relative luminances.
   *
   * @param lumA first relative luminance
   * @param lumB second relative luminance
   * @return contrast ratio in {@code [1.0, 21.0]}
   */
  public static double contrastRatioLuminance(double lumA, double lumB) {
    double max = Math.max(lumA, lumB);
    double min = Math.min(lumA, lumB);
    return (max + 0.05) / (min + 0.05);
  }

  /**
   * Computes the contrast ratio of a legacy color code ({@code '0'..'9', 'a'..'f'}) against the
   * parchment page background ({@code #F4E8C1}).
   *
   * @param code legacy color character (case-insensitive)
   * @return contrast ratio against parchment
   */
  public static double parchmentContrastRatio(char code) {
    Integer rgb = LEGACY_COLOR_RGB.get(Character.toLowerCase(code));
    if (rgb == null) {
      throw new IllegalArgumentException("Unknown legacy color code: " + code);
    }
    return contrastRatioRgb(PARCHMENT_BG_RGB, rgb);
  }

  /**
   * Computes the contrast ratio of a legacy color code ({@code '0'..'9', 'a'..'f'}) against the
   * dark hover tooltip background ({@code #100010}).
   *
   * @param code legacy color character (case-insensitive)
   * @return contrast ratio against dark hover tooltip box
   */
  public static double hoverContrastRatio(char code) {
    Integer rgb = LEGACY_COLOR_RGB.get(Character.toLowerCase(code));
    if (rgb == null) {
      throw new IllegalArgumentException("Unknown legacy color code: " + code);
    }
    return contrastRatioRgb(HOVER_TOOLTIP_BG_RGB, rgb);
  }

  /**
   * Remaps any low-contrast legacy color codes in {@code pageText} to their high-contrast
   * parchment-safe dark counterparts ({@code &f/&7 -> &8}, {@code &e/&6 -> &4}, {@code &a -> &2},
   * {@code &b/&9 -> &1}, {@code &c -> &4}, {@code &d -> &5}).
   *
   * @param pageText legacy-coded book page text
   * @return parchment-safe text
   */
  public static String sanitizeForParchmentPage(@Nullable String pageText) {
    if (pageText == null || pageText.isEmpty()) {
      return pageText == null ? "" : pageText;
    }
    char[] chars = pageText.toCharArray();
    for (int i = 0; i < chars.length - 1; i++) {
      if (chars[i] == '&' || chars[i] == '\u00a7') {
        char next = Character.toLowerCase(chars[i + 1]);
        chars[i + 1] =
            switch (next) {
              case 'f', '7' -> '8';
              case 'e', '6', 'c' -> '4';
              case 'a' -> '2';
              case 'b', '9' -> '1';
              case 'd' -> '5';
              default -> chars[i + 1];
            };
        i++;
      }
    }
    return new String(chars);
  }

  /**
   * Remaps any dark parchment color codes in {@code hoverText} to their high-contrast light
   * counterparts for Minecraft's dark {@code #100010} hover tooltip box ({@code &0 -> &7}, {@code
   * &1 -> &b}, {@code &2 -> &a}, {@code &3 -> &b}, {@code &4 -> &c}, {@code &5 -> &d}, {@code &8 ->
   * &7}).
   *
   * @param hoverText legacy-coded hover tooltip text, or {@code null}
   * @return dark-tooltip-safe hover text, or {@code null} if input was {@code null}
   */
  public static @Nullable String sanitizeForDarkHoverTooltip(@Nullable String hoverText) {
    if (hoverText == null || hoverText.isEmpty()) {
      return hoverText;
    }
    char[] chars = hoverText.toCharArray();
    for (int i = 0; i < chars.length - 1; i++) {
      if (chars[i] == '&' || chars[i] == '\u00a7') {
        char next = Character.toLowerCase(chars[i + 1]);
        chars[i + 1] =
            switch (next) {
              case '0', '8' -> '7';
              case '1', '3' -> 'b';
              case '2' -> 'a';
              case '4' -> 'c';
              case '5' -> 'd';
              default -> chars[i + 1];
            };
        i++;
      }
    }
    return new String(chars);
  }

  /**
   * Audits a {@link MenuDescriptor} for dual-surface contrast violations (pale colors on parchment
   * page text, or dark colors inside hover tooltips).
   *
   * @param descriptor menu descriptor to audit
   * @return immutable list of human-readable violation diagnostics (empty when compliant)
   */
  public static List<String> auditMenuDescriptor(MenuDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    List<String> violations = new ArrayList<>();
    List<MenuDescriptor.Page> pages = descriptor.pages();
    for (int p = 0; p < pages.size(); p++) {
      List<MenuDescriptor.Line> lines = pages.get(p).lines();
      for (int l = 0; l < lines.size(); l++) {
        List<MenuDescriptor.Fragment> fragments = lines.get(l).fragments();
        for (int f = 0; f < fragments.size(); f++) {
          MenuDescriptor.Fragment frag = fragments.get(f);
          String loc = "page[" + p + "].line[" + l + "].fragment[" + f + "]";
          auditSurfaceString(frag.text(), true, loc + ".text", violations);
          if (frag.hover() != null && !frag.hover().isEmpty()) {
            auditSurfaceString(frag.hover(), false, loc + ".hover", violations);
          }
        }
      }
    }
    return List.copyOf(violations);
  }

  /**
   * Audits a {@link BookMenuSpec} for dual-surface contrast violations.
   *
   * @param spec book menu specification to audit
   * @return immutable list of human-readable violation diagnostics (empty when compliant)
   */
  public static List<String> auditBookMenuSpec(BookMenuSpec spec) {
    Objects.requireNonNull(spec, "spec");
    List<String> violations = new ArrayList<>();
    List<BookMenuSpec.Page> pages = spec.pages();
    for (int p = 0; p < pages.size(); p++) {
      List<BookMenuSpec.Line> lines = pages.get(p).lines();
      for (int l = 0; l < lines.size(); l++) {
        List<BookMenuSpec.Fragment> fragments = lines.get(l).fragments();
        for (int f = 0; f < fragments.size(); f++) {
          BookMenuSpec.Fragment frag = fragments.get(f);
          String loc = "page[" + p + "].line[" + l + "].fragment[" + f + "]";
          auditSurfaceString(frag.text(), true, loc + ".text", violations);
          if (frag.hover() != null && !frag.hover().isEmpty()) {
            auditSurfaceString(frag.hover(), false, loc + ".hover", violations);
          }
        }
      }
    }
    return List.copyOf(violations);
  }

  private static void auditSurfaceString(
      String text, boolean parchmentSurface, String location, List<String> violations) {
    if (text == null || text.isEmpty()) {
      return;
    }
    for (int i = 0; i < text.length() - 1; i++) {
      char prefix = text.charAt(i);
      if (prefix != '&' && prefix != '\u00a7') {
        continue;
      }
      char code = Character.toLowerCase(text.charAt(i + 1));
      Integer rgb = LEGACY_COLOR_RGB.get(code);
      if (rgb == null) {
        continue;
      }
      if (parchmentSurface) {
        double ratio = contrastRatioRgb(PARCHMENT_BG_RGB, rgb);
        if (FORBIDDEN_PARCHMENT_CODES.contains(code) || ratio < MIN_PARCHMENT_CONTRAST_RATIO) {
          violations.add(
              String.format(
                  java.util.Locale.ROOT,
                  "%s uses low-contrast parchment code '&%c' (contrast %.2f:1 < %.1f:1 on #F4E8C1) in: %s",
                  location,
                  code,
                  ratio,
                  MIN_PARCHMENT_CONTRAST_RATIO,
                  text));
        }
      } else {
        double ratio = contrastRatioRgb(HOVER_TOOLTIP_BG_RGB, rgb);
        if (FORBIDDEN_HOVER_CODES.contains(code) || ratio < MIN_HOVER_CONTRAST_RATIO) {
          violations.add(
              String.format(
                  java.util.Locale.ROOT,
                  "%s uses dark code '&%c' inside dark hover tooltip (contrast %.2f:1 < %.1f:1 on #100010) in: %s",
                  location,
                  code,
                  ratio,
                  MIN_HOVER_CONTRAST_RATIO,
                  text));
        }
      }
      i++;
    }
  }
}
