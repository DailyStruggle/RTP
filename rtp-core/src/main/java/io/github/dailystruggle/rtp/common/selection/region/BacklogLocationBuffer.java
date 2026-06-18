package io.github.dailystruggle.rtp.common.selection.region;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Bounded, order-preserving FIFO buffer of pre-selected but not-yet-fully-verified
 * candidate locations for the L3 backlog cache (ADR-028).
 *
 * <p>Each entry carries a tri-state {@link Validity} tag:
 * <ul>
 *   <li>{@link Validity#UNVERIFIED} — the candidate has not yet been examined by
 *       the Anvil pre-filter.</li>
 *   <li>{@link Validity#VALIDATED} — the candidate passed the Anvil pre-filter
 *       and is ready to be promoted into L2 ({@code unkeptLocations}).</li>
 *   <li>{@link Validity#INVALIDATED} — the candidate failed the Anvil pre-filter
 *       and must be discarded; it does not stall promotion of younger entries.</li>
 * </ul>
 *
 * <h2>Order preservation</h2>
 * Insertion order is preserved. Promotion via {@link #pollContiguousValidatedHead(int)}
 * drains the head while it is {@link Validity#VALIDATED}, dropping any
 * {@link Validity#INVALIDATED} entries it encounters at the head, and stops at
 * the first {@link Validity#UNVERIFIED} entry. This is the &quot;head-blocking&quot;
 * semantics that lets L3 behave as a virtual L2 from the consumer's perspective.
 *
 * <h2>Capacity</h2>
 * The buffer is bounded by its construction-time capacity (mirroring
 * {@code backlogCacheCap} from the owning region's {@link RegionSettings}). Once
 * full, {@link #offerUnverified(RTPLocation)} returns {@code null} and the caller
 * is expected to skip the insert; older entries are <em>not</em> evicted.
 *
 * <h2>Thread-safety</h2>
 * This class is not internally synchronized. The expected usage model is
 * single-writer from {@code Region.execute()}; cross-thread visibility of
 * validity transitions is provided by the {@code volatile} field on
 * {@link BacklogEntry}. If multi-threaded mutation is ever required, callers
 * must add their own synchronization.
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
  /**
   * Guards every access to {@link #entries}. {@code ArrayDeque} is not
   * internally synchronized, and the backlog drain / refill / verification
   * paths can run on different async scheduler threads. Without this lock a
   * concurrent drain could leave {@code isEmpty()==false} while
   * {@code peekFirst()==null} (observed as an NPE in
   * {@link #pollContiguousValidatedHead(int)}), or corrupt the deque's
   * internal state outright. All public methods that touch {@code entries}
   * synchronize on this monitor.
   */
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
   * Drains contiguous {@link Validity#VALIDATED} entries from the head, dropping
   * any {@link Validity#INVALIDATED} head entries it encounters along the way.
   * Stops at the first {@link Validity#UNVERIFIED} entry, when {@code maxN}
   * validated entries have been collected, or when the buffer is empty.
   *
   * @param maxN maximum number of validated entries to return; must be
   *             non-negative. {@code 0} returns an empty list (but still drops
   *             any {@code INVALIDATED} head entries as a side effect).
   * @return validated entries in insertion order; never {@code null}, possibly empty
   */
  public List<BacklogEntry> pollContiguousValidatedHead(int maxN) {
    if (maxN < 0) throw new IllegalArgumentException("maxN must be non-negative: " + maxN);
    List<BacklogEntry> out = new ArrayList<>(Math.min(maxN, 16));
    synchronized (lock) {
      while (!entries.isEmpty()) {
        BacklogEntry head = entries.peekFirst();
        // Defensive null guard: even under the lock this stays correct, and it
        // documents that a null head means "nothing more to drain" rather than
        // an NPE — the symptom seen on the lite assembly where the L3 backlog
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
   * Eagerly removes every entry currently tagged {@link Validity#INVALIDATED}
   * from anywhere in the buffer, not just the head. Intended to be called by
   * {@code Region.processBacklog()} immediately after a bin-verification pass
   * so explicit Anvil-pre-filter rejections are ejected (and the displayed L3
   * occupancy reflects only candidates still in play) without waiting for the
   * rejected entries to drift to the head via head-blocking drain.
   *
   * <p>Order of remaining entries is preserved. {@code WorldBacklogBinIndex}
   * may still hold weak/list references to the removed entries; this is
   * harmless because the verification loop skips any entry whose validity is
   * no longer {@code UNVERIFIED}, and the bin list itself becomes
   * GC-eligible once every contributing buffer has dropped its strong
   * references.
   *
   * @return the number of entries removed
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
