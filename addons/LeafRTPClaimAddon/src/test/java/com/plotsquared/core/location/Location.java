package com.plotsquared.core.location;

import com.plotsquared.core.plot.Plot;
import com.sk89q.worldedit.math.BlockVector3;

/**
 * Test stub of PlotSquared 7 {@code Location} with the real set of static {@code at} overloads:
 * four take four arguments with a {@code String} first ({@code (String, int, int, int)} and
 * {@code (String, BlockVector3, float, float)}), so only an exact signature lookup is safe.
 */
public final class Location {
  /** What the plot world holds at a column; set by tests. */
  public interface Layout {
    /** {@code null} outside any plot area. */
    Cell at(String world, int x, int z);
  }

  /** Column contents. */
  public record Cell(boolean road, Plot plot) {}

  private static Layout layout = (w, x, z) -> null;

  public static void setLayout(Layout value) {
    layout = value != null ? value : (w, x, z) -> null;
  }

  private final String world;
  private final BlockVector3 position;

  private Location(String world, BlockVector3 position) {
    this.world = world;
    this.position = position;
  }

  public static Location at(String world, BlockVector3 blockVector3, float yaw, float pitch) {
    return new Location(world, blockVector3);
  }

  public static Location at(String world, BlockVector3 blockVector3) {
    return at(world, blockVector3, 0f, 0f);
  }

  public static Location at(String world, int x, int y, int z, float yaw, float pitch) {
    return at(world, BlockVector3.at(x, y, z), yaw, pitch);
  }

  public static Location at(String world, int x, int y, int z) {
    return at(world, BlockVector3.at(x, y, z));
  }

  public static Location at(World<?> world, BlockVector3 blockVector3, float yaw, float pitch) {
    return new Location(world.getName(), blockVector3);
  }

  public static Location at(World<?> world, BlockVector3 blockVector3) {
    return at(world, blockVector3, 0f, 0f);
  }

  public static Location at(World<?> world, int x, int y, int z, float yaw, float pitch) {
    return at(world, BlockVector3.at(x, y, z), yaw, pitch);
  }

  public static Location at(World<?> world, int x, int y, int z) {
    return at(world, BlockVector3.at(x, y, z));
  }

  private Cell cell() {
    return layout.at(world, position.x(), position.z());
  }

  public boolean isPlotArea() {
    return cell() != null;
  }

  public boolean isPlotRoad() {
    Cell cell = cell();
    return cell != null && cell.road();
  }

  public Plot getPlot() {
    Cell cell = cell();
    return cell == null || cell.road() ? null : cell.plot();
  }

  public Plot getOwnedPlot() {
    Plot plot = getPlot();
    return plot != null && plot.hasOwner() ? plot : null;
  }
}
