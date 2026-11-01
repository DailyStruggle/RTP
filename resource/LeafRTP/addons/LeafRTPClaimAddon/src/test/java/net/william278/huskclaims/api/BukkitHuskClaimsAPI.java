package net.william278.huskclaims.api;

import java.util.function.Predicate;
import net.william278.huskclaims.position.Position;
import org.bukkit.Location;

/**
 * Test stand-in of {@code BukkitHuskClaimsAPI} (huskclaims-bukkit 1.5.10): static
 * {@code getInstance()} throwing while unregistered, {@code getPosition(Location)} and
 * {@code isClaimAt(Position)}.
 */
public class BukkitHuskClaimsAPI extends HuskClaimsAPI {
  private final Predicate<Location> claimed;

  public BukkitHuskClaimsAPI(Predicate<Location> claimed) {
    this.claimed = claimed;
  }

  public static BukkitHuskClaimsAPI getInstance() {
    if (!(instance instanceof BukkitHuskClaimsAPI api)) {
      throw new IllegalStateException("HuskClaims API is not registered");
    }
    return api;
  }

  public static void register(BukkitHuskClaimsAPI api) {
    instance = api;
  }

  public Position getPosition(Location location) {
    return new Position(location);
  }

  public boolean isClaimAt(Position position) {
    return claimed.test(position.location());
  }
}
