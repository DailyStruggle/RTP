package io.github.dailystruggle.rtp.api.trigger;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

import java.util.Objects;

/**
 * Immutable specification for physical world triggers (ADR-093).
 *
 * @param id              unique trigger identifier
 * @param type            trigger type (PORTAL, PRESSURE_PLATE, STEP_IN)
 * @param worldName       world name
 * @param minX            min X block coordinate
 * @param minY            min Y block coordinate
 * @param minZ            min Z block coordinate
 * @param maxX            max X block coordinate
 * @param maxY            max Y block coordinate
 * @param maxZ            max Z block coordinate
 * @param actionId        target action identifier to execute upon entry
 * @param cooldownSeconds per-player trigger cooldown in seconds
 */
@PublicApi
public record PhysicalTriggerSpec(
    String id,
    TriggerType type,
    String worldName,
    int minX,
    int minY,
    int minZ,
    int maxX,
    int maxY,
    int maxZ,
    String actionId,
    long cooldownSeconds) {

  public PhysicalTriggerSpec {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(worldName, "worldName must not be null");
    Objects.requireNonNull(actionId, "actionId must not be null");
    if (minX > maxX) {
      int t = minX; minX = maxX; maxX = t;
    }
    if (minY > maxY) {
      int t = minY; minY = maxY; maxY = t;
    }
    if (minZ > maxZ) {
      int t = minZ; minZ = maxZ; maxZ = t;
    }
    cooldownSeconds = Math.max(0L, cooldownSeconds);
  }

  public enum TriggerType {
    PORTAL,
    PRESSURE_PLATE,
    STEP_IN
  }

  /**
   * Checks whether coordinates (x, y, z) in {@code world} fall within this trigger volume.
   */
  public boolean contains(String world, int x, int y, int z) {
    if (!worldName.equalsIgnoreCase(world)) return false;
    return x >= minX && x <= maxX
        && y >= minY && y <= maxY
        && z >= minZ && z <= maxZ;
  }
}
