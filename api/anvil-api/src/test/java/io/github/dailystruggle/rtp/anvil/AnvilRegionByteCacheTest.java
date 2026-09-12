package io.github.dailystruggle.rtp.anvil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnvilRegionByteCacheTest {

  @BeforeEach
  void reset() {
    AnvilRegionByteCache.invalidateAll();
    AnvilRegionByteCache.resetStats();
    // Deterministic staleness for tests that rewrite files within one window.
    AnvilRegionByteCache.setRevalidateIntervalMillis(0L);
  }

  @Test
  void get_returnsNullForMissingFile(@TempDir Path tmp) {
    assertNull(AnvilRegionByteCache.get(tmp.resolve("nope.mca")));
  }

  @Test
  void get_cachesBytesBetweenCalls(@TempDir Path tmp) throws Exception {
    Path f = tmp.resolve("r.0.0.mca");
    Files.write(f, new byte[] {1, 2, 3, 4});
    byte[] first = AnvilRegionByteCache.get(f);
    byte[] second = AnvilRegionByteCache.get(f);
    assertNotNull(first);
    assertSame(first, second, "hot read must return the same cached array");
  }

  @Test
  void get_invalidatesWhenMtimeAdvances(@TempDir Path tmp) throws Exception {
    Path f = tmp.resolve("r.0.0.mca");
    Files.write(f, new byte[] {1, 2, 3, 4});
    byte[] first = AnvilRegionByteCache.get(f);
    // Rewrite file, then bump mtime forward. Order matters: Files.write resets mtime
    // to "now", which on fast filesystems can equal the original mtime (1s granularity
    // on some platforms), so we explicitly set mtime afterward to guarantee advancement.
    Files.write(f, new byte[] {9, 9, 9, 9, 9});
    Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(
        Files.getLastModifiedTime(f).toMillis() + 5_000L));
    byte[] second = AnvilRegionByteCache.get(f);
    assertNotNull(first);
    assertNotNull(second);
    assertEquals(5, second.length, "rewritten file should be re-read");
    assertEquals(9, second[0]);
  }

  @Test
  void get_skipsStatWithinRevalidationWindow(@TempDir Path tmp) throws Exception {
    AnvilRegionByteCache.setRevalidateIntervalMillis(60_000L);
    Path f = tmp.resolve("r.0.0.mca");
    Files.write(f, new byte[] {1, 2, 3, 4});
    assertNotNull(AnvilRegionByteCache.get(f));
    AnvilRegionByteCache.resetStats();
    for (int i = 0; i < 100; i++) {
      assertNotNull(AnvilRegionByteCache.get(f));
    }
    assertEquals(100, AnvilRegionByteCache.stats().statSkips(),
        "warm hits inside the window must not stat the region file");
    // Zeroing the window restores stat-on-every-get, so a rewrite is observed immediately.
    AnvilRegionByteCache.setRevalidateIntervalMillis(0L);
    Files.write(f, new byte[] {9, 9, 9, 9, 9});
    Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(
        Files.getLastModifiedTime(f).toMillis() + 5_000L));
    assertEquals(5, AnvilRegionByteCache.get(f).length);
  }

  @Test
  void lru_evictsBeyondCapacity(@TempDir Path tmp) throws Exception {
    // Write 20 region files; cache capacity is 16.
    Path[] files = new Path[20];
    for (int i = 0; i < files.length; i++) {
      files[i] = tmp.resolve("r." + i + ".0.mca");
      Files.write(files[i], new byte[] {(byte) i});
    }
    for (Path f : files) {
      assertNotNull(AnvilRegionByteCache.get(f));
    }
    assertTrue(AnvilRegionByteCache.size() <= 16,
        "cache must cap at 16 entries, saw " + AnvilRegionByteCache.size());
  }

  @Test
  void bufferPool_reusesEvictedBuffers(@TempDir Path tmp) throws Exception {
    AnvilRegionByteCache.resetAll();
    // Create 20 region files of standard size (8 KiB)
    Path[] files = new Path[20];
    byte[] payload = new byte[8192];
    for (int i = 0; i < files.length; i++) {
      files[i] = tmp.resolve("r." + i + ".0.mca");
      payload[0] = (byte) i;
      Files.write(files[i], payload);
    }
    // Read first 16: fills cache up to capacity
    for (int i = 0; i < 16; i++) {
      assertNotNull(AnvilRegionByteCache.get(files[i]));
    }
    assertEquals(16, AnvilRegionByteCache.size());

    // Reading 17th file evicts the eldest, which is recycled into BUFFER_POOL
    assertNotNull(AnvilRegionByteCache.get(files[16]));
    // The evicted buffer should be recycled or reused
    // Read remaining files: evicted buffers are continuously recycled and reused
    for (int i = 17; i < 20; i++) {
      assertNotNull(AnvilRegionByteCache.get(files[i]));
    }
    assertEquals(16, AnvilRegionByteCache.size());

    // Invalidate all puts cached buffers into the pool
    AnvilRegionByteCache.invalidateAll();
    assertEquals(0, AnvilRegionByteCache.size());
    assertTrue(AnvilRegionByteCache.bufferPoolSize() > 0, "buffer pool should contain recycled buffers");

    // Re-reading a file reuses an existing pooled buffer instance
    byte[] reused = AnvilRegionByteCache.get(files[0]);
    assertNotNull(reused);
    assertEquals(8192, reused.length, "pooled reuse must stay exact-length");
  }

  @Test
  void get_smallFileAfterLargeFile_reportsExactFileLength(@TempDir Path tmp) throws Exception {
    AnvilRegionByteCache.resetAll();
    // A large region file first, so its buffer lands in the pool on eviction/invalidation.
    Path large = tmp.resolve("r.0.0.mca");
    Files.write(large, new byte[512 * 1024]);
    assertNotNull(AnvilRegionByteCache.get(large));
    AnvilRegionByteCache.invalidateAll();
    assertTrue(AnvilRegionByteCache.bufferPoolSize() > 0, "large buffer should have been pooled");

    // A smaller region file must never be served on the oversized pooled buffer: its array
    // length is the corruption guard's fileLen in AnvilReader, and the stale tail would
    // otherwise belong to the large region file.
    Path small = tmp.resolve("r.1.0.mca");
    byte[] smallPayload = new byte[8192];
    smallPayload[0] = 7;
    Files.write(small, smallPayload);
    byte[] bytes = AnvilRegionByteCache.get(small);
    assertNotNull(bytes);
    assertEquals(Files.size(small), bytes.length, "cached array length must equal the real file length");
    assertEquals(Files.size(small), AnvilRegionByteCache.cachedLength(small));
    assertEquals(7, bytes[0]);
  }
}
