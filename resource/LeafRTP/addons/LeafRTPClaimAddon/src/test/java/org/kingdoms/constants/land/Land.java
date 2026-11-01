package org.kingdoms.constants.land;

import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.kingdoms.constants.land.location.SimpleChunkLocation;

/**
 * Test stub of KingdomsX {@code Land}. {@code Land.getLand(Location)} returns {@code null} for a
 * chunk with no data; a non-null land may still be unclaimed ({@code !isClaimed()}).
 */
public class Land {
  /** Land data per world/chunk; set by tests. */
  public interface Lookup {
    Land at(String world, int chunkX, int chunkZ);
  }

  private static Lookup lookup = (w, x, z) -> null;

  public static void setLookup(Lookup value) {
    lookup = value != null ? value : (w, x, z) -> null;
  }

  private final UUID kingdomId;

  public Land(UUID kingdomId) {
    this.kingdomId = kingdomId;
  }

  public static Land getLand(Chunk chunk) {
    throw new AssertionError("getLand(Chunk) needs a loaded chunk; not expected");
  }

  public static Land getLand(SimpleChunkLocation chunk) {
    return chunk.getLand();
  }

  public static Land getLand(Location location) {
    return lookup.at(
        location.getWorld().getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
  }

  public boolean isClaimed() {
    return kingdomId != null;
  }

  public UUID getKingdomId() {
    return kingdomId;
  }
}
