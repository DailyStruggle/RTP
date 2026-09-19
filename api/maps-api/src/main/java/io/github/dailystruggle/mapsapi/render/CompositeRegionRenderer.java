package io.github.dailystruggle.mapsapi.render;

import io.github.dailystruggle.mapsapi.MapCanvas;
import io.github.dailystruggle.mapsapi.model.CompositeRegionModel;

/**
 * Stateless {@link ChartRenderer} for {@link CompositeRegionModel} (ADR-089).
 *
 * <p>Composes:
 * <ol>
 *   <li><b>Layer 1 (Biome Backdrop):</b> Desaturated biome pixels (~45% desaturation) so
 *       geographic contours are visible without visually competing with overlays.
 *   <li><b>Layer 2 (Hazard Wash):</b> Translucent red wash blended over unsafe chunks
 *       (oceans, lava, void, claims) while preserving safe land.
 *   <li><b>Layer 3 (Trajectory Lines):</b> Bresenham vector lines connecting consecutive arrivals.
 *   <li><b>Layer 4 (Candidate & Arrival Points):</b> High-contrast outlined markers
 *       (emerald for L1, cyan for L2, purple for L3, gold for arrivals).
 *   <li><b>Layer 5 (Queue Capacity Health Bars):</b> Bottom panel health bars matching the
 *       queue dot colors.
 * </ol>
 *
 * <p>Stateless singleton pattern per REQ-RTP-MAP-002; zero chunk I/O (S-005).
 */
public final class CompositeRegionRenderer implements ChartRenderer<CompositeRegionModel> {

  public static final CompositeRegionRenderer INSTANCE = new CompositeRegionRenderer();

  private static final int COLOR_OUTSIDE = 0xFF141414; // Dark backdrop
  private static final int COLOR_UNSAMPLED = 0xFF222222;
  private static final int COLOR_HAZARD_TINT = 0xFFE74C3C; // Red hazard wash
  private static final float DESATURATION_FACTOR = 0.50f; // 50% desaturated
  private static final float HAZARD_BLEND_FACTOR = 0.45f; // 45% red tint

  @Override
  public void render(MapCanvas canvas, CompositeRegionModel model) {
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

    final int[] biomeRgb = model.biomeRgb();
    final boolean[] hazardMask = model.hazardMask();
    final boolean[] insideDomain = model.insideDomain();

    // Determine bottom margin for gauge bars (reserve 16-24 pixels at the bottom)
    int gaugeBarHeight = Math.max(3, ch / 40);
    int gaugeSectionHeight = (gaugeBarHeight + 2) * 3 + 4;
    int mapRenderHeight = Math.max(10, ch - gaugeSectionHeight);

    // 1. Render Layer 1 & 2: Desaturated Biomes + Red Hazard Wash
    for (int cy = 0; cy < mapRenderHeight; cy++) {
      int my = (int) ((long) cy * mh / mapRenderHeight);
      if (my >= mh) my = mh - 1;
      int mRow = my * mw;

      for (int cx = 0; cx < cw; cx++) {
        int mx = (int) ((long) cx * mw / cw);
        if (mx >= mw) mx = mw - 1;
        int mIdx = mRow + mx;

        if (!insideDomain[mIdx]) {
          canvas.setPixelRgb(cx, cy, COLOR_OUTSIDE);
          continue;
        }

        int rawBiome = biomeRgb[mIdx];
        if ((rawBiome & 0xFFFFFF) == 0) {
          rawBiome = COLOR_UNSAMPLED;
        }

        // Apply desaturation
        int desat = CanvasDrawing.desaturate(rawBiome, DESATURATION_FACTOR);

        // Apply hazard red tint if marked unsafe
        if (hazardMask[mIdx]) {
          int hazardColor = CanvasDrawing.blend(desat, COLOR_HAZARD_TINT, HAZARD_BLEND_FACTOR);
          canvas.setPixelRgb(cx, cy, hazardColor);
        } else {
          canvas.setPixelRgb(cx, cy, desat);
        }
      }
    }

    // 2. Render Layer 3: Trajectory Lines
    for (CompositeRegionModel.VectorLine line : model.trajectoryLines()) {
      int x0 = (int) ((long) line.x0() * cw / mw);
      int y0 = (int) ((long) line.y0() * mapRenderHeight / mh);
      int x1 = (int) ((long) line.x1() * cw / mw);
      int y1 = (int) ((long) line.y1() * mapRenderHeight / mh);
      CanvasDrawing.drawLine(canvas, x0, y0, x1, y1, line.rgb());
    }

    // 3. Render Layer 4: High-Contrast Outlined Point Markers
    int pointRadius = Math.max(1, cw / 128); // 1px for 128x128, scales up on larger images
    for (CompositeRegionModel.Marker marker : model.markers()) {
      int cx = (int) ((long) marker.x() * cw / mw);
      int cy = (int) ((long) marker.y() * mapRenderHeight / mh);
      int r = marker.radius() > 0 ? marker.radius() : pointRadius;
      CanvasDrawing.drawOutlinedPoint(canvas, cx, cy, marker.fillRgb(), marker.borderRgb(), r);
    }

    // 4. Render Layer 5: Queue Capacity Health Bars along the bottom panel
    int barY = mapRenderHeight + 2;
    int barWidth = Math.max(10, cw - 8);
    int barX = 4;
    int emptyColor = 0xFF2A2A2A;
    int borderColor = 0xFF444444;

    // L1 Hot Queue Bar (Chunky)
    CompositeRegionModel.QueueGauge l1 = model.l1Gauge();
    CanvasDrawing.drawGaugeBar(canvas, barX, barY, barWidth, gaugeBarHeight + 2,
        l1.fraction(), l1.fillRgb(), emptyColor, borderColor);
    barY += gaugeBarHeight + 4;

    // L2 Cold Queue Bar (Mid-weight)
    CompositeRegionModel.QueueGauge l2 = model.l2Gauge();
    CanvasDrawing.drawGaugeBar(canvas, barX, barY, barWidth, gaugeBarHeight + 1,
        l2.fraction(), l2.fillRgb(), emptyColor, borderColor);
    barY += gaugeBarHeight + 3;

    // L3 Backlog Queue Bar (Fine)
    CompositeRegionModel.QueueGauge l3 = model.l3Gauge();
    CanvasDrawing.drawGaugeBar(canvas, barX, barY, barWidth, Math.max(2, gaugeBarHeight),
        l3.fraction(), l3.fillRgb(), emptyColor, borderColor);
  }
}
