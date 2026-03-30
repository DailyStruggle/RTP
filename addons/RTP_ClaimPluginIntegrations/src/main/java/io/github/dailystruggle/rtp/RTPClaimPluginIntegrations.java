package io.github.dailystruggle.rtp;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers;
import io.github.dailystruggle.rtp.softdepends.FactionsChecker;
import io.github.dailystruggle.rtp.softdepends.GriefDefenderChecker;
import io.github.dailystruggle.rtp.softdepends.GriefPreventionChecker;
import io.github.dailystruggle.rtp.softdepends.HuskTownsChecker;
import io.github.dailystruggle.rtp.softdepends.LandsChecker;
import io.github.dailystruggle.rtp.softdepends.RedProtectChecker;
import io.github.dailystruggle.rtp.softdepends.TownyAdvancedChecker;
import io.github.dailystruggle.rtp.softdepends.WorldGuardChecker;
import org.bukkit.plugin.java.JavaPlugin;

/** Main class for RTPClaimPluginIntegrations addon */
public final class RTPClaimPluginIntegrations extends JavaPlugin {
  /** Default constructor for RTPClaimPluginIntegrations */
  public RTPClaimPluginIntegrations() {}

  @Override
  public void onEnable() {
    // Plugin startup logic
    Configs configs = RTP.configs;
    ConfigParser<IntegrationsKeys> integrations =
        new ConfigParser<>(
            IntegrationsKeys.class,
            "integrations",
            "1.0",
            RTP.serverAccessor.getPluginDirectory(),
            null,
            RTP.configs.fileDatabase,
            this.getClassLoader());
    configs.putParser(integrations);
    LandsChecker.landsSetup(this);

    Configs.onReload(
        () ->
            RTP.configs.putParser(
                new ConfigParser<>(
                    IntegrationsKeys.class,
                    "integrations",
                    "1.0",
                    RTP.serverAccessor.getPluginDirectory(),
                    null,
                    RTP.configs.fileDatabase,
                    this.getClassLoader())));
  }

  @Override
  public void onDisable() {
    // Plugin shutdown logic
  }

  public void setupIntegrations() {
    ConfigParser<IntegrationsKeys> configParser =
        (ConfigParser<IntegrationsKeys>) RTP.configs.getParser(IntegrationsKeys.class);

    boolean factionsEnabled =
        Boolean.parseBoolean(
            configParser.getConfigValue(IntegrationsKeys.rerollFactions, false).toString());
    boolean griefDefenderEnabled =
        Boolean.parseBoolean(
            configParser.getConfigValue(IntegrationsKeys.rerollGriefDefender, false).toString());
    boolean griefPreventionEnabled =
        Boolean.parseBoolean(
            configParser.getConfigValue(IntegrationsKeys.rerollGriefPrevention, false).toString());
    boolean landsEnabled =
        Boolean.parseBoolean(
            configParser.getConfigValue(IntegrationsKeys.rerollLands, false).toString());
    boolean huskTownsEnabled =
        Boolean.parseBoolean(
            configParser.getConfigValue(IntegrationsKeys.rerollHuskTowns, false).toString());
    boolean redProtectEnabled =
        Boolean.parseBoolean(
            configParser.getConfigValue(IntegrationsKeys.rerollRedProtect, false).toString());
    boolean townyAdvancedEnabled =
        Boolean.parseBoolean(
            configParser.getConfigValue(IntegrationsKeys.rerollTownyAdvanced, false).toString());
    boolean worldGuardEnabled =
        Boolean.parseBoolean(
            configParser.getConfigValue(IntegrationsKeys.rerollWorldGuard, false).toString());

    if (factionsEnabled) {
      GlobalRegionVerifiers.addGlobalRegionVerifier(loc -> !FactionsChecker.isInClaim(loc));
    }
    if (griefDefenderEnabled) {
      GlobalRegionVerifiers.addGlobalRegionVerifier(loc -> !GriefDefenderChecker.isInClaim(loc));
    }
    if (griefPreventionEnabled) {
      GlobalRegionVerifiers.addGlobalRegionVerifier(loc -> !GriefPreventionChecker.isInClaim(loc));
    }
    if (landsEnabled) {
      GlobalRegionVerifiers.addGlobalRegionVerifier(loc -> !LandsChecker.isInClaim(loc));
    }
    if (huskTownsEnabled) {
      GlobalRegionVerifiers.addGlobalRegionVerifier(loc -> !HuskTownsChecker.isInClaim(loc));
    }
    if (redProtectEnabled) {
      GlobalRegionVerifiers.addGlobalRegionVerifier(loc -> !RedProtectChecker.isInClaim(loc));
    }
    if (townyAdvancedEnabled) {
      GlobalRegionVerifiers.addGlobalRegionVerifier(loc -> !TownyAdvancedChecker.isInClaim(loc));
    }
    if (worldGuardEnabled) {
      GlobalRegionVerifiers.addGlobalRegionVerifier(loc -> !WorldGuardChecker.isInClaim(loc));
    }
  }
}
