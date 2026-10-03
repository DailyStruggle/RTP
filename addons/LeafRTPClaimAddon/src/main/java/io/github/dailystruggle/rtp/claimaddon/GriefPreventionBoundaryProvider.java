package io.github.dailystruggle.rtp.claimaddon;

import io.github.dailystruggle.rtp.api.claim.ClaimBoundary;
import io.github.dailystruggle.rtp.api.claim.ClaimBoundaryProvider;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

/**
 * ClaimBoundaryProvider for GriefPrevention claims.
 *
 * <p>Resolves the player's primary claim in the specified world, providing its boundary,
 * bounding chunk coordinates, and centroid.
 */
public class GriefPreventionBoundaryProvider implements ClaimBoundaryProvider {
  private static boolean exists = true;

  @Override
  public String namespace() {
    return "griefprevention";
  }

  @Override
  public int priority() {
    return 15;
  }

  @Override
  public Optional<ClaimBoundary> getBoundary(UUID playerId, String worldName) {
    if (!exists || playerId == null || worldName == null) {
      return Optional.empty();
    }

    try {
      Plugin plugin = Bukkit.getServer().getPluginManager().getPlugin("GriefPrevention");
      if (!(plugin instanceof GriefPrevention)) {
        return Optional.empty();
      }

      DataStore dataStore = GriefPrevention.instance.dataStore;
      if (dataStore == null) return Optional.empty();

      // Look for claims owned by the player in the requested world
      // GriefPrevention datastore provides getPlayerData or iterating player claims
      me.ryanhamshire.GriefPrevention.PlayerData playerData = dataStore.getPlayerData(playerId);
      if (playerData == null || playerData.getClaims() == null || playerData.getClaims().isEmpty()) {
        return Optional.empty();
      }

      Claim targetClaim = null;
      for (Claim claim : playerData.getClaims()) {
        if (claim == null) continue;
        Location lesser = claim.getLesserBoundaryCorner();
        if (lesser != null && lesser.getWorld() != null && worldName.equalsIgnoreCase(lesser.getWorld().getName())) {
          targetClaim = claim;
          break;
        }
      }

      if (targetClaim == null) {
        return Optional.empty();
      }

      final Claim claim = targetClaim;
      Location lesser = claim.getLesserBoundaryCorner();
      Location greater = claim.getGreaterBoundaryCorner();
      if (lesser == null || greater == null) return Optional.empty();

      int minX = Math.min(lesser.getBlockX(), greater.getBlockX());
      int maxX = Math.max(lesser.getBlockX(), greater.getBlockX());
      int minZ = Math.min(lesser.getBlockZ(), greater.getBlockZ());
      int maxZ = Math.max(lesser.getBlockZ(), greater.getBlockZ());

      int minChunkX = minX >> 4;
      int maxChunkX = maxX >> 4;
      int minChunkZ = minZ >> 4;
      int maxChunkZ = maxZ >> 4;

      int centroidX = minX + (maxX - minX) / 2;
      int centroidZ = minZ + (maxZ - minZ) / 2;
      String claimId = (claim.getID() != null) ? claim.getID().toString() : ("gp_" + playerId);

      return Optional.of(buildBoundary(claimId, worldName, minX, minZ, maxX, maxZ, minChunkX, minChunkZ, maxChunkX, maxChunkZ, centroidX, centroidZ));
    } catch (Throwable t) {
      exists = false;
      RTP.log(
          Level.WARNING,
          "[RTP] GriefPrevention integration encountered an error resolving claim boundary. Disabling GP boundary lookup.",
          t);
      return Optional.empty();
    }
  }

  @Override
  public Optional<ClaimBoundary> getBoundaryAt(String worldName, int x, int z) {
    if (!exists || worldName == null) {
      return Optional.empty();
    }

    try {
      Plugin plugin = Bukkit.getServer().getPluginManager().getPlugin("GriefPrevention");
      if (!(plugin instanceof GriefPrevention)) {
        return Optional.empty();
      }

      DataStore dataStore = GriefPrevention.instance.dataStore;
      if (dataStore == null) return Optional.empty();

      org.bukkit.World world = Bukkit.getWorld(worldName);
      if (world == null) return Optional.empty();

      Location loc = new Location(world, x, 64, z);
      Claim claim = dataStore.getClaimAt(loc, false, null);
      if (claim == null) {
        return Optional.empty();
      }

      Location lesser = claim.getLesserBoundaryCorner();
      Location greater = claim.getGreaterBoundaryCorner();
      if (lesser == null || greater == null) return Optional.empty();

      int minX = Math.min(lesser.getBlockX(), greater.getBlockX());
      int maxX = Math.max(lesser.getBlockX(), greater.getBlockX());
      int minZ = Math.min(lesser.getBlockZ(), greater.getBlockZ());
      int maxZ = Math.max(lesser.getBlockZ(), greater.getBlockZ());

      int minChunkX = minX >> 4;
      int maxChunkX = maxX >> 4;
      int minChunkZ = minZ >> 4;
      int maxChunkZ = maxZ >> 4;

      int centroidX = minX + (maxX - minX) / 2;
      int centroidZ = minZ + (maxZ - minZ) / 2;
      String claimId = (claim.getID() != null) ? claim.getID().toString() : ("gp_" + x + "_" + z);

      return Optional.of(buildBoundary(claimId, worldName, minX, minZ, maxX, maxZ, minChunkX, minChunkZ, maxChunkX, maxChunkZ, centroidX, centroidZ));
    } catch (Throwable t) {
      RTP.log(
          Level.WARNING,
          "[RTP] GriefPrevention integration encountered an error resolving claim boundary at (" + x + "," + z + ").",
          t);
      return Optional.empty();
    }
  }

  private static ClaimBoundary buildBoundary(
      String claimId,
      String worldName,
      int minX,
      int minZ,
      int maxX,
      int maxZ,
      int minChunkX,
      int minChunkZ,
      int maxChunkX,
      int maxChunkZ,
      int centroidX,
      int centroidZ) {
    return new ClaimBoundary() {
      @Override
      public String id() {
        return claimId;
      }

      @Override
      public String world() {
        return worldName;
      }

      @Override
      public boolean contains(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
      }

      @Override
      public boolean containsChunk(int cx, int cz) {
        int cMinX = cx << 4;
        int cMaxX = cMinX + 15;
        int cMinZ = cz << 4;
        int cMaxZ = cMinZ + 15;
        return !(cMaxX < minX || cMinX > maxX || cMaxZ < minZ || cMinZ > maxZ);
      }

      @Override
      public int[] centroid() {
        return new int[] {centroidX, centroidZ};
      }

      @Override
      public int minChunkX() {
        return minChunkX;
      }

      @Override
      public int minChunkZ() {
        return minChunkZ;
      }

      @Override
      public int maxChunkX() {
        return maxChunkX;
      }

      @Override
      public int maxChunkZ() {
        return maxChunkZ;
      }

      @Override
      public int minX() {
        return minX;
      }

      @Override
      public int minZ() {
        return minZ;
      }

      @Override
      public int maxX() {
        return maxX;
      }

      @Override
      public int maxZ() {
        return maxZ;
      }
    };
  }
}
