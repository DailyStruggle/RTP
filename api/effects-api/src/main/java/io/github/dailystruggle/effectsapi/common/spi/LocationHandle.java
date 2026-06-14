package io.github.dailystruggle.effectsapi.common.spi;

import org.jetbrains.annotations.NotNull;
import java.util.Map;

/**
 * Platform-neutral handle for a spatial location.
 *
 * <p>Implemented by {@code rtp-api}'s {@code RTPLocation} and platform-specific
 * wrappers in {@code effects-api}.</p>
 */
public interface LocationHandle {
    /** @return x-coordinate (block/integer precision) */
    int x();

    /** @return y-coordinate (block/integer precision) */
    int y();

    /** @return z-coordinate (block/integer precision) */
    int z();

    /** @return x-coordinate with full double precision */
    double doubleX();

    /** @return y-coordinate with full double precision */
    double doubleY();

    /** @return z-coordinate with full double precision */
    double doubleZ();

    /** @return platform-neutral world name or identifier */
    @NotNull String worldName();

    /**
     * @return the underlying platform-specific location or vector object (e.g. Bukkit Location or Fabric Vec3).
     */
    @NotNull Object platformLocation();

    /**
     * Plays a sound at this location.
     */
    void playSound(Object type, float volume, float pitch);

    /**
     * Spawns particles at this location.
     */
    void spawnParticle(Object type, int count, double dx, double dy, double dz, double speed);

    /**
     * Spawns a firework at this location.
     */
    void spawnFirework(Map<String, Object> data);

    /**
     * Plays a note at this location.
     */
    void playNote(Object instrument, int tone);
}
