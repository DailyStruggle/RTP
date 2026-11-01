package net.william278.huskclaims.position;

import org.bukkit.Location;

/** Test stand-in of the HuskClaims {@code Position}: wraps the adapted Bukkit location. */
public class Position {
  private final Location location;

  public Position(Location location) {
    this.location = location;
  }

  public Location location() {
    return location;
  }
}
