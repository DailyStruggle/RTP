package io.github.dailystruggle.rtp.common.selection.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPChunk;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.model.FailTypes;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.GenericVerticalAdjustorKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.TerrainTestProbe;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.annotations.NotNull;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * REQ-RTP-F-015 / ADR-120: live terrain recheck in {@link PregenTask#evaluateSafety} reads only
 * chunks the server holds (S-005) and rejects through the safety-reject path (S-004).
 */
@DisplayName("REQ-RTP-F-015 PregenTask live terrain recheck (ADR-120)")
class PregenTaskTerrainRecheckTest {

  @TempDir File tempDir;

  private MockRTPServerAccessor accessor;

  /** Floor (feet-Y) by world block X; terrain is uniform along Z. */
  private static final IntUnaryOperator FLAT = wx -> 64;
  /** 45 deg sawtooth hillside: every 16/32-block sample along X differs by >= 16 blocks. */
  private static final IntUnaryOperator HILL = wx -> 64 + Math.floorMod(wx, 128);

  private static final class TerrainChunk extends MockRTPChunk {
    private final IntUnaryOperator floor;
    private final int cx;

    TerrainChunk(int cx, int cz, MockRTPWorld w, IntUnaryOperator floor) {
      super(cx, cz, w);
      this.cx = cx;
      this.floor = floor;
    }

    @Override
    public boolean isSelfContained() {
      return true;
    }

    @Override
    public boolean isAir(int x, int y, int z) {
      return y >= floor.applyAsInt(cx * 16 + x);
    }

    @Override
    public String materialNameAt(int x, int y, int z) {
      return isAir(x, y, z) ? "AIR" : "STONE";
    }

    @Override
    public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
      return true;
    }

    @Override
    public boolean isSafe(int x, int y, int z, io.github.dailystruggle.rtp.api.safety.CompiledUnsafeSet u) {
      return true;
    }

    @Override
    public String getBiome(int x, int y, int z) {
      return "PLAINS";
    }
  }

  /**
   * Candidate chunks come from {@code getOrLoadChunk}; neighbours are resident only where
   * {@link #resident} says so. Records every load request for a non-resident chunk.
   */
  private static final class TerrainWorld extends MockRTPWorld {
    volatile IntUnaryOperator floor = FLAT;
    volatile boolean resident = true;
    final AtomicInteger nonResidentLoads = new AtomicInteger();
    final Set<Long> candidates = ConcurrentHashMap.newKeySet();
    /** When set, probes and square jobs answer from the height field, every chunk in one file. */
    volatile boolean probes;
    final AtomicInteger squareJobs = new AtomicInteger();
    final AtomicInteger squareFetches = new AtomicInteger();
    final AtomicInteger plainProbes = new AtomicInteger();
    final AtomicInteger candidateLoads = new AtomicInteger();
    final Set<Long> cachedLookups = ConcurrentHashMap.newKeySet();

    @Override
    public CompletableFuture<io.github.dailystruggle.rtp.api.world.ChunkColumnProbe> probeChunkColumn(
        int cx, int cz, int minY, int maxY) {
      plainProbes.incrementAndGet();
      if (!probes) return CompletableFuture.completedFuture(null);
      IntUnaryOperator f = floor;
      return CompletableFuture.completedFuture(
          new TerrainTestProbe(cx, cz, minY, maxY, (x, z) -> f.applyAsInt(x), (x, z) -> true));
    }

    @Override
    public <T> CompletableFuture<T> probeChunkSquare(int cx, int cz, int minY, int maxY,
        java.util.function.Function<io.github.dailystruggle.rtp.anvil.ChunkSquare, T> job) {
      squareJobs.incrementAndGet();
      if (!probes) return CompletableFuture.completedFuture(null);
      IntUnaryOperator f = floor;
      TerrainTestProbe centre = new TerrainTestProbe(cx, cz, minY, maxY, (x, z) -> f.applyAsInt(x), (x, z) -> true);
      T v = job.apply(centre.square());
      squareFetches.addAndGet(centre.fetches.get());
      return CompletableFuture.completedFuture(v);
    }

    TerrainWorld() {
      super("terrain_world");
    }

    private static long key(int cx, int cz) {
      return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private boolean held(int cx, int cz) {
      return resident || candidates.contains(key(cx, cz));
    }

    @Override
    public boolean isChunkLoaded(int cx, int cz) {
      return held(cx, cz);
    }

    @Override
    public CompletableFuture<Long> getChunkAt(int cx, int cz) {
      if (!held(cx, cz)) nonResidentLoads.incrementAndGet();
      return CompletableFuture.completedFuture(key(cx, cz));
    }

    @Override
    public CompletableFuture<ChunkSet> getChunkAtAsync(int cx, int cz) {
      if (!held(cx, cz)) nonResidentLoads.incrementAndGet();
      return super.getChunkAtAsync(cx, cz);
    }

    @Override
    public RTPChunk<?> getCachedChunk(long key) {
      cachedLookups.add(key);
      return new TerrainChunk((int) (key >> 32), (int) (key & 0xFFFFFFFFL), this, floor);
    }

    @Override
    public CompletableFuture<RTPChunk<?>> getOrLoadChunk(int cx, int cz, String origin) {
      candidateLoads.incrementAndGet();
      candidates.add(key(cx, cz));
      return CompletableFuture.completedFuture(new TerrainChunk(cx, cz, this, floor));
    }
  }

  private TerrainWorld world;
  private LinearAdjustor vert;

  @BeforeEach
  void setUp() {
    accessor = RTPTestSetup.install(tempDir);
    world = new TerrainWorld();
    accessor.addWorld(world);
    @SuppressWarnings("unchecked")
    ConfigParser<LoggingKeys> logging = (ConfigParser<LoggingKeys>) ActiveConfigs.get().getParser(LoggingKeys.class);
    logging.set(LoggingKeys.selection_failure, true);

    // Picks the centre column of the candidate chunk at its floor; terrain is read by the recheck.
    vert = new LinearAdjustor(new ArrayList<>()) {
      @Override
      public RTPCoords adjust(@NotNull RTPChunk input) {
        int wx = input.x() * 16 + 8;
        return new RTPCoords(world.name(), wx, world.floor.applyAsInt(wx), input.z() * 16 + 8);
      }

      @Override
      public RTPCoords adjustFromProbe(
          @NotNull io.github.dailystruggle.rtp.api.world.ChunkColumnProbe probe, @NotNull String w) {
        int wx = probe.chunkX() * 16 + 8;
        return new RTPCoords(w, wx, world.floor.applyAsInt(wx), probe.chunkZ() * 16 + 8);
      }
    };
  }

  @AfterEach
  void tearDown() {
    RTPRuntime.setServerAccessor(null);
    RTPRuntime.setScheduler(null);
    LocationGenerator.setRng(null);
  }

  private PregenState state(long attempts) {
    return state(attempts, Collections.emptySet());
  }

  private PregenState state(long attempts, Set<String> biomes) {
    Square square = new Square();
    square.set(GenericMemoryShapeParams.radius, 100L);
    square.set(GenericMemoryShapeParams.centerRadius, 0L);
    RegionSettings settings = new RegionSettings("terrain_region", world, square, vert, false, false, 10L, 1000L,
        0L, 5, 0.0, 1L, "", false);
    PregenState s = PregenState.build(new Region("terrain_region", settings), biomes);
    s.maxAttempts = attempts;
    return s;
  }

  private GenerationResult run(PregenState s) throws Exception {
    CompletableFuture<GenerationResult> result = new CompletableFuture<>();
    new PregenTask(s, result, 1L).run();
    assertTrue(result.isDone());
    return result.get();
  }

  private static long count(PregenState s, FailTypes t) {
    return s.failMap.get(t).values().stream().mapToLong(Long::longValue).sum();
  }

  @Test
  @DisplayName("hillside with every neighbour resident: rejected under terrain, reschedules to exhaustion")
  void hillsideRejected() throws Exception {
    world.floor = HILL;
    vert.set(GenericVerticalAdjustorKeys.maxHillAngle, 30);
    PregenState s = state(3L);
    GenerationResult r = run(s);
    assertNull(r.coords(), "every candidate sits on the hillside");
    assertEquals(3L, count(s, FailTypes.terrain), "one terrain reject per attempt");
    assertTrue(s.failMap.get(FailTypes.terrain).keySet().stream().anyMatch(k -> k.startsWith("steep=")));
    assertEquals(0L, count(s, FailTypes.safety));
  }

  @Test
  @DisplayName("flat ground with maxHillAngle set is accepted")
  void flatAccepted() throws Exception {
    vert.set(GenericVerticalAdjustorKeys.maxHillAngle, 30);
    PregenState s = state(3L);
    GenerationResult r = run(s);
    assertNotNull(r.coords());
    assertEquals(0L, count(s, FailTypes.terrain));
  }

  @Test
  @DisplayName("maxHillAngle unset: the hillside is accepted and no neighbour is queried")
  void unsetLeavesBehaviour() throws Exception {
    world.floor = HILL;
    PregenState s = state(3L);
    GenerationResult r = run(s);
    assertNotNull(r.coords(), "the check is off");
    assertEquals(0L, count(s, FailTypes.terrain));
  }

  @Test
  @DisplayName("S-005: non-resident neighbours are never loaded; the rule abstains, the in-chunk plane decides")
  void nonResidentNeverLoaded() throws Exception {
    world.floor = HILL;
    world.resident = false;
    vert.set(GenericVerticalAdjustorKeys.maxHillAngle, 30);
    PregenState s = state(2L);
    GenerationResult r = run(s);
    assertEquals(0, world.nonResidentLoads.get(), "a non-resident neighbour was requested");
    // Only the centre chunk is readable: 0 directions measured; 64 of 81 plane points (column 8)
    // stay in the chunk, so the plane fit runs and rejects the 45 deg slope.
    assertNull(r.coords());
    assertEquals(Set.of("steep=0/0 plane"), s.failMap.get(FailTypes.terrain).keySet());
  }

  @Test
  @DisplayName("probe verdict: a hillside is rejected as prefilterTerrain before any chunk load")
  void probeRejectsBeforeLoad() throws Exception {
    world.floor = HILL;
    world.probes = true;
    vert.set(GenericVerticalAdjustorKeys.maxHillAngle, 30);
    PregenState s = state(2L);
    GenerationResult r = run(s);
    assertNull(r.coords());
    assertTrue(world.squareJobs.get() > 0, "the square job ran");
    long probeRejects = count(s, FailTypes.prefilterTerrain);
    assertEquals(world.squareJobs.get(), probeRejects, "every probed candidate rejected before a load");
    // Attempts the ADR-109 governor sends straight to a load are caught by the live recheck.
    assertEquals(world.candidateLoads.get(), count(s, FailTypes.terrain), s.failMap.toString());
    assertTrue(probeRejects > world.candidateLoads.get(), s.failMap.toString());
    // Staged check: the plane fit decides the 45 deg slope first, so the rule never runs.
    assertTrue(s.failMap.get(FailTypes.prefilterTerrain).containsKey("steep=0/8 plane"), s.failMap.toString());
    assertTrue(world.squareFetches.get() <= 3 * world.squareJobs.get(),
        "a plane reject fetches at most the 3 neighbours the +-8 fit touches: " + world.squareFetches.get());
  }

  @Test
  @DisplayName("probe verdict: flat ground passes the probe and the live recheck")
  void probeAcceptsFlat() throws Exception {
    world.probes = true;
    vert.set(GenericVerticalAdjustorKeys.maxHillAngle, 30);
    PregenState s = state(2L);
    GenerationResult r = run(s);
    assertNotNull(r.coords());
    assertEquals(0L, count(s, FailTypes.prefilterTerrain));
    assertEquals(1, world.candidateLoads.get());
    assertEquals(1, world.squareJobs.get());
    assertTrue(world.squareFetches.get() <= 8, "flat accept reads the 3x3 only: " + world.squareFetches.get());
  }

  @Test
  @DisplayName("live recheck reads only the 3x3 around the candidate at every angle (ADR-120)")
  void liveRecheckReadsInnerSquareOnly() throws Exception {
    for (int angle : new int[] {30, 45, 60}) {
      world.cachedLookups.clear();
      world.candidates.clear();
      vert.set(GenericVerticalAdjustorKeys.maxHillAngle, angle);
      GenerationResult r = run(state(1L));
      assertNotNull(r.coords(), "flat ground at " + angle);
      assertFalse(world.cachedLookups.isEmpty(), "the recheck looked up neighbours at " + angle);
      for (long k : world.cachedLookups) {
        int cx = (int) (k >> 32), cz = (int) (k & 0xFFFFFFFFL);
        assertTrue(world.candidates.stream().anyMatch(c -> Math.abs((int) (c >> 32) - cx) <= 1
                && Math.abs((int) (c & 0xFFFFFFFFL) - cz) <= 1),
            "chunk (" + cx + "," + cz + ") is outside the 3x3 at " + angle);
      }
    }
  }

  @Test
  @DisplayName("maxHillAngle 60: the probe square stays within the 3x3")
  void steepLimitUsesInnerSquare() throws Exception {
    world.probes = true;
    vert.set(GenericVerticalAdjustorKeys.maxHillAngle, 60);
    PregenState s = state(2L);
    assertNotNull(run(s).coords());
    assertTrue(world.squareFetches.get() <= 8, "only 3x3 neighbours: " + world.squareFetches.get());
  }

  @Test
  @DisplayName("prefilter rejects never run the square job")
  void prefilterRejectSkipsSquare() throws Exception {
    world.floor = HILL;
    world.probes = true;
    vert.set(GenericVerticalAdjustorKeys.maxHillAngle, 30);
    PregenState s = state(2L, Set.of("desert"));
    run(s);
    assertTrue(count(s, FailTypes.prefilterBiome) > 0, s.failMap.toString());
    assertEquals(0, world.squareJobs.get());
  }

  @Test
  @DisplayName("maxHillAngle unset: no square job runs")
  void unsetRequestsNoSquare() throws Exception {
    world.probes = true;
    run(state(2L));
    assertEquals(0, world.squareJobs.get());
    assertTrue(world.plainProbes.get() > 0);
  }

  @Test
  @DisplayName("S-005: flat ground with no resident neighbour is accepted (unmeasured is not a failure)")
  void nonResidentFlatAccepted() throws Exception {
    world.resident = false;
    vert.set(GenericVerticalAdjustorKeys.maxHillAngle, 30);
    PregenState s = state(2L);
    GenerationResult r = run(s);
    assertEquals(0, world.nonResidentLoads.get());
    assertNotNull(r.coords());
    assertFalse(count(s, FailTypes.terrain) > 0);
  }
}
