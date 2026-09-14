package io.github.dailystruggle.rtp.common.tools.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * Unit coverage for the {@link BiomeActivityTracker} accumulator backing
 * {@code /rtp info biomes}. Exercises both in-memory recording and scheduled sampling.
 */
class BiomeActivityTrackerTest {

  @TempDir
  File pluginDir;

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(pluginDir);
  }

  @AfterEach
  void tearDown() {
    RTP.configs = null;
    RTP.serverAccessor = null;
    RTP.scheduler = null;
  }

  @Test
  @DisplayName("snapshot orders biomes by descending sample count, ties by name")
  void snapshotOrdering() {
    BiomeActivityTracker t = new BiomeActivityTracker();
    for (int i = 0; i < 5; i++) t.record("PLAINS");
    for (int i = 0; i < 3; i++) t.record("FOREST");
    t.record("DESERT");
    t.record("BADLANDS"); // ties DESERT at 1 -> alphabetical first

    assertEquals(10L, t.totalSamples());
    assertEquals(4, t.distinctBiomes());

    List<String> order = new ArrayList<>(t.snapshot().keySet());
    assertEquals(List.of("PLAINS", "FOREST", "BADLANDS", "DESERT"), order);

    Map<String, Long> snap = t.snapshot();
    assertEquals(5L, snap.get("PLAINS"));
    assertEquals(3L, snap.get("FOREST"));
    assertEquals(1L, snap.get("DESERT"));
  }

  @Test
  @DisplayName("null/blank biome names are ignored and casing is normalised")
  void ignoresBlankAndNormalisesCase() {
    BiomeActivityTracker t = new BiomeActivityTracker();
    t.record(null);
    t.record("   ");
    t.record("");
    t.record("plains");
    t.record("PLAINS");
    t.record(" Plains ");

    assertEquals(3L, t.totalSamples());
    assertEquals(1, t.distinctBiomes());
    assertEquals(3L, t.snapshot().get("PLAINS"));
  }

  @Test
  @DisplayName("reset clears all accumulated occupancy")
  void resetClears() {
    BiomeActivityTracker t = new BiomeActivityTracker();
    t.record("PLAINS");
    t.record("FOREST");
    assertTrue(t.totalSamples() > 0);

    t.reset();

    assertEquals(0L, t.totalSamples());
    assertEquals(0, t.distinctBiomes());
    assertTrue(t.snapshot().isEmpty());
  }

  @Test
  @DisplayName("sample with null or empty players collection returns empty list")
  void sampleNullOrEmpty() {
    BiomeActivityTracker t = new BiomeActivityTracker();
    assertTrue(t.sample(null).isEmpty());
    assertTrue(t.sample(Collections.emptyList()).isEmpty());
  }

  @Test
  @DisplayName("sample processes online players, skips offline or null players")
  void sampleOnlineAndOfflinePlayers() throws Exception {
    BiomeActivityTracker t = new BiomeActivityTracker();
    MockRTPServerAccessor accessor = (MockRTPServerAccessor) RTP.serverAccessor;
    MockRTPWorld world = (MockRTPWorld) accessor.getRTPWorld("world");
    assertNotNull(world);

    RTPLocation loc = new RTPLocation(world, 10, 64, 20);
    MockRTPPlayer onlinePlayer = new MockRTPPlayer(UUID.randomUUID(), "OnlineGuy", loc);
    onlinePlayer.setOnline(true);

    MockRTPPlayer offlinePlayer = new MockRTPPlayer(UUID.randomUUID(), "OfflineGuy", loc);
    offlinePlayer.setOnline(false);

    List<CompletableFuture<String>> futures = t.sample(List.of(onlinePlayer, offlinePlayer));
    assertEquals(1, futures.size());

    // Advance ticks so scheduled task runs
    accessor.getMockScheduler().tick(1L);

    CompletableFuture<String> f = futures.get(0);
    assertTrue(f.isDone());
    String biome = f.get();
    assertEquals("PLAINS", biome);
    assertEquals(1L, t.totalSamples());
    assertEquals(1, t.distinctBiomes());
  }

  @Test
  @DisplayName("sample completes with null when player location or world is null")
  void samplePlayerWithNullLocationOrWorld() throws Exception {
    BiomeActivityTracker t = new BiomeActivityTracker();
    MockRTPServerAccessor accessor = (MockRTPServerAccessor) RTP.serverAccessor;
    MockRTPPlayer playerWithoutLoc = new MockRTPPlayer(UUID.randomUUID(), "NoLocGuy", null);
    playerWithoutLoc.setOnline(true);

    List<CompletableFuture<String>> futures = t.sample(List.of(playerWithoutLoc));
    assertEquals(1, futures.size());

    accessor.getMockScheduler().tick(1L);

    CompletableFuture<String> f = futures.get(0);
    assertTrue(f.isDone());
    assertNull(f.get());
    assertEquals(0L, t.totalSamples());
  }

  @Test
  @DisplayName("sample completes exceptionally when task execution throws")
  void sampleExecutionThrows() {
    BiomeActivityTracker t = new BiomeActivityTracker();
    MockRTPServerAccessor accessor = (MockRTPServerAccessor) RTP.serverAccessor;
    MockRTPWorld world = Mockito.mock(MockRTPWorld.class);
    Mockito.when(world.getBiome(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
        .thenThrow(new RuntimeException("boom"));

    RTPLocation loc = new RTPLocation(world, 0, 64, 0);
    MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "BoomGuy", loc);
    player.setOnline(true);

    List<CompletableFuture<String>> futures = t.sample(List.of(player));
    assertEquals(1, futures.size());

    accessor.getMockScheduler().tick(1L);

    CompletableFuture<String> f = futures.get(0);
    assertTrue(f.isCompletedExceptionally());
  }
}
