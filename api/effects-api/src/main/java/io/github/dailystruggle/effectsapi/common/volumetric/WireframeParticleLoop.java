package io.github.dailystruggle.effectsapi.common.volumetric;

import io.github.dailystruggle.effectsapi.common.spi.EffectRuntime;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scheduled asynchronous render loop ticking active spatial wireframes and displaying boundary particles.
 *
 * <p>Periodically queries active wireframes, calculates volumetric boundary points, performs
 * distance culling against viewers, applies tick rate-limiting (budget: 200 particles/tick),
 * and dispatches particles through the platform particle spawner sink.</p>
 */
public class WireframeParticleLoop implements AutoCloseable {

    @FunctionalInterface
    public interface ParticleSink {
        void spawn(String world, Object particleType, Vector3d location);
    }

    @FunctionalInterface
    public interface ViewerProvider {
        Collection<Vector3d> getViewers(String world);
    }

    private final Map<String, SpatialWireframeRenderSpec> activeWireframes = new ConcurrentHashMap<>();
    private final VolumetricParticleRenderer renderer;
    private final ParticleSink particleSink;
    private final ViewerProvider viewerProvider;
    private final AtomicLong currentTick = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(false);

    public WireframeParticleLoop(ParticleSink particleSink, ViewerProvider viewerProvider) {
        this(new VolumetricParticleRenderer(), particleSink, viewerProvider);
    }

    public WireframeParticleLoop(VolumetricParticleRenderer renderer, ParticleSink particleSink, ViewerProvider viewerProvider) {
        this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
        this.particleSink = Objects.requireNonNull(particleSink, "particleSink must not be null");
        this.viewerProvider = Objects.requireNonNull(viewerProvider, "viewerProvider must not be null");
    }

    public VolumetricParticleRenderer renderer() {
        return renderer;
    }

    public void registerWireframe(SpatialWireframeRenderSpec wireframe) {
        Objects.requireNonNull(wireframe, "wireframe must not be null");
        activeWireframes.put(wireframe.id().toLowerCase(), wireframe);
    }

    public boolean unregisterWireframe(String wireframeId) {
        if (wireframeId == null) return false;
        return activeWireframes.remove(wireframeId.toLowerCase()) != null;
    }

    public SpatialWireframeRenderSpec getWireframe(String wireframeId) {
        if (wireframeId == null) return null;
        return activeWireframes.get(wireframeId.toLowerCase());
    }

    public Collection<SpatialWireframeRenderSpec> getActiveWireframes() {
        return Collections.unmodifiableCollection(activeWireframes.values());
    }

    public void clear() {
        activeWireframes.clear();
    }

    /**
     * Executes a single render tick across all active wireframes.
     * Can be invoked directly in tests or scheduled periodically.
     */
    public void tick() {
        if (activeWireframes.isEmpty()) return;
        long tick = currentTick.incrementAndGet();

        for (SpatialWireframeRenderSpec wireframe : activeWireframes.values()) {
            Collection<Vector3d> viewers = viewerProvider.getViewers(wireframe.worldName());
            renderer.render(
                    wireframe.bounds(),
                    wireframe.style(),
                    wireframe.step(),
                    tick,
                    viewers,
                    (point, count) -> particleSink.spawn(wireframe.worldName(), wireframe.particleType(), point)
            );
        }
    }

    /**
     * Schedules the repeating render loop on the provided {@link EffectRuntime}.
     *
     * @param runtime effect runtime scheduler
     * @param periodTicks interval between particle ticks (typically 1 to 5 ticks)
     */
    public void start(EffectRuntime runtime, long periodTicks) {
        Objects.requireNonNull(runtime, "runtime must not be null");
        if (periodTicks <= 0) periodTicks = 2L;
        if (running.compareAndSet(false, true)) {
            runtime.scheduleRepeating(this::tick, 0L, periodTicks);
        }
    }

    @Override
    public void close() {
        running.set(false);
        clear();
    }
}
