package io.github.dailystruggle.rtp.fabric.v26_2_R1;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
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
final class V26_2_R1FabricLegacyText {

    private static final char SECTION = '\u00A7';
    private static final Pattern HEX_PATTERN = Pattern.compile("&?#([0-9a-fA-F]{6})");

    private V26_2_R1FabricLegacyText() {}

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

    // MC 26.2 trimmed ChatFormatting's public introspection (no isColor() /
    // getColor()), so color-vs-format is decided here from the enum constant
    // set: the 16 named colors versus the formatting / RESET codes.
    private static final java.util.EnumSet<ChatFormatting> COLORS = java.util.EnumSet.of(
            ChatFormatting.BLACK, ChatFormatting.DARK_BLUE, ChatFormatting.DARK_GREEN,
            ChatFormatting.DARK_AQUA, ChatFormatting.DARK_RED, ChatFormatting.DARK_PURPLE,
            ChatFormatting.GOLD, ChatFormatting.GRAY, ChatFormatting.DARK_GRAY,
            ChatFormatting.BLUE, ChatFormatting.GREEN, ChatFormatting.AQUA,
            ChatFormatting.RED, ChatFormatting.LIGHT_PURPLE, ChatFormatting.YELLOW,
            ChatFormatting.WHITE);

    private static Style applyFormatting(Style style, ChatFormatting fmt) {
        if (COLORS.contains(fmt)) return Style.EMPTY.withColor(fmt);
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

    /**
     * V26-local mirror of {@code FabricLegacyText.parseInteractive}: parse
     * {@code raw} as legacy text and decorate the root style with an optional
     * hover ({@code SHOW_TEXT}) and an optional click ({@code RUN_COMMAND} when
     * {@code run}, else {@code SUGGEST_COMMAND}). On any mapping-drift failure
     * the un-decorated component is returned rather than aborting the caller.
     */
    static Component parseInteractive(String raw, String hover, String click, boolean run) {
        Component base = parse(raw);
        boolean hasHover = hover != null && !hover.isEmpty();
        boolean hasClick = click != null && !click.isEmpty();
        if (!hasHover && !hasClick) return base;
        try {
            MutableComponent mut = base.copy();
            Style style = mut.getStyle();
            if (hasHover) {
                HoverEvent hoverEv = buildShowTextHover(parse(hover));
                if (hoverEv != null) style = style.withHoverEvent(hoverEv);
            }
            if (hasClick) {
                ClickEvent clickEv = buildClick(run, stripColor(click));
                if (clickEv != null) style = style.withClickEvent(clickEv);
            }
            return mut.setStyle(style);
        } catch (Throwable ignored) {
            return base;
        }
    }

    /** Render a legacy string to plain text with all colour/format codes removed. */
    static String stripColor(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String text = normalise(raw);
        StringBuilder out = new StringBuilder(text.length());
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == SECTION && i + 1 < n) {
                char code = text.charAt(i + 1);
                if ((code == 'x' || code == 'X') && i + 13 < n) { i += 14; continue; }
                if (byCode(code) != null) { i += 2; continue; }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    // Cross-shape HoverEvent / ClickEvent construction (reflective): on MC 26.x
    // these became sealed interfaces whose instances are per-action records, so
    // the legacy two-arg constructors are gone. Probe both shapes reflectively
    // and cache; mirrors rtp-fabric-common FabricLegacyText.
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
        } catch (Throwable ignored) { /* degrade to no hover */ }
        return null;
    }

    private static ClickEvent buildClick(boolean run, String payload) {
        if (!CLICK_PROBED) probeClickCtors();
        try {
            ClickEvent.Action action = run
                    ? ClickEvent.Action.RUN_COMMAND
                    : ClickEvent.Action.SUGGEST_COMMAND;
            if (CLICK_CTOR_LEGACY != null) {
                return (ClickEvent) CLICK_CTOR_LEGACY.newInstance(action, payload);
            }
            java.lang.reflect.Constructor<?> recordCtor = run ? CLICK_CTOR_RUN : CLICK_CTOR_SUGGEST;
            if (recordCtor != null) {
                return (ClickEvent) recordCtor.newInstance(payload);
            }
        } catch (Throwable ignored) { /* degrade to no click */ }
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
    }

    private static synchronized void probeClickCtors() {
        if (CLICK_PROBED) return;
        try {
            CLICK_CTOR_LEGACY = ClickEvent.class.getConstructor(ClickEvent.Action.class, String.class);
        } catch (Throwable ignored) { /* not on this runtime */ }
        if (CLICK_CTOR_LEGACY == null) {
            try {
                for (Class<?> nested : ClickEvent.class.getDeclaredClasses()) {
                    if (!ClickEvent.class.isAssignableFrom(nested)) continue;
                    java.lang.reflect.Constructor<?> ctor;
                    try {
                        ctor = nested.getDeclaredConstructor(String.class);
                    } catch (NoSuchMethodException ignored) {
                        continue;
                    }
                    ctor.setAccessible(true);
                    try {
                        Object instance = ctor.newInstance("");
                        Object action = readClickAction(instance);
                        if (action == ClickEvent.Action.SUGGEST_COMMAND && CLICK_CTOR_SUGGEST == null) {
                            CLICK_CTOR_SUGGEST = ctor;
                        } else if (action == ClickEvent.Action.RUN_COMMAND && CLICK_CTOR_RUN == null) {
                            CLICK_CTOR_RUN = ctor;
                        }
                    } catch (Throwable ignored) { /* not this one */ }
                    if (CLICK_CTOR_SUGGEST != null && CLICK_CTOR_RUN != null) break;
                }
            } catch (Throwable ignored) { /* nothing matched */ }
        }
        CLICK_PROBED = true;
    }

    private static Object readClickAction(Object clickEvent) {
        for (String name : new String[] {"getAction", "action"}) {
            try {
                java.lang.reflect.Method m = clickEvent.getClass().getMethod(name);
                return m.invoke(clickEvent);
            } catch (Throwable ignored) { /* try next accessor */ }
        }
        return null;
    }
}
