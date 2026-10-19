package net.william278.huskclaims.api;

import java.util.Optional;
import java.util.function.Predicate;
import net.william278.huskclaims.position.Position;
import net.william278.huskclaims.user.OnlineUser;
import org.bukkit.Location;

/**
 * Test stub of the HuskClaims common API. Like the real class it has two one-argument
 * {@code isClaimAt} overloads, {@code (OnlineUser)} and {@code (Position)}, both returning
 * {@code boolean}.
 */
public class HuskClaimsAPI {
  private Predicate<Location> claimPredicate = loc -> false;

  public void setClaimPredicate(Predicate<Location> predicate) {
    this.claimPredicate = predicate != null ? predicate : loc -> false;
  }

  public boolean isClaimAt(OnlineUser user) {
    throw new AssertionError("isClaimAt(OnlineUser) is not a location lookup");
  }

  public boolean isClaimAt(Position position) {
    return claimPredicate.test(position.bukkit());
  }

  public Optional<Object> getClaimAt(OnlineUser user) {
    return Optional.empty();
  }

  public Optional<Object> getClaimAt(Position position) {
    return isClaimAt(position) ? Optional.of("claim") : Optional.empty();
  }
}
