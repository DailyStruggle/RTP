package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import org.simpleyaml.configuration.ConfigurationSection;

import java.util.List;
import java.util.Map;

public class RegionConfigLoader {

    public static RegionSettings load(ConfigParser<RegionKeys> regionParser) {
        String name = regionParser.name.replace(".yml", "");
//        System.out.println("[RTP-DEBUG] RegionLoader: --- Processing Region '" + name + "' ---");

        ConfigParser<LoggingKeys> logging = (ConfigParser<LoggingKeys>) RTP.configs.getParser(LoggingKeys.class);
        boolean detailedRegionInit = true;
        if (logging != null) {
            Object o = logging.getConfigValue(LoggingKeys.detailed_region_init, false);
            if (o instanceof Boolean) {
                detailedRegionInit = (Boolean) o;
            } else {
                detailedRegionInit = Boolean.parseBoolean(o.toString());
            }
        }

        // 1. Safe World parsing using RTP-2 fallback logic
        Object rawWorld = regionParser.getConfigValue(RegionKeys.world, null);
        RTPWorld<?> world = null;

        if (rawWorld instanceof RTPWorld<?>) {
            world = (RTPWorld<?>) rawWorld;
        } else {
            String worldName = String.valueOf(rawWorld);

            // Handle index-based world selection (e.g., "[0]")
            if (worldName.startsWith("[") && worldName.endsWith("]")) {
                try {
                    int num = Integer.parseInt(worldName.substring(1, worldName.length() - 1));
                    List<RTPWorld<?>> worlds = RTP.serverAccessor.getRTPWorlds();
                    if (num >= 0 && num < worlds.size()) {
                        world = worlds.get(num);
                    }
                } catch (NumberFormatException ignored) {}
            } else {
                world = RTP.serverAccessor.getRTPWorld(worldName);
            }

            // RTP-2 Master Fallback: If world is still null (invalid name or bad index),
            // force it to the server's primary world to prevent NPEs during Generation.
            if (world == null) {
                List<RTPWorld<?>> worlds = RTP.serverAccessor.getRTPWorlds();
                if (worlds != null && !worlds.isEmpty()) {
                    world = worlds.get(0);
//                    System.out.println("[RTP-DEBUG] RegionLoader: World '" + worldName + "' not found. Falling back to primary world '" + world.name() + "'");
                }
            }
        }

        // 2. Deserializing the Shape
        Object rawShape = regionParser.getConfigValue(RegionKeys.shape, null);
//        System.out.println("[RTP-DEBUG] RegionLoader: Raw Shape Object Type: " + (rawShape == null ? "null" : rawShape.getClass().getSimpleName()));

        Shape<?> shape = null;
        if (rawShape instanceof Shape<?>) {
            shape = (Shape<?>) rawShape;
//            System.out.println("[RTP-DEBUG] RegionLoader: Shape was already a valid Shape object.");
        } else if (rawShape instanceof ConfigurationSection) {
            ConfigurationSection section = (ConfigurationSection) rawShape;
            shape = deserializeShape(section.getMapValues(false));
            if (shape != null) regionParser.set(RegionKeys.shape, shape);
        } else if (rawShape instanceof Map) {
            shape = deserializeShape((Map<String, Object>) rawShape);
            if (shape != null) regionParser.set(RegionKeys.shape, shape);
        }

        if (shape == null) {
//            System.out.println("[RTP-DEBUG] RegionLoader: WARNING - SHAPE IS NULL! Region '" + name + "' cannot generate locations.");
        }

        // 3. Deserializing the Vertical Adjustor
        Object rawVert = regionParser.getConfigValue(RegionKeys.vert, null);
//        System.out.println("[RTP-DEBUG] RegionLoader: Raw Vert Object Type: " + (rawVert == null ? "null" : rawVert.getClass().getSimpleName()));

        VerticalAdjustor<?> vert = null;
        if (rawVert instanceof VerticalAdjustor<?>) {
            vert = (VerticalAdjustor<?>) rawVert;
//            System.out.println("[RTP-DEBUG] RegionLoader: Vert was already a valid VerticalAdjustor object.");
        } else if (rawVert instanceof ConfigurationSection) {
            ConfigurationSection section = (ConfigurationSection) rawVert;
            vert = deserializeVert(section.getMapValues(false));
            if (vert != null) regionParser.set(RegionKeys.vert, vert);
        } else if (rawVert instanceof Map) {
            vert = deserializeVert((Map<String, Object>) rawVert);
            if (vert != null) regionParser.set(RegionKeys.vert, vert);
        }

        if (vert == null) {
//            System.out.println("[RTP-DEBUG] RegionLoader: WARNING - VERT IS NULL! Region '" + name + "' lacks a vertical adjustor.");
        }

        boolean worldBorderOverride = getBoolean(regionParser.getConfigValue(RegionKeys.worldBorderOverride, false));
        boolean requirePermission = getBoolean(regionParser.getConfigValue(RegionKeys.requirePermission, false));
        long cacheCap = getNumber(regionParser.getConfigValue(RegionKeys.cacheCap, 10L)).longValue();
        int activeChunkCap = getNumber(regionParser.getConfigValue(RegionKeys.activeChunkCap, 3)).intValue();
        double price = getNumber(regionParser.getConfigValue(RegionKeys.price, 0.0)).doubleValue();
        long spatialResolution = getNumber(regionParser.getConfigValue(RegionKeys.spatialResolution, 1L)).longValue();

        if (shape != null && shape instanceof MemoryShape<?> memoryShape) memoryShape.spatialResolution = spatialResolution;
        String override = String.valueOf(regionParser.getConfigValue(RegionKeys.override, "default"));

        if (shape instanceof MemoryShape<?>) {
            if (detailedRegionInit) {
                // Here we CAN use RTP.log if we want, but since we are debugging early init, sysout is safer
//                System.out.println("[RTP-DEBUG] RegionLoader: [" + name + "] memory shape detected, reading location data from file...");
            }
            String worldName = (world != null) ? world.name() : "null";
            long worldSeed = (world != null) ? world.getSeed() : 0L;
            ((MemoryShape<?>) shape).load(name + "_" + worldSeed + ".bin", worldName);
        }

        return new RegionSettings(
                name,
                world,
                shape,
                vert,
                worldBorderOverride,
                requirePermission,
                cacheCap,
                activeChunkCap,
                price,
                spatialResolution,
                override,
                detailedRegionInit
        );
    }

    private static Shape<?> deserializeShape(Map<String, Object> map) {
        String shapeName = String.valueOf(map.getOrDefault("name", "CIRCLE")).toUpperCase();
//        System.out.println("[RTP-DEBUG] RegionLoader: Deserializing Shape... Extracted Name = '" + shapeName + "'");

        Factory<Shape<?>> factory = (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
        Shape<?> prototype = (Shape<?>) factory.get(shapeName);

        if (prototype != null) {
            Shape<?> clone = prototype.clone();
            clone.setData(map);
//            System.out.println("[RTP-DEBUG] RegionLoader: Successfully mapped to prototype '" + prototype.name + "' and applied config data.");
            return clone;
        } else {
//            System.out.println("[RTP-DEBUG] RegionLoader: FAILURE - No shape prototype found in factory for '" + shapeName + "'. Available: " + factory.list());
        }
        return null;
    }

    private static VerticalAdjustor<?> deserializeVert(Map<String, Object> map) {
        String vertName = String.valueOf(map.getOrDefault("name", "JUMP")).toUpperCase();
//        System.out.println("[RTP-DEBUG] RegionLoader: Deserializing Vert... Extracted Name = '" + vertName + "'");

        Factory<VerticalAdjustor<?>> factory = (Factory<VerticalAdjustor<?>>) RTP.factoryMap.get(RTP.factoryNames.vert);
        VerticalAdjustor<?> prototype = (VerticalAdjustor<?>) factory.get(vertName);

        if (prototype != null) {
            VerticalAdjustor<?> clone = (VerticalAdjustor<?>) prototype.clone();
            clone.setData(map);
//            System.out.println("[RTP-DEBUG] RegionLoader: Successfully mapped to prototype '" + prototype.name + "' and applied config data.");
            return clone;
        } else {
//            System.out.println("[RTP-DEBUG] RegionLoader: FAILURE - No vert prototype found in factory for '" + vertName + "'. Available: " + factory.list());
        }
        return null;
    }

    private static boolean getBoolean(Object o) {
        if (o instanceof Boolean) return (boolean) o;
        if (o == null) return false;
        return Boolean.parseBoolean(o.toString());
    }

    private static Number getNumber(Object o) {
        if (o instanceof Number) return (Number) o;
        if (o == null) return 0;
        try {
            return Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
