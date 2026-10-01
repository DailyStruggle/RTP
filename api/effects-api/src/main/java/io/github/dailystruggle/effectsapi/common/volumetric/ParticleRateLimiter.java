package io.github.dailystruggle.effectsapi.common.volumetric;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe rate limiter for volumetric particle rendering.
 *
 * <p>Enforces a maximum particle count budget per tick (e.g. 200 particles/tick).
 * Resets each tick boundary and allows callers to query whether an emission quota is available.</p>
 */
public final class ParticleRateLimiter {

    private final int maxParticlesPerTick;
    private final AtomicInteger currentTickParticles = new AtomicInteger(0);
    private volatile long currentTick = -1L;

    public ParticleRateLimiter(int maxParticlesPerTick) {
        if (maxParticlesPerTick <= 0) {
            throw new IllegalArgumentException("maxParticlesPerTick must be > 0: " + maxParticlesPerTick);
        }
        this.maxParticlesPerTick = maxParticlesPerTick;
    }

    /**
     * @return maximum particles allowed per tick
     */
    public int maxParticlesPerTick() {
        return maxParticlesPerTick;
    }

    /**
     * Synchronizes with the tick counter and resets quota if advancing to a new tick.
     *
     * @param tick current tick index
     */
    public synchronized void updateTick(long tick) {
        if (tick != this.currentTick) {
            this.currentTick = tick;
            this.currentTickParticles.set(0);
        }
    }

    /**
     * Attempts to acquire a quota of particles for emission in the current tick.
     *
     * @param count requested particle count
     * @return actual particle count granted (between 0 and count)
     */
    public int tryAcquire(int count) {
        if (count <= 0) return 0;
        while (true) {
            int current = currentTickParticles.get();
            if (current >= maxParticlesPerTick) {
                return 0; // budget exhausted
            }
            int available = maxParticlesPerTick - current;
            int grant = Math.min(count, available);
            if (currentTickParticles.compareAndSet(current, current + grant)) {
                return grant;
            }
        }
    }

    /**
     * @return current number of particles consumed in this tick
     */
    public int currentUsage() {
        return currentTickParticles.get();
    }

    /**
     * Resets rate limiter usage for testing or tick manual advances.
     */
    public void reset() {
        currentTickParticles.set(0);
    }
}
