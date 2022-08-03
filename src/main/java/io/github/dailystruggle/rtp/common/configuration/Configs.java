package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.*;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import org.jetbrains.annotations.Nullable;
import org.simpleyaml.configuration.MemorySection;

import java.io.File;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;


public class Configs {
    protected File worldLangMap;
    protected final File pluginDirectory;

    public Map<Class<?>,ConfigParser<?>> configParserMap = new ConcurrentHashMap<>();
    public Map<Class<?>,MultiConfigParser<?>> multiConfigParserMap = new ConcurrentHashMap<>();

    public Configs(File pluginDirectory) {
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
            multiConfigParser.addParser(new ConfigParser<>(WorldKeys.class, worldName,"1.0", multiConfigParser.myDirectory, worldLangMap));
        }

        return multiConfigParser.getParser(worldName);
    }

    public CompletableFuture<Boolean> reload() {
        CompletableFuture<Boolean> res = new CompletableFuture<>();

        //ensure async to protect server timings
        RTP.getInstance().miscAsyncTasks.add(new RTPRunnable(0) {
            @Override
            public void run() {
                try {
                    reloadAction();
                    res.complete(true);
                } catch (Exception e) { //on any code failure, complete false
                    res.complete(false);
                    throw e;
                }
            }
        });
        return res;
    }


    protected void reloadAction() {
        ConfigParser<LangKeys> lang = new ConfigParser<>(LangKeys.class,"lang.yml", "1.0", pluginDirectory, null);

        ConfigParser<ConfigKeys> config = new ConfigParser<>(ConfigKeys.class, "config.yml", "1.0", pluginDirectory);
        ConfigParser<EconomyKeys> economy = new ConfigParser<>(EconomyKeys.class, "economy.yml", "1.0", pluginDirectory);
        ConfigParser<IntegrationsKeys> integrations = new ConfigParser<>(IntegrationsKeys.class, "integrations.yml", "1.0", pluginDirectory);
        ConfigParser<PerformanceKeys> performance = new ConfigParser<>(PerformanceKeys.class, "performance.yml", "1.0", pluginDirectory);
        ConfigParser<SafetyKeys> safety = new ConfigParser<>(SafetyKeys.class, "safety", "1.0", pluginDirectory);

        MultiConfigParser<RegionKeys> regions = new MultiConfigParser<>(RegionKeys.class, "regions", "1.0", pluginDirectory);
        MultiConfigParser<WorldKeys> worlds = new MultiConfigParser<>(WorldKeys.class, "worlds", "1.0", pluginDirectory);

        for(var world : RTP.getInstance().serverAccessor.getRTPWorlds()) {
            worlds.getParser(world.name());
        }

        putParser(lang);
        putParser(config);
        putParser(economy);
        putParser(integrations);
        putParser(performance);
        putParser(safety);
        putParser(regions);
        putParser(worlds);

        for(ConfigParser<?> regionConfig : regions.configParserFactory.map.values()) {
            EnumMap<RegionKeys, Object> data = (EnumMap<RegionKeys, Object>) regionConfig.getData();

            String worldName = String.valueOf(data.get(RegionKeys.world));
            RTPWorld world;
            if(worldName.startsWith("[") && worldName.endsWith("]")) {
                int num = Integer.parseInt(worldName.substring(1,worldName.length()-1));
                world = RTP.getInstance().serverAccessor.getRTPWorlds().get(num);
            }
            else world = RTP.getInstance().serverAccessor.getRTPWorld(worldName);
            if(world == null) {
                new IllegalArgumentException("world not found - " + worldName).printStackTrace(); //don't need to throw
                continue;
            }
            data.put(RegionKeys.world,world);

            Object shapeObj = data.get(RegionKeys.shape);
            Shape<?> shape;
            if(shapeObj instanceof MemorySection shapeSection) {
                final Map<String, Object> shapeMap = shapeSection.getMapValues(true);
                String shapeName = String.valueOf(shapeMap.get("name"));
                Factory<Shape<?>> factory = (Factory<Shape<?>>) RTP.getInstance().factoryMap.get(RTP.factoryNames.shape);
                shape = (Shape<?>) factory.getOrDefault(shapeName);
                EnumMap<?, Object> shapeData = shape.getData();
                for(var e : shapeData.entrySet()) {
                    String name = e.getKey().name();
                    if(shapeMap.containsKey(name)) {
                        e.setValue(shapeMap.get(name));
                    }
                    else {
                        String altName = shape.language_mapping.get(name);
                        if(altName!=null && shapeMap.containsKey(altName)) {
                            e.setValue(shapeMap.get(altName));
                        }
                    }
                }
                shape.setData(shapeData);
                data.put(RegionKeys.shape,shape);
            }
            else throw new IllegalArgumentException("shape was not a section\n" + shapeObj);


            Object vertObj = data.get(RegionKeys.vert);
            if(vertObj instanceof MemorySection vertSection) {
                final Map<String, Object> vertMap = vertSection.getMapValues(true);
                String shapeName = String.valueOf(vertMap.get("name"));
                Factory<VerticalAdjustor<?>> factory = (Factory<VerticalAdjustor<?>>) RTP.getInstance().factoryMap.get(RTP.factoryNames.vert);
                VerticalAdjustor<?> vert = (VerticalAdjustor<?>) factory.getOrDefault(shapeName);
                EnumMap<?, Object> vertData = vert.getData();
                for(var e : vertData.entrySet()) {
                    String name = e.getKey().name();
                    if(vertMap.containsKey(name)) {
                        e.setValue(vertMap.get(name));
                    }
                    else {
                        String altName = vert.language_mapping.get(name);
                        if(altName!=null && vertMap.containsKey(altName)) {
                            e.setValue(vertMap.get(altName));
                        }
                    }
                }
                vert.setData(vertData);
                data.put(RegionKeys.vert, vert);
            }
            else throw new IllegalArgumentException();

            Region region = new Region(regionConfig.name.replace(".yml",""), data);
            RTP.getInstance().selectionAPI.permRegionLookup.put(region.name,region);
        }
    }

    //todo: region setup
}
