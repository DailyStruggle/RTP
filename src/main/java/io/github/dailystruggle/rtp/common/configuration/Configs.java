package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.*;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.simpleyaml.configuration.MemorySection;
import org.simpleyaml.configuration.file.YamlFile;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class Configs {
    protected File worldLangMap;
    public final File pluginDirectory;

    public Map<Class<?>,ConfigParser<?>> configParserMap = new ConcurrentHashMap<>();
    public Map<Class<?>,MultiConfigParser<?>> multiConfigParserMap = new ConcurrentHashMap<>();

    private static final List<Runnable> onReload = new ArrayList<>();

    public static void onReload(Runnable runnable) {
        onReload.add(runnable);
    }

    public final YamlFileDatabase fileDatabase;

    public Configs(File pluginDirectory) {
        this.pluginDirectory = pluginDirectory;
        RTP.getInstance().startupTasks.add(new RTPRunnable(this::reloadAction,5));
        this.fileDatabase = new YamlFileDatabase(pluginDirectory);
        Map<String, YamlFile> connect = this.fileDatabase.connect();
        this.fileDatabase.disconnect(connect);
    }

    public void putParser(Object instance) {
        if(instance == null)
            throw new NullPointerException("instance is null");

        ConfigParser<LoggingKeys> logging;
        String name;
        if(instance instanceof ConfigParser<?>) {
            name = ((ConfigParser<?>) instance).name;
            if(((ConfigParser<?>) instance).myClass.equals(LoggingKeys.class))
                logging = ((ConfigParser<LoggingKeys>) instance);
            else logging = (ConfigParser<LoggingKeys>) RTP.configs.getParser(LoggingKeys.class);
            configParserMap.put(((ConfigParser<?>) instance).myClass, (ConfigParser<?>) instance);
        }
        else if(instance instanceof MultiConfigParser<?>) {
            logging = (ConfigParser<LoggingKeys>) RTP.configs.getParser(LoggingKeys.class);
            name = ((MultiConfigParser<?>) instance).name;
            multiConfigParserMap.put(((MultiConfigParser<?>) instance).myClass, (MultiConfigParser<?>) instance);
        }
        else
            throw new IllegalArgumentException("invalid type:" + instance.getClass().getSimpleName() + ", expected a config parser");

        boolean detailed_reload = true;
        if(logging!=null) {
            Object o = logging.getConfigValue(LoggingKeys.detailed_reload, false);
            if (o instanceof Boolean) {
                detailed_reload = (Boolean) o;
            } else {
                detailed_reload = Boolean.parseBoolean(o.toString());
            }
        }

        if(detailed_reload) {
            RTP.log(Level.INFO, "[RTP] loaded " + name);
        }
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
            multiConfigParser.addParser(new ConfigParser<>(WorldKeys.class, worldName,"1.0", multiConfigParser.myDirectory, worldLangMap, multiConfigParser.fileDatabase));
        }

        return multiConfigParser.getParser(worldName);
    }

    public boolean reload() {
        this.fileDatabase.processQueries(Long.MAX_VALUE);
        configParserMap.clear();
        multiConfigParserMap.clear();
        reloadAction();
        return true;
    }


    protected void reloadAction() {
        ConfigParser<LoggingKeys> logging = new ConfigParser<>(LoggingKeys.class,"logging.yml", "1.0", pluginDirectory, fileDatabase);
        putParser(logging);

        ConfigParser<MessagesKeys> lang = new ConfigParser<>(MessagesKeys.class,"messages.yml", "1.0", pluginDirectory, fileDatabase);
        putParser(lang);

        ConfigParser<ConfigKeys> config = new ConfigParser<>(ConfigKeys.class, "config.yml", "1.0", pluginDirectory, fileDatabase);
        putParser(config);

        ConfigParser<EconomyKeys> economy = new ConfigParser<>(EconomyKeys.class, "economy.yml", "1.0", pluginDirectory, fileDatabase);
        putParser(economy);

        ConfigParser<PerformanceKeys> performance = new ConfigParser<>(PerformanceKeys.class, "performance.yml", "1.0", pluginDirectory, fileDatabase);
        putParser(performance);

        ConfigParser<SafetyKeys> safety = new ConfigParser<>(SafetyKeys.class, "safety", "1.0", pluginDirectory, fileDatabase);
        putParser(safety);

        MultiConfigParser<RegionKeys> regions = new MultiConfigParser<>(RegionKeys.class, "regions", "1.0", pluginDirectory);
        putParser(regions);

        MultiConfigParser<WorldKeys> worlds = new MultiConfigParser<>(WorldKeys.class, "worlds", "1.0", pluginDirectory);
        putParser(worlds);

        for(RTPWorld world : RTP.serverAccessor.getRTPWorlds()) {
            worlds.addParser(world.name());
        }

        boolean detailed_region_init = true;
        if(logging!=null) {
            Object o = logging.getConfigValue(LoggingKeys.detailed_region_init, false);
            if (o instanceof Boolean) {
                detailed_region_init = (Boolean) o;
            } else {
                detailed_region_init = Boolean.parseBoolean(o.toString());
            }
        }

        for(ConfigParser<RegionKeys> regionConfig : regions.configParserFactory.map.values()) {
            EnumMap<RegionKeys, Object> data = regionConfig.getData();
            String name = regionConfig.name.replace(".yml","");
            if(detailed_region_init) {
                data.forEach((regionKeys, o1) -> {
                    StringBuilder builder = new StringBuilder("[RTP] [" + name + "] " + regionKeys.name() + ": ");
                    if(o1 instanceof MemorySection) {
                        RTP.log(Level.INFO, builder.toString());
                        MemorySection section = (MemorySection) o1;
                        appendMemorySectionRecursive(section, name, 1);
                    }
                    else {
                        builder.append(o1.toString());
                        RTP.log(Level.INFO, builder.toString());
                    }
                });
            }
            Region region = new Region(regionConfig.name.replace(".yml",""), data);
            RTP.getInstance().selectionAPI.permRegionLookup.put(region.name,region);
            if(detailed_region_init) {
                RTP.log(Level.INFO, "[RTP] [" + name + "] successfully created teleport region - " + region.name);
            }
        }
        if(onReload.size()>0) onReload.forEach(Runnable::run);
    }

    private static void appendMemorySectionRecursive(MemorySection memorySection, String name, int indent) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, Object> entry : memorySection.getMapValues(false).entrySet()) {
            String s = entry.getKey();
            Object o = entry.getValue();
            builder.append("[RTP] [").append(name).append("] ").append(StringUtils.repeat("    ", indent)).append(s).append(": ");
            if (o instanceof MemorySection) {
                RTP.log(Level.INFO, builder.toString());
                appendMemorySectionRecursive(memorySection, name, indent + 1);
            } else {
                builder.append(o.toString());
                RTP.log(Level.INFO, builder.toString());
            }
            builder = new StringBuilder();
        }
    }

    //todo: region setup
}
