package io.github.dailystruggle.rtp.common.text;

/**
 * Platform-agnostic stripper for legacy Minecraft color and formatting codes.
 * Strips section codes (§a), ampersand codes (&a), Bukkit hex runs (§x§r§r...), and #RRGGBB.
 */
public final class LegacyColorStrip {

    private static final char SECTION = '\u00A7';
    /** Recognised legacy single-char codes (case-insensitive). */
    private static final String LEGACY_CODES = "0123456789abcdefklmnorABCDEFKLMNOR";

    private LegacyColorStrip() {}

    /** Result of {@link #strip2(String)}: stripped text plus raw-offset projection. */
    public static final class StripResult {
        public final String stripped;
        /**
         * {@code strippedToRaw[i]} is the offset into the original raw input
         * of the character now at index {@code i} in {@link #stripped}.
         * Length equals {@code stripped.length()}.
         */
        public final int[] strippedToRaw;

        public StripResult(String stripped, int[] strippedToRaw) {
            this.stripped = stripped;
            this.strippedToRaw = strippedToRaw;
        }
    }

    /** Drop-in equivalent of {@code ChatColor.stripColor} with broader coverage. */
    public static String strip(String raw) {
        if (raw == null || raw.isEmpty()) return raw == null ? "" : "";
        return strip2(raw).stripped;
    }

    /**
     * Strip color/format codes from {@code raw} and produce an offset map back
     * to the original characters.
     */
    public static StripResult strip2(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new StripResult("", new int[0]);
        }
        // Scan the raw string directly so the offset map references *raw* indices.
        StringBuilder out = new StringBuilder(raw.length());
        int[] map = new int[raw.length()];
        int outLen = 0;
        int i = 0;
        int n = raw.length();
        while (i < n) {
            char c = raw.charAt(i);

            // 1) Bukkit-style §x§R§R§G§G§B§B run (14 chars).
            if (c == SECTION && i + 13 < n) {
                char next = raw.charAt(i + 1);
                if ((next == 'x' || next == 'X') && isHexRun(raw, i + 2)) {
                    i += 14;
                    continue;
                }
            }
            // 2) §<code>
            if (c == SECTION && i + 1 < n && isLegacyCode(raw.charAt(i + 1))) {
                i += 2;
                continue;
            }
            // 3) &<code>
            if (c == '&' && i + 1 < n && isLegacyCode(raw.charAt(i + 1))) {
                i += 2;
                continue;
            }
            // 4) &#RRGGBB
            if (c == '&' && i + 7 < n && raw.charAt(i + 1) == '#'
                    && isHex6(raw, i + 2)) {
                i += 8;
                continue;
            }
            // 5) #RRGGBB
            if (c == '#' && i + 6 < n && isHex6(raw, i + 1)) {
                i += 7;
                continue;
            }
            // Plain character - keep.
            out.append(c);
            map[outLen++] = i;
            i++;
        }
        int[] trimmed = new int[outLen];
        System.arraycopy(map, 0, trimmed, 0, outLen);
        return new StripResult(out.toString(), trimmed);
    }

    /**
     * Zero-width space inserted between a color introducer and its code by
     * {@link #escape(String)} so the downstream render pipeline
     * ({@code translateAlternateColorCodes}, the section-sign deserializer, and
     * the {@code #RRGGBB} hex pass) no longer recognizes the sequence and the
     * code is shown to the reader as literal text.
     */
    private static final char ZERO_WIDTH_SPACE = '\u200B';

    /**
     * Neutralizes legacy color/format codes in {@code raw} by inserting zero-width
     * spaces so they render as literal text rather than being parsed by color engines.
     *
     * @param raw text to escape (may be null)
     * @return escaped text with intact visible characters
     */
    public static String escape(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        StringBuilder out = new StringBuilder(raw.length() + 8);
        int i = 0;
        int n = raw.length();
        boolean changed = false;
        while (i < n) {
            char c = raw.charAt(i);

            // 1) Bukkit-style §x§R§R§G§G§B§B run (14 chars).
            if (c == SECTION && i + 13 < n) {
                char next = raw.charAt(i + 1);
                if ((next == 'x' || next == 'X') && isHexRun(raw, i + 2)) {
                    out.append(c).append(ZERO_WIDTH_SPACE);
                    out.append(raw, i + 1, i + 14);
                    i += 14;
                    changed = true;
                    continue;
                }
            }
            // 2) §<code>
            if (c == SECTION && i + 1 < n && isLegacyCode(raw.charAt(i + 1))) {
                out.append(c).append(ZERO_WIDTH_SPACE).append(raw.charAt(i + 1));
                i += 2;
                changed = true;
                continue;
            }
            // 3) &<code>
            if (c == '&' && i + 1 < n && isLegacyCode(raw.charAt(i + 1))) {
                out.append(c).append(ZERO_WIDTH_SPACE).append(raw.charAt(i + 1));
                i += 2;
                changed = true;
                continue;
            }
            // 4) &#RRGGBB
            if (c == '&' && i + 7 < n && raw.charAt(i + 1) == '#'
                    && isHex6(raw, i + 2)) {
                out.append(c).append(ZERO_WIDTH_SPACE);
                out.append(raw, i + 1, i + 8);
                i += 8;
                changed = true;
                continue;
            }
            // 5) #RRGGBB
            if (c == '#' && i + 6 < n && isHex6(raw, i + 1)) {
                out.append(c).append(ZERO_WIDTH_SPACE);
                out.append(raw, i + 1, i + 7);
                i += 7;
                changed = true;
                continue;
            }
            // Plain character - keep.
            out.append(c);
            i++;
        }
        return changed ? out.toString() : raw;
    }

    private static boolean isLegacyCode(char c) {
        return LEGACY_CODES.indexOf(c) >= 0;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isHex6(String s, int from) {
        if (from + 6 > s.length()) return false;
        for (int k = 0; k < 6; k++) {
            if (!isHex(s.charAt(from + k))) return false;
        }
        return true;
    }

    /** True iff positions {@code from..from+11} are 6 §<hex> pairs. */
    private static boolean isHexRun(String s, int from) {
        if (from + 12 > s.length()) return false;
        for (int k = 0; k < 6; k++) {
            int p = from + (k * 2);
            if (s.charAt(p) != SECTION) return false;
            if (!isHex(s.charAt(p + 1))) return false;
        }
        return true;
    }
}
