package io.github.dailystruggle.rtp.groupaddon;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Resolves a profile's {@code distribution} name into a fully parameterized {@link Shape} used to
 * mask the subspace placement lattice.
 *
 * <p>The registered shape (from RTP's shape factory) is cloned and its parameters completed: the
 * radius is derived from the lattice extent unless the preset overrides it, then any operator
 * {@code shapeParams} are applied. Unknown names fall back to the full square lattice (a {@code null}
 * mask), matching {@code SubspaceShape.selectSafeSlots}'s {@code null}-shape contract.
 */
public final class GroupShapes {

  private GroupShapes() {}

  /** Extracts the case-insensitive shape name from a region-style shape block. */
  private static String shapeName(Map<String, Object> shapeBlock) {
    if (shapeBlock != null) {
      for (Map.Entry<String, Object> e : shapeBlock.entrySet()) {
        if ("name".equalsIgnoreCase(e.getKey()) && e.getValue() != null) {
          return String.valueOf(e.getValue()).trim().toLowerCase(Locale.ROOT);
        }
      }
    }
    return "square";
  }

  /**
   * Resolves and parameterizes the profile's placement shape from its region-style shape block.
   *
   * @param shapeBlock the profile {@code shape} block ({@code name} + shape params); may be empty
   * @param latticeUnits lattice half-extent in placement-distance units (drives the shape radius)
   * @return a fully parameterized {@link Shape} mask, or {@code null} for the full square lattice
   *     (also returned when the name is unknown - the lattice itself is already square)
   */
  @SuppressWarnings("unchecked")
  public static Shape<?> resolve(Map<String, Object> shapeBlock, int latticeUnits) {
    String name = shapeName(shapeBlock);

    // Square == the full lattice; the selector's null mask already yields exactly that, so avoid
    // depending on Shape.contains() coordinate semantics for the common/default case.
    if ("square".equals(name)) return null;

    Factory<Shape<?>> factory;
    try {
      factory = (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
    } catch (Throwable t) {
      return null;
    }
    if (factory == null || !factory.contains(name)) {
      RTP.log(Level.WARNING, "[group] unknown shape '" + name + "'; using square lattice");
      return null;
    }

    Shape<?> shape = (Shape<?>) factory.get(name);
    if (shape == null) return null;

    // The mask's radius is the lattice extent (in selection units), NOT the block's `radius` (which
    // is the subspace footprint in chunks). So seed defaults from the lattice, then layer the block's
    // params EXCEPT radius/name (radius is reserved for the footprint; name is not a shape param).
    Map<String, Object> completed = new LinkedHashMap<>();
    completed.put("radius", (long) Math.max(0, latticeUnits));
    completed.put("centerRadius", 0L);
    completed.put("centerX", 0L);
    completed.put("centerZ", 0L);
    if (shapeBlock != null) {
      for (Map.Entry<String, Object> e : shapeBlock.entrySet()) {
        String k = e.getKey();
        if (k == null || "name".equalsIgnoreCase(k) || "radius".equalsIgnoreCase(k)) continue;
        completed.put(k, e.getValue());
      }
    }
    try {
      shape.setData(completed);
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "[group] failed to parameterize shape '" + name + "'", t);
      return null;
    }
    return shape;
  }
}
