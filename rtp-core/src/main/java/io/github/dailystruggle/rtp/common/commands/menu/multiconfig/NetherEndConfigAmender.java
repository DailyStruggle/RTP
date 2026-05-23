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
 * Helper that applies the nether/end seed amendment to a region's parameter
 * map prior to a region-config update.
 *
 * <p>Lifted verbatim from {@code SubConfigCmd.onCommand} (the
 * {@code vertFixBlock} at lines 82-140 of the pre-refactor file) so that the
 * same amendment can be applied from both the {@code /rtp config region ...}
 * command path and the upcoming MultiConfig submenu's ADD path. Behavior is
 * intentionally identical to the original block; any change here must be
 * mirrored in both call sites (or, preferably, made here only).
 *
 * <p>Rules (matching the previous inline block):
 * <ul>
 *   <li>If the target world ends with {@code _nether} or {@code _the_end},
 *       seed the vert name to {@code LINEAR} and direction to {@code 2}.</li>
 *   <li>Resolve {@code vert.name}, {@code vert.maxY}, {@code vert.minY}
 *       from {@code parameterValues} first, then from the region parser's
 *       existing {@code vert} section / {@link VerticalAdjustor}.</li>
 *   <li>Clamp {@code maxY <= 128} for {@code _nether}; force
 *       {@code requireskylight=false} for both nether and end.</li>
 *   <li>Clamp {@code maxY} to {@code rtpWorld.getMaxHeight()} and
 *       {@code minY} to {@code rtpWorld.getMinHeight()}.</li>
 *   <li>{@code parameterValues.putIfAbsent} is used for every write so an
 *       explicit user-supplied value wins over the seed.</li>
 * </ul>
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
        String name = "JUMP";
        if (rtpWorld.name().endsWith("_nether") || rtpWorld.name().endsWith("_the_end")) {
            name = "LINEAR";
            parameterValues.putIfAbsent("direction", Collections.singletonList(String.valueOf(2)));
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
        if (rtpWorld.name().endsWith("_nether")) {
            maxY = Math.min(maxY, 128);
            parameterValues.putIfAbsent(
                    "requireskylight", Collections.singletonList(String.valueOf(false)));
        } else if (rtpWorld.name().endsWith("_the_end")) {
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
}
