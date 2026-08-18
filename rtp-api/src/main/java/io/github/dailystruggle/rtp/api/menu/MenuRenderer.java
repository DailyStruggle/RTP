package io.github.dailystruggle.rtp.api.menu;

import java.util.UUID;

/**
 * Renders a {@link MenuModel} to a player on a specific platform (ADR-048, ADR-050).
 * Throws {@link IllegalStateException} if invoked before core loads (REQ-RTP-S-006).
 */
public interface MenuRenderer {

    /**
     * Render {@code model} to the given player.
     *
     * @param playerId target player UUID; never {@code null}.
     * @param model    the menu to render; never {@code null}.
     * @throws IllegalStateException if invoked before {@code rtp-core} is loaded
     *                               (REQ-RTP-S-006).
     */
    void render(UUID playerId, MenuModel model);
}
