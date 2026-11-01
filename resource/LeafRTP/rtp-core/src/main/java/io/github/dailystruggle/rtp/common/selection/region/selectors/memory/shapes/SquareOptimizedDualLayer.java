package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.util.Collection;
import java.util.Map;

/**
 * Square shape implementing the continuous spiral-addressed Hilbert key space with
 * hardware-cache segmented secondary tables (ADR-085).
 *
 * <p>Registered in ShapeFactory as {@code "SQUARE_OPTIMIZED_DUAL_LAYER"}.
 */
public class SquareOptimizedDualLayer extends AbstractDualLayerShape {

  public SquareOptimizedDualLayer() {
    this("SQUARE_OPTIMIZED_DUAL_LAYER");
  }

  public SquareOptimizedDualLayer(String name) {
    super(name, Square.defaults);
  }

  public SquareOptimizedDualLayer(String name, int pointEdgeChunks) {
    super(name, Square.defaults, pointEdgeChunks);
  }

  @Override
  public Map<String, CommandParameter> getParameters() {
    return Square.subParameters;
  }

  @Override
  public Collection<String> keys() {
    return Square.keys;
  }

  @Override
  public long getRange() {
    int p = getPointEdgeChunks();
    int area = p * p;
    long r = getNumber(GenericMemoryShapeParams.radius, 256L).longValue();
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    if (r <= cr) return 0L;
    long kOuter = Math.max(1L, (r + p - 1) / p);
    long kInner = cr / p;
    if (kOuter <= kInner) return 0L;
    return (4L * kOuter * kOuter - 4L * kInner * kInner) * area;
  }

  @Override
  public long xzToLocation(long cx, long cz) {
    int p = getPointEdgeChunks();
    int area = p * p;
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    long r = getNumber(GenericMemoryShapeParams.radius, 256L).longValue();
    long cenX = getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
    long cenZ = getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();

    long relX = cx - cenX;
    long relZ = cz - cenZ;

    long chebyshev = Math.max(Math.abs(relX), Math.abs(relZ));
    if (chebyshev < cr) return -1L;
    if (!expand() && chebyshev > r) return -1L;

    long px = Math.floorDiv(relX, p);
    long pz = Math.floorDiv(relZ, p);

    long kX = (px >= 0) ? (px + 1L) : -px;
    long kZ = (pz >= 0) ? (pz + 1L) : -pz;
    long K = Math.max(kX, kZ);

    long kInner = cr / p;
    long kOuter = Math.max(1L, (r + p - 1) / p);

    if (K <= kInner) return -1L;
    if (!expand() && K > kOuter) return -1L;

    long side;
    long sideStep;
    if (px == K - 1L && pz > -K) {
      side = 0L;
      sideStep = pz + (K - 1L);
    } else if (pz == K - 1L && px < K - 1L) {
      side = 1L;
      sideStep = (K - 2L) - px;
    } else if (px == -K && pz < K - 1L) {
      side = 2L;
      sideStep = (K - 2L) - pz;
    } else {
      side = 3L;
      sideStep = px - (-K + 1L);
    }

    long fullMacroIdx = 4L * (K - 1L) * (K - 1L) + side * (2L * K - 1L) + sideStep;
    long macroLoc = fullMacroIdx - 4L * kInner * kInner;
    if (macroLoc < 0) return -1L;

    int lx = (int) (relX - px * p);
    int lz = (int) (relZ - pz * p);

    int orientation = orientationFor(px, pz);
    long h = xyToHilbert(lx, lz, p, orientation);
    return macroLoc * area + h;
  }

  @Override
  public void locationToXZ(long loc, MutableRTPCoords output) {
    if (loc < 0) {
      if (output != null) output.setXZ(0, 0);
      return;
    }

    int p = getPointEdgeChunks();
    int area = p * p;
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    long cenX = getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
    long cenZ = getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();

    long kInner = cr / p;
    long macroLoc = loc / area;
    long h = loc % area;

    long fullMacroIdx = macroLoc + 4L * kInner * kInner;

    long target = fullMacroIdx / 4L;
    long K = (long) Math.floor(Math.sqrt(target)) + 1L;
    while ((K - 1L) * (K - 1L) > target) K--;
    while (K * K <= target) K++;

    long ringBase = 4L * (K - 1L) * (K - 1L);
    long step = fullMacroIdx - ringBase;
    long sideLen = 2L * K - 1L;
    long side = step / sideLen;
    long sideStep = step % sideLen;

    long px, pz;
    if (side == 0) {
      px = K - 1L;
      pz = -(K - 1L) + sideStep;
    } else if (side == 1) {
      pz = K - 1L;
      px = (K - 2L) - sideStep;
    } else if (side == 2) {
      px = -K;
      pz = (K - 2L) - sideStep;
    } else {
      pz = -K;
      px = (-K + 1L) + sideStep;
    }

    int orientation = orientationFor(px, pz);
    int[] local = hilbertToXY((int) h, p, orientation);

    long cx = cenX + px * p + local[0];
    long cz = cenZ + pz * p + local[1];

    if (output != null) {
      output.setXZ((int) cx, (int) cz);
    }
  }

  @Override
  public boolean contains(int x, int z) {
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    long r = getNumber(GenericMemoryShapeParams.radius, 256L).longValue();
    long cenX = getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
    long cenZ = getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();

    long relX = Math.abs(x - cenX);
    long relZ = Math.abs(z - cenZ);
    long chebyshev = Math.max(relX, relZ);
    if (chebyshev < cr) return false;
    if (!expand() && chebyshev > r) return false;

    if (expand()) {
      long loc = xzToLocation(x, z);
      if (loc < 0L || loc >= getEffectiveRange()) return false;
    }

    return true;
  }

  @Override
  protected long postProcess(long location) {
    if (location < 0) return location;
    MutableRTPCoords coords = new MutableRTPCoords(0, 0);
    locationToXZ(location, coords);
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    long r = getNumber(GenericMemoryShapeParams.radius, 256L).longValue();
    long cenX = getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
    long cenZ = getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();
    long relX = Math.abs(coords.x - cenX);
    long relZ = Math.abs(coords.z - cenZ);
    long chebyshev = Math.max(relX, relZ);
    if (chebyshev < cr || (!expand() && chebyshev > r)) {
      return -1L;
    }
    return location;
  }
}
