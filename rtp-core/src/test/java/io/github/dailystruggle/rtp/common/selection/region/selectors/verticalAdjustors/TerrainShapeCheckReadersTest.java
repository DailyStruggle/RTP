package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.TerrainShapeCheck.Verdict;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** REQ-RTP-F-015 / ADR-016 / ADR-120: probe, live and resident readers of the terrain square. */
@DisplayName("REQ-RTP-F-015 terrain square readers: probe/live parity and resident-only reads")
class TerrainShapeCheckReadersTest {

  private static final int MIN = 0;
  private static final int MAX = 255;
  private static final double TAN30 = TerrainShapeCheck.tanOf(30);

  private static final IntBinaryOperator[] TERRAINS = {
      (x, z) -> 64,
      (x, z) -> 64 + x / 2,
      (x, z) -> 64 + Math.floorMod(x, 128),
      (x, z) -> (x > 20) ? 80 : 64,
      (x, z) -> 64 + (int) Math.round(20 * Math.sin(x / 9.0) * Math.cos(z / 7.0)),
      (x, z) -> 64 + Math.abs(x - 8) + Math.abs(z - 8),
  };

  private static RTPChunk<?>[] liveGrid(TerrainTestProbe centre, MockRTPWorld w) {
    RTPChunk<?>[] grid = new RTPChunk<?>[25];
    for (int dz = -2; dz <= 2; dz++) {
      for (int dx = -2; dx <= 2; dx++) {
        io.github.dailystruggle.rtp.anvil.ChunkColumnProbe n = centre.neighbour(dx, dz);
        grid[TerrainShapeCheck.gridIndex(dx, dz)] =
            (n == null) ? null : new ProbeBackedMockChunk((TerrainTestProbe) n, w);
      }
    }
    return grid;
  }

  @Test
  @DisplayName("ADR-016 parity: the probe square and the live square give equal verdicts")
  void probeLiveParity() {
    MockRTPWorld w = new MockRTPWorld("parity");
    int checked = 0, rejected = 0;
    for (IntBinaryOperator f : TERRAINS) {
      for (int[] col : new int[][] {{8, 8}, {2, 2}, {12, 12}, {2, 12}}) {
        // Region-local (5, 5): the whole 5x5 square lies in the centre's region file.
        TerrainTestProbe centre = new TerrainTestProbe(5, 5, MIN - 1, MAX, f, (x, z) -> true);
        int y = f.applyAsInt(5 * 16 + col[0], 5 * 16 + col[1]);
        Verdict p = TerrainShapeCheck.evaluate(TerrainShapeCheck.probeSquare(centre, centre.square(), 2), col[0], col[1], y, TAN30,
            MIN, MAX);
        Verdict l = TerrainShapeCheck.evaluate(TerrainShapeCheck.chunkSquare(liveGrid(centre, w)), col[0],
            col[1], y, TAN30, MIN, MAX);
        assertEquals(p, l, "terrain " + checked);
        assertTrue(p.full());
        checked++;
        if (p.reject(TAN30)) rejected++;
      }
    }
    assertTrue(rejected > 0 && rejected < checked, "the cases cover both verdicts: " + rejected + "/" + checked);
  }

  @Test
  @DisplayName("region-file clipping: a probe at a file corner measures 3 directions, and parity still holds")
  void clippedProbeSquare() {
    MockRTPWorld w = new MockRTPWorld("clip");
    IntBinaryOperator hill = (x, z) -> 64 + Math.floorMod(x, 128);
    // Centre at region-local (31, 31) of file r.0.0: neighbours with cx or cz >= 32 are elsewhere.
    TerrainTestProbe centre = new TerrainTestProbe(31, 31, MIN - 1, MAX, hill, (x, z) -> x < 32 && z < 32);
    assertNull(centre.neighbour(1, 0));
    Verdict p = TerrainShapeCheck.evaluate(TerrainShapeCheck.probeSquare(centre, centre.square(), 2), 8, 8, hill.applyAsInt(31 * 16 + 8,
        0), TAN30, MIN, MAX);
    Verdict l = TerrainShapeCheck.evaluate(TerrainShapeCheck.chunkSquare(liveGrid(centre, w)), 8, 8,
        hill.applyAsInt(31 * 16 + 8, 0), TAN30, MIN, MAX);
    assertEquals(3, p.measured());
    assertFalse(p.full());
    assertEquals(p, l);
  }

  @Test
  @DisplayName("S-005: residentSquare uses the safety grid, queries only resident chunks and never loads")
  void residentSquareNeverLoads() {
    AtomicInteger loads = new AtomicInteger();
    AtomicInteger readable = new AtomicInteger();
    MockRTPWorld w = new MockRTPWorld("resident") {
      @Override
      public CompletableFuture<Long> getChunkAt(int cx, int cz) {
        if (!isChunkLoaded(cx, cz)) loads.incrementAndGet();
        return super.getChunkAt(cx, cz);
      }

      @Override
      public CompletableFuture<Long> getChunkIfReadable(int cx, int cz) {
        readable.incrementAndGet();
        return super.getChunkIfReadable(cx, cz);
      }
    };
    // Resident: x >= 10 only (encoded key: cx in the high bits).
    w.isChunkLoadedPredicate = key -> (int) (key >> 32) >= 10;
    RTPChunk<?> c = w.getCachedChunk(((long) 10 << 32) | (10 & 0xFFFFFFFFL));
    RTPChunk<?>[] local = new RTPChunk<?>[9];
    local[4] = c;
    local[(1 + 1) * 3 + (0 + 1)] = new io.github.dailystruggle.rtp.common.mock.MockRTPChunk(11, 10, w);
    RTPChunk<?>[] grid = TerrainShapeCheck.residentSquare(w, 10, 10, local, 3).getNow(null);
    assertNotNull(grid);
    assertSame(local[(1 + 1) * 3 + 1], grid[TerrainShapeCheck.gridIndex(1, 0)], "safety-grid chunk reused");
    assertSame(c, grid[TerrainShapeCheck.gridIndex(0, 0)], "centre comes from the safety grid");
    assertEquals(0, loads.get(), "no load for a non-resident chunk");
    for (int dz = -2; dz <= 2; dz++) {
      for (int dx = -2; dx <= 2; dx++) {
        if (dx == 0 && dz == 0) continue;
        RTPChunk<?> g = grid[TerrainShapeCheck.gridIndex(dx, dz)];
        if (10 + dx < 10) assertNull(g, "not resident: unmeasured (" + dx + "," + dz + ")");
        else assertNotNull(g, "resident: read (" + dx + "," + dz + ")");
      }
    }
    assertEquals(24 - 10 - 1, readable.get(), "lookups only for resident chunks not already in the safety grid");
  }
}
