package io.github.dailystruggle.rtp.common.selection.region;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lock-free bounded FIFO buffer of pre-selected candidate locations for L3 backlog cache (ADR-028).
 * Preserves insertion order and supports head-blocking promotion of validated entries using
 * lock-free copy-then-swap state transitions.
 *
 * @see BacklogEntry
 * @see Validity
 * @see RegionFileCoord
 */
public final class BacklogLocationBuffer {

  private static final BacklogEntry[] EMPTY = new BacklogEntry[0];
  private static final int MAX_CAS_RETRIES = 64;

  /** Tri-state per-entry validity tag. */
  public enum Validity {
    /** Not yet examined by the Anvil pre-filter. Blocks promotion at the head. */
    UNVERIFIED,
    /** Passed the Anvil pre-filter; eligible for promotion. */
    VALIDATED,
    /** Failed the Anvil pre-filter; dropped from the head, never promoted. */
    INVALIDATED
  }

  /**
   * One unverified-or-verified entry in the L3 backlog. Identity-equal
   * (intentional): the same entry instance is shared between the owning
   * {@link BacklogLocationBuffer} and the world-level cross-region bin index, so
   * a single validity write is observable from both readers.
   */
  public static final class BacklogEntry {
    private final RTPLocation location;
    private volatile Validity validity;
    /**
     * Strong reference to the world-bin list this entry was inserted into, if any.
     * Pinning the list here keeps it alive in the {@link WorldBacklogBinIndex}'s
     * {@link java.lang.ref.WeakReference} for as long as the entry itself is
     * live in some {@link BacklogLocationBuffer}. Once every contributing entry
     * is removed (buffer drains or clears), the list becomes GC-eligible.
     */
    @SuppressWarnings("unused")
    private Object pinnedBinList;

    BacklogEntry(RTPLocation location) {
      this.location = Objects.requireNonNull(location, "location");
      this.validity = Validity.UNVERIFIED;
    }

    /**
     * Pins the world-bin list reference so it stays alive while this entry is.
     * Called by {@link WorldBacklogBinIndex#insert}. Idempotent within a single
     * insert; subsequent calls overwrite (only one bin per entry by design).
     *
     * @param binList list reference to pin; never {@code null}
     */
    void pinBinList(Object binList) {
      this.pinnedBinList = Objects.requireNonNull(binList, "binList");
    }

    /**
     * Returns the candidate location.
     *
     * @return the candidate location; never {@code null}
     */
    public RTPLocation location() {
      return location;
    }

    /**
     * Returns the current validity tag.
     *
     * @return current validity; reads are visible across threads ({@code volatile})
     */
    public Validity validity() {
      return validity;
    }

    /**
     * Sets the validity tag. Intended to be called by the verification stage
     * inside {@code Region.execute()}. Writes are visible to any thread that
     * subsequently reads {@link #validity()}.
     *
     * @param next new tag; never {@code null}
     */
    public void setValidity(Validity next) {
      this.validity = Objects.requireNonNull(next, "validity");
    }
  }

  private final int capacity;
  private final AtomicReference<BacklogEntry[]> state = new AtomicReference<>(EMPTY);

  /**
   * Constructs a new buffer with the given maximum capacity.
   *
   * @param capacity maximum number of entries; must be positive. Mirrors
   *                 {@code RegionSettings.backlogCacheCap}.
   * @throws IllegalArgumentException if {@code capacity <= 0}
   */
  public BacklogLocationBuffer(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive: " + capacity);
    }
    this.capacity = capacity;
  }

  /**
   * Returns the buffer's maximum capacity.
   *
   * @return the buffer's maximum capacity
   */
  public int capacity() {
    return capacity;
  }

  /**
   * Appends a fresh {@link Validity#UNVERIFIED} entry wrapping {@code location}.
   *
   * @param location candidate location; never {@code null}
   * @return the newly created entry, or {@code null} if the buffer is at capacity
   */
  public BacklogEntry offerUnverified(RTPLocation location) {
    Objects.requireNonNull(location, "location");
    BacklogEntry entry = new BacklogEntry(location);
    for (int retry = 0; retry < MAX_CAS_RETRIES; retry++) {
      BacklogEntry[] curr = state.get();
      if (curr.length >= capacity) return null;
      BacklogEntry[] next = Arrays.copyOf(curr, curr.length + 1);
      next[curr.length] = entry;
      if (state.compareAndSet(curr, next)) {
        return entry;
      }
    }
    return null;
  }

  /**
   * Drains contiguous {@link Validity#VALIDATED} entries from head, dropping {@link Validity#INVALIDATED} entries.
   * Stops at the first unverified entry or once {@code maxN} entries are drained.
   *
   * @param maxN maximum validated entries to return (non-negative)
   * @return validated entries in insertion order (never null)
   */
  public List<BacklogEntry> pollContiguousValidatedHead(int maxN) {
    if (maxN < 0) throw new IllegalArgumentException("maxN must be non-negative: " + maxN);
    for (int retry = 0; retry < MAX_CAS_RETRIES; retry++) {
      BacklogEntry[] curr = state.get();
      if (curr.length == 0) return Collections.emptyList();

      int headIdx = 0;
      List<BacklogEntry> out = new ArrayList<>(Math.min(maxN, 16));
      while (headIdx < curr.length) {
        BacklogEntry head = curr[headIdx];
        if (head == null) break;
        Validity v = head.validity();
        if (v == Validity.INVALIDATED) {
          headIdx++;
          continue;
        }
        if (v == Validity.UNVERIFIED) break;
        // VALIDATED
        if (out.size() >= maxN) break;
        out.add(head);
        headIdx++;
      }

      if (headIdx == 0) {
        return out;
      }

      int remLen = curr.length - headIdx;
      BacklogEntry[] next = new BacklogEntry[remLen];
      System.arraycopy(curr, headIdx, next, 0, remLen);

      if (state.compareAndSet(curr, next)) {
        return out;
      }
    }
    return Collections.emptyList();
  }

  /**
   * Returns the oldest entry whose validity is still {@link Validity#UNVERIFIED},
   * or {@code null} if the buffer contains no such entry. Used to pick
   * the next bin to verify in {@code Region.execute()}.
   *
   * @return the oldest unverified entry, or {@code null} if none
   */
  public BacklogEntry peekOldestUnverified() {
    BacklogEntry[] curr = state.get();
    for (BacklogEntry e : curr) {
      if (e != null && e.validity() == Validity.UNVERIFIED) return e;
    }
    return null;
  }

  /**
   * Returns the current entry count, including all validity states.
   *
   * @return current entry count
   */
  public int size() {
    return state.get().length;
  }

  /**
   * Returns the number of entries currently tagged {@link Validity#VALIDATED}.
   *
   * @return number of validated entries
   */
  public int validatedSize() {
    BacklogEntry[] curr = state.get();
    int n = 0;
    for (BacklogEntry e : curr) {
      if (e != null && e.validity() == Validity.VALIDATED) n++;
    }
    return n;
  }

  /**
   * Returns the number of entries currently tagged {@link Validity#INVALIDATED}.
   *
   * @return number of invalidated entries
   */
  public int invalidatedSize() {
    BacklogEntry[] curr = state.get();
    int n = 0;
    for (BacklogEntry e : curr) {
      if (e != null && e.validity() == Validity.INVALIDATED) n++;
    }
    return n;
  }

  /**
   * Cleans invalidated entries via lock-free copy-then-swap compaction if heuristic conditions are met.
   * Compaction is triggered when the buffer is full (freeing capacity for new candidate selection)
   * or when the invalidated entry count reaches a significant fraction of capacity.
   *
   * @return number of removed invalidated entries, or {@code 0} if heuristic was not met
   */
  public int cleanIfHeuristicMet() {
    for (int retry = 0; retry < MAX_CAS_RETRIES; retry++) {
      BacklogEntry[] curr = state.get();
      int invalidCount = 0;
      for (BacklogEntry e : curr) {
        if (e != null && e.validity() == Validity.INVALIDATED) {
          invalidCount++;
        }
      }
      if (invalidCount == 0) return 0;

      boolean shouldClean = (curr.length >= capacity)
          || (invalidCount >= Math.max(4, capacity / 4));

      if (!shouldClean) return 0;

      BacklogEntry[] next = new BacklogEntry[curr.length - invalidCount];
      int idx = 0;
      for (BacklogEntry e : curr) {
        if (e != null && e.validity() != Validity.INVALIDATED) {
          next[idx++] = e;
        }
      }

      if (state.compareAndSet(curr, next)) {
        return invalidCount;
      }
    }
    return 0;
  }

  /**
   * Removes all entries tagged {@link Validity#INVALIDATED} using lock-free copy-then-swap compaction
   * while preserving the relative FIFO order of surviving entries.
   *
   * @return number of removed entries
   */
  public int removeInvalidated() {
    for (int retry = 0; retry < MAX_CAS_RETRIES; retry++) {
      BacklogEntry[] curr = state.get();
      int invalidCount = 0;
      for (BacklogEntry e : curr) {
        if (e != null && e.validity() == Validity.INVALIDATED) {
          invalidCount++;
        }
      }
      if (invalidCount == 0) return 0;

      BacklogEntry[] next = new BacklogEntry[curr.length - invalidCount];
      int idx = 0;
      for (BacklogEntry e : curr) {
        if (e != null && e.validity() != Validity.INVALIDATED) {
          next[idx++] = e;
        }
      }

      if (state.compareAndSet(curr, next)) {
        return invalidCount;
      }
    }
    return 0;
  }

  /**
   * Returns {@code true} if no entries are present.
   *
   * @return {@code true} if the buffer is empty
   */
  public boolean isEmpty() {
    return state.get().length == 0;
  }

  /** Removes all entries. Validity tags on already-issued entries are unaffected. */
  public void clear() {
    state.set(EMPTY);
  }
}
