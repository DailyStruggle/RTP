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
  void lru_evictsBeyondCapacity(@TempDir Path tmp) throws Exception {
    // Write 20 region files; cache capacity is 16 (PR-12).
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
}
