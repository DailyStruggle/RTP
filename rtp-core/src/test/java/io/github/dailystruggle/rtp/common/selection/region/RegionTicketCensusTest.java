package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import io.github.dailystruggle.rtp.common.tools.TicketCensusRegion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RTP-67: the MemoryTracker census reads regions through {@link TicketCensusRegion} views. */
@DisplayName("RTP-67 - RegionTicketCensus exposes region queues to the MemoryTracker sweep")
class RegionTicketCensusTest {

  @TempDir
  File tempDir;

  private MockRTPWorld world;
  private Region region;
  private final Set<UUID> teleportIds = new HashSet<>();

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(tempDir);
    world = new MockRTPWorld("census_world");
    region = newRegion("census_a");
  }

  @AfterEach
  void tearDown() {
    for (UUID id : teleportIds) {
      TeleportSessions.current().latestTeleportData.remove(id);
    }
    RTPTestSetup.cleanUp();
  }

  private Region newRegion(String name) {
    Square square = new Square();
    square.set(GenericMemoryShapeParams.radius, 1000L);
    square.set(GenericMemoryShapeParams.centerRadius, 0L);
    RegionSettings settings = new RegionSettings(
        name, world, square, new LinearAdjustor(new ArrayList<>()),
        false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
    return new Region(name, settings);
  }

  private RTPLocation reserved(int x, int z) {
    int cx = x >> 4;
    int cz = z >> 4;
    long key = (cx & 0xffffffffL) | ((long) cz << 32);
    ChunkSet set = new ChunkSet(world, cx, cz,
        Collections.singletonList(CompletableFuture.completedFuture(key)), new CompletableFuture<>());
    return new RTPLocation(new RTPCoords(world.name(), x, 64, z), 1, new ChunkReservation(set, world));
  }

  private void pipelineTo(Region target, RTPCoords coords, boolean completed) {
    TeleportData data = new TeleportData();
    data.targetRegion = target;
    data.selectedCoords = coords;
    data.completed = completed;
    UUID id = UUID.randomUUID();
    teleportIds.add(id);
    TeleportSessions.current().latestTeleportData.put(id, data);
  }

  @Test
  @DisplayName("counts kept locations and their reserved chunk tickets")
  void countsKeptLocationsAndTickets() {
    region.queueManager.keptLocations.offer(reserved(40, 40));
    region.queueManager.keptLocations.offer(reserved(400, 400));
    region.queueManager.keptLocations.offer(new RTPLocation(new RTPCoords(world.name(), 800, 64, 800), 1));

    RegionTicketCensus census = new RegionTicketCensus(region);

    assertEquals(3, census.queuedLocations(), "kept queue size");
    assertEquals(0, census.perPlayerLocations(), "no per-player queues");
    assertEquals(2, census.reservedChunkTickets(), "only reserved locations hold tickets");
    RegionSettings s = region.getSettings();
    assertEquals(s.cacheCap() + s.activeChunkCap() + s.backlogCacheCap(), census.cacheBudget());
    assertEquals(s.activeChunkCap(), census.activeChunkCap());
    assertSame(world, census.world());
  }

  @Test
  @DisplayName("keep-alive keys cover queued locations and open pipelines to this region only")
  void keepAliveKeysCoverQueueAndOpenPipelines() {
    RTPLocation queued = reserved(40, 40);
    region.queueManager.keptLocations.offer(queued);
    RTPCoords open = new RTPCoords(world.name(), 1000, 64, 1000);
    RTPCoords done = new RTPCoords(world.name(), 2000, 64, 2000);
    RTPCoords other = new RTPCoords(world.name(), 3000, 64, 3000);
    pipelineTo(region, open, false);
    pipelineTo(region, done, true);
    pipelineTo(newRegion("census_b"), other, false);

    Set<Long> keys = new HashSet<>();
    new RegionTicketCensus(region).collectKeepAliveChunkKeys(keys);

    assertTrue(keys.contains(queued.coords().getChunkKey()), "queued location kept alive");
    assertTrue(keys.contains(open.getChunkKey()), "open pipeline destination kept alive");
    assertFalse(keys.contains(done.getChunkKey()), "completed pipeline released");
    assertFalse(keys.contains(other.getChunkKey()), "other region's pipeline not attributed here");
  }

  @Test
  @DisplayName("generation in flight or a pending kept promotion skips the region in the sweep")
  void inFlightWorkGatesSweep() {
    RegionTicketCensus census = new RegionTicketCensus(region);
    assertFalse(census.generationInFlight());

    region.inFlightCalculations.incrementAndGet();
    assertTrue(census.generationInFlight(), "in-flight generation");
    region.inFlightCalculations.decrementAndGet();

    region.pendingKeptPromotions.incrementAndGet();
    assertTrue(census.generationInFlight(), "pending kept promotion");
    region.pendingKeptPromotions.decrementAndGet();
  }

  @Test
  @DisplayName("allRegions lists permanent and temporary regions")
  void allRegionsListsPermanentAndTemporary() {
    Region temp = newRegion("census_temp");
    ActiveSelection.get().permRegionLookup.put("census_a", region);
    UUID tempOwner = UUID.randomUUID();
    ActiveSelection.get().tempRegions.put(tempOwner, temp);
    try {
      Set<Object> worlds = new HashSet<>();
      int matches = 0;
      for (TicketCensusRegion view : RegionTicketCensus.allRegions()) {
        worlds.add(view.world());
        if (view.world() == world) matches++;
      }
      assertEquals(2, matches, "both regions in the census world");
      assertTrue(worlds.contains(world));
    } finally {
      ActiveSelection.get().permRegionLookup.remove("census_a");
      ActiveSelection.get().tempRegions.remove(tempOwner);
    }
  }
}
