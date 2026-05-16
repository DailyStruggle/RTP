package io.github.dailystruggle.rtp.api.menu;

import java.util.List;
import java.util.Objects;

/**
 * Platform-neutral, deeply-immutable menu model.
 *
 * <p>Built by a producer (e.g. the command-tree reflector from ADR-044) and
 * consumed by a {@link MenuRenderer}. The model carries no platform types —
 * renderers translate to Adventure {@code Book}, {@code tellraw}-equivalent
 * components, or other native representations.
 *
 * @param title plain-text title rendered as the book/screen heading.
 * @param pages ordered, non-empty list of pages. Pagination is the renderer's
 *              responsibility; producers should respect the renderer's
 *              line/page caps (ADR-035 §Click handling §4).
 */
public record MenuModel(String title, List<MenuPage> pages) {

    public MenuModel {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(pages, "pages");
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("MenuModel must contain at least one page");
        }
        for (int i = 0; i < pages.size(); i++) {
            Objects.requireNonNull(pages.get(i), "pages[" + i + "]");
        }
        pages = List.copyOf(pages);
    }
}
