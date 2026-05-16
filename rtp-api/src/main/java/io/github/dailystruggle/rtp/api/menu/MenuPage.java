package io.github.dailystruggle.rtp.api.menu;

import java.util.List;
import java.util.Objects;

/**
 * One page of a {@link MenuModel}, composed of {@link MenuLine}s.
 *
 * <p>The line list is defensively copied at construction and exposed via an
 * unmodifiable view; instances are deeply immutable.
 */
public record MenuPage(List<MenuLine> lines) {

    public MenuPage {
        Objects.requireNonNull(lines, "lines");
        for (int i = 0; i < lines.size(); i++) {
            Objects.requireNonNull(lines.get(i), "lines[" + i + "]");
        }
        lines = List.copyOf(lines);
    }
}
