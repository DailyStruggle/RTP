package io.github.dailystruggle.rtp.neoforge.tools;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NM-free legacy-text utilities used by the NeoForge console log path
 * (NeoForge analogue of {@code FabricAnsiText}).
 *
 * <p>This class deliberately has <b>no</b> {@code net.minecraft.*} imports or
 * static references. It mirrors {@link NeoForgeLegacyText#toAnsiString(String)}
 * and {@link NeoForgeLegacyText#stripColor(String)} so the early-startup log
 * pipeline does not transitively load any chat/network NM types - keeping log
 * emission resilient even before the chat stack is fully linked.</p>
 */
public final class NeoForgeAnsiText {

    private static final char SECTION = '\u00A7';
    private static final Pattern HEX_PATTERN = Pattern.compile("&?#([0-9a-fA-F]{6})");

    private NeoForgeAnsiText() {}

    /** See {@link NeoForgeLegacyText#toAnsiString(String)}. */
    public static String toAnsiString(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String text = normalise(raw);
        StringBuilder out = new StringBuilder(text.length() + 16);
        boolean styled = false;
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == SECTION && i + 1 < n) {
                char code = text.charAt(i + 1);
                if ((code == 'x' || code == 'X') && i + 13 < n) {
                    int r = (hexVal(text.charAt(i + 3)) << 4) | hexVal(text.charAt(i + 5));
                    int g = (hexVal(text.charAt(i + 7)) << 4) | hexVal(text.charAt(i + 9));
                    int b = (hexVal(text.charAt(i + 11)) << 4) | hexVal(text.charAt(i + 13));
                    out.append("\u001b[38;2;").append(r).append(';').append(g).append(';').append(b).append('m');
                    styled = true;
                    i += 14;
                    continue;
                }
                String ansi = ansiFor(code);
                if (ansi != null) {
                    out.append(ansi);
                    styled = true;
                    i += 2;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        if (styled) out.append("\u001b[0m");
        return out.toString();
    }

    /** See {@link NeoForgeLegacyText#stripColor(String)}. */
    public static String stripColor(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String text = normalise(raw);
        StringBuilder out = new StringBuilder(text.length());
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == SECTION && i + 1 < n) {
                char code = text.charAt(i + 1);
                if ((code == 'x' || code == 'X') && i + 13 < n) {
                    i += 14;
                    continue;
                }
                if (isLegacyCode(code)) {
                    i += 2;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String normalise(String raw) {
        // Expand MiniMessage <tag> color/format markup into legacy &-codes so
        // console output honors MiniMessage colors on Adventure-less NeoForge.
        raw = io.github.dailystruggle.rtp.common.tools.MiniMessageColorExpander.expand(raw);
        Matcher m = HEX_PATTERN.matcher(raw);
        StringBuilder sb = new StringBuilder(raw.length());
        int last = 0;
        while (m.find()) {
            sb.append(raw, last, m.start());
            String hex = m.group(1);
            sb.append(SECTION).append('x');
            for (int k = 0; k < 6; k++) sb.append(SECTION).append(Character.toLowerCase(hex.charAt(k)));
            last = m.end();
        }
        sb.append(raw, last, raw.length());

        String s = sb.toString();
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length() && isLegacyCode(s.charAt(i + 1))) {
                out.append(SECTION).append(s.charAt(i + 1));
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static int hexVal(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return 10 + (c - 'a');
        if (c >= 'A' && c <= 'F') return 10 + (c - 'A');
        return 0;
    }

    private static boolean isLegacyCode(char c) {
        char lc = Character.toLowerCase(c);
        if (lc >= '0' && lc <= '9') return true;
        if (lc >= 'a' && lc <= 'f') return true;
        switch (lc) {
            case 'k': case 'l': case 'm': case 'n': case 'o': case 'r':
                return true;
            default:
                return false;
        }
    }

    private static String ansiFor(char code) {
        switch (Character.toLowerCase(code)) {
            case '0': return "\u001b[30m";
            case '1': return "\u001b[34m";
            case '2': return "\u001b[32m";
            case '3': return "\u001b[36m";
            case '4': return "\u001b[31m";
            case '5': return "\u001b[35m";
            case '6': return "\u001b[33m";
            case '7': return "\u001b[37m";
            case '8': return "\u001b[90m";
            case '9': return "\u001b[94m";
            case 'a': return "\u001b[92m";
            case 'b': return "\u001b[96m";
            case 'c': return "\u001b[91m";
            case 'd': return "\u001b[95m";
            case 'e': return "\u001b[93m";
            case 'f': return "\u001b[97m";
            case 'l': return "\u001b[1m";
            case 'm': return "\u001b[9m";
            case 'n': return "\u001b[4m";
            case 'o': return "\u001b[3m";
            case 'k': return "";
            case 'r': return "\u001b[0m";
            default:  return null;
        }
    }
}
