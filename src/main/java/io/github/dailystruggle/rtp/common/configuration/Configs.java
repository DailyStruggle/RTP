package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.bukkit.commonBukkitImpl.config.BukkitConfigParser;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;


public abstract class Configs {
    protected File worldLangMap;
    protected final File pluginDirectory;

//    public ConfigParser<LangKeys> lang;
//    public ConfigParser<ConfigKeys> config;
//    public ConfigParser<EconomyKeys> economy;
//    public ConfigParser<IntegrationsKeys> integrations;
//    public ConfigParser<PerformanceKeys> performance;
//    public ConfigParser<SafetyKeys> safety;
//    public MultiConfigParser<RegionKeys> regions;
//    public MultiConfigParser<WorldKeys> worlds;

    public Map<Class<?>,ConfigParser<?>> configParserMap = new ConcurrentHashMap<>();
    public Map<Class<?>,MultiConfigParser<?>> multiConfigParserMap = new ConcurrentHashMap<>();

    protected Configs(File pluginDirectory) {
        this.pluginDirectory = pluginDirectory;
        reload();
    }

    public void putParser(Object instance) {
        if(instance == null)
            throw new NullPointerException("instance is null");

        if(instance instanceof ConfigParser<?> configParser)
            configParserMap.put(configParser.myClass, configParser);
        else if(instance instanceof MultiConfigParser<?> multiConfigParser)
            multiConfigParserMap.put(multiConfigParser.myClass, multiConfigParser);
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
        MultiConfigParser<WorldKeys> multiConfigParser = (MultiConfigParser<WorldKeys>) multiConfigParserMap.get(WorldKeys.class);
        Objects.requireNonNull(multiConfigParser);

        if(RTP.getInstance().serverAccessor.getRTPWorld(worldName) == null) {
            return null;
        }

        if(!multiConfigParser.configParserFactory.contains(worldName)) {
            multiConfigParser.addParser(new BukkitConfigParser<>(WorldKeys.class, worldName,"1.0", multiConfigParser.myDirectory, worldLangMap));
        }

        return multiConfigParser.getParser(worldName);
    }

    public abstract CompletableFuture<Boolean> reload();

    //todo: region setup
}
