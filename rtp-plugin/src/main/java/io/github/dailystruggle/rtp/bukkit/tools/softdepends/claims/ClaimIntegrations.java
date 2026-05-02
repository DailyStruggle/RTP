package io.github.dailystruggle.rtp.bukkit.tools.softdepends.claims;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.hooks.RegionVerifierRegistry;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.LanguageBootstrap;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bundled claim-plugin integrations for Bukkit-family servers.
 *
 * <p>Each supported claim/protection plugin contributes a {@code GlobalRegionVerifier} that rejects
 * locations reported as "in a claim" by that plugin. Verifiers are registered once at plugin
 * startup (and re-registered after config reload); they are never invoked inline from a command or
 * teleport pipeline stage, preserving <b>REQ-RTP-S-003</b>.
 *
 * <p>Originally shipped as the {@code RTP_ClaimPluginIntegrations} addon; folded into the plugin
 * core in ADR-019.
 */
public final class ClaimIntegrations {
  private ClaimIntegrations() {}

  /**
   * Load {@code integrations.yml}, register it as a {@link ConfigParser}, install the reload hook,
   * and register each enabled verifier.
   *
   * @param plugin bukkit plugin instance (required by {@code LandsIntegration})
   */
  public static void setup(JavaPlugin plugin) {
    Configs configs = RTP.configs;
    ConfigParser<IntegrationsKeys> parser = buildParser(plugin);
    configs.putParser(parser);

    // Initialize Lands once the plugin is loaded (no-op if Lands isn't installed).
    if (Bukkit.getPluginManager().isPluginEnabled("Lands")) {
      LandsChecker.landsSetup(plugin);
    }

    Configs.onReload(() -> {
      ConfigParser<IntegrationsKeys> p = buildParser(plugin);
      RTP.configs.putParser(p);
    });

    registerVerifiers();
  }

  private static ConfigParser<IntegrationsKeys> buildParser(JavaPlugin plugin) {
    String locale = LanguageBootstrap.resolve(RTP.serverAccessor.getPluginDirectory());
    return new ConfigParser<>(
        IntegrationsKeys.class,
        "integrations",
        "1.0",
        RTP.serverAccessor.getPluginDirectory(),
        null,
        RTP.configs.fileDatabase,
        plugin.getClass().getClassLoader(),
        locale);
  }

  @SuppressWarnings("unchecked")
  private static void registerVerifiers() {
    ConfigParser<IntegrationsKeys> configParser =
        (ConfigParser<IntegrationsKeys>) RTP.configs.getParser(IntegrationsKeys.class);
    if (configParser == null) return;

    // ADR-026: register through the public RTPHooks facade rather than the
    // internal GlobalRegionVerifiers static API. Behaviour is identical (the
    // facade delegates to GlobalRegionVerifiers); the indirection is what makes
    // this site auditable per docs/dev/EXTERNAL_HOOKS.md.
    RegionVerifierRegistry verifiers = RTPAPI.hooks().verifiers();

    if (flag(configParser, IntegrationsKeys.rerollFactions)
        && Bukkit.getPluginManager().isPluginEnabled("Factions")) {
      verifiers.register(loc -> !FactionsChecker.isInClaim(loc));
    }
    if (flag(configParser, IntegrationsKeys.rerollGriefDefender)
        && Bukkit.getPluginManager().isPluginEnabled("GriefDefender")) {
      verifiers.register(loc -> !GriefDefenderChecker.isInClaim(loc));
    }
    if (flag(configParser, IntegrationsKeys.rerollGriefPrevention)
        && Bukkit.getPluginManager().isPluginEnabled("GriefPrevention")) {
      verifiers.register(loc -> !GriefPreventionChecker.isInClaim(loc));
    }
    if (flag(configParser, IntegrationsKeys.rerollLands)
        && Bukkit.getPluginManager().isPluginEnabled("Lands")) {
      verifiers.register(loc -> !LandsChecker.isInClaim(loc));
    }
    if (flag(configParser, IntegrationsKeys.rerollHuskTowns)
        && Bukkit.getPluginManager().isPluginEnabled("HuskTowns")) {
      verifiers.register(loc -> !HuskTownsChecker.isInClaim(loc));
    }
    if (flag(configParser, IntegrationsKeys.rerollRedProtect)
        && Bukkit.getPluginManager().isPluginEnabled("RedProtect")) {
      verifiers.register(loc -> !RedProtectChecker.isInClaim(loc));
    }
    if (flag(configParser, IntegrationsKeys.rerollTownyAdvanced)
        && Bukkit.getPluginManager().isPluginEnabled("Towny")) {
      verifiers.register(loc -> !TownyAdvancedChecker.isInClaim(loc));
    }
    if (flag(configParser, IntegrationsKeys.rerollWorldGuard)
        && Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
      verifiers.register(loc -> !WorldGuardChecker.isInClaim(loc));
    }
  }

  private static boolean flag(ConfigParser<IntegrationsKeys> parser, IntegrationsKeys key) {
    Object v = parser.getConfigValue(key, false);
    if (v instanceof Boolean) return (Boolean) v;
    return Boolean.parseBoolean(String.valueOf(v));
  }
}
