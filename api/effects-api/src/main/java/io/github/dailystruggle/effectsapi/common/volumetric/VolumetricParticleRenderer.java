package io.github.dailystruggle.effectsapi.common.volumetric;


import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Volumetric particle wireframe renderer for spatial zones, lobbies, and volumes.
 *
 * <p>Supports geometry point generators ({@code OUTLINE}, {@code BEAM}, {@code SWIRL},
 * {@code PULSE}, {@code DUST_WALL}), distance culling (default: 48 blocks), and rate
 * limiting (default: 200 particles/tick).</p>
 */
public class VolumetricParticleRenderer {

    public static final double DEFAULT_RENDER_DISTANCE = 48.0;
    public static final int DEFAULT_MAX_PARTICLES_PER_TICK = 200;

    private final double renderDistance;
    private final double renderDistanceSq;
    private final ParticleRateLimiter rateLimiter;

    public VolumetricParticleRenderer() {
        this(DEFAULT_RENDER_DISTANCE, DEFAULT_MAX_PARTICLES_PER_TICK);
    }

    public VolumetricParticleRenderer(double renderDistance, int maxParticlesPerTick) {
        if (renderDistance <= 0) {
            throw new IllegalArgumentException("renderDistance must be > 0: " + renderDistance);
        }
        this.renderDistance = renderDistance;
        this.renderDistanceSq = renderDistance * renderDistance;
        this.rateLimiter = new ParticleRateLimiter(maxParticlesPerTick);
    }

    public double renderDistance() {
        return renderDistance;
    }

    public ParticleRateLimiter rateLimiter() {
        return rateLimiter;
    }

    /**
     * Determines if a point is within render distance of any viewer in the collection.
     *
     * @param point target coordinate
     * @param viewers collection of viewer coordinates
     * @return true if visible to at least one viewer within render distance
     */
    public boolean isVisibleToAny(Vector3d point, Collection<Vector3d> viewers) {
        if (viewers == null || viewers.isEmpty()) return false;
        for (Vector3d viewer : viewers) {
            if (viewer != null && point.distanceSquared(viewer) <= renderDistanceSq) {
                return true;
            }
        }
        return false;
    }

    /**
     * Filters generated geometry points by distance to nearby viewers and applies rate limiting.
     *
     * @param rawPoints generated points
     * @param viewers viewer coordinates (e.g. online players in the world)
     * @param tick current tick index
     * @return culled and rate-limited points to display in this tick
     */
    public List<Vector3d> cullAndRateLimit(List<Vector3d> rawPoints, Collection<Vector3d> viewers, long tick) {
        Objects.requireNonNull(rawPoints, "rawPoints must not be null");
        if (rawPoints.isEmpty()) return Collections.emptyList();

        rateLimiter.updateTick(tick);

        List<Vector3d> visiblePoints = new ArrayList<>(rawPoints.size());
        for (Vector3d pt : rawPoints) {
            if (viewers == null || viewers.isEmpty() || isVisibleToAny(pt, viewers)) {
                visiblePoints.add(pt);
            }
        }

        if (visiblePoints.isEmpty()) return Collections.emptyList();

        int granted = rateLimiter.tryAcquire(visiblePoints.size());
        if (granted <= 0) return Collections.emptyList();

        if (granted >= visiblePoints.size()) {
            return visiblePoints;
        }

        // Sub-sample or truncate to granted budget
        return new ArrayList<>(visiblePoints.subList(0, granted));
    }

    /**
     * Renders geometry for a spatial bounding box using the provided particle spawner callback.
     *
     * @param bounds bounding box
     * @param style geometry style
     * @param step point spacing step
     * @param tick animation tick
     * @param viewers viewer positions for distance culling
     * @param spawner consumer invoked for each approved point: (point, count)
     */
    public int render(SpatialBounds bounds,
                      GeometryStyle style,
                      double step,
                      long tick,
                      Collection<Vector3d> viewers,
                      BiConsumer<Vector3d, Integer> spawner) {
        Objects.requireNonNull(spawner, "spawner must not be null");
        List<Vector3d> rawPoints = GeometryPointGenerator.generate(bounds, style, step, tick);
        List<Vector3d> pointsToRender = cullAndRateLimit(rawPoints, viewers, tick);

        for (Vector3d pt : pointsToRender) {
            spawner.accept(pt, 1);
        }

        return pointsToRender.size();
    }
}
