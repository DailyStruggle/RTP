package com.bekvon.bukkit.residence.protection;

import org.bukkit.Location;

public interface ResidenceManager {
  ClaimedResidence getByLoc(Location loc);
}
