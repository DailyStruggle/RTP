package me.angeschossen.lands.api.integration;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

public class LandsIntegration {
  private static LandsIntegration instance;
  private static boolean claimedResult = false;

  public LandsIntegration(Plugin plugin) {
    instance = this;
  }

  public LandsIntegration() {
    instance = this;
  }

  public static void setClaimedResult(boolean claimed) {
    claimedResult = claimed;
  }

  public static LandsIntegration getInstance() {
    return instance;
  }

  public boolean isClaimed(World world, int x, int z) {
    return claimedResult;
  }
}
