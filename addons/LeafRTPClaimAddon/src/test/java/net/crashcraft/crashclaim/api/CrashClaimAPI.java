package net.crashcraft.crashclaim.api;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.crashcraft.crashclaim.claimobjects.Claim;
import org.bukkit.Location;

/** Test stub of {@code CrashClaimAPI}: {@code getClaim(Location)} returns the claim or null. */
public class CrashClaimAPI {
  private final Function<Location, Claim> lookup;

  public CrashClaimAPI(Function<Location, Claim> lookup) {
    this.lookup = lookup;
  }

  public Claim getClaim(Location location) {
    return lookup.apply(location);
  }

  public CompletableFuture<Claim> getClaimAsync(Location location) {
    return CompletableFuture.completedFuture(getClaim(location));
  }
}
