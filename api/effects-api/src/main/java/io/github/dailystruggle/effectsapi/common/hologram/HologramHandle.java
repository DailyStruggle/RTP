package io.github.dailystruggle.effectsapi.common.hologram;

import io.github.dailystruggle.effectsapi.common.volumetric.Vector3d;

import java.util.List;
import java.util.Objects;

/**
 * Handle to an active platform-rendered hologram / floating text display entity.
 */
public interface HologramHandle extends AutoCloseable {

    /**
     * @return unique identifier of the hologram
     */
    String id();

    /**
     * @return world identifier where the hologram is spawned
     */
    String worldName();

    /**
     * @return current 3D position
     */
    Vector3d position();

    /**
     * @return current lines of text
     */
    List<String> lines();

    /**
     * Updates the lines of text rendered on the hologram.
     *
     * @param lines new text lines
     */
    void updateLines(List<String> lines);

    /**
     * Updates the 3D position of the hologram.
     *
     * @param newPosition new location
     */
    void teleport(Vector3d newPosition);

    /**
     * Removes the hologram and cleans up any backing entities.
     */
    @Override
    void close();
}
