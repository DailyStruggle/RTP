package net.william278.husktowns.api;

import java.util.Optional;
import java.util.function.Predicate;
import net.william278.husktowns.claim.TownClaim;
import org.bukkit.Chunk;
import org.bukkit.Location;

/**
 * Test stub of HuskTowns 3.x {@code BukkitHuskTownsAPI}: static {@code getInstance()} and the
 * Bukkit overloads {@code getClaimAt(Chunk)} / {@code getClaimAt(Location)}, both returning
 * {@code Optional<TownClaim>}.
 */
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

  public Optional<TownClaim> getClaimAt(Chunk chunk) {
    throw new AssertionError("getClaimAt(Chunk) needs a loaded chunk; not expected");
  }

  public Optional<TownClaim> getClaimAt(Location location) {
    return claimPredicate.test(location) ? Optional.of(new TownClaim()) : Optional.empty();
  }
}
