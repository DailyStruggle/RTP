package io.github.dailystruggle.rtp.claimaddon;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.hooks.RegionVerifierRegistry;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.LanguageBootstrap;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Registers one safety verifier per supported claim/protection plugin.
 *
 * <p>Each verifier rejects a destination that the named plugin reports as claimed. They are
 * registered once at addon load (and re-registered on {@code /rtp reload}) through the public
 * hook facade (ADR-026); they are never invoked inline from a command or pipeline stage, which
 * preserves REQ-RTP-S-003.
 *
 * <p>Every checker is gated on both its {@code integrations.yml} flag and the host plugin being
 * enabled, so only installed-and-enabled integrations register.
 */
public final class ClaimIntegrations {
  private ClaimIntegrations() {}

  /**
   * Load {@code integrations.yml}, install the reload hook, and register each enabled verifier.
   *
   * @param resourceLoader the addon class loader, used to extract the bundled {@code integrations.yml}
   */
  public static void setup(ClassLoader resourceLoader) {
    RTP.configs.putParser(buildParser(resourceLoader));

    // Lands needs a Bukkit plugin instance to register its API; reuse the RTP plugin.
    Plugin host = Bukkit.getPluginManager().getPlugin("RTP");
    if (host != null && Bukkit.getPluginManager().isPluginEnabled("Lands")) {
      LandsChecker.landsSetup(host);
    }

    Configs.onReload(() -> RTP.configs.putParser(buildParser(resourceLoader)));

    registerVerifiers();
  }

  private static ConfigParser<IntegrationsKeys> buildParser(ClassLoader resourceLoader) {
    String locale = LanguageBootstrap.resolve(RTP.serverAccessor.getPluginDirectory());
    return new ConfigParser<>(
        IntegrationsKeys.class,
        "integrations",
        "1.0",
        RTP.serverAccessor.getPluginDirectory(),
        null,
        RTP.configs.fileDatabase,
        resourceLoader,
        locale);
  }

  @SuppressWarnings("unchecked")
  private static void registerVerifiers() {
    ConfigParser<IntegrationsKeys> parser =
        (ConfigParser<IntegrationsKeys>) RTP.configs.getParser(IntegrationsKeys.class);
    if (parser == null) return;

    RegionVerifierRegistry verifiers = RTPAPI.hooks().verifiers();

    register(parser, verifiers, IntegrationsKeys.rerollSaberFactions, "Factions", () -> SaberFactionsChecker::isInClaim);
    register(parser, verifiers, IntegrationsKeys.rerollFactionsBridge, "FactionsBridge", () -> FactionsBridgeChecker::isInClaim);
    register(parser, verifiers, IntegrationsKeys.rerollGriefDefender, "GriefDefender", () -> GriefDefenderChecker::isInClaim);
    register(parser, verifiers, IntegrationsKeys.rerollGriefPrevention, "GriefPrevention", () -> GriefPreventionChecker::isInClaim);
    register(parser, verifiers, IntegrationsKeys.rerollLands, "Lands", () -> LandsChecker::isInClaim);
    register(parser, verifiers, IntegrationsKeys.rerollRedProtect, "RedProtect", () -> RedProtectChecker::isInClaim);
    register(parser, verifiers, IntegrationsKeys.rerollResidence, "Residence", () -> ResidenceChecker::isInClaim);
    register(parser, verifiers, IntegrationsKeys.rerollCrashClaim, "CrashClaim", () -> CrashClaimChecker::isInClaim);
    register(parser, verifiers, IntegrationsKeys.rerollHuskClaims, "HuskClaims", () -> HuskClaimsChecker::isInClaim);
    register(parser, verifiers, IntegrationsKeys.rerollKingdomsX, "Kingdoms", () -> KingdomsXChecker::isInClaim);
    register(parser, verifiers, IntegrationsKeys.rerollTownyAdvanced, "Towny", () -> TownyAdvancedChecker::isInClaim);
    register(parser, verifiers, IntegrationsKeys.rerollWorldGuard, "WorldGuard", () -> WorldGuardChecker::isInClaim);
  }

  /**
   * Registers a reroll-on-claim verifier when {@code key} is enabled and {@code pluginName} is
   * running.
   *
   * <p>The checker is supplied lazily through {@code checkSupplier} so that resolving the
   * checker class (and any method handles it references) happens only for installed-and-enabled
   * integrations, inside a guard. This keeps a single integration whose host plugin shipped an
   * incompatible API (e.g. a removed/relocated class triggering {@link NoClassDefFoundError})
   * from aborting registration of every other integration.
   */
  private static void register(
      ConfigParser<IntegrationsKeys> parser,
      RegionVerifierRegistry verifiers,
      IntegrationsKeys key,
      String pluginName,
      java.util.function.Supplier<ClaimCheck> checkSupplier) {
    if (!flag(parser, key) || !Bukkit.getPluginManager().isPluginEnabled(pluginName)) {
      return;
    }
    try {
      ClaimCheck check = checkSupplier.get();
      verifiers.register(loc -> !check.isInClaim(loc));
    } catch (Throwable t) {
      RTP.log(
          java.util.logging.Level.WARNING,
          "[RTP] claim integration for "
              + pluginName
              + " could not be registered (incompatible plugin version?); skipping it.",
          t);
    }
  }

  private static boolean flag(ConfigParser<IntegrationsKeys> parser, IntegrationsKeys key) {
    Object v = parser.getConfigValue(key, false);
    if (v instanceof Boolean) return (Boolean) v;
    return Boolean.parseBoolean(String.valueOf(v));
  }

  /** A single claim-plugin "is this location claimed?" probe. */
  @FunctionalInterface
  private interface ClaimCheck {
    boolean isInClaim(io.github.dailystruggle.rtp.api.world.RTPCoords location);
  }
}
