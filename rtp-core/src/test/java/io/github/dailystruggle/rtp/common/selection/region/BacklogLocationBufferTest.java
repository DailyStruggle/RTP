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
 * Unit tests for {@link BacklogLocationBuffer}, the L3 backlog cache primitive
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
}
