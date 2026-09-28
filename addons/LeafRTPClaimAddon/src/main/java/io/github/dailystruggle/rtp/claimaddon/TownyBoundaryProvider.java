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

      int minChunkX = Integer.MAX_VALUE;
      int minChunkZ = Integer.MAX_VALUE;
      int maxChunkX = Integer.MIN_VALUE;
      int maxChunkZ = Integer.MIN_VALUE;
      long sumX = 0;
      long sumZ = 0;
      int count = 0;
      Set<Long> chunkKeys = new HashSet<>();

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
        int cx = (Integer) getXMethod.invoke(tb);
        int cz = (Integer) getZMethod.invoke(tb);

        if (cx < minChunkX) minChunkX = cx;
        if (cx > maxChunkX) maxChunkX = cx;
        if (cz < minChunkZ) minChunkZ = cz;
        if (cz > maxChunkZ) maxChunkZ = cz;

        sumX += ((long) cx << 4) + 8;
        sumZ += ((long) cz << 4) + 8;
        chunkKeys.add((((long) cx) << 32) | (cz & 0xFFFFFFFFL));
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

      return Optional.of(
          new ClaimBoundary() {
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
              return containsChunk(x >> 4, z >> 4);
            }

            @Override
            public boolean containsChunk(int cx, int cz) {
              long key = (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
              return chunkKeys.contains(key);
            }

            @Override
            public int[] centroid() {
              return new int[] {centroidX, centroidZ};
            }

            @Override
            public int minChunkX() {
              return finalMinX;
            }

            @Override
            public int minChunkZ() {
              return finalMinZ;
            }

            @Override
            public int maxChunkX() {
              return finalMaxX;
            }

            @Override
            public int maxChunkZ() {
              return finalMaxZ;
            }
          });
    } catch (Throwable t) {
      exists = false;
      RTP.log(
          Level.WARNING,
          "[RTP] Towny integration encountered an error resolving claim boundary. Disabling Towny boundary lookup.",
          t);
      return Optional.empty();
    }
  }
}
