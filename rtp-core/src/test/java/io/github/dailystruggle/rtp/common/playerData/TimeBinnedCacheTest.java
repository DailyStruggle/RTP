package io.github.dailystruggle.rtp.common.playerData;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TimeBinnedCacheTest {

  @Test
  @DisplayName("TimeBinnedCache stores and retrieves entries across epoch buckets")
  void testStoreAndRetrieve() {
    TimeBinnedCache<UUID, String> cache = new TimeBinnedCache<>(1000L, 5000L);
    UUID id1 = UUID.randomUUID();
    UUID id2 = UUID.randomUUID();

    cache.put(id1, "val1", 1000L);
    cache.put(id2, "val2", 2000L);

    assertEquals("val1", cache.get(id1));
    assertEquals("val2", cache.get(id2));
    assertEquals(2, cache.size());
    assertTrue(cache.containsKey(id1));
    assertTrue(cache.containsKey(id2));
  }

  @Test
  @DisplayName("TimeBinnedCache purges expired bins based on retention cutoff")
  void testPurgeExpired() {
    long binWidth = 1000L;
    long retention = 3000L;
    TimeBinnedCache<UUID, String> cache = new TimeBinnedCache<>(binWidth, retention);

    UUID old1 = UUID.randomUUID();
    UUID old2 = UUID.randomUUID();
    UUID recent = UUID.randomUUID();

    cache.put(old1, "oldVal1", 1000L); // bin 1
    cache.put(old2, "oldVal2", 1500L); // bin 1
    cache.put(recent, "recentVal", 5000L); // bin 5

    assertEquals(3, cache.size());

    // Purge at time 6000. Cutoff is (6000 - 3000) / 1000 = bin 3.
    // Bins < 3 (bin 1) must be evicted.
    int purged = cache.purgeExpired(6000L);

    assertEquals(2, purged);
    assertEquals(1, cache.size());
    assertNull(cache.get(old1));
    assertNull(cache.get(old2));
    assertEquals("recentVal", cache.get(recent));
    assertFalse(cache.containsKey(old1));
    assertTrue(cache.containsKey(recent));
  }

  @Test
  @DisplayName("TimeBinnedCache handles update and bucket re-assignment")
  void testKeyReassignment() {
    TimeBinnedCache<UUID, String> cache = new TimeBinnedCache<>(1000L, 3000L);
    UUID id = UUID.randomUUID();

    cache.put(id, "initial", 1000L); // bin 1
    assertEquals("initial", cache.get(id));

    cache.put(id, "updated", 5000L); // bin 5
    assertEquals("updated", cache.get(id));
    assertEquals(1, cache.size());

    // Purge at time 6000 (cutoff bin 3).
    // The key is now in bin 5, so it must survive.
    int purged = cache.purgeExpired(6000L);
    assertEquals(0, purged);
    assertEquals("updated", cache.get(id));
  }

  @Test
  @DisplayName("TimeBinnedCache standard removal removes key from both index and bin")
  void testExplicitRemoval() {
    TimeBinnedCache<UUID, String> cache = new TimeBinnedCache<>(1000L, 5000L);
    UUID id = UUID.randomUUID();

    cache.put(id, "val", 1000L);
    assertEquals("val", cache.remove(id));
    assertNull(cache.get(id));
    assertEquals(0, cache.size());
    assertFalse(cache.containsKey(id));
  }
}
