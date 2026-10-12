package io.github.dailystruggle.rtp.common.playerData;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBinStoreTest {

  @Test
  @DisplayName("FileBinStore roundtrips offline player record to disk and back")
  void testSaveAndLoad(@TempDir File tempDir) {
    long binWidth = 60_000L;
    long retention = 120_000L;
    FileBinStore store = new FileBinStore(tempDir, binWidth, retention);

    UUID uuid = UUID.randomUUID();
    long now = System.currentTimeMillis();

    TeleportData data = new TeleportData();
    data.sender = new PureDataRTPCommandSender(uuid, "TestPlayer", 60_000L, 5000L, Collections.emptySet());
    data.time = now;
    data.cost = 100.0;
    data.attempts = 3;
    data.completed = true;
    data.targetRegionName = "default";
    data.originalCoords = new RTPCoords("world", 10, 64, 10);
    data.selectedCoords = new RTPCoords("world", 500, 72, -300);

    store.save(data);

    TeleportData loaded = store.load(uuid);
    assertNotNull(loaded);
    assertEquals(uuid, loaded.sender.uuid());
    assertEquals("TestPlayer", loaded.sender.name());
    assertEquals(now, loaded.time);
    assertEquals(100.0, loaded.cost);
    assertEquals(3, loaded.attempts);
    assertTrue(loaded.completed);
    assertEquals("default", loaded.targetRegionName);
    assertNotNull(loaded.originalCoords);
    assertEquals(10, loaded.originalCoords.x());
    assertNotNull(loaded.selectedCoords);
    assertEquals(500, loaded.selectedCoords.x());
  }

  @Test
  @DisplayName("FileBinStore purges expired bin files from disk")
  void testPurgeExpiredBins(@TempDir File tempDir) throws IOException {
    long binWidth = 1000L;
    long retention = 3000L;
    FileBinStore store = new FileBinStore(tempDir, binWidth, retention);

    // Create old bin file: bin_1.dat (timestamp 1000)
    TeleportData oldData = new TeleportData();
    UUID oldUuid = UUID.randomUUID();
    oldData.sender = new PureDataRTPCommandSender(oldUuid, "OldPlayer", 1000L, 0L, Collections.emptySet());
    oldData.time = 1000L;
    store.save(oldData);

    // Create recent bin file: bin_5.dat (timestamp 5000)
    TeleportData recentData = new TeleportData();
    UUID recentUuid = UUID.randomUUID();
    recentData.sender = new PureDataRTPCommandSender(recentUuid, "RecentPlayer", 1000L, 0L, Collections.emptySet());
    recentData.time = 5000L;
    store.save(recentData);

    File oldFile = new File(tempDir, "bin_1.dat");
    File recentFile = new File(tempDir, "bin_5.dat");
    assertTrue(oldFile.exists());
    assertTrue(recentFile.exists());

    // Purge at time 6000. Cutoff is (6000 - 3000) / 1000 = bin 3.
    // bin_1.dat must be deleted, bin_5.dat must remain.
    int deleted = store.purgeExpired(6000L);
    assertEquals(1, deleted);
    assertFalse(oldFile.exists());
    assertTrue(recentFile.exists());
  }
}
