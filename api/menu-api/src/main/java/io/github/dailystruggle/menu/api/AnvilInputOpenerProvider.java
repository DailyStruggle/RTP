package io.github.dailystruggle.menu.api;

import io.github.dailystruggle.server.api.PlatformFamily;
import org.jetbrains.annotations.Nullable;

/**
 * Service-discovery provider for an {@link AnvilInputOpener} via {@link java.util.ServiceLoader}
 * (ADR-070).
 *
 * @governedBy ADR-045
 * @governedBy ADR-070
 * @governedBy ADR-115
 */
public interface AnvilInputOpenerProvider {

  /**
   * Instantiates and wires the {@link AnvilInputOpener}.
   *
   * @return ready-to-use input opener; never {@code null}
   */
  AnvilInputOpener create();

  /**
   * Returns the {@link PlatformFamily} this opener is bound to, or {@code null} when
   * platform-neutral.
   *
   * @return bound platform family, or {@code null}
   */
  default @Nullable PlatformFamily platformFamily() {
    return null;
  }
}
