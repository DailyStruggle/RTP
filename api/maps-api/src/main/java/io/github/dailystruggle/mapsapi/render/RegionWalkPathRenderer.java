package io.github.dailystruggle.mapsapi.render;

import io.github.dailystruggle.mapsapi.MapCanvas;
import io.github.dailystruggle.mapsapi.model.RegionWalkPath;
import java.util.List;

/**
 * Stateless {@link ChartRenderer} for {@link RegionWalkPath} (ADR-089).
 *
 * <p>Renders:
 * <ul>
 *   <li><b>Background domain:</b> Shaded to differentiate inside-bounds from outside-bounds with
 *       optional biome and hazard wash mirroring the comprehensive view.</li>
 *   <li><b>Walk path line &amp; steps:</b> Continuous high-resolution selection trajectory shaded
 *       according to validity, bounds, and progression:
 *     <ul>
 *       <li><b>VALID:</b> Smooth progression gradient (cool cyan/blue at start to emerald/lime/gold at end).</li>
 *       <li><b>HAZARD:</b> High-contrast crimson / hazard red.</li>
 *       <li><b>OUT_OF_BOUNDS:</b> Dim charcoal / gray stride.</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>Stateless singleton pattern per REQ-RTP-MAP-002; zero chunk I/O (S-005).
 */
public final class RegionWalkPathRenderer implements ChartRenderer<RegionWalkPath> {

  public static final RegionWalkPathRenderer INSTANCE = new RegionWalkPathRenderer();

  private static final int COLOR_OUTSIDE = 0xFF141414;       // Void outside region bounds
  private static final int COLOR_INSIDE = 0xFF1B2228;        // Default active region domain background
  private static final int COLOR_BOUNDARY = 0xFF30363D;      // Boundary border edge
  private static final int COLOR_HAZARD = 0xFFE74C3C;        // Hazard step (bad location)
  private static final int COLOR_OUT_BOUNDS = 0xFF484F58;    // Outside shape bounds step
  private static final float DESATURATION_FACTOR = 0.45f;
  private static final float HAZARD_BLEND_FACTOR = 0.45f;

  @Override
  public void render(MapCanvas canvas, RegionWalkPath model) {
    if (canvas == null) {
      throw new IllegalArgumentException("canvas shall not be null");
    }
    if (model == null) {
      throw new IllegalArgumentException("model shall not be null");
    }

    final int cw = canvas.width();
    final int ch = canvas.height();
    final int mw = model.width();
    final int mh = model.height();

    final boolean[] insideDomain = model.insideDomain();
    final int[] biomeRgb = model.biomeRgb();
    final boolean[] hazardMask = model.hazardMask();
    final boolean hasBackdropData = (biomeRgb != null && biomeRgb.length == insideDomain.length);

    // 1. Render Domain Backdrop
    for (int cy = 0; cy < ch; cy++) {
      int my = (int) ((long) cy * mh / ch);
      if (my >= mh) my = mh - 1;
      int mRow = my * mw;

      for (int cx = 0; cx < cw; cx++) {
        int mx = (int) ((long) cx * mw / cw);
        if (mx >= mw) mx = mw - 1;
        int mIdx = mRow + mx;

        if (insideDomain[mIdx]) {
          if (hasBackdropData) {
            int rawBiome = biomeRgb[mIdx];
            int desat = CanvasDrawing.desaturate(rawBiome, DESATURATION_FACTOR);
            if (hazardMask != null && hazardMask.length > mIdx && hazardMask[mIdx]) {
              canvas.setPixelRgb(cx, cy, CanvasDrawing.blend(desat, COLOR_HAZARD, HAZARD_BLEND_FACTOR));
            } else {
              canvas.setPixelRgb(cx, cy, desat);
            }
          } else {
            // Check boundary edge
            boolean isEdge = (mx == 0 || mx == mw - 1 || my == 0 || my == mh - 1
                || (mx > 0 && !insideDomain[mIdx - 1])
                || (mx < mw - 1 && !insideDomain[mIdx + 1])
                || (my > 0 && !insideDomain[mIdx - mw])
                || (my < mh - 1 && !insideDomain[mIdx + mw]));
            canvas.setPixelRgb(cx, cy, isEdge ? COLOR_BOUNDARY : COLOR_INSIDE);
          }
        } else {
          canvas.setPixelRgb(cx, cy, COLOR_OUTSIDE);
        }
      }
    }

    // 2. Render Walk Path Trajectory & Shaded Points
    List<RegionWalkPath.WalkStep> steps = model.steps();
    if (steps.isEmpty()) return;

    final int minX = model.minX();
    final int minZ = model.minZ();
    final long boundW = model.boundW();
    final long boundH = model.boundH();
    final boolean useWorldCoords = (boundW > 0 && boundH > 0 && (model.maxX() != model.minX()));

    // Line segments connecting consecutive walk steps
    int prevX = -1;
    int prevY = -1;
    for (int i = 0; i < steps.size(); i++) {
      RegionWalkPath.WalkStep s = steps.get(i);
      int currX;
      int currY;
      if (useWorldCoords) {
        currX = (int) (((long) s.x() - minX) * cw / boundW);
        currY = (int) (((long) s.y() - minZ) * ch / boundH);
      } else {
        currX = (int) ((long) s.x() * cw / mw);
        currY = (int) ((long) s.y() * ch / mh);
      }

      if (i > 0) {
        int lineColor = resolveStepColor(s);
        CanvasDrawing.drawLine(canvas, prevX, prevY, currX, currY, lineColor);
      }
      prevX = currX;
      prevY = currY;
    }

    // For smaller step counts, or hazard/extreme points, draw high-contrast point markers
    if (steps.size() <= 2000) {
      int dotRadius = Math.max(1, cw / 256);
      for (RegionWalkPath.WalkStep step : steps) {
        int sx;
        int sy;
        if (useWorldCoords) {
          sx = (int) (((long) step.x() - minX) * cw / boundW);
          sy = (int) (((long) step.y() - minZ) * ch / boundH);
        } else {
          sx = (int) ((long) step.x() * cw / mw);
          sy = (int) ((long) step.y() * ch / mh);
        }
        int fillColor = resolveStepColor(step);
        int borderColor = (step.status() == RegionWalkPath.StepStatus.HAZARD) ? 0xFF800000 : 0xFF000000;
        CanvasDrawing.drawOutlinedPoint(canvas, sx, sy, fillColor, borderColor, dotRadius);
      }
    }
  }

  /**
   * Resolves 24-bit RGB color shaded according to validity, bounds, and progression.
   */
  public static int resolveStepColor(RegionWalkPath.WalkStep step) {
    if (step.status() == RegionWalkPath.StepStatus.HAZARD) {
      return COLOR_HAZARD;
    }
    if (step.status() == RegionWalkPath.StepStatus.OUT_OF_BOUNDS) {
      return COLOR_OUT_BOUNDS;
    }

    // VALID: Smooth progression gradient sweeping from Cyan/Blue (0.58f) to Warm Orange/Red (0.0f)
    // matching ComprehensiveRegionImageExporter progressColor()
    float t = Math.clamp(step.progress(), 0.0f, 1.0f);
    float hue = 0.58f - 0.58f * t;
    int rgb = java.awt.Color.HSBtoRGB(hue, 0.85f, 0.95f);
    return rgb & 0xFFFFFF;
  }
}
