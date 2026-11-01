package io.github.dailystruggle.rtp.api.menu;

import java.util.Objects;
import java.util.UUID;

/**
 * Renders a {@link MenuModel} to a player on a specific platform (ADR-048, ADR-050).
 * Throws {@link IllegalStateException} if invoked before core loads (REQ-RTP-S-006).
 *
 * @governedBy ADR-048
 * @governedBy ADR-050
 * @governedBy ADR-115
 * @trace REQ-RTP-S-006
 */
@FunctionalInterface
public interface MenuRenderer extends io.github.dailystruggle.menu.api.MenuRenderer {

    /**
     * Render {@code model} to the given player.
     *
     * @param playerId target player UUID; never {@code null}.
     * @param model    the menu to render; never {@code null}.
     * @throws IllegalStateException if invoked before {@code rtp-core} is loaded
     *                               (REQ-RTP-S-006).
     */
    void render(UUID playerId, MenuModel model);

    @Override
    default void render(UUID viewer, io.github.dailystruggle.menu.api.MenuDescriptor descriptor) {
        render(viewer, MenuModel.fromMenuDescriptor(descriptor));
    }

    /**
     * Adapts a platform-neutral {@link io.github.dailystruggle.menu.api.MenuRenderer} to a
     * {@link MenuRenderer}.
     *
     * @param delegate descriptor renderer; must not be {@code null}
     * @return adapted {@link MenuRenderer}
     */
    static MenuRenderer fromDescriptorRenderer(
            io.github.dailystruggle.menu.api.MenuRenderer delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return (playerId, model) ->
                delegate.render(playerId, Objects.requireNonNull(model, "model").toMenuDescriptor());
    }
}
