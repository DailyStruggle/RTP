package io.github.dailystruggle.rtp.groupaddon;

import io.github.dailystruggle.rtp.common.selection.region.cache.CacheStage;
import io.github.dailystruggle.rtp.common.selection.region.cache.HotSink;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Composite subspace {@link HotSink} implementation (ADR-078 Phase 6).
 *
 * <p>Represents a hot consumer pool for multi-entity / group subspaces branching off
 * a cold pre-verified subspace inventory. Reports the multi-chunk footprint via
 * {@link #chunkCostPerEntry()}, enabling combinatorial subsumption (ADR-078 Rule 0:
 * {@code from.chunkCostPerEntry() >= to.chunkCostPerEntry()}).
 */
public final class GroupSubspaceHotSink implements HotSink<GroupSubspace> {
  private final String name;
  private final CacheStage<GroupSubspace> stage;
  private final CacheStage<?> coldSource;
  private final int chunkCostPerEntry;
  private final boolean extrinsicVerifier;
  private final boolean externallyLeased;
  private final boolean narrowsBeyondColdSource;
  private final Predicate<GroupSubspace> customAccepts;
  private final AtomicLong demandWeight = new AtomicLong(0L);

  /**
   * Computes the square chunk footprint for a given block radius.
   *
   * @param blockRadius radius in blocks
   * @return total chunks in the footprint (e.g. 1 chunk radius -> 3x3 = 9 chunks)
   */
  public static int calculateChunkCost(int blockRadius) {
    int chunkRadius = (Math.max(0, blockRadius) + 15) / 16;
    int side = 2 * chunkRadius + 1;
    return side * side;
  }

  public GroupSubspaceHotSink(
      String name,
      CacheStage<GroupSubspace> stage,
      CacheStage<?> coldSource,
      int chunkCostPerEntry) {
    this(name, stage, coldSource, chunkCostPerEntry, false, false, false, null);
  }

  public GroupSubspaceHotSink(
      String name,
      CacheStage<GroupSubspace> stage,
      CacheStage<?> coldSource,
      int chunkCostPerEntry,
      boolean extrinsicVerifier,
      boolean externallyLeased,
      boolean narrowsBeyondColdSource,
      Predicate<GroupSubspace> customAccepts) {
    this.name = (name != null) ? name : "group-subspace-hot-sink";
    this.stage = Objects.requireNonNull(stage, "stage cannot be null");
    this.coldSource = Objects.requireNonNull(coldSource, "coldSource cannot be null");
    this.chunkCostPerEntry = Math.max(1, chunkCostPerEntry);
    this.extrinsicVerifier = extrinsicVerifier;
    this.externallyLeased = externallyLeased;
    this.narrowsBeyondColdSource = narrowsBeyondColdSource;
    this.customAccepts = customAccepts;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public CacheStage<GroupSubspace> stage() {
    return stage;
  }

  @Override
  public CacheStage<?> coldSource() {
    return coldSource;
  }

  /**
   * Evaluates if this sink accepts the given candidate group subspace.
   *
   * <p>Fails closed: returns {@code false} if the subspace is {@code null}, not hot,
   * or holds closed/empty reservations without performing any chunk I/O (S-005).
   *
   * @param entry the candidate subspace holding live reservations
   * @return {@code true} if accepted
   */
  @Override
  public boolean accepts(GroupSubspace entry) {
    if (entry == null) return false;
    if (!entry.isHot()) return false;
    if (entry.reservations().isEmpty()) return false;
    if (customAccepts != null && !customAccepts.test(entry)) {
      return false;
    }
    return true;
  }

  @Override
  public boolean hasExtrinsicVerifier() {
    return extrinsicVerifier;
  }

  @Override
  public boolean isExternallyLeased() {
    return externallyLeased;
  }

  @Override
  public boolean narrowsBeyondColdSource() {
    return narrowsBeyondColdSource;
  }

  @Override
  public int chunkCostPerEntry() {
    return chunkCostPerEntry;
  }

  @Override
  public long demandWeight() {
    return demandWeight.get();
  }

  public void recordDemand(long weight) {
    demandWeight.addAndGet(Math.max(0, weight));
  }

  public void setDemandWeight(long weight) {
    demandWeight.set(Math.max(0, weight));
  }
}
