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
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
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

    GlobalRegionVerifiers.addGlobalRegionVerifier(
        rtpLocation -> {
          World world = Bukkit.getWorld(rtpLocation.worldName());
          if (world == null) return false;
          Location location =
              new Location(world, rtpLocation.x(), rtpLocation.y(), rtpLocation.z());

          boolean res = true;

          if (Boolean.parseBoolean(
              configParser.getConfigValue(IntegrationsKeys.rerollFactions, false).toString())) {
            res = !FactionsChecker.isInClaim(location);
          }

          if (res
              && Boolean.parseBoolean(
                  configParser
                      .getConfigValue(IntegrationsKeys.rerollGriefDefender, false)
                      .toString())) {
            res = !GriefDefenderChecker.isInClaim(location);
          }

          if (res
              && Boolean.parseBoolean(
                  configParser
                      .getConfigValue(IntegrationsKeys.rerollGriefPrevention, false)
                      .toString())) {
            res = !GriefPreventionChecker.isInClaim(location);
          }

          if (res
              && Boolean.parseBoolean(
                  configParser.getConfigValue(IntegrationsKeys.rerollLands, false).toString())) {
            res = !LandsChecker.isInClaim(location);
          }

          if (res
              && Boolean.parseBoolean(
                  configParser
                      .getConfigValue(IntegrationsKeys.rerollHuskTowns, false)
                      .toString())) {
            res = !HuskTownsChecker.isInClaim(location);
          }

          if (res
              && Boolean.parseBoolean(
                  configParser
                      .getConfigValue(IntegrationsKeys.rerollRedProtect, false)
                      .toString())) {
            res = !RedProtectChecker.isInClaim(location);
          }

          if (res
              && Boolean.parseBoolean(
                  configParser
                      .getConfigValue(IntegrationsKeys.rerollTownyAdvanced, false)
                      .toString())) {
            res = !TownyAdvancedChecker.isInClaim(location);
          }

          if (res
              && Boolean.parseBoolean(
                  configParser
                      .getConfigValue(IntegrationsKeys.rerollWorldGuard, false)
                      .toString())) {
            res = !WorldGuardChecker.isInClaim(location);
          }

          return res;
        });
  }
}
