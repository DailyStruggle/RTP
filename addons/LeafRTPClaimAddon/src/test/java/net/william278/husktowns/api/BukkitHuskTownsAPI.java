package net.william278.husktowns.api;

import org.bukkit.Location;
import java.util.Optional;
import java.util.function.Predicate;

public class BukkitHuskTownsAPI {
  private static BukkitHuskTownsAPI instance;
  private Predicate<Location> claimPredicate = loc -> false;

  public static BukkitHuskTownsAPI getInstance() {
    if (instance == null) {
      instance = new BukkitHuskTownsAPI();
    }
    return instance;
  }

  public static void setInstance(BukkitHuskTownsAPI inst) {
    instance = inst;
  }

  public void setClaimPredicate(Predicate<Location> predicate) {
    this.claimPredicate = predicate != null ? predicate : loc -> false;
  }

  public Optional<?> getClaimAt(Location location) {
    if (claimPredicate.test(location)) {
      return Optional.of("ClaimedTown");
    }
    return Optional.empty();
  }
}
