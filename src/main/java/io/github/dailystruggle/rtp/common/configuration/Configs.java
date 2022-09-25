package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.*;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Configs {
    protected File worldLangMap;
    public final File pluginDirectory;

    public Map<Class<?>,ConfigParser<?>> configParserMap = new ConcurrentHashMap<>();
    public Map<Class<?>,MultiConfigParser<?>> multiConfigParserMap = new ConcurrentHashMap<>();

    private static final List<Runnable> onReload = new ArrayList<>();

    public static void onReload(Runnable runnable) {
        onReload.add(runnable);
    }

    public Configs(File pluginDirectory) {
        this.pluginDirectory = pluginDirectory;
        RTP.getInstance().startupTasks.add(new RTPRunnable(this::reloadAction,5));
    }

    public void putParser(Object instance) {
        if(instance == null)
            throw new NullPointerException("instance is null");

        if(instance instanceof ConfigParser<?>)
            configParserMap.put(((ConfigParser<?>) instance).myClass, (ConfigParser<?>) instance);
        else if(instance instanceof MultiConfigParser<?>)
            multiConfigParserMap.put(((MultiConfigParser<?>) instance).myClass, (MultiConfigParser<?>) instance);
        else
            throw new IllegalArgumentException("invalid type:" + instance.getClass().getSimpleName() + ", expected a config parser");
    }

    public <T extends Enum<T>> FactoryValue<T> getParser(Class<T> parserEnumClass) {
        if(configParserMap.containsKey(parserEnumClass))
            return (FactoryValue<T>) configParserMap.get(parserEnumClass);
        if(multiConfigParserMap.containsKey(parserEnumClass))
            return (FactoryValue<T>) multiConfigParserMap.get(parserEnumClass);
        return null;
    }

    @Nullable
    public ConfigParser<WorldKeys> getWorldParser(String worldName) {
        if(RTP.serverAccessor.getRTPWorld(worldName) == null) {
            return null;
        }

        MultiConfigParser<WorldKeys> multiConfigParser = (MultiConfigParser<WorldKeys>) multiConfigParserMap.get(WorldKeys.class);

        Objects.requireNonNull(multiConfigParser);

        if(!multiConfigParser.configParserFactory.contains(worldName)) {
            multiConfigParser.addParser(new ConfigParser<>(WorldKeys.class, worldName,"1.0", multiConfigParser.myDirectory, worldLangMap));
        }

        return multiConfigParser.getParser(worldName);
    }

    public boolean reload() {
        configParserMap.clear();
        multiConfigParserMap.clear();
        reloadAction();
        ConfigParser<LangKeys> lang = (ConfigParser<LangKeys>) RTP.getInstance().configs.getParser(LangKeys.class);
        if(lang == null) return false;
        String msg = String.valueOf(lang.getConfigValue(LangKeys.reloaded,""));
        if(msg!=null) msg = StringUtils.replace(msg,"[filename]", "configs");
        RTP.serverAccessor.sendMessage(CommandsAPI.serverId,msg);
        return true;
    }


    protected void reloadAction() {
        ConfigParser<LangKeys> lang = new ConfigParser<>(LangKeys.class,"lang.yml", "1.0", pluginDirectory, null);

        ConfigParser<ConfigKeys> config = new ConfigParser<>(ConfigKeys.class, "config.yml", "1.0", pluginDirectory);
        ConfigParser<EconomyKeys> economy = new ConfigParser<>(EconomyKeys.class, "economy.yml", "1.0", pluginDirectory);
        ConfigParser<PerformanceKeys> performance = new ConfigParser<>(PerformanceKeys.class, "performance.yml", "1.0", pluginDirectory);
        ConfigParser<SafetyKeys> safety = new ConfigParser<>(SafetyKeys.class, "safety", "1.0", pluginDirectory);

        MultiConfigParser<RegionKeys> regions = new MultiConfigParser<>(RegionKeys.class, "regions", "1.0", pluginDirectory);
        MultiConfigParser<WorldKeys> worlds = new MultiConfigParser<>(WorldKeys.class, "worlds", "1.0", pluginDirectory);

        for(RTPWorld world : RTP.serverAccessor.getRTPWorlds()) {
            worlds.getParser(world.name());
        }

        putParser(lang);
        putParser(config);
        putParser(economy);
        putParser(performance);
        putParser(safety);
        putParser(regions);
        putParser(worlds);

        for(ConfigParser<RegionKeys> regionConfig : regions.configParserFactory.map.values()) {
            EnumMap<RegionKeys, Object> data = regionConfig.getData();
            Region region = new Region(regionConfig.name.replace(".yml",""), data);
            RTP.getInstance().selectionAPI.permRegionLookup.put(region.name.toUpperCase(),region);
        }
        if(onReload.size()>0) onReload.forEach(Runnable::run);
    }

    //todo: region setup
}
