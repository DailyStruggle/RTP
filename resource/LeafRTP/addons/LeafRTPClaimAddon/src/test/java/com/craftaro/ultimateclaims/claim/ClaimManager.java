package com.craftaro.ultimateclaims.claim;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Test stub with the public overload set of UltimateClaims 2.x {@code ClaimManager}. It has no
 * {@code getClaim(Location)}; chunk lookups are by {@code Chunk} or by world name and chunk
 * coordinates.
 */
public class ClaimManager {
  /** Answers {@code getClaim(String, int, int)}; set by tests. */
  public interface ChunkLookup {
    Claim at(String world, int chunkX, int chunkZ);
  }

  private ChunkLookup lookup = (w, x, z) -> null;

  public void setLookup(ChunkLookup lookup) {
    this.lookup = lookup;
  }

  public boolean hasClaim(UUID owner) {
    return false;
  }

  public boolean hasClaim(Player owner) {
    return false;
  }

  public boolean hasClaim(Chunk chunk) {
    throw new AssertionError("hasClaim(Chunk) needs a loaded chunk; not expected");
  }

  public Claim getClaim(UUID owner) {
    return null;
  }

  public Claim getClaim(Player owner) {
    return null;
  }

  public Claim getClaim(Chunk chunk) {
    throw new AssertionError("getClaim(Chunk) needs a loaded chunk; not expected");
  }

  public Claim getClaim(String world, int chunkX, int chunkZ) {
    return lookup.at(world, chunkX, chunkZ);
  }

  public List<Claim> getClaims(OfflinePlayer player) {
    return List.of();
  }

  public Collection<Claim> getRegisteredClaims() {
    return List.of();
  }
}
