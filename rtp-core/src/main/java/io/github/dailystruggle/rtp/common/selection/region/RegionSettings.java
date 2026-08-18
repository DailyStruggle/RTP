package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;

/**
 * Immutable configuration settings defining a teleportation region's behavior.
 * Holds world, shape, vertical adjustor, cache limits, pricing, and override parameters.
 */
public record RegionSettings(
    String name,
    RTPWorld<?> world,
    Shape<?> shape,
    VerticalAdjustor<?> vert,
    boolean worldBorderOverride,
    boolean requirePermission,
    long cacheCap,
    long backlogCacheCap,
    long networkReserveSize,
    int activeChunkCap,
    double price,
    long spatialResolution,
    String override,
    boolean detailedRegionInit
) {}
