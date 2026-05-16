/**
 * Platform-neutral menu framework foundation.
 *
 * <p>This package defines the value types and SPIs that let RTP render
 * interactive menus (written-book on Paper/Folia, chat fallback elsewhere)
 * without dragging any platform or rendering library into {@code rtp-api}.
 *
 * <p><b>Layering.</b> No {@code org.bukkit.*}, no Adventure, no platform
 * imports. Renderers and the token registry implementation live in adapter
 * modules and {@code rtp-core} respectively.
 *
 * <p><b>Design decisions:</b>
 * <ul>
 *   <li>ADR-035 — Interactive menus via written book (book-first, chat fallback).</li>
 *   <li>ADR-044 — Command-tree → {@link io.github.dailystruggle.rtp.api.menu.MenuModel}
 *       reflector.</li>
 *   <li>ADR-042 — Block-comment preservation in the in-house YAML substrate
 *       (unblocks parameter hover text).</li>
 * </ul>
 *
 * <p><b>Requirements inherited via the redeem command pipeline:</b>
 * REQ-RTP-S-004 (no silent failures), REQ-RTP-S-005 (no main-thread chunk I/O),
 * REQ-RTP-S-006 (require-by-contract API entry points),
 * REQ-RTP-S-007 / REQ-RTP-F-013 (configurable failure messages).
 */
package io.github.dailystruggle.rtp.api.menu;
