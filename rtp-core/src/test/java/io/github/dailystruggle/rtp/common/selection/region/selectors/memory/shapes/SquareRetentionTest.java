package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator.FailTypes;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** REQ-RTP-S-004: a mark inside the addressable domain shall not be silently discarded. */
public class SquareRetentionTest {

  private static Square shape(int cellRadius, int centerRadius) {
    Square s = new Square();
    s.set(GenericMemoryShapeParams.radius, (long) cellRadius);
    s.set(GenericMemoryShapeParams.centerRadius, (long) centerRadius);
    s.setSpatialResolution(1L);
    return s;
  }

  @Test
  @DisplayName("REQ-RTP-S-004: Square retains every in-domain mark it is offered")
  void retainsEveryInDomainMark() {
    for (int cellRadius : new int[] {32, 64, 128, 256}) {
      Square s = shape(cellRadius, 0);
      Set<Long> distinct = new HashSet<>();
      long offered = 0L;
      for (int cx = -cellRadius + 1; cx < cellRadius; cx++) {
        for (int cz = -cellRadius + 1; cz < cellRadius; cz++) {
          long loc = s.xzToLocation(cx, cz);
          s.addBadLocation(loc, FailTypes.biome);
          distinct.add(loc);
          offered++;
        }
      }
      s.flushAndRebuild(1L);
      assertEquals(
          0L,
          s.getOutOfDomainMarkCount(),
          "cellRadius " + cellRadius + ": in-domain marks refused (offered " + offered + ")");
      assertEquals(
          distinct.size(),
          s.getEffectiveBadCount(),
          "cellRadius " + cellRadius + ": retention differs from distinct offered indices");
    }
  }

  @Test
  @DisplayName("REQ-RTP-S-004: marks inside centerRadius are accounted, not silently dropped")
  void centerHoleMarksAreCounted() {
    int cellRadius = 128;
    int centerRadius = 64;
    Square s = shape(cellRadius, centerRadius);
    long inHole = 0L;
    for (int cx = -centerRadius + 1; cx < centerRadius; cx++) {
      for (int cz = -centerRadius + 1; cz < centerRadius; cz++) {
        s.addBadLocation(s.xzToLocation(cx, cz), FailTypes.biome);
        inHole++;
      }
    }
    s.flushAndRebuild(1L);
    assertEquals(inHole, s.getOutOfDomainMarkCount(), "center-hole marks must all be reported");
    assertEquals(0L, s.getEffectiveBadCount(), "center-hole marks must not be retained");
  }
}
