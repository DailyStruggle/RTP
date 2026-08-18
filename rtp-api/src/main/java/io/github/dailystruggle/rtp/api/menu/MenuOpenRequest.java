package io.github.dailystruggle.rtp.api.menu;

import java.util.Objects;
import java.util.UUID;

/**
 * Inputs passed to page builder when opening a menu page (ADR-035).
 *
 * <p>Bundles viewer UUID and zero-based page index. Builders clamp out-of-range pages.
 */
public record MenuOpenRequest(UUID viewer, int pageIndex) {

    public MenuOpenRequest {
        Objects.requireNonNull(viewer, "viewer");
        if (pageIndex < 0) {
            throw new IllegalArgumentException("pageIndex must be >= 0, was " + pageIndex);
        }
    }

    /** Convenience factory for the default first-page open ({@code pageIndex == 0}). */
    public static MenuOpenRequest firstPage(UUID viewer) {
        return new MenuOpenRequest(viewer, 0);
    }
}
