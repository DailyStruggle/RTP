package io.github.dailystruggle.rtp.api.menu;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * One styled-text run within a {@link MenuLine}.
 *
 * <p>The surface is intentionally plain-text: no Adventure {@code Component},
 * no Bukkit {@code BaseComponent}, no platform color objects. Renderers
 * translate {@code text} and {@code hover} to their native representation
 * (ADR-035 §Menu model).
 *
 * @param text   the visible text. Never {@code null}; may be empty.
 * @param hover  optional tooltip shown on cursor hover. {@code null} means no
 *               tooltip. Hover text is populated at mint time by the reflector
 *               (ADR-044) — a config edit between mint and redeem cannot
 *               desync the displayed hover.
 * @param action optional click action. {@code null} means the fragment is
 *               purely decorative.
 */
public record MenuFragment(String text, @Nullable String hover, @Nullable MenuAction action) {

    public MenuFragment {
        Objects.requireNonNull(text, "text");
    }

    /** Convenience factory for a decorative (non-clickable, no-hover) fragment. */
    public static MenuFragment plain(String text) {
        return new MenuFragment(text, null, null);
    }
}
