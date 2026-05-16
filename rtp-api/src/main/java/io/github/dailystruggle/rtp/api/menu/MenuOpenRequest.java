package io.github.dailystruggle.rtp.api.menu;

import java.util.Objects;
import java.util.UUID;

/**
 * Inputs passed from {@code MenuRedeemSubcommand}'s open-page branch to the
 * caller-supplied page builder (CHECKLIST-generalized-menu.md item 5.3.a,
 * D-005 approved 2026-05-15).
 *
 * <p>Bundles the viewer UUID with the requested {@code pageIndex} (zero-based)
 * so chat-style renderers — which lack a native client-side page-navigation
 * click — can round-trip through {@code /rtp menu page:<n>} and have the
 * builder slice the reflected {@link MenuModel} to the matching page. The
 * record exists as a stable parameter object so future open-time inputs
 * (filter text, search prefix, locale override, …) can be added without
 * churning every {@code pageBuilder} signature.
 *
 * <p>Construction validation:
 * <ul>
 *   <li>{@code viewer} is non-null (S-006: API entry points reject pre-load
 *       state explicitly; here a null UUID would indicate caller misuse, not a
 *       missing core).</li>
 *   <li>{@code pageIndex} is non-negative. Out-of-range high values are
 *       <em>not</em> rejected here — builders are responsible for clamping to
 *       the model's actual page count so a stale click after a config edit
 *       degrades gracefully instead of throwing.</li>
 * </ul>
 *
 * <p>Plain-text on the API surface, no platform imports — per ADR-035 and the
 * package-info contract.
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
