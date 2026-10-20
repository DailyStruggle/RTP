package com.massivecraft.factions;

import org.bukkit.Location;

/** Test stand-in of the Factions {@code FLocation}: world name plus chunk coordinates of a location. */
public class FLocation {
  private final String worldName;
  private final long x;
  private final long z;

  public FLocation(Location location) {
    this.worldName = location.getWorld().getName();
    this.x = location.getBlockX() >> 4;
    this.z = location.getBlockZ() >> 4;
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
