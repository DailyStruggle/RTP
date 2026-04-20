package io.github.dailystruggle.rtp.common.selection.worldborder;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Represents a world's border, providing a way to get its shape and check if a
 * location is inside it. This class is a simple container for the functions that
 * define the border's behavior, allowing for different border implementations.
 */
public final class WorldBorder {
  private final Supplier<Shape<?>> getShape;
  private final Function<RTPLocation, Boolean> isInside;

  /**
   * Constructs a new WorldBorder.
   *
   * @param getShape A supplier that provides the {@link Shape} of the world border.
   * @param isInside A function that checks if a given {@link RTPLocation} is inside the border.
   */
  public WorldBorder(Supplier<Shape<?>> getShape, Function<RTPLocation, Boolean> isInside) {
    this.getShape = getShape;
    this.isInside = isInside;
  }

  /**
   * Gets the supplier for the world border's shape.
   *
   * @return The shape supplier.
   */
  public Supplier<Shape<?>> getShape() {
    return getShape;
  }

  /**
   * Gets the function used to check if a location is inside the world border.
   *
   * @return The checking function.
   */
  public Function<RTPLocation, Boolean> isInside() {
    return isInside;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) return true;
    if (obj == null || obj.getClass() != this.getClass()) return false;
    WorldBorder that = (WorldBorder) obj;
    return Objects.equals(this.getShape, that.getShape)
        && Objects.equals(this.isInside, that.isInside);
  }

  @Override
  public int hashCode() {
    return Objects.hash(getShape, isInside);
  }

  @Override
  public String toString() {
    return "WorldBorder[" + "getShape=" + getShape + ", " + "isInside=" + isInside + ']';
  }
}
