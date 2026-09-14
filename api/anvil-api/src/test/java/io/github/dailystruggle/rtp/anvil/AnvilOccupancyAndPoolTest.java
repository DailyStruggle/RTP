package io.github.dailystruggle.rtp.anvil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnvilOccupancyAndPoolTest {

  @Test
  @DisplayName("AnvilRegionOccupancyCache detects occupied chunk slots from header bytes")
  void testOccupancyCache(@TempDir Path tempDir) throws IOException {
    AnvilRegionOccupancyCache.invalidateAll();

    assertFalse(AnvilRegionOccupancyCache.isOccupied(null, 0, 0));
    assertFalse(AnvilRegionOccupancyCache.isOccupied(tempDir.resolve("missing.mca"), 0, 0));

    // Create a mock .mca file with 4096 bytes header
    Path mca = tempDir.resolve("r.0.0.mca");
    byte[] header = new byte[8192];
    // Slot (0, 0): offset index 0: offset 2, sectors 1 -> occupied
    header[0] = 0;
    header[1] = 0;
    header[2] = 2;
    header[3] = 1;

    // Slot (1, 0): offset index 1: offset 0, sectors 0 -> empty
    header[4] = 0;
    header[5] = 0;
    header[6] = 0;
    header[7] = 0;

    // Slot (31, 31): index = 31 + 31*32 = 1023 -> occupied
    int idx1023 = 1023 * 4;
    header[idx1023] = 0;
    header[idx1023 + 1] = 0;
    header[idx1023 + 2] = 5;
    header[idx1023 + 3] = 2;

    Files.write(mca, header);

    assertTrue(AnvilRegionOccupancyCache.isOccupied(mca, 0, 0));
    assertFalse(AnvilRegionOccupancyCache.isOccupied(mca, 1, 0));
    assertTrue(AnvilRegionOccupancyCache.isOccupied(mca, 31, 31));
    assertFalse(AnvilRegionOccupancyCache.isOccupied(mca, 15, 15));

    // Cache hit
    assertTrue(AnvilRegionOccupancyCache.isOccupied(mca, 0, 0));

    // Invalidate
    AnvilRegionOccupancyCache.invalidateAll();

    // File with less than 4096 bytes
    Path tiny = tempDir.resolve("tiny.mca");
    Files.write(tiny, new byte[100]);
    assertFalse(AnvilRegionOccupancyCache.isOccupied(tiny, 0, 0));
  }

  @Test
  @DisplayName("AnvilIoPool executor service lifecycle and memory budget")
  void testAnvilIoPool() throws ExecutionException, InterruptedException {
    assertNotNull(AnvilIoPool.get());
    Future<String> f = AnvilIoPool.get().submit(() -> Thread.currentThread().getName());
    String threadName = f.get();
    assertTrue(threadName.startsWith("RTP-Anvil-IO-"));

    long defaultBudget = AnvilIoPool.getMemoryBudgetBytes();
    assertTrue(defaultBudget > 0);

    AnvilIoPool.setMemoryBudgetBytes(128L * 1024L * 1024L);
    assertEquals(128L * 1024L * 1024L, AnvilIoPool.getMemoryBudgetBytes());

    // Restore
    AnvilIoPool.setMemoryBudgetBytes(defaultBudget);
  }

  @Test
  @DisplayName("UnsupportedAnvilFormatException exception message and cause")
  void testUnsupportedFormatException() {
    UnsupportedAnvilFormatException ex1 = new UnsupportedAnvilFormatException("unsupported version");
    assertEquals("unsupported version", ex1.getMessage());

    Throwable cause = new RuntimeException("inner");
    UnsupportedAnvilFormatException ex2 = new UnsupportedAnvilFormatException("unsupported with cause", cause);
    assertEquals("unsupported with cause", ex2.getMessage());
    assertEquals(cause, ex2.getCause());
  }
}
