package leafcraft.rtp.api.configuration;

import leafcraft.rtp.api.RTPAPI;
import leafcraft.rtp.api.configuration.enums.*;
import leafcraft.rtp.api.factory.Factory;
import leafcraft.rtp.api.selection.region.selectors.shapes.Shape;
import leafcraft.rtp.api.selection.region.selectors.verticalAdjustors.VerticalAdjustor;

import java.io.File;
import java.util.EnumMap;
import java.util.concurrent.CompletableFuture;


public abstract class Configs {
    protected final File pluginDirectory;

    public ConfigParser<LangKeys> lang;
    public ConfigParser<ConfigKeys> config;
    public ConfigParser<EconomyKeys> economy;
    public ConfigParser<IntegrationsKeys> integrations;
    public ConfigParser<PerformanceKeys> performance;
    public ConfigParser<SafetyKeys> safety;
    public MultiConfigParser<RegionKeys> regions;
    public MultiConfigParser<WorldKeys> worlds;

    protected Configs(File pluginDirectory) {
        this.pluginDirectory = pluginDirectory;
        reload();
    }

    public abstract boolean checkWorldExists(String name);

    public abstract CompletableFuture<Boolean> reload();

    //todo: region setup
}
