package io.github.dailystruggle.rtp.folia.scheduling;

import io.github.dailystruggle.rtp.common.governor.RegionShareAllocator;
import io.github.dailystruggle.rtp.common.predict.TickTokenBucket;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

/**
 * Per-tick-thread and per-cell-group token buckets for RTP work on Folia region threads
 * (ADR-112, ADR-123 Sections 3, 6, 8, Invariant I1).
 *
 * <p>A tick thread earns {@code budget} ns of RTP time per 50 ms and holds at most one budget,
 * so a region whose tick ran long (the server's own load) gets no catch-up allowance: RTP adds
 * at most about one budget to any region tick. With {@code autoTune} enabled, each resolved
 * cell group {@code k} maintains an inner group bucket ({@code S_k + e_k}) inside the outer
 * per-thread bucket ({@code S_cfg}, or {@code min(2 * S_cfg, 25 ms)} when the thread has run
 * RTP work for no other group in the last second — Invariant I1).
 */
final class RegionThreadBudget {
  static final String GLOBAL_GROUP_ID = "global";
  static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
  static final long ONE_SECOND_NANOS = TimeUnit.SECONDS.toNanos(1);
  static final long TWO_SECONDS_NANOS = TimeUnit.SECONDS.toNanos(2);
  static final long GROUP_IDLE_NANOS = TimeUnit.SECONDS.toNanos(60);
  static final long MAX_LOAN_THREAD_CAP_NANOS = TimeUnit.MILLISECONDS.toNanos(25);
  static final int CELL_SHIFT = 3;
  /** {@link #begin()} result for a span nested in another; never charged. */
  static final long NESTED = Long.MIN_VALUE;

  /** Predicate testing whether chunk {@code (cx, cz)} belongs to the current tick region. */
  @FunctionalInterface
  interface OwnershipPredicate {
    boolean isOwnedByCurrentRegion( int cx, int cz);
  }

  record CellId(UUID worldId, int sx, int sz) {}

  static final class LiveGroup {
    final String groupId;
    final UUID worldId;
    volatile int repCx;
    volatile int repCz;
    volatile long lastActiveNanos;
    final TickTokenBucket bucket;
    final AtomicLong interactiveChargedNanos = new AtomicLong(0L);
    final AtomicLong backgroundChargedNanos = new AtomicLong(0L);

    LiveGroup(String groupId, UUID worldId, int repCx, int repCz, long nowNanos, long initialBudgetNanos) {
      this.groupId = groupId;
      this.worldId = worldId;
      this.repCx = repCx;
      this.repCz = repCz;
      this.lastActiveNanos = nowNanos;
      this.bucket = new TickTokenBucket(TICK_NANOS, initialBudgetNanos, false, nowNanos);
    }
  }

  private static final class ThreadBucketState {
    final TickTokenBucket bucket = new TickTokenBucket(TICK_NANOS);
    long nestingDepth = 0L;
  }

  private static final class ThreadActivity {
    final ConcurrentHashMap<String, Long> groupLastRunNanos = new ConcurrentHashMap<>();
    volatile long lastRunNanos = Long.MIN_VALUE;
  }

  private final LongSupplier clock;
  private final LongSupplier budgetNanos;
  private final BooleanSupplier autoTuneEnabled;
  private final ToLongFunction<String> groupShareNanos;
  private final ToLongFunction<String> groupLoanNanos;
  private final RegionShareAllocator allocator;

  /** Thread-confined token bucket and span nesting depth. */
  private final ThreadLocal<ThreadBucketState> state = ThreadLocal.withInitial(ThreadBucketState::new);
  private final ThreadLocal<String> currentSliceGroup = new ThreadLocal<>();
  private final ConcurrentHashMap<String, AtomicLong> groupChargedNanos = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<CellId, String> cellToGroup = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, LiveGroup> liveGroups = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Long, ThreadActivity> threadActivities = new ConcurrentHashMap<>();
  private final AtomicInteger groupSeq = new AtomicInteger(0);
  private final AtomicInteger mergeProbeCursor = new AtomicInteger(0);

  RegionThreadBudget(LongSupplier clock, LongSupplier budgetNanos) {
    this(clock, budgetNanos, () -> false, null, null, new RegionShareAllocator());
  }

  RegionThreadBudget(
      LongSupplier clock,
      LongSupplier budgetNanos,
      BooleanSupplier autoTuneEnabled,
      ToLongFunction<String> groupShareNanos,
      ToLongFunction<String> groupLoanNanos,
      RegionShareAllocator allocator
  ) {
    this.clock = clock;
    this.budgetNanos = budgetNanos;
    this.autoTuneEnabled = autoTuneEnabled != null ? autoTuneEnabled : () -> false;
    this.groupShareNanos = groupShareNanos;
    this.groupLoanNanos = groupLoanNanos;
    this.allocator = allocator != null ? allocator : new RegionShareAllocator();
  }

  long now() {
    return clock.getAsLong();
  }

  /** Drops the calling thread's bucket so the thread-local does not outlive this budget. */
  void release() {
    state.remove();
    currentSliceGroup.remove();
  }

  RegionShareAllocator allocator() {
    return allocator;
  }

  /**
   * Resolves the cell group for {@code (worldId, cx, cz)} at slice start and runs one round-robin
   * merge probe over the world's other live groups (ADR-123 Section 3).
   */
  String beginSliceGroup(UUID worldId, int cx, int cz, OwnershipPredicate ownership) {
    if (worldId == null) return null;
    long now = clock.getAsLong();
    evictIdleGroups(now);

    int sx = cx >> CELL_SHIFT;
    int sz = cz >> CELL_SHIFT;
    CellId cellId = new CellId(worldId, sx, sz);

    String previousGroupId = cellToGroup.get(cellId);
    LiveGroup resolved = null;

    if (previousGroupId != null) {
      LiveGroup prevGroup = liveGroups.get(previousGroupId);
      if (prevGroup != null && ownership.isOwnedByCurrentRegion(prevGroup.repCx, prevGroup.repCz)) {
        resolved = prevGroup;
      }
    }

    List<LiveGroup> worldGroups = liveGroupsForWorld(worldId);
    if (resolved == null) {
      worldGroups.sort(Comparator.comparingInt(g ->
          Math.abs((g.repCx >> CELL_SHIFT) - sx) + Math.abs((g.repCz >> CELL_SHIFT) - sz)));
      for (LiveGroup candidate : worldGroups) {
        if (previousGroupId != null && previousGroupId.equals(candidate.groupId)) continue;
        if (ownership.isOwnedByCurrentRegion(candidate.repCx, candidate.repCz)) {
          resolved = candidate;
          break;
        }
      }
    }

    if (resolved == null) {
      String newGroupId = "group-" + groupSeq.incrementAndGet();
      long sCfg = baseBudget();
      long startBudget = autoTuneEnabled.getAsBoolean() ? Math.max(1L, sCfg / 2L) : sCfg;
      if (previousGroupId != null) {
        LiveGroup old = liveGroups.get(previousGroupId);
        if (old != null) {
          startBudget = Math.min(startBudget, old.bucket.configuredCeilingNanos());
        }
        allocator.recordSplit(previousGroupId, newGroupId, sCfg, now);
      }
      LiveGroup created = new LiveGroup(newGroupId, worldId, cx, cz, now, startBudget);
      if (autoTuneEnabled.getAsBoolean()) {
        created.bucket.initializeFull(startBudget, now);
      }
      liveGroups.put(newGroupId, created);
      resolved = created;
    } else {
      resolved.lastActiveNanos = now;
    }

    cellToGroup.put(cellId, resolved.groupId);

    // Round-robin merge probe: test one other live group in the same world per slice (ADR-123 Section 3)
    List<LiveGroup> others = new ArrayList<>();
    for (LiveGroup g : worldGroups) {
      if (!g.groupId.equals(resolved.groupId) && liveGroups.containsKey(g.groupId)) {
        others.add(g);
      }
    }
    if (!others.isEmpty()) {
      int idx = Math.floorMod(mergeProbeCursor.getAndIncrement(), others.size());
      LiveGroup probeTarget = others.get(idx);
      if (ownership.isOwnedByCurrentRegion(probeTarget.repCx, probeTarget.repCz)) {
        resolved = mergeLiveGroups(resolved, probeTarget, now);
      }
    }

    currentSliceGroup.set(resolved.groupId);
    return resolved.groupId;
  }

  void endSliceGroup() {
    currentSliceGroup.remove();
  }

  private LiveGroup mergeLiveGroups(LiveGroup a, LiveGroup b, long nowNanos) {
    LiveGroup surviving = a.groupId.compareTo(b.groupId) <= 0 ? a : b;
    LiveGroup absorbed = (surviving == a) ? b : a;
    if (liveGroups.remove(absorbed.groupId, absorbed)) {
      surviving.bucket.clampToMin(absorbed.bucket);
      surviving.interactiveChargedNanos.addAndGet(absorbed.interactiveChargedNanos.get());
      surviving.backgroundChargedNanos.addAndGet(absorbed.backgroundChargedNanos.get());
      surviving.lastActiveNanos = nowNanos;
      for (Map.Entry<CellId, String> e : cellToGroup.entrySet()) {
        if (absorbed.groupId.equals(e.getValue())) {
          cellToGroup.put(e.getKey(), surviving.groupId);
        }
      }
      allocator.recordMerge(surviving.groupId, absorbed.groupId, nowNanos);
    }
    return surviving;
  }

  private List<LiveGroup> liveGroupsForWorld(UUID worldId) {
    List<LiveGroup> list = new ArrayList<>();
    for (LiveGroup g : liveGroups.values()) {
      if (worldId.equals(g.worldId)) {
        list.add(g);
      }
    }
    return list;
  }

  private void evictIdleGroups(long nowNanos) {
    for (Map.Entry<String, LiveGroup> e : liveGroups.entrySet()) {
      LiveGroup g = e.getValue();
      if (g != null && nowNanos >= g.lastActiveNanos && (nowNanos - g.lastActiveNanos) > GROUP_IDLE_NANOS) {
        if (liveGroups.remove(e.getKey(), g)) {
          cellToGroup.entrySet().removeIf(ce -> e.getKey().equals(ce.getValue()));
        }
      }
    }
  }

  /** Whether the calling thread (and active cell group when autoTune is enabled) may start another RTP task. */
  boolean hasBudget() {
    return hasBudget(currentSliceGroup.get());
  }

  boolean hasBudget(String groupId) {
    String gId = (groupId != null) ? groupId : currentSliceGroup.get();
    long threadTokens = refillThreadBucket(gId);
    if (threadTokens <= 0L) {
      return false;
    }
    if (!autoTuneEnabled.getAsBoolean() || gId == null) {
      return true;
    }
    return refillGroupBucket(gId) > 0L;
  }

  /** Remaining tokens for the calling thread, nanoseconds (negative while in debt). */
  long remaining() {
    String gId = currentSliceGroup.get();
    long threadRem = refillThreadBucket(gId);
    if (!autoTuneEnabled.getAsBoolean() || gId == null) {
      return threadRem;
    }
    return Math.min(threadRem, refillGroupBucket(gId));
  }

  long remainingForGroup(String groupId) {
    return refillGroupBucket(groupId);
  }

  /**
   * Opens a measured span; returns its start, or {@link #NESTED} when nested inside another span on
   * this thread (the outer span already covers the time, so it must not be charged twice).
   */
  long begin() {
    ThreadBucketState s = state.get();
    return (s.nestingDepth++ == 0L) ? clock.getAsLong() : NESTED;
  }

  /** Closes a span opened by {@link #begin()} and charges it when it was the outermost one. */
  void end(long start) {
    ThreadBucketState s = state.get();
    if (s.nestingDepth > 0L) s.nestingDepth--;
    if (start != NESTED) charge(clock.getAsLong() - start);
  }

  /**
   * Opens a measured span for the global region ({@code k = "global"}, ADR-123 Section 2 & 11).
   */
  long beginGlobal() {
    ThreadBucketState s = state.get();
    return (s.nestingDepth++ == 0L) ? clock.getAsLong() : NESTED;
  }

  /**
   * Closes a global-region span opened by {@link #beginGlobal()} and records its elapsed time
   * under {@link #GLOBAL_GROUP_ID} without bounding global-region execution when unactuated
   * (ADR-123 Section 11).
   */
  void endGlobal(long start) {
    ThreadBucketState s = state.get();
    if (s.nestingDepth > 0L) s.nestingDepth--;
    if (start != NESTED) {
      long elapsed = Math.max(0L, clock.getAsLong() - start);
      recordGroupCharge(GLOBAL_GROUP_ID, elapsed);
    }
  }

  void recordGroupCharge(String groupId, long nanos) {
    if (groupId == null || nanos <= 0L) return;
    groupChargedNanos.computeIfAbsent(groupId, k -> new AtomicLong(0L)).addAndGet(nanos);
  }

  long groupChargedNanos(String groupId) {
    if (groupId == null) return 0L;
    AtomicLong c = groupChargedNanos.get(groupId);
    return (c != null) ? c.get() : 0L;
  }

  long globalChargedNanos() {
    return groupChargedNanos(GLOBAL_GROUP_ID);
  }

  /** Debits {@code nanos} of measured work from the calling thread's bucket (and group bucket when enabled). */
  void charge(long nanos) {
    charge(currentSliceGroup.get(), nanos, false);
  }

  void charge(String groupId, long nanos, boolean interactive) {
    long debit = Math.max(0L, nanos);
    long now = clock.getAsLong();
    String gId = (groupId != null) ? groupId : currentSliceGroup.get();

    if (gId != null) {
      recordThreadActivity(Thread.currentThread().getId(), gId, now);
      recordGroupCharge(gId, debit);
    }

    long b = effectiveThreadBudget(gId, now);
    state.get().bucket.charge(debit, b, now);

    if (autoTuneEnabled.getAsBoolean() && gId != null) {
      LiveGroup g = liveGroups.get(gId);
      if (g != null) {
        long gb = effectiveGroupBudget(gId);
        g.bucket.charge(debit, gb, now);
        g.lastActiveNanos = now;
        if (interactive) {
          g.interactiveChargedNanos.addAndGet(debit);
        } else {
          g.backgroundChargedNanos.addAndGet(debit);
        }
      }
    }
  }

  void recordThreadActivity(long threadId, String groupId, long nowNanos) {
    if (groupId == null) return;
    ThreadActivity act = threadActivities.computeIfAbsent(threadId, k -> new ThreadActivity());
    act.lastRunNanos = nowNanos;
    act.groupLastRunNanos.put(groupId, nowNanos);
    act.groupLastRunNanos.entrySet().removeIf(e ->
        nowNanos >= e.getValue() && (nowNanos - e.getValue()) > ONE_SECOND_NANOS);
  }

  /**
   * Returns {@code true} if {@code threadId} has run RTP work for no group other than {@code groupId}
   * in the last 1 second (ADR-123 Section 3 & 8, Invariant I1).
   */
  boolean isThreadExclusiveToGroup(long threadId, String groupId, long nowNanos) {
    if (groupId == null) return false;
    ThreadActivity act = threadActivities.get(threadId);
    if (act == null) return true;
    for (Map.Entry<String, Long> e : act.groupLastRunNanos.entrySet()) {
      Long last = e.getValue();
      if (last != null && nowNanos >= last && (nowNanos - last) <= ONE_SECOND_NANOS) {
        if (!groupId.equals(e.getKey())) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Returns the number of distinct tick threads {@code Theta} that ran RTP work in the last 1 second
   * (ADR-123 Section 8).
   */
  int distinctActiveThreads(long nowNanos) {
    int count = 0;
    for (ThreadActivity act : threadActivities.values()) {
      if (act != null && act.lastRunNanos != Long.MIN_VALUE
          && nowNanos >= act.lastRunNanos
          && (nowNanos - act.lastRunNanos) <= ONE_SECOND_NANOS) {
        count++;
      }
    }
    return count;
  }

  int liveGroupCount() {
    return liveGroups.size();
  }

  String groupForCell(UUID worldId, int cx, int cz) {
    if (worldId == null) return null;
    return cellToGroup.get(new CellId(worldId, cx >> CELL_SHIFT, cz >> CELL_SHIFT));
  }

  /** Clamped to one tick: RTP may never be granted a whole region tick. */
  private long baseBudget() {
    return Math.max(1L, Math.min(budgetNanos.getAsLong(), TICK_NANOS));
  }

  long effectiveGroupBudget(String groupId) {
    long sCfg = baseBudget();
    if (!autoTuneEnabled.getAsBoolean() || groupId == null) {
      return sCfg;
    }
    long share = (groupShareNanos != null) ? groupShareNanos.applyAsLong(groupId) : -1L;
    if (share <= 0L) {
      LiveGroup g = liveGroups.get(groupId);
      share = (g != null && g.bucket.configuredCeilingNanos() > 0L)
          ? g.bucket.configuredCeilingNanos()
          : Math.max(1L, sCfg / 2L);
    }
    long loan = (groupLoanNanos != null) ? Math.max(0L, groupLoanNanos.applyAsLong(groupId)) : 0L;
    if (loan > 0L && isThreadExclusiveToGroup(Thread.currentThread().getId(), groupId, clock.getAsLong())) {
      return Math.min(Math.min(2L * sCfg, MAX_LOAN_THREAD_CAP_NANOS), share + loan);
    }
    return Math.min(sCfg, share);
  }

  long effectiveThreadBudget(String groupId, long nowNanos) {
    long sCfg = baseBudget();
    if (!autoTuneEnabled.getAsBoolean() || groupId == null) {
      return sCfg;
    }
    long loan = (groupLoanNanos != null) ? Math.max(0L, groupLoanNanos.applyAsLong(groupId)) : 0L;
    if (loan > 0L && isThreadExclusiveToGroup(Thread.currentThread().getId(), groupId, nowNanos)) {
      long share = (groupShareNanos != null) ? groupShareNanos.applyAsLong(groupId) : sCfg;
      if (share <= 0L) share = sCfg;
      return Math.min(Math.min(2L * sCfg, MAX_LOAN_THREAD_CAP_NANOS), share + loan);
    }
    return sCfg;
  }

  private long refillGroupBucket(String groupId) {
    if (groupId == null) return baseBudget();
    long now = clock.getAsLong();
    LiveGroup g = liveGroups.computeIfAbsent(groupId,
        id -> new LiveGroup(id, new UUID(0L, 0L), 0, 0, now, effectiveGroupBudget(id)));
    return g.bucket.refill(effectiveGroupBudget(groupId), now);
  }

  private long refillThreadBucket(String groupId) {
    long now = clock.getAsLong();
    long b = effectiveThreadBudget(groupId, now);
    return state.get().bucket.refill(b, now);
  }
}
