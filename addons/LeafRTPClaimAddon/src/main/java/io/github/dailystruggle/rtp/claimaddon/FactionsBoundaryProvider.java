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
          // Ignored: neither getAllClaims nor getClaims method exists on faction
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

      return Optional.of(buildBoundary(facId, worldName, chunkKeys, centroidX, centroidZ, finalMinX, finalMinZ, finalMaxX, finalMaxZ));
    } catch (Throwable t) {
      if (ClaimCheckFailure.isIncompatibility(t)) {
        exists = false;
        RTP.log(
            Level.SEVERE,
            "[RTP] Factions API is missing or incompatible. Disabling Factions boundary lookup.",
            t);
      } else {
        RTP.log(
            Level.WARNING,
            "[RTP] Factions integration encountered an error resolving claim boundary for player " + playerId + ".",
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
    if (Bukkit.getServer() == null || Bukkit.getPluginManager() == null || !Bukkit.getPluginManager().isPluginEnabled("Factions")) {
      return Optional.empty();
    }

    try {
      int cx = x >> 4;
      int cz = z >> 4;

      Object faction = null;
      try {
        Class<?> boardClass = Class.forName("com.massivecraft.factions.Board");
        Object board = boardClass.getMethod("getInstance").invoke(null);
        if (board != null) {
          Class<?> fLocationClass = Class.forName("com.massivecraft.factions.FLocation");
          Object fLocation = fLocationClass.getConstructor(String.class, int.class, int.class).newInstance(worldName, cx, cz);
          faction = boardClass.getMethod("getFactionAt", fLocationClass).invoke(board, fLocation);
        }
      } catch (Throwable ignored) {
        // Ignored: Board or FLocation reflection lookup failed
      }

      if (faction == null) {
        return AdaptiveClaimProber.probeBoundary(worldName, x, z, SaberFactionsChecker::isInClaim);
      }

      Method isWilderness = faction.getClass().getMethod("isWilderness");
      if (Boolean.TRUE.equals(isWilderness.invoke(faction))) {
        return Optional.empty();
      }

      try {
        Method isSafeZone = faction.getClass().getMethod("isSafeZone");
        if (Boolean.TRUE.equals(isSafeZone.invoke(faction))) {
          return Optional.empty();
        }
      } catch (NoSuchMethodException ignored) {
      }
      try {
        Method isWarZone = faction.getClass().getMethod("isWarZone");
        if (Boolean.TRUE.equals(isWarZone.invoke(faction))) {
          return Optional.empty();
        }
      } catch (NoSuchMethodException ignored) {
      }

      Method getAllClaims = null;
      try {
        getAllClaims = faction.getClass().getMethod("getAllClaims");
      } catch (NoSuchMethodException e) {
        try {
          getAllClaims = faction.getClass().getMethod("getClaims");
        } catch (NoSuchMethodException ignored) {
          // Ignored: neither getAllClaims nor getClaims method exists on faction
        }
      }

      if (getAllClaims == null) {
        return AdaptiveClaimProber.probeBoundary(worldName, x, z, SaberFactionsChecker::isInClaim);
      }

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
        if (count >= 1024) break;
        Method getWorldNameMethod = fLoc.getClass().getMethod("getWorldName");
        Object wNameObj = getWorldNameMethod.invoke(fLoc);
        if (wNameObj == null || !worldName.equalsIgnoreCase(wNameObj.toString())) {
          continue;
        }

        Method getXMethod = fLoc.getClass().getMethod("getX");
        Method getZMethod = fLoc.getClass().getMethod("getZ");
        long cxLong = ((Number) getXMethod.invoke(fLoc)).longValue();
        long czLong = ((Number) getZMethod.invoke(fLoc)).longValue();
        int chunkX = (int) cxLong;
        int chunkZ = (int) czLong;

        if (chunkX < minChunkX) minChunkX = chunkX;
        if (chunkX > maxChunkX) maxChunkX = chunkX;
        if (chunkZ < minChunkZ) minChunkZ = chunkZ;
        if (chunkZ > maxChunkZ) maxChunkZ = chunkZ;

        sumX += ((long) chunkX << 4) + 8;
        sumZ += ((long) chunkZ << 4) + 8;
        chunkKeys.add((((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL));
        count++;
      }

      if (count == 0) {
        return Optional.empty();
      }

      int centroidX = (int) (sumX / count);
      int centroidZ = (int) (sumZ / count);

      String resolvedFacId;
      try {
        Method getIdMethod = faction.getClass().getMethod("getId");
        Object idObj = getIdMethod.invoke(faction);
        resolvedFacId = (idObj != null) ? idObj.toString() : "faction_" + x + "_" + z;
      } catch (Exception e) {
        resolvedFacId = "faction_" + x + "_" + z;
      }

      return Optional.of(buildBoundary(resolvedFacId, worldName, chunkKeys, centroidX, centroidZ, minChunkX, minChunkZ, maxChunkX, maxChunkZ));
    } catch (Throwable t) {
      if (ClaimCheckFailure.isIncompatibility(t)) {
        exists = false;
        RTP.log(
            Level.SEVERE,
            "[RTP] Factions API is missing or incompatible. Disabling Factions boundary lookup.",
            t);
      } else {
        RTP.log(
            Level.WARNING,
            "[RTP] Factions integration encountered an error resolving claim boundary at (" + x + "," + z + ").",
            t);
      }
      return Optional.empty();
    }
  }

  private static ClaimBoundary buildBoundary(
      String facId,
      String worldName,
      Set<Long> chunkKeys,
      int centroidX,
      int centroidZ,
      int minChunkX,
      int minChunkZ,
      int maxChunkX,
      int maxChunkZ) {
    return new ClaimBoundary() {
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
