package br.net.fabiozumbi12.RedProtect.Bukkit.API;

import br.net.fabiozumbi12.RedProtect.Bukkit.Region;
import java.util.function.Function;
import org.bukkit.Location;

/** Test stand-in of the RedProtect {@code RedProtectAPI}: {@code getRegion(Location)}, null when unprotected. */
public class RedProtectAPI {
  private final Function<Location, Region> lookup;

  public RedProtectAPI(Function<Location, Region> lookup) {
    this.lookup = lookup;
  }

  public Region getRegion(Location location) {
    return lookup.apply(location);
  }
}
