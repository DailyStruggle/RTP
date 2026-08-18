package io.github.dailystruggle.rtp.api.menu;

import java.util.List;
import java.util.Objects;

/**
 * Platform-neutral, immutable menu model (ADR-044).
 *
 * @param title plain-text title rendered as heading
 * @param pages ordered, non-empty list of pages
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
