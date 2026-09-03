package io.github.dailystruggle.rtp.common.selection.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.BacklogLocationBuffer.BacklogEntry;
import io.github.dailystruggle.rtp.common.selection.region.BacklogLocationBuffer.Validity;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BacklogLocationBuffer}, the backlog cache primitive
 * specified by ADR-028. Covers the contract documented in the class javadoc:
 * order preservation, head-blocking on {@link Validity#UNVERIFIED}, head-drop
 * on {@link Validity#INVALIDATED}, and bounded capacity.
 */
class BacklogLocationBufferTest {

  private static RTPLocation loc(int x) {
    return new RTPLocation(new RTPCoords("world", x, 64, 0), 1L);
  }

  @Test
  @DisplayName("offerUnverified appends entries in insertion order; new entries are UNVERIFIED")
  void appendsUnverifiedInOrder() {
    BacklogLocationBuffer b = new BacklogLocationBuffer(4);
    BacklogEntry e0 = b.offerUnverified(loc(0));
    BacklogEntry e1 = b.offerUnverified(loc(1));
    BacklogEntry e2 = b.offerUnverified(loc(2));
    assertNotNull(e0);
    assertNotNull(e1);
    assertNotNull(e2);
    assertEquals(Validity.UNVERIFIED, e0.validity());
    assertEquals(Validity.UNVERIFIED, e1.validity());
    assertEquals(Validity.UNVERIFIED, e2.validity());
    assertEquals(3, b.size());
    assertEquals(0, b.validatedSize());
  }

  @Test
  @DisplayName("offerUnverified returns null when the buffer is full; older entries are not evicted")
  void capacityRejectsNewWithoutEviction() {
    BacklogLocationBuffer b = new BacklogLocationBuffer(2);
    BacklogEntry e0 = b.offerUnverified(loc(0));
    BacklogEntry e1 = b.offerUnverified(loc(1));
    BacklogEntry e2 = b.offerUnverified(loc(2));
    assertNotNull(e0);
    assertNotNull(e1);
    assertNull(e2, "third insert into capacity-2 buffer must be rejected");
    assertEquals(2, b.size());
  }

  @Test
  @DisplayName("pollContiguousValidatedHead stops at the first UNVERIFIED head (head-blocking)")
  void headBlockingOnUnverified() {
    BacklogLocationBuffer b = new BacklogLocationBuffer(4);
    BacklogEntry e0 = b.offerUnverified(loc(0));
    BacklogEntry e1 = b.offerUnverified(loc(1));
    BacklogEntry e2 = b.offerUnverified(loc(2));
    e0.setValidity(Validity.VALIDATED);
    // e1 stays UNVERIFIED -> blocks promotion of e2 even if e2 were VALIDATED.
    e2.setValidity(Validity.VALIDATED);

    List<BacklogEntry> drained = b.pollContiguousValidatedHead(10);
    assertEquals(1, drained.size(), "only the head e0 should drain; e1 (UNVERIFIED) blocks e2");
    assertSame(e0, drained.get(0));
    assertEquals(2, b.size(), "e1 and e2 remain in the buffer");
  }

  @Test
  @DisplayName("pollContiguousValidatedHead drops INVALIDATED head entries without blocking")
  void invalidatedHeadDropsButDoesNotBlock() {
    BacklogLocationBuffer b = new BacklogLocationBuffer(4);
    BacklogEntry e0 = b.offerUnverified(loc(0));
    BacklogEntry e1 = b.offerUnverified(loc(1));
    BacklogEntry e2 = b.offerUnverified(loc(2));
    e0.setValidity(Validity.INVALIDATED);
    e1.setValidity(Validity.VALIDATED);
    e2.setValidity(Validity.UNVERIFIED); // explicit; default already

    List<BacklogEntry> drained = b.pollContiguousValidatedHead(10);
    assertEquals(1, drained.size(), "INVALIDATED e0 dropped silently; e1 promoted; e2 blocks");
    assertSame(e1, drained.get(0));
    assertEquals(1, b.size(), "only e2 should remain");
  }

  @Test
  @DisplayName("pollContiguousValidatedHead respects maxN cap")
  void respectsMaxN() {
    BacklogLocationBuffer b = new BacklogLocationBuffer(4);
    BacklogEntry e0 = b.offerUnverified(loc(0));
    BacklogEntry e1 = b.offerUnverified(loc(1));
    BacklogEntry e2 = b.offerUnverified(loc(2));
    e0.setValidity(Validity.VALIDATED);
    e1.setValidity(Validity.VALIDATED);
    e2.setValidity(Validity.VALIDATED);

    List<BacklogEntry> drained = b.pollContiguousValidatedHead(2);
    assertEquals(2, drained.size());
    assertSame(e0, drained.get(0));
    assertSame(e1, drained.get(1));
    assertEquals(1, b.size(), "e2 remains, untouched");
  }

  @Test
  @DisplayName("peekOldestUnverified returns the first UNVERIFIED entry (skipping VALIDATED ones)")
  void peekOldestUnverifiedSkipsValidated() {
    BacklogLocationBuffer b = new BacklogLocationBuffer(4);
    BacklogEntry e0 = b.offerUnverified(loc(0));
    BacklogEntry e1 = b.offerUnverified(loc(1));
    BacklogEntry e2 = b.offerUnverified(loc(2));
    e0.setValidity(Validity.VALIDATED);
    // e1 and e2 remain UNVERIFIED.

    BacklogEntry oldest = b.peekOldestUnverified();
    assertSame(e1, oldest);
  }

  @Test
  @DisplayName("peekOldestUnverified returns null when none remain UNVERIFIED")
  void peekOldestUnverifiedNullWhenNone() {
    BacklogLocationBuffer b = new BacklogLocationBuffer(4);
    BacklogEntry e0 = b.offerUnverified(loc(0));
    BacklogEntry e1 = b.offerUnverified(loc(1));
    e0.setValidity(Validity.VALIDATED);
    e1.setValidity(Validity.INVALIDATED);
    assertNull(b.peekOldestUnverified());
  }

  @Test
  @DisplayName("validatedSize counts VALIDATED entries only")
  void validatedSizeCountsValidated() {
    BacklogLocationBuffer b = new BacklogLocationBuffer(4);
    BacklogEntry e0 = b.offerUnverified(loc(0));
    BacklogEntry e1 = b.offerUnverified(loc(1));
    BacklogEntry e2 = b.offerUnverified(loc(2));
    e0.setValidity(Validity.VALIDATED);
    e1.setValidity(Validity.INVALIDATED);
    // e2 stays UNVERIFIED
    assertEquals(1, b.validatedSize());
    assertEquals(3, b.size());
  }

  @Test
  @DisplayName("clear empties the buffer; previously issued entries retain their validity tag")
  void clearEmptiesButPreservesEntryTags() {
    BacklogLocationBuffer b = new BacklogLocationBuffer(4);
    BacklogEntry e0 = b.offerUnverified(loc(0));
    e0.setValidity(Validity.VALIDATED);
    b.clear();
    assertTrue(b.isEmpty());
    assertEquals(0, b.size());
    assertEquals(Validity.VALIDATED, e0.validity(),
        "validity tags on issued entries are unaffected by clear()");
  }

  @Test
  @DisplayName("constructor rejects non-positive capacity")
  void rejectsNonPositiveCapacity() {
    assertThrows(IllegalArgumentException.class, () -> new BacklogLocationBuffer(0));
    assertThrows(IllegalArgumentException.class, () -> new BacklogLocationBuffer(-1));
  }

  @Test
  @DisplayName("pollContiguousValidatedHead rejects negative maxN")
  void rejectsNegativeMaxN() {
    BacklogLocationBuffer b = new BacklogLocationBuffer(2);
    assertThrows(IllegalArgumentException.class, () -> b.pollContiguousValidatedHead(-1));
  }

  @Test
  @DisplayName("setValidity(null) is rejected")
  void rejectsNullValidity() {
    BacklogLocationBuffer b = new BacklogLocationBuffer(2);
    BacklogEntry e0 = b.offerUnverified(loc(0));
    assertThrows(NullPointerException.class, () -> e0.setValidity(null));
  }

  @Test
  @DisplayName("offerUnverified(null) is rejected")
  void rejectsNullLocation() {
    BacklogLocationBuffer b = new BacklogLocationBuffer(2);
    assertThrows(NullPointerException.class, () -> b.offerUnverified(null));
  }

  @Test
  @DisplayName("maxN=0 still drains INVALIDATED head entries as a side effect")
  void maxNZeroDropsInvalidatedHead() {
    BacklogLocationBuffer b = new BacklogLocationBuffer(4);
    BacklogEntry e0 = b.offerUnverified(loc(0));
    BacklogEntry e1 = b.offerUnverified(loc(1));
    e0.setValidity(Validity.INVALIDATED);
    e1.setValidity(Validity.VALIDATED);

    List<BacklogEntry> drained = b.pollContiguousValidatedHead(0);
    assertTrue(drained.isEmpty());
    // The INVALIDATED head should have been dropped; e1 now sits at head.
    assertEquals(1, b.size());
    assertFalse(b.isEmpty());
    assertSame(e1, b.pollContiguousValidatedHead(1).get(0));
  }

  @Test
  @DisplayName("pollContiguousValidatedHead does not NPE when drained concurrently (race guard)")
  void concurrentDrainDoesNotThrow() throws InterruptedException {
    // Regression guard: ArrayDeque is not internally synchronized, so a
    // concurrent drain could leave isEmpty()==false while peekFirst()==null,
    // which previously dereferenced null and threw an NPE (notably on lite,
    // where the backlog is unsupported but the drain path is still pulsed).
    final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
        new java.util.concurrent.atomic.AtomicReference<>();
    for (int iter = 0; iter < 200 && failure.get() == null; iter++) {
      BacklogLocationBuffer b = new BacklogLocationBuffer(64);
      for (int i = 0; i < 64; i++) {
        BacklogEntry e = b.offerUnverified(loc(i));
        e.setValidity(Validity.VALIDATED);
      }
      java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
      Runnable drain = () -> {
        try {
          start.await();
          while (!b.isEmpty()) {
            b.pollContiguousValidatedHead(8);
          }
        } catch (Throwable t) {
          failure.compareAndSet(null, t);
        }
      };
      Thread t1 = new Thread(drain);
      Thread t2 = new Thread(drain);
      t1.start();
      t2.start();
      start.countDown();
      t1.join();
      t2.join();
    }
    if (failure.get() != null) {
      throw new AssertionError("concurrent drain threw", failure.get());
    }
  }

  @Test
  @DisplayName("removeInvalidated cleans all invalidated entries and preserves relative order")
  void removeInvalidatedPrunesAllAndPreservesOrder() {
    BacklogLocationBuffer b = new BacklogLocationBuffer(6);
    BacklogEntry e0 = b.offerUnverified(loc(0));
    BacklogEntry e1 = b.offerUnverified(loc(1));
    BacklogEntry e2 = b.offerUnverified(loc(2));
    BacklogEntry e3 = b.offerUnverified(loc(3));
    BacklogEntry e4 = b.offerUnverified(loc(4));

    e0.setValidity(Validity.VALIDATED);
    e1.setValidity(Validity.INVALIDATED);
    e2.setValidity(Validity.UNVERIFIED);
    e3.setValidity(Validity.INVALIDATED);
    e4.setValidity(Validity.VALIDATED);

    assertEquals(2, b.invalidatedSize());
    assertEquals(5, b.size());

    int cleaned = b.removeInvalidated();
    assertEquals(2, cleaned);
    assertEquals(3, b.size());
    assertEquals(0, b.invalidatedSize());

    List<BacklogEntry> drained = b.pollContiguousValidatedHead(10);
    assertEquals(1, drained.size());
    assertSame(e0, drained.get(0));

    // Next at head is e2 (UNVERIFIED)
    assertEquals(2, b.size());
    assertSame(e2, b.peekOldestUnverified());
  }

  @Test
  @DisplayName("cleanIfHeuristicMet triggers when buffer is full and has invalid entries")
  void cleanIfHeuristicMetWhenFull() {
    BacklogLocationBuffer b = new BacklogLocationBuffer(3);
    BacklogEntry e0 = b.offerUnverified(loc(0));
    BacklogEntry e1 = b.offerUnverified(loc(1));
    BacklogEntry e2 = b.offerUnverified(loc(2));

    e1.setValidity(Validity.INVALIDATED);
    e0.setValidity(Validity.VALIDATED);
    e2.setValidity(Validity.VALIDATED);

    assertEquals(3, b.size());
    int cleaned = b.cleanIfHeuristicMet();
    assertEquals(1, cleaned);
    assertEquals(2, b.size());

    // Can now insert another candidate
    BacklogEntry e3 = b.offerUnverified(loc(3));
    assertNotNull(e3);
    assertEquals(3, b.size());
  }

  @Test
  @DisplayName("cleanIfHeuristicMet triggers when invalid count reaches threshold")
  void cleanIfHeuristicMetWhenInvalidThresholdReached() {
    BacklogLocationBuffer b = new BacklogLocationBuffer(16);
    for (int i = 0; i < 8; i++) {
      BacklogEntry e = b.offerUnverified(loc(i));
      if (i < 4) {
        e.setValidity(Validity.INVALIDATED);
      } else {
        e.setValidity(Validity.VALIDATED);
      }
    }

    assertEquals(4, b.invalidatedSize());
    assertEquals(8, b.size());

    int cleaned = b.cleanIfHeuristicMet();
    assertEquals(4, cleaned);
    assertEquals(4, b.size());
    assertEquals(0, b.invalidatedSize());
  }

  @Test
  @DisplayName("cleanIfHeuristicMet no-ops when invalid count is below threshold on non-full buffer")
  void cleanIfHeuristicMetNoopsBelowThreshold() {
    BacklogLocationBuffer b = new BacklogLocationBuffer(16);
    BacklogEntry e0 = b.offerUnverified(loc(0));
    BacklogEntry e1 = b.offerUnverified(loc(1));
    e0.setValidity(Validity.INVALIDATED);

    assertEquals(1, b.invalidatedSize());
    assertEquals(2, b.size());

    int cleaned = b.cleanIfHeuristicMet();
    assertEquals(0, cleaned);
    assertEquals(2, b.size());
  }
}
