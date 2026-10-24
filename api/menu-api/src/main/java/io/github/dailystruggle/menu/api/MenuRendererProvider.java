package io.github.dailystruggle.menu.api;

import io.github.dailystruggle.server.api.PlatformFamily;
import org.jetbrains.annotations.Nullable;

/**
 * Service-discovery provider for a {@link MenuRenderer} via {@link java.util.ServiceLoader}
 * (ADR-070).
 */
public interface MenuRendererProvider {

  /**
   * Returns the lowercase renderer identifier matched against configuration (for example,
   * {@code "book"} or {@code "chat"}).
   *
   * @return renderer identifier; never {@code null}
   */
  String id();

  /**
   * Instantiates a ready-to-use {@link MenuRenderer}.
   *
   * @return menu renderer instance; never {@code null}
   */
  MenuRenderer create();

  /**
   * Returns the {@link PlatformFamily} this provider serves, or {@code null} when platform-neutral.
   *
   * @return bound platform family, or {@code null} for any platform
   */
  default @Nullable PlatformFamily platformFamily() {
    return null;
  }
}
