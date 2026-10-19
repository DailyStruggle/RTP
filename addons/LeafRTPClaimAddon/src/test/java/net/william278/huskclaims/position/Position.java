package net.william278.huskclaims.position;

import org.bukkit.Location;

/** Test stub of the HuskClaims position model; keeps the adapted Bukkit location for lookups. */
public final class Position {
  private final Location location;

  public Position(Location location) {
    this.location = location;
  }

  public Location bukkit() {
    return location;
  }
}
