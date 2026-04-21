package io.github.dailystruggle.rtp.bukkit.tools.softdepends.claims;

/**
 * Configuration keys for bundled claim-plugin integrations.
 *
 * <p>These keys drive {@code integrations.yml} and are honored by {@link ClaimIntegrations} when
 * registering {@code GlobalRegionVerifiers} at plugin startup.
 */
public enum IntegrationsKeys {
  /** Whether to reroll if inside a WorldGuard region */
  rerollWorldGuard,
  /** Whether to reroll if inside a GriefDefender claim */
  rerollGriefDefender,
  /** Whether to reroll if inside a GriefPrevention claim */
  rerollGriefPrevention,
  /** Whether to reroll if inside a TownyAdvanced town */
  rerollTownyAdvanced,
  /** Whether to reroll if inside a HuskTowns town */
  rerollHuskTowns,
  /** Whether to reroll if inside a Factions territory */
  rerollFactions,
  /** Whether to reroll if inside a Lands land */
  rerollLands,
  /** Whether to reroll if inside a RedProtect region */
  rerollRedProtect,
}
