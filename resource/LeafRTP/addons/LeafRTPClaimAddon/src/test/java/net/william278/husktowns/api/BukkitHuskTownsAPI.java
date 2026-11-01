package net.william278.husktowns.api;

import java.util.Optional;
import java.util.function.Predicate;
import net.william278.husktowns.claim.TownClaim;
import org.bukkit.Location;

/**
 * Test stand-in of {@code BukkitHuskTownsAPI} (husktowns-bukkit 3.1.4): static
 * {@code getInstance()} throwing while unregistered and {@code getClaimAt(Location)}.
 */
public class BukkitHuskTownsAPI extends HuskTownsAPI {
  private final Predicate<Location> claimed;

  public BukkitHuskTownsAPI(Predicate<Location> claimed) {
    this.claimed = claimed;
  }

  public static BukkitHuskTownsAPI getInstance() {
    if (!(instance instanceof BukkitHuskTownsAPI api)) {
      throw new IllegalStateException("HuskTowns API is not registered");
    }
    return api;
  }

  public static void register(BukkitHuskTownsAPI api) {
    instance = api;
  }

  public Optional<TownClaim> getClaimAt(Location location) {
    return claimed.test(location) ? Optional.of(new TownClaim()) : Optional.empty();
  }
}
