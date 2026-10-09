package io.github.dailystruggle.rtp.claimaddon;

import com.palmergames.bukkit.towny.TownyAPI;
import io.github.dailystruggle.rtp.api.claim.ClaimBoundary;
import io.github.dailystruggle.rtp.api.claim.ClaimBoundaryProvider;
import io.github.dailystruggle.rtp.common.RTP;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * ClaimBoundaryProvider for Towny Advanced.
 *
 * <p>Resolves the player's town territory boundary in the specified world, computing the bounding
 * chunk coordinates and geometric centroid across all town blocks. Uses reflection for resident
 * town inspection to remain decoupled from Adventure/Paper shaded audience classes in Spigot environments.
 */
public class TownyBoundaryProvider implements ClaimBoundaryProvider {
  private static boolean exists = true;

  private static int getTownBlockSize() {
    try {
      Class<?> settingsClass = Class.forName("com.palmergames.bukkit.towny.TownySettings");
      Method m = settingsClass.getMethod("getTownBlockSize");
      Object res = m.invoke(null);
      if (res instanceof Number n && n.intValue() > 0) {
        return n.intValue();
      }
    } catch (Throwable ignored) {
    }
    return 16;
  }

  @Override
  public String namespace() {
    return "towny";
  }

  @Override
  public int priority() {
    return 20;
  }

  @Override
  public Optional<ClaimBoundary> getBoundary(UUID playerId, String worldName) {
    if (!exists || playerId == null || worldName == null) {
      return Optional.empty();
    }
    if (Bukkit.getServer() == null || Bukkit.getPluginManager() == null || !Bukkit.getPluginManager().isPluginEnabled("Towny")) {
      return Optional.empty();
    }

    try {
      TownyAPI api = TownyAPI.getInstance();
      if (api == null) return Optional.empty();

      Object resident = null;
      try {
        Method getResidentUUID = api.getClass().getMethod("getResident", UUID.class);
        resident = getResidentUUID.invoke(api, playerId);
      } catch (NoSuchMethodException ignored) {
      }

      if (resident == null) {
        Player p = Bukkit.getPlayer(playerId);
        if (p != null) {
          Method getResidentPlayer = api.getClass().getMethod("getResident", Player.class);
          resident = getResidentPlayer.invoke(api, p);
        }
      }

      if (resident == null) return Optional.empty();

      Method hasTownMethod = resident.getClass().getMethod("hasTown");
      if (!Boolean.TRUE.equals(hasTownMethod.invoke(resident))) {
        return Optional.empty();
      }

      Method getTownOrNull = resident.getClass().getMethod("getTownOrNull");
      Object town = getTownOrNull.invoke(resident);
      if (town == null) return Optional.empty();

      Method getTownBlocks = town.getClass().getMethod("getTownBlocks");
      Object blocksObj = getTownBlocks.invoke(town);
      if (!(blocksObj instanceof Collection<?> blocks) || blocks.isEmpty()) {
        return Optional.empty();
      }

      int tbSize = getTownBlockSize();
      int minChunkX = Integer.MAX_VALUE;
      int minChunkZ = Integer.MAX_VALUE;
      int maxChunkX = Integer.MIN_VALUE;
      int maxChunkZ = Integer.MIN_VALUE;
      long sumX = 0;
      long sumZ = 0;
      int count = 0;
      Set<Long> townBlockKeys = new HashSet<>();

      for (Object tb : blocks) {
        if (tb == null) continue;
        Method getWorldMethod = tb.getClass().getMethod("getWorld");
        Object tbWorld = getWorldMethod.invoke(tb);
        if (tbWorld == null) continue;
        Method getNameMethod = tbWorld.getClass().getMethod("getName");
        Object nameObj = getNameMethod.invoke(tbWorld);
        if (nameObj == null || !worldName.equalsIgnoreCase(nameObj.toString())) {
          continue;
        }

        Method getXMethod = tb.getClass().getMethod("getX");
        Method getZMethod = tb.getClass().getMethod("getZ");
        int tbX = (Integer) getXMethod.invoke(tb);
        int tbZ = (Integer) getZMethod.invoke(tb);

        int minBx = tbX * tbSize;
        int maxBx = minBx + tbSize - 1;
        int minBz = tbZ * tbSize;
        int maxBz = minBz + tbSize - 1;

        int cMinX = minBx >> 4;
        int cMaxX = maxBx >> 4;
        int cMinZ = minBz >> 4;
        int cMaxZ = maxBz >> 4;

        if (cMinX < minChunkX) minChunkX = cMinX;
        if (cMaxX > maxChunkX) maxChunkX = cMaxX;
        if (cMinZ < minChunkZ) minChunkZ = cMinZ;
        if (cMaxZ > maxChunkZ) maxChunkZ = cMaxZ;

        sumX += minBx + (tbSize / 2);
        sumZ += minBz + (tbSize / 2);
        townBlockKeys.add((((long) tbX) << 32) | (tbZ & 0xFFFFFFFFL));
        count++;
      }

      if (count == 0) {
        return Optional.empty();
      }

      int centroidX = (int) (sumX / count);
      int centroidZ = (int) (sumZ / count);
      int finalMinX = minChunkX;
      int finalMinZ = minChunkZ;
      int finalMaxX = maxChunkX;
      int finalMaxZ = maxChunkZ;

      String resolvedTownId;
      try {
        Method getUUIDMethod = town.getClass().getMethod("getUUID");
        Object uuidObj = getUUIDMethod.invoke(town);
        resolvedTownId = (uuidObj != null) ? uuidObj.toString() : null;
      } catch (Exception ignored) {
        resolvedTownId = null;
      }
      if (resolvedTownId == null) {
        try {
          Method getNameMethod = town.getClass().getMethod("getName");
          Object nameObj = getNameMethod.invoke(town);
          resolvedTownId = (nameObj != null) ? nameObj.toString() : "town_" + playerId;
        } catch (Exception e) {
          resolvedTownId = "town_" + playerId;
        }
      }
      final String townId = resolvedTownId;

      return Optional.of(buildBoundary(townId, worldName, tbSize, townBlockKeys, centroidX, centroidZ, finalMinX, finalMinZ, finalMaxX, finalMaxZ));
    } catch (Throwable t) {
      if (ClaimCheckFailure.isIncompatibility(t)) {
        exists = false;
        RTP.log(
            Level.SEVERE,
            "[RTP] Towny API is missing or incompatible. Disabling Towny boundary lookup.",
            t);
      } else {
        RTP.log(
            Level.WARNING,
            "[RTP] Towny integration encountered an error resolving claim boundary for player " + playerId + ".",
            t);
      }
      return Optional.empty();
    }
  }

  @Override
  public Optional<ClaimBoundary> getBoundaryAt(String worldName, int x, int z) {
    if (!exists || worldName == null) {
      return Optional.empty();
    }
    if (Bukkit.getServer() == null || Bukkit.getPluginManager() == null || !Bukkit.getPluginManager().isPluginEnabled("Towny")) {
      return Optional.empty();
    }

    try {
      TownyAPI api = TownyAPI.getInstance();
      if (api == null) return Optional.empty();

      org.bukkit.World world = Bukkit.getWorld(worldName);
      if (world == null) return Optional.empty();
      org.bukkit.Location loc = new org.bukkit.Location(world, x, 64, z);

      if (api.isWilderness(loc)) {
        return Optional.empty();
      }

      Method getTownBlockMethod = null;
      try {
        getTownBlockMethod = api.getClass().getMethod("getTownBlock", org.bukkit.Location.class);
      } catch (NoSuchMethodException ignored) {
      }

      Object townBlock = null;
      if (getTownBlockMethod != null) {
        townBlock = getTownBlockMethod.invoke(api, loc);
      }

      if (townBlock == null) {
        // Fallback to AdaptiveClaimProber if TownBlock object cannot be retrieved directly
        return AdaptiveClaimProber.probeBoundary(worldName, x, z, coords -> {
          org.bukkit.Location testLoc = new org.bukkit.Location(world, coords.x(), coords.y(), coords.z());
          return !api.isWilderness(testLoc);
        });
      }

      Method getTownMethod = townBlock.getClass().getMethod("getTown");
      Object town = getTownMethod.invoke(townBlock);
      if (town == null) {
        return AdaptiveClaimProber.probeBoundary(worldName, x, z, coords -> {
          org.bukkit.Location testLoc = new org.bukkit.Location(world, coords.x(), coords.y(), coords.z());
          return !api.isWilderness(testLoc);
        });
      }

      Method getTownBlocks = town.getClass().getMethod("getTownBlocks");
      Object blocksObj = getTownBlocks.invoke(town);
      if (!(blocksObj instanceof Collection<?> blocks) || blocks.isEmpty()) {
        return Optional.empty();
      }

      int tbSize = getTownBlockSize();
      int minChunkX = Integer.MAX_VALUE;
      int minChunkZ = Integer.MAX_VALUE;
      int maxChunkX = Integer.MIN_VALUE;
      int maxChunkZ = Integer.MIN_VALUE;
      long sumX = 0;
      long sumZ = 0;
      int count = 0;
      Set<Long> townBlockKeys = new HashSet<>();

      for (Object tb : blocks) {
        if (tb == null) continue;
        Method getWorldMethod = tb.getClass().getMethod("getWorld");
        Object tbWorld = getWorldMethod.invoke(tb);
        if (tbWorld == null) continue;
        Method getNameMethod = tbWorld.getClass().getMethod("getName");
        Object nameObj = getNameMethod.invoke(tbWorld);
        if (nameObj == null || !worldName.equalsIgnoreCase(nameObj.toString())) {
          continue;
        }

        Method getXMethod = tb.getClass().getMethod("getX");
        Method getZMethod = tb.getClass().getMethod("getZ");
        int tbX = (Integer) getXMethod.invoke(tb);
        int tbZ = (Integer) getZMethod.invoke(tb);

        int minBx = tbX * tbSize;
        int maxBx = minBx + tbSize - 1;
        int minBz = tbZ * tbSize;
        int maxBz = minBz + tbSize - 1;

        int cMinX = minBx >> 4;
        int cMaxX = maxBx >> 4;
        int cMinZ = minBz >> 4;
        int cMaxZ = maxBz >> 4;

        if (cMinX < minChunkX) minChunkX = cMinX;
        if (cMaxX > maxChunkX) maxChunkX = cMaxX;
        if (cMinZ < minChunkZ) minChunkZ = cMinZ;
        if (cMaxZ > maxChunkZ) maxChunkZ = cMaxZ;

        sumX += minBx + (tbSize / 2);
        sumZ += minBz + (tbSize / 2);
        townBlockKeys.add((((long) tbX) << 32) | (tbZ & 0xFFFFFFFFL));
        count++;
      }

      if (count == 0) {
        return Optional.empty();
      }

      int centroidX = (int) (sumX / count);
      int centroidZ = (int) (sumZ / count);

      String resolvedTownId;
      try {
        Method getUUIDMethod = town.getClass().getMethod("getUUID");
        Object uuidObj = getUUIDMethod.invoke(town);
        resolvedTownId = (uuidObj != null) ? uuidObj.toString() : null;
      } catch (Exception ignored) {
        resolvedTownId = null;
      }
      if (resolvedTownId == null) {
        try {
          Method getNameMethod = town.getClass().getMethod("getName");
          Object nameObj = getNameMethod.invoke(town);
          resolvedTownId = (nameObj != null) ? nameObj.toString() : "town_" + x + "_" + z;
        } catch (Exception e) {
          resolvedTownId = "town_" + x + "_" + z;
        }
      }

      return Optional.of(buildBoundary(resolvedTownId, worldName, tbSize, townBlockKeys, centroidX, centroidZ, minChunkX, minChunkZ, maxChunkX, maxChunkZ));
    } catch (Throwable t) {
      if (ClaimCheckFailure.isIncompatibility(t)) {
        exists = false;
        RTP.log(
            Level.SEVERE,
            "[RTP] Towny API is missing or incompatible. Disabling Towny boundary lookup.",
            t);
      } else {
        RTP.log(
            Level.WARNING,
            "[RTP] Towny integration encountered an error resolving claim boundary at (" + x + "," + z + ").",
            t);
      }
      return Optional.empty();
    }
  }

  static ClaimBoundary buildBoundary(
      String townId,
      String worldName,
      int tbSize,
      Set<Long> townBlockKeys,
      int centroidX,
      int centroidZ,
      int minChunkX,
      int minChunkZ,
      int maxChunkX,
      int maxChunkZ) {
    return new ClaimBoundary() {
      @Override
      public String id() {
        return townId;
      }

      @Override
      public String world() {
        return worldName;
      }

      @Override
      public boolean contains(int x, int z) {
        int tbX = Math.floorDiv(x, tbSize);
        int tbZ = Math.floorDiv(z, tbSize);
        long key = (((long) tbX) << 32) | (tbZ & 0xFFFFFFFFL);
        return townBlockKeys.contains(key);
      }

      @Override
      public boolean containsChunk(int cx, int cz) {
        if (tbSize == 16) {
          long key = (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
          return townBlockKeys.contains(key);
        }
        int centerX = (cx << 4) + 8;
        int centerZ = (cz << 4) + 8;
        int tbX = Math.floorDiv(centerX, tbSize);
        int tbZ = Math.floorDiv(centerZ, tbSize);
        long key = (((long) tbX) << 32) | (tbZ & 0xFFFFFFFFL);
        return townBlockKeys.contains(key);
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
    };
  }
}
