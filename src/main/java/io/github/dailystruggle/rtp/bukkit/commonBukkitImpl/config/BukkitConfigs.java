package io.github.dailystruggle.rtp.bukkit.commonBukkitImpl.config;

import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.bukkit.commonBukkitImpl.substitutions.BukkitRTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.*;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.substitutions.RTPWorld;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class BukkitConfigs extends Configs {
    public BukkitConfigs(File pluginDirectory) {
        super(pluginDirectory);
    }

    @Override
    public CompletableFuture<Boolean> reload() {
        CompletableFuture<Boolean> res = new CompletableFuture<>();

        //ensure async to protect server timings
        Bukkit.getScheduler().runTaskAsynchronously(RTPBukkitPlugin.getInstance(), () -> {
            try {
                reloadAction();
                res.complete(true);
            } catch (Exception e) { //on any code failure, complete false
                res.complete(false);
                throw e;
            }
        });
        return res;
    }

    protected void reloadAction() {
        BukkitConfigParser<LangKeys> lang = new BukkitConfigParser<>(LangKeys.class,"lang.yml", "1.0", pluginDirectory, null);

        BukkitConfigParser<ConfigKeys> config = new BukkitConfigParser<>(ConfigKeys.class, "config.yml", "1.0", pluginDirectory);
        BukkitConfigParser<EconomyKeys> economy = new BukkitConfigParser<>(EconomyKeys.class, "economy.yml", "1.0", pluginDirectory);
        BukkitConfigParser<IntegrationsKeys> integrations = new BukkitConfigParser<>(IntegrationsKeys.class, "integrations.yml", "1.0", pluginDirectory);
        BukkitConfigParser<PerformanceKeys> performance = new BukkitConfigParser<>(PerformanceKeys.class, "performance.yml", "1.0", pluginDirectory);
        BukkitConfigParser<SafetyKeys> safety = new BukkitConfigParser<>(SafetyKeys.class, "safety", "1.0", pluginDirectory);

        worldLangMap = new File(pluginDirectory + File.separator + "lang" + File.separator + "worlds.lang.yml");

        BukkitMultiConfigParser<RegionKeys> regions = new BukkitMultiConfigParser<>(RegionKeys.class, "regions", "1.0", pluginDirectory);
        BukkitMultiConfigParser<WorldKeys> worlds = new BukkitMultiConfigParser<>(WorldKeys.class, "worlds", "1.0", pluginDirectory);

        for(World world : Bukkit.getWorlds()) {
            worlds.getParser(world.getName());
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
                world = new BukkitRTPWorld(Bukkit.getWorlds().get(num));
            }
            else world = RTP.getInstance().serverAccessor.getRTPWorld(worldName);
            if(world == null) {
                new IllegalArgumentException("world not found - " + worldName).printStackTrace(); //don't need to throw
                continue;
            }
            data.put(RegionKeys.world,world);

            Object shapeObj = data.get(RegionKeys.shape);
            if(shapeObj instanceof Map shapeMap) {
                String shapeName = String.valueOf(shapeMap.get("name"));
                Factory<Shape<?>> factory = (Factory<Shape<?>>) RTP.getInstance().factoryMap.get(RTP.factoryNames.shape);
                Shape<?> shape = (Shape<?>) factory.getOrDefault(shapeName);
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
            else throw new IllegalArgumentException();
            RTP.log(Level.WARNING, shapeObj.toString());
            RTP.log(Level.CONFIG, data.get(RegionKeys.shape).toString());


            Object vertObj = data.get(RegionKeys.vert);
            if(vertObj instanceof Map vertMap) {
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
            RTP.log(Level.WARNING, vertObj.toString());
            RTP.log(Level.CONFIG, data.get(RegionKeys.vert).toString());

            Region region = new Region(regionConfig.name.replace(".yml",""), data);
            RTP.getInstance().selectionAPI.permRegionLookup.put(region.name,region);
        }
    }
}
