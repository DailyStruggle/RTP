package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;

/**
 * A record that holds the configuration settings for a teleportation region.
 * This object is immutable and contains all the parameters that define a region's
 * behavior.
 *
 * @param name                The name of the region.
 * @param world               The world this region belongs to.
 * @param shape               The shape of the region.
 * @param vert                The vertical adjustor to use for finding safe locations.
 * @param worldBorderOverride Whether to override the region's shape with the world border.
 * @param requirePermission   Whether a player needs a specific permission to use this region.
 * @param cacheCap            The maximum number of pre-generated locations to keep in the cache.
 * @param backlogCacheCap     The maximum number of unverified locations held in the L3 backlog cache (ADR-028). 0 disables L3.
 * @param activeChunkCap      The maximum number of chunks to keep actively loaded for new locations.
 * @param price               The cost to use this region.
 * @param spatialResolution   The resolution for spatial hashing in memory-based shapes.
 * @param override            The name of another region to use as an override if conditions are not met.
 * @param detailedRegionInit  Whether to perform a detailed (and slower) initialization for the region.
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
    int activeChunkCap,
    double price,
    long spatialResolution,
    String override,
    boolean detailedRegionInit
) {}
