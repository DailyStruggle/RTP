package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.selection.region.BacklogLocationBuffer;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class BacklogStrategyToggleTest {

  @Test
  @DisplayName("Default strategy FLAT_STRIDE fills L3 via shape.select() and drains contiguous FIFO")
  public void testFlatStrideStrategy() {
    SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer("FLAT_STRIDE_TEST", 32);
    shape.set(GenericMemoryShapeParams.radius, 256L);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    shape.set(GenericMemoryShapeParams.centerX, 0L);
    shape.set(GenericMemoryShapeParams.centerZ, 0L);
    shape.set(GenericMemoryShapeParams.mode, "ACCUMULATE");

    BacklogLocationBuffer buffer = new BacklogLocationBuffer(100);

    // Flat stride filling: calls shape.select() directly
    for (int i = 0; i < 20; i++) {
      int[] sel = shape.select();
      assertNotNull(sel);
      io.github.dailystruggle.rtp.api.world.RTPCoords coords =
          new io.github.dailystruggle.rtp.api.world.RTPCoords("world", (sel[0] << 4) + 7, 64, (sel[1] << 4) + 7);
      BacklogLocationBuffer.BacklogEntry entry = buffer.offerUnverified(new RTPLocation(coords, 0L));
      assertNotNull(entry);
      entry.setValidity(BacklogLocationBuffer.Validity.VALIDATED);
    }

    // Flat stride draining: contiguous FIFO head
    List<BacklogLocationBuffer.BacklogEntry> drained = buffer.pollContiguousValidatedHead(10);
    assertEquals(10, drained.size());
    assertEquals(10, buffer.size());
  }

  @Test
  @DisplayName("BINNED_AMORTIZED strategy fills L3 via selectL3Candidate() and drains random validated")
  public void testBinnedAmortizedStrategy() {
    SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer("BINNED_TEST", 32);
    shape.set(GenericMemoryShapeParams.radius, 256L);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    shape.set(GenericMemoryShapeParams.centerX, 0L);
    shape.set(GenericMemoryShapeParams.centerZ, 0L);
    shape.set(GenericMemoryShapeParams.mode, "ACCUMULATE");

    BacklogLocationBuffer buffer = new BacklogLocationBuffer(100);

    // Binned filling: calls selectL3Candidate()
    for (int i = 0; i < 20; i++) {
      long cand = shape.selectL3Candidate();
      assertTrue(cand >= 0);
      io.github.dailystruggle.rtp.api.world.MutableRTPCoords mc =
          new io.github.dailystruggle.rtp.api.world.MutableRTPCoords(0, 0);
      shape.locationToXZ(cand, mc);
      io.github.dailystruggle.rtp.api.world.RTPCoords coords =
          new io.github.dailystruggle.rtp.api.world.RTPCoords("world", (mc.x << 4) + 7, 64, (mc.z << 4) + 7);
      BacklogLocationBuffer.BacklogEntry entry = buffer.offerUnverified(new RTPLocation(coords, 0L));
      assertNotNull(entry);
      entry.setValidity(BacklogLocationBuffer.Validity.VALIDATED);
    }

    // Binned draining: pollRandomValidated via slot-nulling
    List<BacklogLocationBuffer.BacklogEntry> drained = buffer.pollRandomValidated(10);
    assertEquals(10, drained.size());
    assertEquals(10, buffer.size());
  }
}
