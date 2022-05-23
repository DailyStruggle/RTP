package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;

import java.io.File;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;


public abstract class Configs {
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

    public abstract ConfigParser<WorldKeys> getWorldParser(String worldName);

    public abstract CompletableFuture<Boolean> reload();

    //todo: region setup
}
