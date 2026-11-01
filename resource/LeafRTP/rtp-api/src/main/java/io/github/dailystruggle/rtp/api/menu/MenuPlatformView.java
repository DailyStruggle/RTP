package io.github.dailystruggle.rtp.api.menu;

import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Per-build snapshot of platform seams for menu page builders (ADR-048).
 * Pure snapshot captured once at builder construction time.
 *
 * @param hasPermission        permission predicate; never null
 * @param effectivePermissions granted rtp.* permissions; never null
 * @param locale               locale tag; never null
 * @param regionDescriptor     short region descriptor; never null
 */
public record MenuPlatformView(
    Predicate<String> hasPermission,
    Set<String> effectivePermissions,
    String locale,
    String regionDescriptor) {

  public MenuPlatformView {
    Objects.requireNonNull(hasPermission, "hasPermission");
    Objects.requireNonNull(effectivePermissions, "effectivePermissions");
    Objects.requireNonNull(locale, "locale");
    Objects.requireNonNull(regionDescriptor, "regionDescriptor");
  }

  /**
   * Snapshots the menu platform surface for {@code player} from {@code accessor}.
   *
   * @param accessor server accessor; non-null
   * @param player   player UUID; non-null
   * @return new snapshot view; never null
   */
  public static MenuPlatformView of(RTPServerAccessor accessor, UUID player) {
    Objects.requireNonNull(accessor, "accessor");
    Objects.requireNonNull(player, "player");
    return new MenuPlatformView(
        accessor.menuPermissionProbe(player),
        accessor.menuEffectivePermissions(player),
        accessor.menuLocale(player),
        accessor.menuRegionDescriptor(player));
  }
}
