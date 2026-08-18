package io.github.dailystruggle.rtp.api.menu;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Plain-text styled run within a {@link MenuLine} (ADR-035).
 *
 * @param text   visible text; never {@code null}
 * @param hover  optional tooltip shown on cursor hover (ADR-044), or {@code null}
 * @param action optional click action, or {@code null} if purely decorative
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
