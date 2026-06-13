package io.github.dailystruggle.rtp.api.hooks;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

import io.github.dailystruggle.rtp.api.platform.PlatformCreator;

/**
 * Single-binding registry for the arrival-{@linkplain PlatformCreator platform creator}
 * (ADR-058). A bound creator builds the safe footprint a teleporting player lands on at the
 * confirmed arrival location - a pasted schematic, a procedurally generated pad, a lobby
 * structure, etc.
 *
 * <p>RTP consults the bound creator at teleport commit. When no creator is bound here, RTP
 * falls back to the platform adapter's own creator (installed at bootstrap, e.g. the
 * {@code SchematicPaster} slot on {@code BukkitRTPWorld}) and ultimately to the emergency
 * block disc written by {@code RTPWorld#platform(RTPLocation)}; so an unbound registry never
 * changes behaviour.
 *
 * <p>This is the addon-facing override path: an addon shipping a custom platform builder
 * binds it here instead of reaching into a platform adapter's static setter (ADR-026). The
 * bundled file-backed specialisation is
 * {@link io.github.dailystruggle.rtp.api.schematic.SchematicPaster}; addons may bind any
 * {@code PlatformCreator}.
 *
 * <p><b>Threading.</b> Any blocking work in the bound creator runs off the region/tick
 * thread (S-005); block writes run on the region-owning thread. Implementations shall be
 * thread-safe.
 */
@PublicApi
public interface PlatformCreatorRegistry {

  /**
   * Install {@code creator} as the active platform creator, overriding the platform default.
   *
   * @param creator non-null platform creator
   */
  void bind(PlatformCreator creator);

  /**
   * @return the currently bound creator, or {@code null} if no addon override is active and
   *     the platform default is used.
   */
  PlatformCreator current();

  /** Restore the platform default (no addon override). */
  void clear();
}
