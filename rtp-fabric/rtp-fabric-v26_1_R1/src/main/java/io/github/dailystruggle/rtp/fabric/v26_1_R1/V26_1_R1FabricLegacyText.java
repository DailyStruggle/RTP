package io.github.dailystruggle.rtp.fabric.v26_1_R1;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V26.1.2-local mirror of {@code FabricLegacyText.parse}.
 *
 * <p>The common-module {@code FabricLegacyText} is compiled by Loom against
 * intermediary mappings (its {@code Component} references end up as
 * {@code class_2561}). On a deobfuscated 26.1.2 runtime that descriptor does
 * not resolve and any cross-module call from v26 raises
 * {@code NoSuchMethodError}. Keeping the parser in this module ensures its
 * bytecode constant pool references only Mojang names that the live runtime
 * actually has loaded.
 *
 * <p>Logic is intentionally a straight copy of {@code FabricLegacyText.parse}
 * (the rich {@code parseInteractive} / hover / click variants are not used by
 * the v26 player sink, so they are omitted).
 */
final class V26_1_R1FabricLegacyText {

    private static final char SECTION = '\u00A7';
    private static final Pattern HEX_PATTERN = Pattern.compile("&?#([0-9a-fA-F]{6})");

    private V26_1_R1FabricLegacyText() {}

    static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        String text = normalise(raw);
        MutableComponent out = Component.empty();
        Style style = Style.EMPTY;
        StringBuilder buf = new StringBuilder();
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == SECTION && i + 1 < n) {
                char code = text.charAt(i + 1);
                if ((code == 'x' || code == 'X') && i + 13 < n) {
                    StringBuilder hex = new StringBuilder(6);
                    boolean ok = true;
                    for (int k = 0; k < 6; k++) {
                        int idx = i + 2 + k * 2;
                        if (idx + 1 >= n || text.charAt(idx) != SECTION) { ok = false; break; }
                        char h = text.charAt(idx + 1);
                        if (!isHex(h)) { ok = false; break; }
                        hex.append(h);
                    }
                    if (ok) {
                        flush(out, style, buf);
                        try {
                            style = style.withColor(TextColor.fromRgb(Integer.parseInt(hex.toString(), 16)));
                        } catch (NumberFormatException ignored) { /* gated by isHex */ }
                        i += 14;
                        continue;
                    }
                }
                ChatFormatting fmt = byCode(code);
                if (fmt != null) {
                    flush(out, style, buf);
                    style = applyFormatting(style, fmt);
                    i += 2;
                    continue;
                }
            }
            buf.append(c);
            i++;
        }
        flush(out, style, buf);
        return out;
    }

    private static String normalise(String raw) {
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
            if (c == '&' && i + 1 < s.length() && byCode(s.charAt(i + 1)) != null) {
                out.append(SECTION).append(s.charAt(i + 1));
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static void flush(MutableComponent parent, Style style, StringBuilder buf) {
        if (buf.length() == 0) return;
        parent.append(Component.literal(buf.toString()).setStyle(style));
        buf.setLength(0);
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static ChatFormatting byCode(char c) {
        return ChatFormatting.getByCode(Character.toLowerCase(c));
    }

    private static Style applyFormatting(Style style, ChatFormatting fmt) {
        if (fmt.isColor()) return Style.EMPTY.withColor(fmt);
        switch (fmt) {
            case BOLD:          return style.withBold(true);
            case ITALIC:        return style.withItalic(true);
            case UNDERLINE:     return style.withUnderlined(true);
            case STRIKETHROUGH: return style.withStrikethrough(true);
            case OBFUSCATED:    return style.withObfuscated(true);
            case RESET:         return Style.EMPTY;
            default:            return style;
        }
    }
}
