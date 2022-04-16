package leafcraft.rtp.api.configuration;

import leafcraft.rtp.api.configuration.enums.LangKeys;
import leafcraft.rtp.api.configuration.enums.ConfigKeys;
import leafcraft.rtp.api.configuration.enums.RegionKeys;
import leafcraft.rtp.api.configuration.enums.WorldKeys;

import java.io.File;


public abstract class Configs {
    protected final File pluginDirectory;
    public ConfigParser<LangKeys> lang;
    public ConfigParser<ConfigKeys> config;
    public MultiConfigParser<RegionKeys> regions;
    public MultiConfigParser<WorldKeys> worlds;

    protected Configs(File pluginDirectory) {
        this.pluginDirectory = pluginDirectory;
        reload();
    }

    public abstract boolean checkWorldExists(String name);

    public abstract void reload();
}
