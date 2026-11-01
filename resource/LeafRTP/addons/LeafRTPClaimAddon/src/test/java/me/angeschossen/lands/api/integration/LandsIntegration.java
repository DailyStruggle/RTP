package me.angeschossen.lands.api.integration;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

public class LandsIntegration {
  /** Per-chunk claim lookup; when set it overrides {@link #setClaimedResult(boolean)}. */
  public interface ChunkLookup {
    boolean isClaimed(String world, int chunkX, int chunkZ);
  }

  private static LandsIntegration instance;
  private static boolean claimedResult = false;
  private static ChunkLookup lookup;

  public LandsIntegration(Plugin plugin) {
    instance = this;
  }

  public LandsIntegration() {
    instance = this;
  }

  public static void setClaimedResult(boolean claimed) {
    claimedResult = claimed;
  }

  public static void setLookup(ChunkLookup value) {
    lookup = value;
  }

  public static LandsIntegration getInstance() {
    return instance;
  }

  public boolean isClaimed(World world, int x, int z) {
    ChunkLookup l = lookup;
    return l != null ? l.isClaimed(world.getName(), x, z) : claimedResult;
  }
}
