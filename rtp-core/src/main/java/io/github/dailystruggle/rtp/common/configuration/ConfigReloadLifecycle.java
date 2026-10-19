package io.github.dailystruggle.rtp.common.configuration;

/**
 * Reload steps that act on tasks and regions, run by {@link Configs} at fixed points of a reload.
 *
 * <p>Inverts the reload dependency: {@code configuration} loads parsers and calls this interface;
 * the Tier 4 implementation (installed by the composition root) owns task shutdown, teleport
 * cancellation, tick-budget refresh and region rebuild. The default methods do nothing, so a
 * {@link Configs} without an installed lifecycle only loads parsers (ADR-115).
 *
 * <p>Order: {@link #beforeReload()} runs before any parser is rebuilt; {@link #onParsersChanged()}
 * runs after the parser maps are swapped; {@link #reloadRegions(Configs)} runs from
 * {@link Configs#reloadRegions()}. Do not call tasks or regions from {@code configuration}
 * directly; add a step here instead.
 */
public interface ConfigReloadLifecycle {

  /** No-op lifecycle used until the composition root installs one. */
  ConfigReloadLifecycle NONE = new ConfigReloadLifecycle() {};

  /** Stops work that must not observe a parser swap (scans, in-flight teleports). */
  default void beforeReload() {}

  /** Refreshes state derived from the parsers, after the maps are swapped or a parser is replaced. */
  default void onParsersChanged() {}

  /**
   * Shuts down current regions and rebuilds them from the region parsers of {@code configs}.
   *
   * @param configs the configuration whose parsers define the regions; never {@code null}
   */
  default void reloadRegions(Configs configs) {}
}
