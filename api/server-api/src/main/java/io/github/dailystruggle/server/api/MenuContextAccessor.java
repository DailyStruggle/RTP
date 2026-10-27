package io.github.dailystruggle.server.api;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Platform-neutral menu viewer permission, locale, and region descriptor probe contract (ADR-048).
 *
 * @governedBy ADR-048
 * @governedBy ADR-115
 */
public interface MenuContextAccessor {
  /**
   * Returns permission probe for menu visibility filtering (ADR-048).
   *
   * @param player player UUID
   * @return non-null permission predicate; defaults to always {@code false}
   */
  default Predicate<String> menuPermissionProbe(UUID player) {
    return node -> false;
  }

  /**
   * Returns a factory mapping a player {@link UUID} to its menu permission probe (ADR-048).
   *
   * @return non-null permission probe factory delegating to {@link #menuPermissionProbe(UUID)}
   */
  default Function<UUID, Predicate<String>> menuPermissionProbeFactory() {
    return this::menuPermissionProbe;
  }

  /**
   * Returns effective permissions snapshot for menu namespaces (ADR-048).
   *
   * @param player player UUID
   * @return permission set; empty if unresolved
   */
  default Set<String> menuEffectivePermissions(UUID player) {
    return Collections.emptySet();
  }

  /**
   * Returns BCP-47 locale tag for menu rendering (ADR-048).
   *
   * @param player player UUID
   * @return locale tag; defaults to {@code "en_us"}
   */
  default String menuLocale(UUID player) {
    return "en_us";
  }

  /**
   * Returns descriptor for player's current region/world (ADR-048).
   *
   * @param player player UUID
   * @return region descriptor; empty string if unknown
   */
  default String menuRegionDescriptor(UUID player) {
    return "";
  }
}
