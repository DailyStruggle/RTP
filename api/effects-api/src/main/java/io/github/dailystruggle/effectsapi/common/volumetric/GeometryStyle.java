package io.github.dailystruggle.effectsapi.common.volumetric;

/**
 * Geometric styles for volumetric particle boundary rendering.
 */
public enum GeometryStyle {
    /**
     * Renders particles along the 12 edges of the cuboid bounding box.
     */
    OUTLINE,

    /**
     * Renders vertical corner pillars/beams and a central beacon line.
     */
    BEAM,

    /**
     * Renders an ascending helical spiral winding around the volume bounds.
     */
    SWIRL,

    /**
     * Renders pulsing concentric boundary rings or shells expanding outward from the center.
     */
    PULSE,

    /**
     * Renders planar particle grids / walls across the perimeter vertical faces.
     */
    DUST_WALL
}
