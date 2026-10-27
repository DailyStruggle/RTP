package io.github.dailystruggle.menu.api;

import java.util.UUID;

/**
 * Platform-neutral SPI for rendering a {@link MenuDescriptor} to a viewer (ADR-048, ADR-050).
 *
 * @governedBy ADR-048
 * @governedBy ADR-050
 * @governedBy ADR-115
 */
@FunctionalInterface
public interface MenuRenderer {

  /**
   * Renders {@code descriptor} to the target viewer.
   *
   * @param viewer target player UUID; must not be {@code null}
   * @param descriptor menu descriptor to render; must not be {@code null}
   * @throws IllegalStateException if invoked before the platform runtime is initialized
   */
  void render(UUID viewer, MenuDescriptor descriptor);
}
