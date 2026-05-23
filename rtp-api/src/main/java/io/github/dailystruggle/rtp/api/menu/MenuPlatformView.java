package io.github.dailystruggle.rtp.api.menu;

import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Per-build snapshot of the platform seams a menu page builder consults.
 *
 * <p>Introduced by ADR-048 to remove the {@code rtp-bukkit} / {@code rtp-fabric}
 * coupling that previously lived inside the {@code rtp-core} page builders
 * ({@code FrontPageBuilder}, {@code AdminPanelBuilder}, etc.). Builders accept
 * a {@code MenuPlatformView} in their constructor and consult only its fields
 * during {@code build()}, so the same builder code runs on every platform.
 *
 * <p>The view is a <b>snapshot</b>, not a live binding: the four fields are
 * captured from {@link RTPServerAccessor} at the moment {@link #of} is called.
 * This keeps builders pure (same input -&gt; same {@code MenuModel}) and avoids
 * cross-thread races on permission / locale changes mid-render.
 *
 * @param hasPermission       permission probe for menu-visibility filtering;
 *                            never {@code null}.
 * @param effectivePermissions menu-relevant {@code rtp.*} permissions granted
 *                            to the player; never {@code null}. May be empty
 *                            when the platform cannot enumerate (see ADR-011
 *                            for Fabric semantics).
 * @param locale              BCP-47-like locale tag (e.g. {@code "en_us"});
 *                            never {@code null}.
 * @param regionDescriptor    short descriptor of the player's current region
 *                            / world, for the {@code FrontPageBuilder} header.
 *                            Never {@code null}; empty string when unknown
 *                            (e.g. cross-backend in network mode).
 *
 * @see RTPServerAccessor#menuPermissionProbe(UUID)
 * @see RTPServerAccessor#menuEffectivePermissions(UUID)
 * @see RTPServerAccessor#menuLocale(UUID)
 * @see RTPServerAccessor#menuRegionDescriptor(UUID)
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
   * <p>Each field is read once; subsequent accessor mutations do not propagate
   * into the returned view. Callers that need a fresh snapshot (e.g. on every
   * {@code /rtp} invocation) should call {@code of} again.
   *
   * @param accessor the server accessor to read from; must not be {@code null}.
   * @param player   the player UUID the view is bound to; must not be {@code null}.
   * @return a new snapshot; never {@code null}.
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
