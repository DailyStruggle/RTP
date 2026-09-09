package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.mapsapi.model.CompositeRegionModel;
import io.github.dailystruggle.mapsapi.render.CompositeRegionRenderer;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.visualization.CompositeRegionModelBuilder;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolver for {@link ChartSpec.Kind#REGION_COMPOSITE} (ADR-089).
 * Produces a composite spatial visualization carrying desaturated biomes,
 * red hazard wash, candidate markers, and queue health bars.
 */
public final class RegionCompositeResolver implements ChartSpecResolver {

  private static final int BUFFER_WIDTH = 128;
  private static final int BUFFER_HEIGHT = 128;

  @Override
  public Resolution resolve(ChartSpec spec) throws UnresolvableChartSpecException {
    if (spec == null) {
      throw new UnresolvableChartSpecException("spec shall not be null");
    }
    if (spec.kind() != ChartSpec.Kind.REGION_COMPOSITE) {
      throw new UnresolvableChartSpecException(
          "RegionCompositeResolver only handles REGION_COMPOSITE, got " + spec.kind());
    }

    Region region;
    try {
      region = RTP.selectionAPI.getRegionOrDefault(spec.regionName());
    } catch (RuntimeException e) {
      throw new UnresolvableChartSpecException(
          "no region resolved for '" + spec.regionName() + "'", e);
    }
    if (region == null) {
      throw new UnresolvableChartSpecException(
          "no region resolved for '" + spec.regionName() + "'");
    }
    if (!(region.shape instanceof MemoryShape<?>)) {
      throw new UnresolvableChartSpecException(
          "region '" + region.name + "' shape is not a MemoryShape");
    }

    // Pull queue state snapshots from region.queueManager
    int l1Count = 0;
    int l2Count = 0;
    int l3Count = 0;
    int maxCap = 20;

    if (region.queueManager != null) {
      if (region.queueManager.keptLocations != null) {
        l1Count = region.queueManager.keptLocations.size();
      }
      if (region.queueManager.unkeptLocations != null) {
        l2Count = region.queueManager.unkeptLocations.size();
      }
      if (region.queueManager.backlogLocations != null) {
        l3Count = region.queueManager.backlogLocations.size();
      }
    }
    if (region.getSettings() != null && region.getSettings().cacheCap() > 0) {
      maxCap = (int) region.getSettings().cacheCap();
    }

    CompositeRegionModel.QueueGauge l1Gauge = new CompositeRegionModel.QueueGauge(
        "L1 Hot", l1Count, maxCap, 0x2ECC71
    );

    CompositeRegionModel.QueueGauge l2Gauge = new CompositeRegionModel.QueueGauge(
        "L2 Cold", l2Count, maxCap * 2, 0x3498DB
    );

    CompositeRegionModel.QueueGauge l3Gauge = new CompositeRegionModel.QueueGauge(
        "L3 Backlog", l3Count, maxCap * 8, 0x9B59B6
    );

    List<CompositeRegionModel.Marker> markers = new ArrayList<>();
    List<CompositeRegionModel.VectorLine> lines = new ArrayList<>();

    CompositeRegionModel model = CompositeRegionModelBuilder.build(
        region, BUFFER_WIDTH, BUFFER_HEIGHT, markers, lines, l1Gauge, l2Gauge, l3Gauge
    );

    return Resolution.of(CompositeRegionRenderer.INSTANCE, model);
  }
}
