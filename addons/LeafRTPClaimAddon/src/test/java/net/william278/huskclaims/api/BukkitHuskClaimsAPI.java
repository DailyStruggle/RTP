package net.william278.huskclaims.api;

import net.william278.huskclaims.position.Position;
import org.bukkit.Location;

/** Test stub of HuskClaims' Bukkit API: static {@code getInstance()}, {@code getPosition(Location)}. */
public class BukkitHuskClaimsAPI extends HuskClaimsAPI {
  private static BukkitHuskClaimsAPI instance;

  public static BukkitHuskClaimsAPI getInstance() {
    if (instance == null) {
      instance = new BukkitHuskClaimsAPI();
    }
    return instance;
  }

  public static void setInstance(BukkitHuskClaimsAPI inst) {
    instance = inst;
  }

  public Position getPosition(Location location) {
    return new Position(location);
  }
}
