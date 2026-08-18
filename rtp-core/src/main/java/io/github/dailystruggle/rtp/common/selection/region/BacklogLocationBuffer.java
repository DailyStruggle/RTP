package io.github.dailystruggle.rtp.common.selection.region;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Bounded FIFO buffer of pre-selected candidate locations for L3 backlog cache (ADR-028).
 * Preserves insertion order and supports head-blocking promotion of validated entries.
 *
 * @see BacklogEntry
 * @see Validity
 * @see RegionFileCoord
 */
public final class BacklogLocationBuffer {

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
  private final Deque<BacklogEntry> entries;
  /** Lock guarding all access to {@link #entries} across concurrent drain/refill paths. */
  private final Object lock = new Object();

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
    this.entries = new ArrayDeque<>(Math.min(capacity, 1024));
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
    synchronized (lock) {
      if (entries.size() >= capacity) return null;
      BacklogEntry entry = new BacklogEntry(location);
      entries.addLast(entry);
      return entry;
    }
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
    List<BacklogEntry> out = new ArrayList<>(Math.min(maxN, 16));
    synchronized (lock) {
      while (!entries.isEmpty()) {
        BacklogEntry head = entries.peekFirst();
        // Defensive null guard: even under the lock this stays correct, and it
        // documents that a null head means "nothing more to drain" rather than
        // an NPE - the symptom seen on the lite assembly where the L3 backlog
        // is unsupported but the drain path is still pulsed by Region.execute().
        if (head == null) break;
        Validity v = head.validity();
        if (v == Validity.INVALIDATED) {
          entries.pollFirst();
          continue;
        }
        if (v == Validity.UNVERIFIED) break;
        // VALIDATED
        if (out.size() >= maxN) break;
        out.add(entries.pollFirst());
      }
    }
    return out;
  }

  /**
   * Returns the oldest entry whose validity is still {@link Validity#UNVERIFIED},
   * or {@code null} if the buffer contains no such entry. Used to pick
   * the next bin to verify in {@code Region.execute()}.
   *
   * @return the oldest unverified entry, or {@code null} if none
   */
  public BacklogEntry peekOldestUnverified() {
    synchronized (lock) {
      for (BacklogEntry e : entries) {
        if (e.validity() == Validity.UNVERIFIED) return e;
      }
      return null;
    }
  }

  /**
   * Returns the current entry count, including all validity states.
   *
   * @return current entry count
   */
  public int size() {
    synchronized (lock) {
      return entries.size();
    }
  }

  /**
   * Returns the number of entries currently tagged {@link Validity#VALIDATED}.
   *
   * @return number of validated entries
   */
  public int validatedSize() {
    synchronized (lock) {
      int n = 0;
      for (BacklogEntry e : entries) {
        if (e.validity() == Validity.VALIDATED) n++;
      }
      return n;
    }
  }

  /**
   * Removes all entries tagged {@link Validity#INVALIDATED} while preserving relative order.
   *
   * @return number of removed entries
   */
  public int removeInvalidated() {
    synchronized (lock) {
      int removed = 0;
      java.util.Iterator<BacklogEntry> it = entries.iterator();
      while (it.hasNext()) {
        if (it.next().validity() == Validity.INVALIDATED) {
          it.remove();
          removed++;
        }
      }
      return removed;
    }
  }

  /**
   * Returns {@code true} if no entries are present.
   *
   * @return {@code true} if the buffer is empty
   */
  public boolean isEmpty() {
    synchronized (lock) {
      return entries.isEmpty();
    }
  }

  /** Removes all entries. Validity tags on already-issued entries are unaffected. */
  public void clear() {
    synchronized (lock) {
      entries.clear();
    }
  }
}
