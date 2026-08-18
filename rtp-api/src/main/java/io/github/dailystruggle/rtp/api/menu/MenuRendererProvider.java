package io.github.dailystruggle.rtp.api.menu;

import io.github.dailystruggle.rtp.api.server.PlatformFamily;
import org.jetbrains.annotations.Nullable;

/**
 * Service-discovery provider for a {@link MenuRenderer} via {@link java.util.ServiceLoader}.
 */
public interface MenuRendererProvider {

    /**
     * The lowercase identifier matched against the {@code menu.renderer}
     * configuration list (e.g. {@code "book"}). Must not be {@code null}.
     *
     * @return the renderer id this provider supplies
     */
    String id();

    /**
     * Instantiate the {@link MenuRenderer}. Implementations should perform any
     * platform wiring they require (the call happens once at command-tree
     * assembly time).
     *
     * @return a ready-to-use renderer; must not be {@code null}
     */
    MenuRenderer create();

    /**
     * The {@link PlatformFamily} this provider's renderer is bound to, or
     * {@code null} when the renderer is platform-neutral.
     *
     * @return platform family this renderer serves, or {@code null} for any
     */
    default @Nullable PlatformFamily platformFamily() {
        return null;
    }
}
