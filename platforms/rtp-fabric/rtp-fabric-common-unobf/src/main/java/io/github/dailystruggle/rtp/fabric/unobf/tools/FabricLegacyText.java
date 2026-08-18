package io.github.dailystruggle.rtp.fabric.unobf.tools;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fabric counterpart to rtp-spigot's {@code SendMessage.Hex2Color} +
 * {@code ChatColor.translateAlternateColorCodes('&', ...)} pipeline.
 *
 * <p>Parses legacy text containing {@code &}-prefixed colour/format codes
 * (e.g. {@code &a}, {@code &l}) and 6-digit hex codes ({@code #RRGGBB} or
 * {@code &#RRGGBB}) into a styled {@link Component} tree that Minecraft
 * renders correctly. Bukkit's hex form {@code section xsection rsection rsection gsection gsection bsection b} is also
 * accepted, allowing plugin output that has already been pre-converted to
 * Bukkit legacy hex to be displayed without losing colour.
 *
 * <p>This class has no dependency on rtp-core / Bukkit / Adventure - it
 * only uses Minecraft's own {@code net.minecraft.network.chat} API, which
 * is the same surface FabricRTPPlayer.sendMessage already speaks.
 */
public final class FabricLegacyText {

    private static final char SECTION = '\u00A7';
    // Matches "&#RRGGBB" or "#RRGGBB"
    private static final Pattern HEX_PATTERN = Pattern.compile("&?#([0-9a-fA-F]{6})");
    // Bukkit-style "section xsection rsection rsection gsection gsection bsection b" hex run
    private static final Pattern BUKKIT_HEX_PATTERN =
            Pattern.compile(SECTION + "[xX](?:" + SECTION + "[0-9a-fA-F]){6}");

    private FabricLegacyText() {}

    /**
     * Parse {@code raw} as legacy text and return a styled {@link Component} with
     * an optional hover ({@code HoverEvent.Action.SHOW_TEXT}) and click-to-suggest
     * ({@code ClickEvent.Action.SUGGEST_COMMAND}) annotation applied to the root component.
     *
     * <p>Mirrors the rtp-spigot {@code SendMessage.sendMessage(target, msg, hover, click)}
     * path, which decorates a Bungee {@code BaseComponent} with the same two events.
     * Hover/click are rendered as a single annotation across the whole line
     * (matching Bukkit's {@code BaseComponent.setHoverEvent}/{@code setClickEvent}
     * semantics - not per-segment).
     *
     * <p>If both {@code hover} and {@code click} are {@code null} or empty, this
     * returns the result of {@link #parse(String)} unchanged.
     *
     * @param raw   primary message; legacy {@code &}/{@code section } codes accepted
     * @param hover hover-tooltip text; legacy codes accepted; {@code null}/empty disables hover
     * @param click suggest-command target; colour codes are stripped before insertion
     *              into the chat input; {@code null}/empty disables click
     */
    public static Component parseInteractive(String raw,
                                             @Nullable String hover,
                                             @Nullable String click) {
        Component base = parse(raw);
        boolean hasHover = hover != null && !hover.isEmpty();
        boolean hasClick = click != null && !click.isEmpty();
        if (!hasHover && !hasClick) return base;

        // Layer hover/click onto the *root* style so the annotation covers the
        // entire line regardless of inner colour/format runs (Bukkit parity).
        //
        // NOTE (drift guard): the HoverEvent / ClickEvent constructor shapes
        // changed in 1.21.5+ - they were converted to sealed records with
        // per-action types (e.g. ClickEvent.SuggestCommand, HoverEvent.ShowText).
        // The legacy `new HoverEvent(Action, Component)` / `new ClickEvent(Action, String)`
        // calls trigger NoSuchMethodError / ClassFormatError at runtime on
        // those versions, which (because callers like InfoCmd dispatch each
        // line via forEach) truncates the output mid-loop. We therefore wrap
        // the styling in a best-effort try/catch and degrade gracefully to
        // the un-decorated component rather than aborting the whole command.
        // The drift itself should be repaired in the per-version adapter
        // (rtp-fabric-v1_21_R5 / R11).
        try {
            MutableComponent mut = base.copy();
            Style style = mut.getStyle();
            if (hasHover) {
                Component hoverComp = parse(hover);
                Object hoverEv = buildShowTextHover(hoverComp);
                if (hoverEv != null) {
                    style = (Style) Style.class.getMethod("withHoverEvent", HoverEvent.class)
                            .invoke(style, hoverEv);
                }
            }
            if (hasClick) {
                // Strip colour from the suggest-command target so section codes never leak
                // into the player's chat input - mirrors the Spigot path which feeds
                // the raw command string straight into ClickEvent.SUGGEST_COMMAND.
                String suggestion = stripColor(click);
                Object clickEv = buildSuggestCommandClick(suggestion);
                if (clickEv != null) {
                    style = (Style) Style.class.getMethod("withClickEvent", ClickEvent.class)
                            .invoke(style, clickEv);
                }
            }
            return mut.setStyle(style);
        } catch (Throwable t) {
            // Mapping drift on 1.21.5+ (HoverEvent/ClickEvent became records).
            // Fall back to the un-decorated component so /rtp info, /rtp help,
            // and any other consumer of the rich-text sink still emit their
            // full output. Best-effort log so the regression is visible.
            try {
                java.util.logging.Logger.getLogger("RTP").log(java.util.logging.Level.FINE,
                        "[RTP] FabricLegacyText.parseInteractive: hover/click decoration "
                                + "unavailable on this MC version (" + t.getClass().getSimpleName()
                                + "), delivering plain component.");
            } catch (Throwable ignored) { /* best-effort */ }
            return base;
        }
    }

    /**
     * Parse {@code raw} as legacy text and return a styled {@link Component}.
     * {@code null} or empty input yields {@link Component#empty()}.
     */
    public static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();

        // Normalise: convert "&"/"#RRGGBB" hex forms and "&" formatting codes
        // into section-sign sequences, then scan once.
        String text = normalise(raw);

        MutableComponent out = Component.empty();
        Style style = Style.EMPTY;
        StringBuilder buf = new StringBuilder();

        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == SECTION && i + 1 < n) {
                char code = text.charAt(i + 1);
                // Bukkit hex: section xsection Rsection Rsection Gsection Gsection Bsection B (7 codes total after section x)
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
                            style = style.withColor(
                                    TextColor.fromRgb(Integer.parseInt(hex.toString(), 16)));
                        } catch (NumberFormatException ignored) {
                            // shouldn't happen - isHex gated above
                        }
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

    /**
     * Normalise the input to Minecraft's section-sign legacy form
     * ({@code section a}, {@code section xsection rsection rsection gsection gsection bsection b}, …).
     *
     * <p>This is what Minecraft's TerminalConsoleAppender expects on the
     * dedicated-server console: {@code section }+code sequences are translated to
     * ANSI escapes so coloured output renders, while {@link Component}-based
     * dispatch via {@link net.minecraft.server.MinecraftServer#sendSystemMessage}
     * loses styling because it logs {@code component.getString()} (plain text).
     *
     * <p>Empty/null input returns the empty string.
     */
    public static String toLegacyString(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return normalise(raw);
    }

    /**
     * Convert a legacy/{@code &}/{@code #RRGGBB} string into ANSI escape sequences for
     * direct console output. Used by the Fabric log path because Minecraft's
     * TerminalConsoleAppender does not translate {@code section }-codes when our
     * Log4j2 logger writes them - and on Windows consoles the raw {@code section }
     * (UTF-8 0xC2 0xA7) renders as {@code ┬º} mojibake.
     *
     * <p>The basic 16 colours map to ANSI 30-37 / 90-97; bold/italic/underline
     * map to their SGR codes; reset is {@code \u001b[0m}; truecolor (hex /
     * {@code section x}) maps to {@code \u001b[38;2;R;G;Bm}. A trailing reset is always
     * appended so subsequent unstyled console output isn't tinted.
     *
     * <p>Empty/null input returns the empty string.
     */
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
                    // section xsection Rsection Rsection Gsection Gsection Bsection B - 6 hex digits each preceded by section 
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

    private static int hexVal(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return 10 + (c - 'a');
        if (c >= 'A' && c <= 'F') return 10 + (c - 'A');
        return 0;
    }

    private static String ansiFor(char code) {
        switch (Character.toLowerCase(code)) {
            case '0': return "\u001b[30m";   // black
            case '1': return "\u001b[34m";   // dark blue
            case '2': return "\u001b[32m";   // dark green
            case '3': return "\u001b[36m";   // dark aqua
            case '4': return "\u001b[31m";   // dark red
            case '5': return "\u001b[35m";   // dark purple
            case '6': return "\u001b[33m";   // gold
            case '7': return "\u001b[37m";   // gray
            case '8': return "\u001b[90m";   // dark gray
            case '9': return "\u001b[94m";   // blue
            case 'a': return "\u001b[92m";   // green
            case 'b': return "\u001b[96m";   // aqua
            case 'c': return "\u001b[91m";   // red
            case 'd': return "\u001b[95m";   // light purple
            case 'e': return "\u001b[93m";   // yellow
            case 'f': return "\u001b[97m";   // white
            case 'l': return "\u001b[1m";    // bold
            case 'm': return "\u001b[9m";    // strikethrough
            case 'n': return "\u001b[4m";    // underline
            case 'o': return "\u001b[3m";    // italic
            case 'k': return "";              // obfuscated - no ANSI equivalent
            case 'r': return "\u001b[0m";    // reset
            default:  return null;
        }
    }

    /** Convenience: render a legacy string to a single line of plain text with no colour. */
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
                    // skip section x and 6 section r pairs
                    i += 14;
                    continue;
                }
                if (byCode(code) != null) {
                    i += 2;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    // --- helpers --------------------------------------------------------

    private static String normalise(String raw) {
        // 0) Expand MiniMessage <tag> color/format markup into legacy &-codes
        //    so Adventure-less Fabric still honors MiniMessage colors.
        raw = io.github.dailystruggle.rtp.common.tools.MiniMessageColorExpander.expand(raw);
        // 1) Convert "#RRGGBB" / "&#RRGGBB" to "section xsection Rsection Rsection Gsection Gsection Bsection B" so the main
        //    scanner can treat hex uniformly with formatting codes.
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

        // 2) Convert remaining "&x" formatting codes to "section x".
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
        // ChatFormatting.getByCode is case-sensitive on lowercase; normalise.
        ChatFormatting f = ChatFormatting.getByCode(Character.toLowerCase(c));
        return f;
    }

    // ------------------------------------------------------------------
    // Cross-version HoverEvent / ClickEvent construction (mojmap unobf carrier).
    //
    // Pre-1.21.5: `new HoverEvent(Action, Component)` / `new ClickEvent(Action, String)`.
    // 1.21.5+ (and MC 26.x): sealed interfaces with per-action records:
    //   net.minecraft.network.chat.HoverEvent$ShowText(Component)
    //   net.minecraft.network.chat.ClickEvent$SuggestCommand(String)
    // We probe both shapes reflectively at first use so this unobf carrier
    // works under either runtime layout; returning null signals "no compatible
    // shape on this runtime" and the caller skips the decoration rather than
    // aborting the message.
    // ------------------------------------------------------------------

    private static volatile java.lang.reflect.Constructor<?> HOVER_CTOR_LEGACY;
    private static volatile java.lang.reflect.Constructor<?> HOVER_CTOR_SHOWTEXT;
    private static volatile boolean HOVER_PROBED;

    private static volatile java.lang.reflect.Constructor<?> CLICK_CTOR_LEGACY;
    private static volatile java.lang.reflect.Constructor<?> CLICK_CTOR_SUGGEST;
    private static volatile boolean CLICK_PROBED;

    private static Object buildShowTextHover(Component hoverComp) {
        if (!HOVER_PROBED) probeHoverCtors();
        try {
            if (HOVER_CTOR_LEGACY != null) {
                return HOVER_CTOR_LEGACY.newInstance(HoverEvent.Action.SHOW_TEXT, hoverComp);
            }
            if (HOVER_CTOR_SHOWTEXT != null) {
                return HOVER_CTOR_SHOWTEXT.newInstance(hoverComp);
            }
        } catch (Throwable t) {
            // fall through to null - caller will skip the hover decoration.
        }
        return null;
    }

    private static Object buildSuggestCommandClick(String suggestion) {
        if (!CLICK_PROBED) probeClickCtors();
        try {
            if (CLICK_CTOR_LEGACY != null) {
                return CLICK_CTOR_LEGACY.newInstance(ClickEvent.Action.SUGGEST_COMMAND, suggestion);
            }
            if (CLICK_CTOR_SUGGEST != null) {
                return CLICK_CTOR_SUGGEST.newInstance(suggestion);
            }
        } catch (Throwable t) {
            // fall through - caller skips the click decoration.
        }
        return null;
    }

    private static synchronized void probeHoverCtors() {
        if (HOVER_PROBED) return;
        // 1.21.x: ctor is generic `HoverEvent(Action<T>, T)` → erasure (Action, Object).
        try {
            HOVER_CTOR_LEGACY = HoverEvent.class.getConstructor(HoverEvent.Action.class, Object.class);
        } catch (Throwable ignored) { /* not on this runtime */ }
        // 1.20.x: ctor was `HoverEvent(Action, Component)` (pre-generification).
        if (HOVER_CTOR_LEGACY == null) {
            try {
                HOVER_CTOR_LEGACY = HoverEvent.class.getConstructor(HoverEvent.Action.class, Component.class);
            } catch (Throwable ignored) { /* not on this runtime */ }
        }
        if (HOVER_CTOR_LEGACY == null) {
            try {
                Class<?> showText = Class.forName("net.minecraft.network.chat.HoverEvent$ShowText");
                HOVER_CTOR_SHOWTEXT = showText.getConstructor(Component.class);
            } catch (Throwable ignored) {
                try {
                    Class<?> showText = Class.forName("net.minecraft.network.chat.HoverEvent$Text");
                    HOVER_CTOR_SHOWTEXT = showText.getConstructor(Component.class);
                } catch (Throwable ignored2) { /* nothing matched */ }
            }
        }
        HOVER_PROBED = true;
    }

    private static synchronized void probeClickCtors() {
        if (CLICK_PROBED) return;
        try {
            CLICK_CTOR_LEGACY = ClickEvent.class.getConstructor(ClickEvent.Action.class, String.class);
        } catch (Throwable ignored) { /* not on this runtime */ }
        if (CLICK_CTOR_LEGACY == null) {
            try {
                Class<?> suggest = Class.forName("net.minecraft.network.chat.ClickEvent$SuggestCommand");
                CLICK_CTOR_SUGGEST = suggest.getConstructor(String.class);
            } catch (Throwable ignored) { /* nothing matched */ }
        }
        CLICK_PROBED = true;
    }

    private static Style applyFormatting(Style style, ChatFormatting fmt) {
        if (fmt.isColor()) {
            // Colour code resets any previous colour and clears formatting,
            // matching Bukkit's legacy behaviour.
            return Style.EMPTY.withColor(fmt);
        }
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
