package io.github.dailystruggle;

import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.github.dailystruggle.softdepends.*;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

public final class RTPClaimPluginIntegrations extends JavaPlugin {

    @Override
    public void onEnable() {
        // Plugin startup logic
        Configs configs = RTP.configs;
        ConfigParser<IntegrationsKeys> integrations = new ConfigParser<>(IntegrationsKeys.class,"integrations","1.0",RTP.serverAccessor.getPluginDirectory(), null, this.getClass().getClassLoader());
        configs.putParser(integrations);

        Configs.onReload(() -> RTP.configs.putParser(new ConfigParser<>(IntegrationsKeys.class,"integrations","1.0",RTP.serverAccessor.getPluginDirectory(), null, this.getClass().getClassLoader())));
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }

    public void setupIntegrations() {
        ConfigParser<IntegrationsKeys> configParser = (ConfigParser<IntegrationsKeys>) RTP.configs.getParser(IntegrationsKeys.class);

        Region.addGlobalRegionVerifier(rtpLocation -> {
            RTPWorld rtpWorld = rtpLocation.world();
            if(!(rtpWorld instanceof BukkitRTPWorld)) return false;
            if(!rtpWorld.isActive()) return false;
            BukkitRTPWorld bukkitRTPWorld = (BukkitRTPWorld) rtpWorld;
            World world = bukkitRTPWorld.world();
            Location location = new Location(world, rtpLocation.x(), rtpLocation.y(), rtpLocation.z());

            boolean res = true;

            if(Boolean.parseBoolean(configParser.getConfigValue(IntegrationsKeys.rerollFactions, false).toString())) {
                res = !FactionsChecker.isInClaim(location);
            }

            if(res && Boolean.parseBoolean(configParser.getConfigValue(IntegrationsKeys.rerollGriefDefender, false).toString())) {
                res = !GriefDefenderChecker.isInClaim(location);
            }

            if(res && Boolean.parseBoolean(configParser.getConfigValue(IntegrationsKeys.rerollGriefPrevention, false).toString())) {
                res = !GriefPreventionChecker.isInClaim(location);
            }

            if(res && Boolean.parseBoolean(configParser.getConfigValue(IntegrationsKeys.rerollLands, false).toString())) {
                res = !LandsChecker.isInClaim(location);
            }

            if(res && Boolean.parseBoolean(configParser.getConfigValue(IntegrationsKeys.rerollHuskTowns, false).toString())) {
                res = !HuskTownsChecker.isInClaim(location);
            }

            if(res && Boolean.parseBoolean(configParser.getConfigValue(IntegrationsKeys.rerollRedProtect, false).toString())) {
                res = !RedProtectChecker.isInClaim(location);
            }

            if(res && Boolean.parseBoolean(configParser.getConfigValue(IntegrationsKeys.rerollTownyAdvanced, false).toString())) {
                res = !TownyAdvancedChecker.isInClaim(location);
            }

            if(res && Boolean.parseBoolean(configParser.getConfigValue(IntegrationsKeys.rerollWorldGuard, false).toString())) {
                res = !WorldGuardChecker.isInClaim(location);
            }

            return res;
        });
    }
}
