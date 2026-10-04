package io.github.dailystruggle.rtp.claimaddon;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Utility for converting platform-neutral {@link RTPCoords} to Bukkit {@link Location}
 * safely without throwing when Bukkit server is uninitialized or in testing environments.
 */
public final class ClaimLocationResolver {
  private ClaimLocationResolver() {}

  /**
   * Safely converts {@link RTPCoords} to Bukkit {@link Location}.
   *
   * <p>Prefers resolving through {@link RTP#serverAccessor} to avoid direct Bukkit static coupling.
   * If uninitialized or unresolvable, falls back to {@link Bukkit#getServer()} guarded against null.
   *
   * @param coords the coordinates to convert
   * @return Bukkit Location or {@code null} if world/server cannot be resolved
   */
  public static Location toLocation(RTPCoords coords) {
    if (coords == null || coords.worldName() == null) return null;

    // 1. Resolve via RTP.serverAccessor without directly calling Bukkit.getWorld()
    try {
      if (RTP.serverAccessor != null) {
        RTPWorld<?> rtpWorld = RTP.serverAccessor.getRTPWorld(coords.worldName());
        if (rtpWorld != null && rtpWorld.world() instanceof World bw) {
          return new Location(bw, coords.x(), coords.y(), coords.z());
        }
      }
    } catch (Throwable ignored) {
      // Ignored: proceed to fallback resolver when server accessor is unavailable
    }

    // 2. Fallback to Bukkit server if present and initialized
    try {
      if (Bukkit.getServer() != null) {
        World bw = Bukkit.getWorld(coords.worldName());
        if (bw != null) {
          return new Location(bw, coords.x(), coords.y(), coords.z());
        }
      }
    } catch (Throwable ignored) {
      // Ignored: Bukkit server may be mock or uninitialized during testing
    }

    return null;
  }
}
