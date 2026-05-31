package io.github.dailystruggle.rtp.common.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.platform.BlockDelta;
import io.github.dailystruggle.rtp.api.platform.PendingPlatformRestore;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.options.H2DatabaseAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPScheduler;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for ADR-060 (emergency-platform block-restoration timeout):
 * the chunk-loaded countdown gate (S-005), restore-on-zero with DB/entry removal,
 * the S-004 restore-failure audit/retire path, and the SQL persistence round-trip.
 */
class ReqRtpAdr060PlatformRestoreTest {

  private MockRTPServerAccessor accessor;
  private MockRTPScheduler scheduler;
  private MockRTPWorld world;
  private File pluginDir;

  @BeforeEach
  void setUp() throws Exception {
    pluginDir = Files.createTempDirectory("rtp-adr060").toFile();
    accessor = new MockRTPServerAccessor(pluginDir);
    world = new MockRTPWorld("restore_world");
    accessor.addWorld(world);
    scheduler = new MockRTPScheduler();
    RTP.serverAccessor = accessor;
    RTP.scheduler = scheduler;
  }

  @AfterEach
  void tearDown() {
    RTP.serverAccessor = null;
    RTP.scheduler = null;
  }

  private PendingPlatformRestore job(int remainingSeconds) {
    List<BlockDelta> blocks = new ArrayList<>();
    blocks.add(new BlockDelta(5, 64, 5, "minecraft:stone"));
    blocks.add(new BlockDelta(5, 65, 5, "minecraft:oak_log[axis=y]"));
    return new PendingPlatformRestore(
        UUID.randomUUID(), world.name(), 0, 0, blocks, remainingSeconds);
  }

  @Test
  @DisplayName("countdown is frozen while the footprint chunk is unloaded (S-005, no force-load)")
  void countdownFrozenWhileChunkUnloaded() {
    PlatformRestoreManager m = new PlatformRestoreManager(null);
    m.enroll(job(2));

    world.isChunkLoadedPredicate = k -> false; // chunk never loaded
    for (int i = 0; i < 10; i++) m.pulse();

    assertEquals(1, m.size(), "entry must remain enrolled while its chunk stays unloaded");
    assertTrue(world.restoredBlocks.isEmpty(), "nothing may be restored while the chunk is unloaded");
  }

  @Test
  @DisplayName("countdown advances only when loaded, then restores and removes the entry")
  void decrementsAndRestoresWhenLoaded() {
    PlatformRestoreManager m = new PlatformRestoreManager(null);
    m.enroll(job(2));
    world.isChunkLoadedPredicate = k -> true;

    m.pulse(); // 2 -> 1
    assertEquals(1, m.size());
    assertTrue(world.restoredBlocks.isEmpty());

    m.pulse(); // 1 -> 0, schedule restore (runs inline in MockRTPScheduler)
    assertEquals(0, m.size(), "entry removed after restore completes");
    assertEquals(2, world.restoredBlocks.size(), "both footprint blocks were written back");
  }

  @Test
  @DisplayName("remainingSeconds==0 restores on the first loaded pulse")
  void zeroRestoresImmediatelyWhenLoaded() {
    PlatformRestoreManager m = new PlatformRestoreManager(null);
    m.enroll(job(0));
    world.isChunkLoadedPredicate = k -> true;

    m.pulse();
    assertEquals(0, m.size());
    assertEquals(2, world.restoredBlocks.size());
  }

  @Test
  @DisplayName("a failed restore is retired without re-firing (S-004)")
  void failedRestoreRetiresEntry() {
    PlatformRestoreManager m = new PlatformRestoreManager(null);
    m.enroll(job(0));
    world.isChunkLoadedPredicate = k -> true;
    world.restoreShouldFail = true;

    m.pulse();
    assertEquals(0, m.size(), "a failed restore must still retire the entry (no re-fire loop)");
    assertTrue(world.restoredBlocks.isEmpty());
  }

  @Test
  @DisplayName("an empty restore job is ignored on enroll")
  void emptyJobIgnored() {
    PlatformRestoreManager m = new PlatformRestoreManager(null);
    m.enroll(new PendingPlatformRestore(UUID.randomUUID(), world.name(), 0, 0, new ArrayList<>(), 5));
    assertEquals(0, m.size());
  }

  @Test
  @DisplayName("block payload with commas/brackets survives tab serialization round-trip")
  void serializeRoundTrip() {
    List<BlockDelta> blocks = new ArrayList<>();
    blocks.add(new BlockDelta(1, 2, 3, "minecraft:grass_block[snowy=false]"));
    blocks.add(new BlockDelta(-4, 70, -8, "minecraft:chest[facing=north,type=single,waterlogged=false]"));
    String payload = PlatformRestoreSqlStore.serialize(blocks);
    List<BlockDelta> back = PlatformRestoreSqlStore.deserialize(payload);
    assertEquals(blocks, back);
  }

  @Test
  @DisplayName("SQL store persists, updates, loads and deletes a restore job (resume across restart)")
  void sqlPersistenceRoundTrip() {
    H2DatabaseAccessor h2 = new H2DatabaseAccessor();
    try {
      PlatformRestoreSqlStore store = new PlatformRestoreSqlStore(h2);
      PendingPlatformRestore r = job(7);
      store.insert(r);

      List<PendingPlatformRestore> loaded = store.loadAll();
      assertEquals(1, loaded.size());
      assertEquals(r.id(), loaded.get(0).id());
      assertEquals(7, loaded.get(0).remainingSeconds());
      assertEquals(2, loaded.get(0).blocks().size());

      store.updateRemaining(r.id(), 3);
      assertEquals(3, store.loadAll().get(0).remainingSeconds());

      store.delete(r.id());
      assertTrue(store.loadAll().isEmpty(), "completed job's row is deleted");
    } finally {
      h2.close();
    }
  }
}
