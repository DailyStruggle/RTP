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
  /** Whether to reroll if inside a SaberFactions territory */
  rerollSaberFactions,
  /** Whether to reroll if inside Factions territory reported via the FactionsBridge API */
  rerollFactionsBridge,
  /** Whether to reroll if inside a Lands land */
  rerollLands,
  /** Whether to reroll if inside a RedProtect region */
  rerollRedProtect,
  /** Whether to reroll if inside a Residence claim */
  rerollResidence,
  /** Whether to reroll if inside a CrashClaim claim */
  rerollCrashClaim,
  /** Whether to reroll if inside a HuskClaims claim */
  rerollHuskClaims,
  /** Whether to reroll if inside KingdomsX claimed land */
  rerollKingdomsX,
}
