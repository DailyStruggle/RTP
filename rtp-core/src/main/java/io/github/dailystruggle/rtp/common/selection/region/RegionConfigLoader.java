package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;

import java.util.logging.Level;

public class RegionConfigLoader {

    public static RegionSettings load(ConfigParser<RegionKeys> regionParser) {
        String name = regionParser.name.replace(".yml", "");

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

        RTPWorld<?> world = (RTPWorld<?>) regionParser.getConfigValue(RegionKeys.world, null);
        Shape<?> shape = (Shape<?>) regionParser.getConfigValue(RegionKeys.shape, null);
        VerticalAdjustor<?> vert = (VerticalAdjustor<?>) regionParser.getConfigValue(RegionKeys.vert, null);
        boolean worldBorderOverride = (boolean) regionParser.getConfigValue(RegionKeys.worldBorderOverride, false);
        boolean requirePermission = (boolean) regionParser.getConfigValue(RegionKeys.requirePermission, false);
        long cacheCap = ((Number) regionParser.getConfigValue(RegionKeys.cacheCap, 10L)).longValue();
        int activeChunkCap = ((Number) regionParser.getConfigValue(RegionKeys.activeChunkCap, 3)).intValue();
        double price = ((Number) regionParser.getConfigValue(RegionKeys.price, 0.0)).doubleValue();
        long spatialResolution = ((Number) regionParser.getConfigValue(RegionKeys.spatialResolution, 1L)).longValue();
        String override = String.valueOf(regionParser.getConfigValue(RegionKeys.override, "default"));

        if (shape instanceof MemoryShape<?>) {
            if (detailedRegionInit) {
                RTP.log(Level.INFO, "&00FFFF[RTP] [" + name + "] memory shape detected, reading location data from file...");
            }
            String worldName = (world != null) ? world.name() : "null";
            ((MemoryShape<?>) shape).load(name + ".bin", worldName);

            long iter = ((MemoryShape<?>) shape).fillIter.get();
            if (iter > 0 && iter < Double.valueOf(((MemoryShape<?>) shape).getRange()).longValue()) {
                // This part is a bit tricky since it modifies a global map.
                // However, the original code did it in the constructor.
            }
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
}
