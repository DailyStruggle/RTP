package net.william278.huskclaims.api;

import org.bukkit.Location;
import java.util.function.Predicate;

public class BukkitHuskClaimsAPI {
  private static BukkitHuskClaimsAPI instance;
  private Predicate<Location> claimPredicate = loc -> false;

  public static BukkitHuskClaimsAPI getInstance() {
    if (instance == null) {
      instance = new BukkitHuskClaimsAPI();
    }
    return instance;
  }

  public static void setInstance(BukkitHuskClaimsAPI inst) {
    instance = inst;
  }

  public void setClaimPredicate(Predicate<Location> predicate) {
    this.claimPredicate = predicate != null ? predicate : loc -> false;
  }

  public Object getPosition(Location location) {
    return location;
  }

  public Boolean isClaimAt(Object position) {
    if (position instanceof Location loc) {
      return claimPredicate.test(loc);
    }
    return false;
  }
}
