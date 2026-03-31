package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;

public record RegionSettings(
    String name,
    RTPWorld<?> world,
    Shape<?> shape,
    VerticalAdjustor<?> vert,
    boolean worldBorderOverride,
    boolean requirePermission,
    long cacheCap,
    double price,
    long spatialResolution,
    String override,
    boolean detailedRegionInit
) {}
