package io.github.dailystruggle.rtp.api.menu;

import java.util.List;
import java.util.Objects;

/**
 * One horizontal line of a {@link MenuPage}, composed of {@link MenuFragment}s.
 *
 * <p>The fragment list is defensively copied at construction and exposed via
 * an unmodifiable view; instances are deeply immutable.
 */
public record MenuLine(List<MenuFragment> fragments) {

    public MenuLine {
        Objects.requireNonNull(fragments, "fragments");
        for (int i = 0; i < fragments.size(); i++) {
            Objects.requireNonNull(fragments.get(i), "fragments[" + i + "]");
        }
        fragments = List.copyOf(fragments);
    }

    /** Convenience factory for a single-fragment line. */
    public static MenuLine of(MenuFragment fragment) {
        return new MenuLine(List.of(fragment));
    }
}
