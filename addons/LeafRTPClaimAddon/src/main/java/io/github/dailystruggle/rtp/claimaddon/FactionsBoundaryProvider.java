package io.github.dailystruggle.rtp.claimaddon;

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

/**
 * ClaimBoundaryProvider for SaberFactions and FactionsUUID territory.
 *
 * <p>Reflectively queries FPlayers and Faction to obtain the faction's claimed chunks
 * in the specified world, computing the bounding chunk coordinates and centroid.
 */
public class FactionsBoundaryProvider implements ClaimBoundaryProvider {
  private static boolean exists = true;

  @Override
  public String namespace() {
    return "factions";
  }

  @Override
  public int priority() {
    return 10;
  }

  @Override
  public Optional<ClaimBoundary> getBoundary(UUID playerId, String worldName) {
    if (!exists || playerId == null || worldName == null) {
      return Optional.empty();
    }
    if (Bukkit.getServer() == null || Bukkit.getPluginManager() == null || !Bukkit.getPluginManager().isPluginEnabled("Factions")) {
      return Optional.empty();
    }

    try {
      // 1. Obtain FPlayer from FPlayers.getInstance().getByPlayer(player) or getById(UUID)
      Class<?> fPlayersClass = Class.forName("com.massivecraft.factions.FPlayers");
      Object fPlayers = fPlayersClass.getMethod("getInstance").invoke(null);
      if (fPlayers == null) return Optional.empty();

      Object fPlayer = null;
      try {
        Method getByPlayer = fPlayersClass.getMethod("getByPlayer", org.bukkit.entity.Player.class);
        org.bukkit.entity.Player p = Bukkit.getPlayer(playerId);
        if (p != null) fPlayer = getByPlayer.invoke(fPlayers, p);
      } catch (NoSuchMethodException ignored) {
      }

      if (fPlayer == null) {
        try {
          Method getById = fPlayersClass.getMethod("getById", String.class);
          fPlayer = getById.invoke(fPlayers, playerId.toString());
        } catch (NoSuchMethodException ignored) {
        }
      }

      if (fPlayer == null) return Optional.empty();

      Method getFaction = fPlayer.getClass().getMethod("getFaction");
      Object faction = getFaction.invoke(fPlayer);
      if (faction == null) return Optional.empty();

      Method isWilderness = faction.getClass().getMethod("isWilderness");
      if (Boolean.TRUE.equals(isWilderness.invoke(faction))) {
        return Optional.empty();
      }

      // 2. Query faction claims: faction.getAllClaims() or faction.getClaims()
      // Usually returns Set<FLocation>
      Method getAllClaims = null;
      try {
        getAllClaims = faction.getClass().getMethod("getAllClaims");
      } catch (NoSuchMethodException e) {
        try {
          getAllClaims = faction.getClass().getMethod("getClaims");
        } catch (NoSuchMethodException ignored) {
        }
      }

      if (getAllClaims == null) return Optional.empty();
      Object rawClaims = getAllClaims.invoke(faction);
      if (!(rawClaims instanceof Collection<?> claimsCollection) || claimsCollection.isEmpty()) {
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

      for (Object fLoc : claimsCollection) {
        if (fLoc == null) continue;
        Method getWorldNameMethod = fLoc.getClass().getMethod("getWorldName");
        Object wNameObj = getWorldNameMethod.invoke(fLoc);
        if (wNameObj == null || !worldName.equalsIgnoreCase(wNameObj.toString())) {
          continue;
        }

        Method getXMethod = fLoc.getClass().getMethod("getX");
        Method getZMethod = fLoc.getClass().getMethod("getZ");
        long cxLong = ((Number) getXMethod.invoke(fLoc)).longValue();
        long czLong = ((Number) getZMethod.invoke(fLoc)).longValue();
        int cx = (int) cxLong;
        int cz = (int) czLong;

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

      String resolvedFacId;
      try {
        Method getIdMethod = faction.getClass().getMethod("getId");
        Object idObj = getIdMethod.invoke(faction);
        resolvedFacId = (idObj != null) ? idObj.toString() : "faction_" + playerId;
      } catch (Exception e) {
        resolvedFacId = "faction_" + playerId;
      }
      final String facId = resolvedFacId;

      return Optional.of(
          new ClaimBoundary() {
            @Override
            public String id() {
              return facId;
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
          "[RTP] Factions integration encountered an error resolving claim boundary. Disabling Factions boundary lookup.",
          t);
      return Optional.empty();
    }
  }
}
