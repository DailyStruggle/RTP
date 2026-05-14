package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.commandsapi.common.parameters.IntegerParameter;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;

import java.util.logging.Level;

// NOTE: parent Circle's static initializer already seeds radius2 in its
// `defaults` map (and Square does the same for its own), so Ellipse does not
// need to mutate the shared defaults map at class-load time.

/**
 * Ellipse shape for region selection.
 *
 * <p>Derives from {@link Circle}. Adds a second axis radius
 * ({@link GenericMemoryShapeParams#radius2}); bounds are checked against a
 * circle of the wider of the two radii. All other selection math reuses the
 * inherited Circle implementation, with the effective radius taken as the
 * wider of {@code radius} and {@code radius2}.
 */
public class Ellipse extends Circle {

  static {
    try {
      // Add a curated tab-completion entry for /rtp shape:ellipse radius2 <TAB>.
      subParameters.putIfAbsent("radius2", new IntegerParameter(
          "rtp.params", "second axis radius of region", (sender, s) -> true, 64, 128, 256, 512, 1024));
    } catch (Exception e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
    }
  }

  /**
   * Default constructor.
   *
   * @throws IllegalArgumentException if default parameters are invalid
   */
  public Ellipse() throws IllegalArgumentException {
    super("ELLIPSE");
  }

  /**
   * Constructor with a custom name (for cloning / config-derived shapes).
   *
   * @param newName the name of the shape
   * @throws IllegalArgumentException if parameters are invalid
   */
  public Ellipse(String newName) throws IllegalArgumentException {
    super(newName);
  }

  /**
   * @return the wider of {@code radius} and {@code radius2}
   */
  private long effectiveRadius() {
    long r1 = getNumber(GenericMemoryShapeParams.radius, 256L).longValue();
    long r2 = getNumber(GenericMemoryShapeParams.radius2, 256L).longValue();
    return Math.max(r1, r2);
  }

  @Override
  public long getRange() {
    long radius = effectiveRadius();
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    return (long) ((radius - cr) * (radius + cr) * Math.PI);
  }

  @Override
  public boolean contains(int x, int z) {
    long cx = getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
    long cz = getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    long radius = effectiveRadius();

    long dx = x - cx;
    long dz = z - cz;
    long distSq = dx * dx + dz * dz;

    return distSq >= cr * cr && distSq < radius * radius;
  }

  @Override
  public long xzToLocation(long x, long z) {
    // Reuse the parent's polar mapping; the parent only depends on centerRadius
    // and the center coordinates, not on radius, so the inherited behavior is
    // correct for the wider bounding circle as well.
    return super.xzToLocation(x, z);
  }

  @Override
  public long xzToLocation(MutableRTPCoords coords) {
    return super.xzToLocation(coords);
  }
}
