package com.bekvon.bukkit.residence.protection;

import java.util.function.Function;
import org.bukkit.Location;

/** Test stand-in of the Residence {@code ResidenceManager}: {@code getByLoc(Location)}, null when unclaimed. */
public class ResidenceManager {
  private final Function<Location, ClaimedResidence> lookup;

  public ResidenceManager(Function<Location, ClaimedResidence> lookup) {
    this.lookup = lookup;
  }

  public ClaimedResidence getByLoc(Location location) {
    return lookup.apply(location);
  }
}
