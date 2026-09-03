package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigDefaultResolver;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;

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

            // No fallback: if the configured world cannot be resolved (e.g. an automatic world
            // generator such as Multiverse has not yet loaded it), leave the region's world as
            // null. The region will be constructed in a dormant state and activated via
            // Region.rebindWorld once WorldLoadEvent delivers the configured world.
        }

        // 2. Deserializing the Shape
        // ADR-073: record @<file> reference so the menu can show inherit state.
        recordReferenceIfAny(regionParser, RegionKeys.shape);
        Object rawShape = regionParser.getConfigValue(RegionKeys.shape, null);

        Shape<?> shape = null;
        if (rawShape instanceof Shape<?>) {
            shape = (Shape<?>) rawShape;
//            System.out.println("[RTP-DEBUG] RegionLoader: Shape was already a valid Shape object.");
        } else if (rawShape instanceof RtpYamlSection) {
            RtpYamlSection section = (RtpYamlSection) rawShape;
            shape = deserializeShape(section.getMapValues(false));
            if (shape != null) regionParser.set(RegionKeys.shape, shape);
        } else if (rawShape instanceof Map) {
            shape = deserializeShape((Map<String, Object>) rawShape);
            if (shape != null) regionParser.set(RegionKeys.shape, shape);
        }

        if (shape == null) {
            // ADR-073: shape could not be read (e.g. a stale config.yml whose `defaults`
            // block predates ADR-073 and lacks `shape`, so the region's `@config` token
            // resolves to null). Never leave a region without a shape - it would surface as
            // "Shape for region <name> was invalid. Falling back to SQUARE." in the Region
            // constructor and break location generation. Recover here with the documented
            // default shape so the value is always read as something valid.
            shape = deserializeShape(new java.util.HashMap<>());
            if (shape != null) {
                regionParser.set(RegionKeys.shape, shape);
                RTP.log(Level.WARNING, "[RTP] Region '" + name
                        + "' had no readable shape; falling back to the default " + shape.name + ".");
            }
        }

        // 3. Deserializing the Vertical Adjustor
        recordReferenceIfAny(regionParser, RegionKeys.vert);
        Object rawVert = regionParser.getConfigValue(RegionKeys.vert, null);

        VerticalAdjustor<?> vert = null;
        if (rawVert instanceof VerticalAdjustor<?>) {
            vert = (VerticalAdjustor<?>) rawVert;
//            System.out.println("[RTP-DEBUG] RegionLoader: Vert was already a valid VerticalAdjustor object.");
        } else if (rawVert instanceof RtpYamlSection) {
            RtpYamlSection section = (RtpYamlSection) rawVert;
            vert = deserializeVert(section.getMapValues(false));
            if (vert != null) regionParser.set(RegionKeys.vert, vert);
        } else if (rawVert instanceof Map) {
            vert = deserializeVert((Map<String, Object>) rawVert);
            if (vert != null) regionParser.set(RegionKeys.vert, vert);
        }

        if (vert == null) {
            // ADR-073: vert could not be read (same stale-config cause as the shape branch
            // above). A null vert previously slipped through to PregenState.build and threw a
            // repeated "invalid state, null vert" IllegalStateException. Recover here with the
            // documented default vertical adjustor so the value is always read as valid.
            java.util.Map<String, Object> vertDefault = new java.util.HashMap<>();
            vertDefault.put("name", "LINEAR");
            vert = deserializeVert(vertDefault);
            if (vert != null) {
                regionParser.set(RegionKeys.vert, vert);
                RTP.log(Level.WARNING, "[RTP] Region '" + name
                        + "' had no readable vert; falling back to the default " + vert.name + ".");
            }
        }

        boolean worldBorderOverride = getBoolean(resolveScalar(regionParser, RegionKeys.worldBorderOverride, false));
        boolean requirePermission = getBoolean(resolveScalar(regionParser, RegionKeys.requirePermission, false));
        long cacheCap = getNumber(resolveScalar(regionParser, RegionKeys.cacheCap, 10L)).longValue();
        long backlogCacheCap = getNumber(resolveScalar(regionParser, RegionKeys.backlogCacheCap, 0L)).longValue();
        long networkReserveSize = getNumber(resolveScalar(regionParser, RegionKeys.networkReserveSize, 0L)).longValue();
        int activeChunkCap = getNumber(resolveScalar(regionParser, RegionKeys.activeChunkCap, 3)).intValue();
        double price = getNumber(resolveScalar(regionParser, RegionKeys.price, 0.0)).doubleValue();
        long spatialResolution = getNumber(resolveScalar(regionParser, RegionKeys.spatialResolution, 1L)).longValue();

        if (shape != null && shape instanceof MemoryShape<?> memoryShape) memoryShape.setSpatialResolution(spatialResolution);
        String override = String.valueOf(regionParser.getConfigValue(RegionKeys.override, "default"));

        if (shape instanceof MemoryShape<?> && world != null) {
            // Filename keyed by RegionCacheKey: any change to seed/shape/vert invalidates the cache.
            ((MemoryShape<?>) shape).load(
                name + "_" + RegionCacheKey.cacheKey(world, shape, vert) + ".bin", world.name());
        }
        // If world is null here, the region is dormant; the MemoryShape (if any) is loaded
        // later via OnWorldLoadUnload -> RegionConfigLoader.load(...) at rebind time.

        return new RegionSettings(
                name,
                world,
                shape,
                vert,
                worldBorderOverride,
                requirePermission,
                cacheCap,
                backlogCacheCap,
                networkReserveSize,
                activeChunkCap,
                price,
                spatialResolution,
                override,
                detailedRegionInit
        );
    }

    /**
     * ADR-073: when {@code key} is configured with an {@code @<file>} reference token,
     * record the raw token in {@code parser.defaultReferences} so the menu can render the
     * inherit/override state. No-op for a literal value.
     */
    private static void recordReferenceIfAny(ConfigParser<RegionKeys> parser, RegionKeys key) {
        Object raw = parser.getData(key);
        if (ConfigDefaultResolver.isReference(raw)) {
            parser.defaultReferences.put(key, ((String) raw).trim());
        }
    }

    /**
     * ADR-073: resolve a scalar setting, honoring an {@code @<file>} reference token. When
     * the stored value is a reference, the token is recorded for the menu and the resolved
     * literal is written back into the parser so direct {@code getNumber}/{@code getData}
     * callers (e.g. {@code RTPCmd}, {@code ScanStartCmd}) observe a concrete value rather
     * than the unresolvable token.
     */
    private static Object resolveScalar(ConfigParser<RegionKeys> parser, RegionKeys key, Object fallback) {
        Object raw = parser.getData(key);
        if (ConfigDefaultResolver.isReference(raw)) {
            parser.defaultReferences.put(key, ((String) raw).trim());
            Object resolved = ConfigDefaultResolver.resolve(raw, key.name(), fallback);
            if (resolved != null) parser.set(key, resolved);
            return resolved;
        }
        return parser.getConfigValue(key, fallback);
    }

    private static Shape<?> deserializeShape(Map<String, Object> map) {
        String shapeName = String.valueOf(map.getOrDefault("name", "CIRCLE")).toUpperCase();
        Factory<Shape<?>> factory = (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
        Shape<?> prototype = (Shape<?>) factory.get(shapeName);

        if (prototype != null) {
            Shape<?> clone = prototype.clone();
            clone.setData(map);
            return clone;
        }
        return null;
    }

    private static VerticalAdjustor<?> deserializeVert(Map<String, Object> map) {
        String vertName = String.valueOf(map.getOrDefault("name", "JUMP")).toUpperCase();
        Factory<VerticalAdjustor<?>> factory = (Factory<VerticalAdjustor<?>>) RTP.factoryMap.get(RTP.factoryNames.vert);
        VerticalAdjustor<?> prototype = (VerticalAdjustor<?>) factory.get(vertName);

        if (prototype != null) {
            VerticalAdjustor<?> clone = (VerticalAdjustor<?>) prototype.clone();
            clone.setData(map);
            return clone;
        }
        return null;
    }

    /**
     * Detects if configured world was unavailable at load time (dormant region).
     * Returns the raw world name to rebind on WorldLoadEvent, or null if matched.
     */
    public static String detectFallbackConfiguredWorld(ConfigParser<RegionKeys> regionParser, RegionSettings settings) {
        if (settings == null) return null;
        Object raw = regionParser.getConfigValue(RegionKeys.world, null);
        if (raw == null) return null;
        String rawName = String.valueOf(raw);
        if (rawName.startsWith("[") && rawName.endsWith("]")) return null;
        // World unavailable at load time -> dormant region; rebind target is the configured name.
        if (settings.world() == null) return rawName;
        if (rawName.equalsIgnoreCase(settings.world().name())) return null;
        return rawName;
    }

    private static boolean getBoolean(Object o) {
        if (o instanceof Boolean) return (boolean) o;
        if (o == null) return false;
        // Tolerant int -> boolean coercion: any non-zero number (and the
        // "0"/"1" string forms) maps the way a YAML author expects.
        if (o instanceof Number) return ((Number) o).intValue() != 0;
        String s = o.toString().trim();
        if (s.equals("0")) return false;
        if (s.equals("1")) return true;
        return Boolean.parseBoolean(s);
    }

    private static Number getNumber(Object o) {
        if (o instanceof Number) return (Number) o;
        if (o == null) return 0;
        // Tolerant boolean -> int coercion (true -> 1, false -> 0).
        if (o instanceof Boolean) return ((Boolean) o) ? 1 : 0;
        try {
            return Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
