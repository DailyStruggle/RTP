package io.github.dailystruggle.rtp.neoforge.tools;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.jetbrains.annotations.Nullable;

import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.dailystruggle.rtp.common.RTP;

/**
 * NeoForge counterpart to rtp-spigot's {@code SendMessage.Hex2Color} +
 * {@code ChatColor.translateAlternateColorCodes('&', ...)} pipeline (the
 * NeoForge analogue of {@code FabricLegacyText}).
 *
 * <p>Parses legacy text containing {@code &}-prefixed colour/format codes
 * (e.g. {@code &a}, {@code &l}) and 6-digit hex codes ({@code #RRGGBB} or
 * {@code &#RRGGBB}) into a styled {@link Component} tree that Minecraft renders
 * correctly. Bukkit's hex form {@code §x§r§r§g§g§b§b} is also accepted.
 *
 * <p>NeoForge ships Mojang-mapped names at runtime, so this class compiles
 * directly against {@code net.minecraft.network.chat}. The HoverEvent /
 * ClickEvent constructor shapes still drift across MC point releases (records
 * since 1.21.5), so the same reflective probe-and-cache strategy as the Fabric
 * implementation is kept to stay robust across the 1.21.x carrier line.
 */
public final class NeoForgeLegacyText {

    private static final char SECTION = '\u00A7';
    private static final Pattern HEX_PATTERN = Pattern.compile("&?#([0-9a-fA-F]{6})");

    private NeoForgeLegacyText() {}

    /** Which click action a generated {@link ClickEvent} should carry. */
    public enum ClickKind {
        SUGGEST,
        RUN
    }

    /**
     * Backwards-compatible overload: uses {@link ClickKind#SUGGEST}.
     */
    public static Component parseInteractive(String raw,
                                             @Nullable String hover,
                                             @Nullable String click) {
        return parseInteractive(raw, hover, click, ClickKind.SUGGEST);
    }

    /**
     * Parse {@code raw} as legacy text and return a styled {@link Component} with
     * an optional hover ({@code HoverEvent.Action.SHOW_TEXT}) and click
     * annotation applied to the root component.
     */
    public static Component parseInteractive(String raw,
                                             @Nullable String hover,
                                             @Nullable String click,
                                             ClickKind clickKind) {
        Component base = parse(raw);
        boolean hasHover = hover != null && !hover.isEmpty();
        boolean hasClick = click != null && !click.isEmpty();
        if (!hasHover && !hasClick) return base;

        try {
            MutableComponent mut = base.copy();
            Style style = mut.getStyle();
            if (hasHover) {
                Component hoverComp = parse(hover);
                HoverEvent hoverEv = buildShowTextHover(hoverComp);
                if (hoverEv != null) style = style.withHoverEvent(hoverEv);
            }
            if (hasClick) {
                String payload = stripColor(click);
                ClickEvent clickEv = buildClick(clickKind, payload);
                if (clickEv != null) style = style.withClickEvent(clickEv);
            }
            return mut.setStyle(style);
        } catch (Throwable t) {
            logDecorationFailureOnce(t, hasHover, hasClick, clickKind);
            return base;
        }
    }

    /**
     * Parse {@code raw} as legacy text and return a styled {@link Component}.
     * {@code null} or empty input yields {@link Component#empty()}.
     */
    public static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();

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
                            // shouldn't happen — isHex gated above
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

    /** Normalise the input to Minecraft's section-sign legacy form. */
    public static String toLegacyString(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return normalise(raw);
    }

    /** Convert a legacy string into ANSI escape sequences for direct console output. */
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

    private static int hexVal(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return 10 + (c - 'a');
        if (c >= 'A' && c <= 'F') return 10 + (c - 'A');
        return 0;
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

    // ------------------------------------------------------------------
    // Cross-version HoverEvent / ClickEvent construction (see Fabric note).
    // ------------------------------------------------------------------

    private static volatile java.lang.reflect.Constructor<?> HOVER_CTOR_LEGACY;
    private static volatile java.lang.reflect.Constructor<?> HOVER_CTOR_SHOWTEXT;
    private static volatile boolean HOVER_PROBED;

    private static volatile java.lang.reflect.Constructor<?> CLICK_CTOR_LEGACY;
    private static volatile java.lang.reflect.Constructor<?> CLICK_CTOR_SUGGEST;
    private static volatile java.lang.reflect.Constructor<?> CLICK_CTOR_RUN;
    private static volatile boolean CLICK_PROBED;

    private static HoverEvent buildShowTextHover(Component hoverComp) {
        if (!HOVER_PROBED) probeHoverCtors();
        try {
            if (HOVER_CTOR_LEGACY != null) {
                return (HoverEvent) HOVER_CTOR_LEGACY.newInstance(HoverEvent.Action.SHOW_TEXT, hoverComp);
            }
            if (HOVER_CTOR_SHOWTEXT != null) {
                return (HoverEvent) HOVER_CTOR_SHOWTEXT.newInstance(hoverComp);
            }
        } catch (Throwable t) {
            logHoverBuildFailureOnce(t);
        }
        return null;
    }

    private static ClickEvent buildClick(ClickKind kind, String payload) {
        if (!CLICK_PROBED) probeClickCtors();
        try {
            ClickEvent.Action action = (kind == ClickKind.RUN)
                    ? ClickEvent.Action.RUN_COMMAND
                    : ClickEvent.Action.SUGGEST_COMMAND;
            if (CLICK_CTOR_LEGACY != null) {
                return (ClickEvent) CLICK_CTOR_LEGACY.newInstance(action, payload);
            }
            java.lang.reflect.Constructor<?> recordCtor =
                    (kind == ClickKind.RUN) ? CLICK_CTOR_RUN : CLICK_CTOR_SUGGEST;
            if (recordCtor != null) {
                return (ClickEvent) recordCtor.newInstance(payload);
            }
        } catch (Throwable t) {
            logClickBuildFailureOnce(t, kind);
        }
        return null;
    }

    private static synchronized void probeHoverCtors() {
        if (HOVER_PROBED) return;
        try {
            HOVER_CTOR_LEGACY = HoverEvent.class.getConstructor(HoverEvent.Action.class, Object.class);
        } catch (Throwable ignored) { /* not on this runtime */ }
        if (HOVER_CTOR_LEGACY == null) {
            try {
                HOVER_CTOR_LEGACY = HoverEvent.class.getConstructor(HoverEvent.Action.class, Component.class);
            } catch (Throwable ignored) { /* not on this runtime */ }
        }
        if (HOVER_CTOR_LEGACY == null) {
            try {
                for (Class<?> nested : HoverEvent.class.getDeclaredClasses()) {
                    if (!HoverEvent.class.isAssignableFrom(nested)) continue;
                    try {
                        java.lang.reflect.Constructor<?> ctor = nested.getDeclaredConstructor(Component.class);
                        ctor.setAccessible(true);
                        HOVER_CTOR_SHOWTEXT = ctor;
                        break;
                    } catch (NoSuchMethodException ignored) { /* try next */ }
                }
            } catch (Throwable ignored) { /* nothing matched */ }
        }
        HOVER_PROBED = true;
        logProbeResults();
    }

    private static synchronized void probeClickCtors() {
        if (CLICK_PROBED) return;
        try {
            CLICK_CTOR_LEGACY = ClickEvent.class.getConstructor(ClickEvent.Action.class, String.class);
        } catch (Throwable ignored) { /* not on this runtime */ }
        if (CLICK_CTOR_LEGACY == null) {
            try {
                // 1.21.5+ refactored ClickEvent into a sealed interface with record
                // implementations (RunCommand, SuggestCommand, ...). Classify the
                // nested ctor by the record's simple name rather than calling
                // getAction(): the carrier compiles against an MC version where
                // ClickEvent is a class, so an invokevirtual getAction() on the
                // runtime interface throws IncompatibleClassChangeError and would be
                // silently swallowed, leaving every click ctor null (clicks dead).
                for (Class<?> nested : ClickEvent.class.getDeclaredClasses()) {
                    if (!ClickEvent.class.isAssignableFrom(nested)) continue;
                    String simpleLower = nested.getSimpleName().toLowerCase();
                    boolean isRun = simpleLower.contains("run");
                    boolean isSuggest = simpleLower.contains("suggest");
                    if (!isRun && !isSuggest) continue;
                    java.lang.reflect.Constructor<?> ctor;
                    try {
                        ctor = nested.getDeclaredConstructor(String.class);
                    } catch (NoSuchMethodException ignored) {
                        continue;
                    }
                    ctor.setAccessible(true);
                    if (isRun && CLICK_CTOR_RUN == null) {
                        CLICK_CTOR_RUN = ctor;
                    } else if (isSuggest && CLICK_CTOR_SUGGEST == null) {
                        CLICK_CTOR_SUGGEST = ctor;
                    }
                    if (CLICK_CTOR_SUGGEST != null && CLICK_CTOR_RUN != null) break;
                }
            } catch (Throwable ignored) { /* nothing matched */ }
        }
        CLICK_PROBED = true;
        logProbeResults();
    }

    private static volatile boolean PROBE_LOGGED;
    private static volatile boolean DECORATION_FAILURE_LOGGED;
    private static volatile boolean HOVER_BUILD_FAILURE_LOGGED;
    private static volatile boolean CLICK_BUILD_FAILURE_LOGGED;

    private static synchronized void logProbeResults() {
        if (PROBE_LOGGED) return;
        PROBE_LOGGED = true;
        try {
            if (!HOVER_PROBED || !CLICK_PROBED) {
                PROBE_LOGGED = false;
                return;
            }
            RTP.log(Level.FINE,
                    "[RTP] NeoForgeLegacyText probe results: "
                            + "HOVER_CTOR_LEGACY=" + (HOVER_CTOR_LEGACY != null)
                            + ", HOVER_CTOR_SHOWTEXT=" + (HOVER_CTOR_SHOWTEXT != null)
                            + ", CLICK_CTOR_LEGACY=" + (CLICK_CTOR_LEGACY != null)
                            + ", CLICK_CTOR_SUGGEST=" + (CLICK_CTOR_SUGGEST != null)
                            + ", CLICK_CTOR_RUN=" + (CLICK_CTOR_RUN != null)
                            + " (HoverEvent class=" + HoverEvent.class.getName()
                            + ", ClickEvent class=" + ClickEvent.class.getName() + ")");
        } catch (Throwable ignored) { /* best-effort */ }
    }

    private static void logDecorationFailureOnce(Throwable t, boolean hasHover,
                                                 boolean hasClick, ClickKind clickKind) {
        if (DECORATION_FAILURE_LOGGED) return;
        DECORATION_FAILURE_LOGGED = true;
        try {
            RTP.log(Level.WARNING,
                    "[RTP] NeoForgeLegacyText.parseInteractive: hover/click decoration "
                            + "failed on this MC version (hasHover=" + hasHover
                            + ", hasClick=" + hasClick + ", clickKind=" + clickKind
                            + "); delivering plain component. Cause: "
                            + t.getClass().getName() + ": " + t.getMessage(), t);
        } catch (Throwable ignored) { /* best-effort */ }
    }

    private static void logHoverBuildFailureOnce(Throwable t) {
        if (HOVER_BUILD_FAILURE_LOGGED) return;
        HOVER_BUILD_FAILURE_LOGGED = true;
        try {
            RTP.log(Level.WARNING,
                    "[RTP] NeoForgeLegacyText.buildShowTextHover: reflective ctor invoke "
                            + "failed (HOVER_CTOR_LEGACY=" + (HOVER_CTOR_LEGACY != null)
                            + ", HOVER_CTOR_SHOWTEXT=" + (HOVER_CTOR_SHOWTEXT != null)
                            + "). Cause: " + t.getClass().getName() + ": " + t.getMessage(), t);
        } catch (Throwable ignored) { /* best-effort */ }
    }

    private static void logClickBuildFailureOnce(Throwable t, ClickKind kind) {
        if (CLICK_BUILD_FAILURE_LOGGED) return;
        CLICK_BUILD_FAILURE_LOGGED = true;
        try {
            RTP.log(Level.WARNING,
                    "[RTP] NeoForgeLegacyText.buildClick: reflective ctor invoke failed "
                            + "(kind=" + kind
                            + ", CLICK_CTOR_LEGACY=" + (CLICK_CTOR_LEGACY != null)
                            + ", CLICK_CTOR_SUGGEST=" + (CLICK_CTOR_SUGGEST != null)
                            + ", CLICK_CTOR_RUN=" + (CLICK_CTOR_RUN != null)
                            + "). Cause: " + t.getClass().getName() + ": " + t.getMessage(), t);
        } catch (Throwable ignored) { /* best-effort */ }
    }

    private static Style applyFormatting(Style style, ChatFormatting fmt) {
        if (fmt.isColor()) {
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
