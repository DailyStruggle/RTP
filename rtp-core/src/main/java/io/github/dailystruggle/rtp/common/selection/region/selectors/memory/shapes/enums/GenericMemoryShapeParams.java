package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums;

/**
 * Common parameters for memory-based shapes
 */
public enum GenericMemoryShapeParams {
    /**
     * The selection mode (e.g. ACCUMULATE, REROLL)
     */
    mode,
    /**
     * The outer radius of the shape
     */
    radius,
    /**
     * The inner radius of the shape
     */
    centerRadius,
    /**
     * The X coordinate of the center
     */
    centerX,
    /**
     * The Z coordinate of the center
     */
    centerZ,
    /**
     * The weight for distribution (if applicable)
     */
    weight,
    /**
     * Whether each selection must be unique
     */
    uniquePlacements,
    /**
     * Whether to expand the region to maintain area
     */
    expand
}


