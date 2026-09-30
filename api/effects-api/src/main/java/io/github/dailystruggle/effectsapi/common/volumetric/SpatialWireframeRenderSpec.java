package io.github.dailystruggle.effectsapi.common.volumetric;

import java.util.Objects;

/**
 * Specification for an active spatial wireframe to be rendered by {@link WireframeParticleLoop}.
 *
 * @param id unique wireframe/trigger id
 * @param worldName world identifier
 * @param bounds spatial boundary box
 * @param particleType particle identifier (e.g. "minecraft:portal", "minecraft:flame", or native particle enum)
 * @param style geometric rendering style (OUTLINE, BEAM, SWIRL, PULSE, DUST_WALL)
 * @param step point density / step size along lines
 */
public record SpatialWireframeRenderSpec(
        String id,
        String worldName,
        SpatialBounds bounds,
        Object particleType,
        GeometryStyle style,
        double step
) {
    public SpatialWireframeRenderSpec {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(worldName, "worldName must not be null");
        Objects.requireNonNull(bounds, "bounds must not be null");
        Objects.requireNonNull(particleType, "particleType must not be null");
        if (style == null) style = GeometryStyle.OUTLINE;
        if (step <= 0.05) step = 0.5;
    }

    public SpatialWireframeRenderSpec(String id, String worldName, SpatialBounds bounds, Object particleType) {
        this(id, worldName, bounds, particleType, GeometryStyle.OUTLINE, 0.5);
    }
}
