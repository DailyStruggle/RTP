package io.github.dailystruggle.effectsapi.common.hologram;

import io.github.dailystruggle.effectsapi.common.volumetric.SpatialBounds;
import io.github.dailystruggle.effectsapi.common.volumetric.Vector3d;

import java.util.List;

/**
 * Platform-neutral SPI provider for creating floating display entities and holograms.
 *
 * <p>Supports modern Display Entities (TextDisplay) and armor stand or virtual packet-based
 * fallback providers.</p>
 */
public interface HologramProvider {

    /**
     * Spawns a floating hologram with the given lines at the target position.
     *
     * @param id unique identifier
     * @param worldName world identifier
     * @param position 3D location
     * @param lines text lines to display
     * @return active handle to the hologram
     */
    HologramHandle spawnHologram(String id, String worldName, Vector3d position, List<String> lines);

    /**
     * Spawns a floating countdown hologram centered above a spatial bounding box.
     *
     * @param id unique identifier
     * @param worldName world identifier
     * @param bounds spatial bounds of the volume
     * @param yOffset offset above the highest point of the bounds (e.g. 1.5 blocks above maxY)
     * @param lines text lines to display
     * @return active handle to the hologram
     */
    default HologramHandle spawnAboveBounds(String id, String worldName, SpatialBounds bounds, double yOffset, List<String> lines) {
        Vector3d center = bounds.center();
        Vector3d pos = new Vector3d(center.x(), bounds.maxY() + yOffset, center.z());
        return spawnHologram(id, worldName, pos, lines);
    }
}
