package io.github.dailystruggle.rtp.common.commands.menu.multiconfig;

import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Applies dimension-specific defaults (Nether/End) to region parameter maps.
 * Handles LINEAR vert, height bounds, and disabled skylight requirements.
 *
 * @see io.github.dailystruggle.rtp.common.commands.config.SubConfigCmd
 */
public final class NetherEndConfigAmender {

    private NetherEndConfigAmender() {
        // utility class
    }

    /**
     * Apply the nether/end amendment to {@code parameterValues} in place.
     *
     * @param parameterValues mutable map of parameter name -> singleton
     *                        value list (the same shape used by
     *                        {@code SubConfigCmd.onCommand}). Never null.
     * @param regionParser    the region's {@link ConfigParser}; used only to
     *                        read the existing {@code vert} section as a
     *                        fallback. Never null.
     * @param rtpWorld        the target world; used for dimension-name
     *                        suffix detection and the final
     *                        {@code min/maxHeight} clamp. Never null.
     */
    public static void amend(
            Map<String, List<String>> parameterValues,
            ConfigParser<RegionKeys> regionParser,
            RTPWorld rtpWorld) {
        String env = rtpWorld.environment();
        boolean isNether = rtpWorld.name().endsWith("_nether") || "NETHER".equalsIgnoreCase(env);
        boolean isEnd = rtpWorld.name().endsWith("_the_end") || "THE_END".equalsIgnoreCase(env);
        String name = "JUMP";
        if (isNether || isEnd) {
            name = "LINEAR";
        }
        int maxY = 255;
        int minY = 0;

        Object o = regionParser.getConfigValue(RegionKeys.vert, null);
        if (o instanceof RtpYamlSection) {
            RtpYamlSection section = (RtpYamlSection) o;
            name =
                    parameterValues.containsKey("vert")
                            ? parameterValues.get("vert").get(0)
                            : section.getString("name");
            if (name == null) return;

            String maxYStr =
                    parameterValues.containsKey("maxy")
                            ? parameterValues.get("maxy").get(0)
                            : section.getString("maxY").replace(",", ".");

            String minYStr =
                    parameterValues.containsKey("miny")
                            ? parameterValues.get("miny").get(0)
                            : section.getString("minY").replace(",", ".");

            try {
                maxY = ((Number) Double.parseDouble(maxYStr)).intValue();
                minY = ((Number) Double.parseDouble(minYStr)).intValue();
            } catch (IllegalArgumentException ignored) {

            }
        } else if (o instanceof VerticalAdjustor<?>) {
            VerticalAdjustor<?> vert = (VerticalAdjustor<?>) o;
            name = vert.name;
            maxY = vert.maxY();
            minY = vert.minY();
        }

        parameterValues.putIfAbsent("vert", Collections.singletonList(name));
        if (isNether) {
            maxY = Math.min(maxY, 128);
            parameterValues.putIfAbsent(
                    "requireskylight", Collections.singletonList(String.valueOf(false)));
        } else if (isEnd) {
            parameterValues.putIfAbsent(
                    "requireskylight", Collections.singletonList(String.valueOf(false)));
        }
        maxY = Math.min(maxY, rtpWorld.getMaxHeight());

        if (maxY < minY) {
            minY = rtpWorld.getMinHeight();
        } else {
            minY = Math.max(minY, rtpWorld.getMinHeight());
        }

        parameterValues.putIfAbsent("miny", Collections.singletonList(String.valueOf(minY)));
        parameterValues.putIfAbsent("maxy", Collections.singletonList(String.valueOf(maxY)));
    }

    /**
     * Resolves dimension-appropriate vertical adjustment settings for a given world,
     * or null if the world is not a recognized non-overworld dimension (nether or end).
     *
     * @param worldName target world name
     * @return map of vert settings (name, minY, maxY, direction, requireSkyLight), or null
     */
    public static Map<String, Object> createDimensionVert(String worldName) {
        if (worldName == null || worldName.isEmpty()) return null;
        RTPWorld<?> rtpWorld = (io.github.dailystruggle.rtp.common.RTP.serverAccessor != null)
                ? io.github.dailystruggle.rtp.common.RTP.serverAccessor.getRTPWorld(worldName)
                : null;
        return createDimensionVert(worldName, rtpWorld);
    }

    /**
     * Resolves dimension-appropriate vertical adjustment settings for a given world name
     * and optional RTPWorld wrapper.
     *
     * @param worldName target world name
     * @param rtpWorld  target world wrapper (nullable)
     * @return map of vert settings, or null if not nether/end
     */
    public static Map<String, Object> createDimensionVert(String worldName, RTPWorld<?> rtpWorld) {
        if (worldName == null || worldName.isEmpty()) return null;
        String env = (rtpWorld != null) ? rtpWorld.environment() : null;
        boolean nether = worldName.endsWith("_nether") || "NETHER".equalsIgnoreCase(env);
        boolean end = worldName.endsWith("_the_end") || "THE_END".equalsIgnoreCase(env);
        if (!nether && !end) return null;

        int maxHeight = (rtpWorld != null) ? rtpWorld.getMaxHeight() : 255;
        int minHeight = (rtpWorld != null) ? rtpWorld.getMinHeight() : 0;
        int maxY = nether ? Math.min(128, maxHeight) : maxHeight;
        int minY = Math.min(minHeight, maxY);

        Map<String, Object> vert = new java.util.LinkedHashMap<>();
        vert.put("name", "LINEAR");
        vert.put("minY", minY);
        vert.put("maxY", maxY);
        vert.put("direction", 2);
        vert.put("requireSkyLight", false);
        return vert;
    }
}
