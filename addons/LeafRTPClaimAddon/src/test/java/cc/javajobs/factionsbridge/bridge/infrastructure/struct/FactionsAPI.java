package cc.javajobs.factionsbridge.bridge.infrastructure.struct;

import org.bukkit.Chunk;
import org.bukkit.Location;

/**
 * Test stub of the FactionsBridge {@code FactionsAPI}; {@code getFactionAt(Location)} is a default
 * method delegating to the chunk lookup, as in the real interface.
 */
public interface FactionsAPI {
  Faction getFactionAt(Chunk chunk);

  /** Test-only seam standing in for the chunk lookup without a loaded chunk. */
  Faction getFactionAtChunk(String world, int chunkX, int chunkZ);

  default Faction getFactionAt(Location location) {
    return getFactionAtChunk(
        location.getWorld().getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
  }
}
