package com.massivecraft.factions;

import org.bukkit.Location;

/** Test stub of FactionsUUID / SaberFactions {@code FLocation} (world name and chunk coordinates). */
public class FLocation {
  private final String worldName;
  private final int x;
  private final int z;

  public FLocation(String worldName, int x, int z) {
    this.worldName = worldName;
    this.x = x;
    this.z = z;
  }

  public FLocation(Location location) {
    this(location.getWorld().getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
  }

  public String getWorldName() {
    return worldName;
  }

  public long getX() {
    return x;
  }

  public long getZ() {
    return z;
  }
}
