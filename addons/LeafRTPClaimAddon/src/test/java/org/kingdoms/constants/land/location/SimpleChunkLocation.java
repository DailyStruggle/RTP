package org.kingdoms.constants.land.location;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.kingdoms.constants.land.Land;

/** Test stub of KingdomsX {@code SimpleChunkLocation} with its one-argument {@code of} overloads. */
public final class SimpleChunkLocation {
  private final Location location;

  private SimpleChunkLocation(Location location) {
    this.location = location;
  }

  public static SimpleChunkLocation of(Chunk chunk) {
    throw new AssertionError("of(Chunk) needs a loaded chunk; not expected");
  }

  public static SimpleChunkLocation of(Block block) {
    throw new AssertionError("of(Block) needs a loaded chunk; not expected");
  }

  public static SimpleChunkLocation of(Location location) {
    return new SimpleChunkLocation(location);
  }

  public Land getLand() {
    return Land.getLand(location);
  }
}
