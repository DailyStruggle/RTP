package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.benchmark.NoiseWorldMask;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.CandidateValidator;
import io.github.dailystruggle.rtp.common.selection.region.GroupPlacementDispatcher;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Diagnostic: Subspace Candidate Validation Drop-off on Realistic Noise Terrain")
class SubspaceNoiseTerrainDiagnosticTest {

  private static final long SEED = 133742L;
  private static final int RADIUS_CHUNKS = 128;
  private static final double USABLE_SHARE = 0.50; // 50% usable land

  @TempDir
  File tempDir;

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(tempDir);
  }

  private static class DummyMemoryShape extends MemoryShape<GenericMemoryShapeParams> {
    private final Set<Long> badChunks = new HashSet<>();

    public DummyMemoryShape() {
      super(GenericMemoryShapeParams.class, "DUMMY", createDefaultData());
    }

    private static EnumMap<GenericMemoryShapeParams, Object> createDefaultData() {
      EnumMap<GenericMemoryShapeParams, Object> data = new EnumMap<>(GenericMemoryShapeParams.class);
      data.put(GenericMemoryShapeParams.mode, "ACCUMULATE");
      data.put(GenericMemoryShapeParams.radius, 1000L);
      data.put(GenericMemoryShapeParams.centerRadius, 0L);
      data.put(GenericMemoryShapeParams.centerX, 0L);
      data.put(GenericMemoryShapeParams.centerZ, 0L);
      data.put(GenericMemoryShapeParams.weight, 1.0);
      data.put(GenericMemoryShapeParams.uniquePlacements, false);
      data.put(GenericMemoryShapeParams.expand, false);
      return data;
    }

    public void markBadChunk(int cx, int cz) {
      badChunks.add(((long) cx << 32) ^ (cz & 0xFFFFFFFFL));
    }

    @Override
    public boolean isKnownBad(int cx, int cz) {
      return badChunks.contains(((long) cx << 32) ^ (cz & 0xFFFFFFFFL));
    }

    @Override
    public boolean isKnownHazard(int cx, int cz) {
      return isKnownBad(cx, cz);
    }

    @Override public long getRange() { return 1000; }
    @Override public long xzToLocation(long x, long z) { return ((x << 32) ^ z); }
    @Override public long xzToLocation(io.github.dailystruggle.rtp.api.world.MutableRTPCoords coords) { return ((long) coords.x << 32) ^ coords.z; }
    @Override public int[] locationToXZ(long location) { return new int[]{0, 0}; }
    @Override public void locationToXZ(long location, io.github.dailystruggle.rtp.api.world.MutableRTPCoords output) {}
    @Override public Map getParameters() { return null; }
    @Override public java.util.Collection<String> keys() { return Collections.emptyList(); }
    @Override public int[] select() { return new int[]{0, 0}; }
    @Override public long rand() { return 0; }
    @Override public boolean contains(int x, int z) { return true; }
  }

  private Region createDummyRegion(DummyMemoryShape shape, MockRTPWorld world) {
    LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
    RegionSettings settings = new RegionSettings(
        "test",
        world,
        shape,
        vert,
        false,
        false,
        10L,
        1000L,
        0L,
        5,
        0.0,
        1L,
        "",
        false);
    return new Region("test", settings);
  }

  @Test
  @DisplayName("Measure why rigid lattice selects and fails early vs. local spatial search")
  void testSubspaceCandidateDropOffAnalysis() {
    NoiseWorldMask noise = new NoiseWorldMask(SEED, RADIUS_CHUNKS, USABLE_SHARE);
    MockRTPWorld world = new MockRTPWorld("noise_world");

    // Continuous elevation derived from chunk coords + local block hash
    CandidateValidator noiseValidator = (x, z) -> {
      int cx = x >> 4;
      int cz = z >> 4;
      if (!noise.isOccupied(cx, cz)) {
        return null; // Water / hazard / invalid chunk
      }
      // Calculate realistic rolling elevation (e.g. 60 to 90)
      double temp = noise.temperature(cx, cz);
      int blockNoise = (Math.abs(x * 31 + z * 17) % 5);
      int y = (int) (68 + temp * 15 + blockNoise);
      return new RTPLocation(new RTPCoords("noise_world", x, y, z), 1);
    };

    int trials = 100;
    int memberCount = 4;
    int minSeparation = 16;
    int elevationTolerance = 8;
    int subspaceRadius = 32;

    int totalRigidSuccesses = 0;
    int droppedAtLatticeCapacity = 0;
    int droppedAtValidationFailure = 0;
    int droppedAtElevationMismatch = 0;
    int localSearchSuccesses = 0;

    Random rng = new Random(SEED);

    for (int t = 0; t < trials; t++) {
      // Pick random anchor chunk that is LAND
      int anchorCX = rng.nextInt(RADIUS_CHUNKS * 2) - RADIUS_CHUNKS;
      int anchorCZ = rng.nextInt(RADIUS_CHUNKS * 2) - RADIUS_CHUNKS;
      if (!noise.isOccupied(anchorCX, anchorCZ)) {
        t--;
        continue;
      }

      int anchorX = (anchorCX << 4) + 8;
      int anchorZ = (anchorCZ << 4) + 8;
      RTPLocation anchorLoc = noiseValidator.validate(anchorX, anchorZ);
      if (anchorLoc == null) {
        t--;
        continue;
      }
      int anchorY = anchorLoc.coords().y();

      DummyMemoryShape memShape = new DummyMemoryShape();
      Region region = createDummyRegion(memShape, world);
      SubspaceShape subspace = new SubspaceShape(anchorLoc, subspaceRadius, region);

      // 1. Run standard rigid lattice slot selection
      List<RTPLocation> rigidSlots = subspace.selectSafeSlots(
          memberCount, minSeparation, elevationTolerance, null, noiseValidator);

      int d = Math.max(1, minSeparation);
      int m = (subspace.getFootprintBlocks() / 2) / d;
      List<int[]> cells = new ArrayList<>();
      for (int u = -m; u <= m; u++) {
        for (int v = -m; v <= m; v++) {
          int wx = anchorX + u * d;
          int wz = anchorZ + v * d;
          if (Math.abs(wx - anchorX) <= subspaceRadius && Math.abs(wz - anchorZ) <= subspaceRadius) {
            cells.add(new int[]{wx, wz});
          }
        }
      }

      if (!rigidSlots.isEmpty()) {
        totalRigidSuccesses++;
      } else {
        // Diagnose why it failed
        if (cells.size() < memberCount) {
          droppedAtLatticeCapacity++;
        } else {
          int validGroundCount = 0;
          int withinElevationCount = 0;

          for (int[] cell : cells) {
            RTPLocation loc = noiseValidator.validate(cell[0], cell[1]);
            if (loc != null) {
              validGroundCount++;
              if (Math.abs(loc.coords().y() - anchorY) <= elevationTolerance) {
                withinElevationCount++;
              }
            }
          }

          if (validGroundCount < memberCount) {
            droppedAtValidationFailure++;
          } else if (withinElevationCount < memberCount) {
            droppedAtElevationMismatch++;
          }
        }
      }

      // 2. Measure potential success if doing a localized search around each lattice node (+- 3 blocks)
      List<RTPLocation> adaptiveSlots = new ArrayList<>();
      for (int[] cell : cells) {
        RTPLocation found = null;
        // Search a small neighborhood around the cell node
        searchLoop:
        for (int dx = -3; dx <= 3; dx += 2) {
          for (int dz = -3; dz <= 3; dz += 2) {
            int testX = cell[0] + dx;
            int testZ = cell[1] + dz;
            RTPLocation loc = noiseValidator.validate(testX, testZ);
            if (loc != null && Math.abs(loc.coords().y() - anchorY) <= elevationTolerance) {
              found = loc;
              break searchLoop;
            }
          }
        }
        if (found != null) {
          adaptiveSlots.add(found);
          if (adaptiveSlots.size() == memberCount) break;
        }
      }

      if (adaptiveSlots.size() >= memberCount) {
        localSearchSuccesses++;
      }
    }

    System.out.println("\n[DEBUG_LOG] === Subspace Noise Terrain Validation Diagnostics ===");
    System.out.printf("[DEBUG_LOG] Trials: %d (Required slots=%d, Radius=%d, minSeparation=%d, elevationTolerance=%d)\n",
        trials, memberCount, subspaceRadius, minSeparation, elevationTolerance);
    System.out.printf("[DEBUG_LOG] Rigid Lattice Success Rate: %d / %d (%.1f%%)\n",
        totalRigidSuccesses, trials, (totalRigidSuccesses * 100.0) / trials);
    System.out.printf("[DEBUG_LOG] Failures due to Validation/Terrain Drop (water/hazard): %d / %d (%.1f%%)\n",
        droppedAtValidationFailure, trials, (droppedAtValidationFailure * 100.0) / trials);
    System.out.printf("[DEBUG_LOG] Failures due to Elevation Tolerance Mismatch vs Anchor: %d / %d (%.1f%%)\n",
        droppedAtElevationMismatch, trials, (droppedAtElevationMismatch * 100.0) / trials);
    System.out.printf("[DEBUG_LOG] Failures due to Lattice Capacity: %d / %d\n",
        droppedAtLatticeCapacity, trials);
    System.out.printf("[DEBUG_LOG] Adaptive Local Search (+-3 block neighborhood) Success Rate: %d / %d (%.1f%%)\n",
        localSearchSuccesses, trials, (localSearchSuccesses * 100.0) / trials);

    assertTrue(trials > 0);
  }

  @Test
  @DisplayName("Phase 4a: High-yield subspace placement on realistic NoiseWorldMask terrain across seeds")
  void testHighYieldNoiseTerrainPlacementAcrossSeeds() throws Exception {
    long[] testSeeds = new long[]{133742L, 987654321L, 5551234L};
    for (long seed : testSeeds) {
      NoiseWorldMask noise = new NoiseWorldMask(seed, RADIUS_CHUNKS, USABLE_SHARE);
      MockRTPWorld world = new MockRTPWorld("noise_world_" + seed);

      CandidateValidator noiseValidator = new CandidateValidator() {
        @Override
        public RTPLocation validate(int x, int z) {
          int cx = x >> 4;
          int cz = z >> 4;
          if (!noise.isOccupied(cx, cz)) {
            return null;
          }
          double temp = noise.temperature(cx, cz);
          int blockNoise = (Math.abs(x * 31 + z * 17) % 5);
          int y = (int) (68 + temp * 15 + blockNoise);
          return new RTPLocation(new RTPCoords("noise_world_" + seed, x, y, z), 1);
        }

        @Override
        public java.util.concurrent.CompletableFuture<RTPLocation> validateAsync(int x, int z) {
          return java.util.concurrent.CompletableFuture.completedFuture(validate(x, z));
        }
      };

      Random rng = new Random(seed);
      int successfulPlacements = 0;
      int attempts = 20;

      for (int i = 0; i < attempts; i++) {
        int anchorCX = rng.nextInt(RADIUS_CHUNKS * 2) - RADIUS_CHUNKS;
        int anchorCZ = rng.nextInt(RADIUS_CHUNKS * 2) - RADIUS_CHUNKS;
        if (!noise.isOccupied(anchorCX, anchorCZ)) {
          i--;
          continue;
        }

        int anchorX = (anchorCX << 4) + 8;
        int anchorZ = (anchorCZ << 4) + 8;
        RTPLocation anchorLoc = noiseValidator.validate(anchorX, anchorZ);
        if (anchorLoc == null) {
          i--;
          continue;
        }

        DummyMemoryShape memShape = new DummyMemoryShape();
        Region region = createDummyRegion(memShape, world);
        SubspaceShape subspace = new SubspaceShape(anchorLoc, 48, region);

        // Request 2 slots with 16 separation and 10 elevation tolerance
        List<RTPLocation> slots = subspace.selectSafeSlotsAsync(2, 4, 16, 10, null, noiseValidator)
            .get(5, java.util.concurrent.TimeUnit.SECONDS);

        if (slots.size() >= 2) {
          successfulPlacements++;
          // Invariant: slots must be at least minSeparation apart
          RTPCoords s1 = slots.get(0).coords();
          RTPCoords s2 = slots.get(1).coords();
          double dist = Math.sqrt(Math.pow(s1.x() - s2.x(), 2) + Math.pow(s1.z() - s2.z(), 2));
          assertTrue(dist >= 16, "Slots must satisfy minSeparation: " + dist);
        }
      }

      // High-yield success rate on realistic terrain across seeds
      double rate = (double) successfulPlacements / attempts;
      System.out.printf("[DEBUG_LOG] Seed %d placement success rate: %d / %d (%.1f%%)\n",
          seed, successfulPlacements, attempts, rate * 100.0);
      assertTrue(successfulPlacements >= 10, "Placement yield across seed " + seed + " should be robust (got " + successfulPlacements + "/" + attempts + ")");
    }
  }

  @Test
  @DisplayName("Phase 4b: S-004 fail-closed reporting on void worlds")
  void testS004FailClosedReportingOnVoidWorlds() throws Exception {
    MockRTPWorld world = new MockRTPWorld("void_world");
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape, world);

    RTPLocation anchor = new RTPLocation(new RTPCoords("void_world", 100, 64, 100), 1);
    SubspaceShape subspace = new SubspaceShape(anchor, 48, region);

    CandidateValidator voidValidator = new CandidateValidator() {
      @Override
      public RTPLocation validate(int x, int z) {
        return null; // Entire void world
      }

      @Override
      public java.util.concurrent.CompletableFuture<RTPLocation> validateAsync(int x, int z) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
      }
    };

    List<RTPLocation> slots = subspace.selectSafeSlotsAsync(2, 4, 16, 10, null, voidValidator)
        .get(5, java.util.concurrent.TimeUnit.SECONDS);

    assertNotNull(slots);
    assertTrue(slots.isEmpty(), "S-004: Subspace on void world must fail closed with 0 slots");
  }

  @Test
  @DisplayName("Phase 4b: S-004 fail-closed reporting on claim-encapsulated zones in GroupPlacementDispatcher")
  void testS004FailClosedOnClaimEncapsulatedZone() throws Exception {
    io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
        (io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor) RTP.serverAccessor;
    RTPWorld<?> world = accessor.getRTPWorld("world");

    UUID p1Uuid = UUID.randomUUID();
    io.github.dailystruggle.rtp.common.mock.MockRTPPlayer p1 =
        new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(p1Uuid, "Player1", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0));
    accessor.addPlayer(p1);

    Region mockRegion = org.mockito.Mockito.mock(Region.class);
    io.github.dailystruggle.rtp.api.world.ChunkReservation anchorTicket = org.mockito.Mockito.mock(io.github.dailystruggle.rtp.api.world.ChunkReservation.class);
    io.github.dailystruggle.rtp.api.selection.GenerationResult genResult = new io.github.dailystruggle.rtp.api.selection.GenerationResult(
        new RTPCoords("world", 100, 64, 100),
        1,
        null,
        anchorTicket
    );
    org.mockito.Mockito.doReturn(world).when(mockRegion).getWorld();
    org.mockito.Mockito.when(mockRegion.getLocation(org.mockito.ArgumentMatchers.anySet())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(genResult));
    org.mockito.Mockito.when(mockRegion.candidateValidator()).thenReturn((x, z) -> new RTPLocation(new RTPCoords("world", x, 64, z), 1L));

    RTP.selectionAPI.permRegionLookup.put("claim_encapsulated_region", mockRegion);

    // Global verifier simulates claim covering the entire area: returns false for any coordinate
    io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers.addGlobalRegionVerifier(coords -> false);

    GroupPlacementDispatcher dispatcher = new GroupPlacementDispatcher();
    try {
      io.github.dailystruggle.rtp.api.group.GroupProfileSpec spec = io.github.dailystruggle.rtp.api.group.GroupProfileSpec.of("square", 32, 2, 5, 4);
      io.github.dailystruggle.rtp.api.group.GroupPlacementRequest request = io.github.dailystruggle.rtp.api.group.GroupPlacementRequest.of(
          "claim_encapsulated_region",
          spec,
          new java.util.ArrayList<>(List.of(p1Uuid))
      );

      java.util.concurrent.CompletableFuture<io.github.dailystruggle.rtp.api.group.GroupPlacementResult> future = dispatcher.place(request);
      io.github.dailystruggle.rtp.api.group.GroupPlacementResult result = future.get(5, java.util.concurrent.TimeUnit.SECONDS);

      assertNotNull(result);
      assertFalse(result.isSuccess(), "Must fail closed when entire subspace is encapsulated by claim (S-003, S-004)");
      assertEquals(io.github.dailystruggle.rtp.api.group.GroupPlacementResult.Reason.INSUFFICIENT_SAFE_SLOTS, result.reason());
      org.mockito.Mockito.verify(anchorTicket).close();
    } finally {
      io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers.clearGlobalRegionVerifiers();
      RTP.selectionAPI.permRegionLookup.remove("claim_encapsulated_region");
    }
  }
}
