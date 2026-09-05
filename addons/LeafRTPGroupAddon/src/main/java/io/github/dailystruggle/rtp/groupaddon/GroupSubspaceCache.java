package io.github.dailystruggle.rtp.groupaddon;

import io.github.dailystruggle.rtp.common.selection.region.cache.CacheStage;
import io.github.dailystruggle.rtp.common.selection.region.cache.KeyedCacheStage;
import io.github.dailystruggle.rtp.common.selection.region.cache.SimpleCacheStage;

import java.util.Objects;

/**
 * Manages the three-tier caching pipeline for group subspaces (Stage 4, leafrtp-group-addon-ADR-002,
 * ported to ADR-078 composable cache stage contracts):
 * <ul>
 *   <li><b>Group Hot (Kept):</b> Pre-verified subspaces with active {@link io.github.dailystruggle.rtp.api.world.ChunkReservation}
 *       tickets held for immediate teleport dispatch, backed by {@link KeyedCacheStage}. Deterministic disposal
 *       on overflow, eviction, or shutdown closes chunk reservations (REQ-RTP-S-002).</li>
 *   <li><b>Group Cold (Unkept):</b> Pre-verified subspace coordinates with chunk tickets released.</li>
 *   <li><b>Group Backlog:</b> FIFO queue of unverified candidate subspaces screened off-tick.</li>
 * </ul>
 */
public final class GroupSubspaceCache {
  public static final int DEFAULT_HOT_CAP = 2;
  public static final int DEFAULT_COLD_CAP = 5;
  public static final int DEFAULT_BACKLOG_CAP = 10;

  private final int hotCap;
  private final int coldCap;
  private final int backlogCap;

  private final KeyedCacheStage<String, GroupSubspace> hotStage;
  private final KeyedCacheStage<String, GroupSubspace> coldStage;
  private final KeyedCacheStage<String, GroupBacklogEntry> backlogStage;

  public GroupSubspaceCache() {
    this(DEFAULT_HOT_CAP, DEFAULT_COLD_CAP, DEFAULT_BACKLOG_CAP);
  }

  public GroupSubspaceCache(int hotCap, int coldCap, int backlogCap) {
    this.hotCap = Math.max(1, hotCap);
    this.coldCap = Math.max(0, coldCap);
    this.backlogCap = Math.max(0, backlogCap);

    this.hotStage =
        new KeyedCacheStage<>(
            "group-hot",
            (key, cap) ->
                new SimpleCacheStage<>(
                    key + ":hot",
                    cap,
                    null,
                    null,
                    GroupSubspace::close));

    this.coldStage =
        new KeyedCacheStage<>(
            "group-cold",
            (key, cap) ->
                new SimpleCacheStage<>(
                    key + ":cold",
                    cap,
                    null,
                    null,
                    null));

    this.backlogStage =
        new KeyedCacheStage<>(
            "group-backlog",
            (key, cap) ->
                new SimpleCacheStage<>(
                    key + ":backlog",
                    cap,
                    null,
                    null,
                    null));
  }

  public int getHotCap() {
    return hotCap;
  }

  public int getColdCap() {
    return coldCap;
  }

  public int getBacklogCap() {
    return backlogCap;
  }

  public KeyedCacheStage<String, GroupSubspace> hotStage() {
    return hotStage;
  }

  public KeyedCacheStage<String, GroupSubspace> coldStage() {
    return coldStage;
  }

  public KeyedCacheStage<String, GroupBacklogEntry> backlogStage() {
    return backlogStage;
  }

  /**
   * Returns a {@link CacheStage} partition for hot subspaces for the specified profile key.
   *
   * @param profileKey region or profile identifier
   * @return hot cache stage partition
   */
  public CacheStage<GroupSubspace> openHotStage(String profileKey) {
    Objects.requireNonNull(profileKey, "profileKey cannot be null");
    return hotStage.open(profileKey, hotCap);
  }

  /**
   * Returns a {@link CacheStage} partition for cold subspaces for the specified profile key.
   *
   * @param profileKey region or profile identifier
   * @return cold cache stage partition
   */
  public CacheStage<GroupSubspace> openColdStage(String profileKey) {
    Objects.requireNonNull(profileKey, "profileKey cannot be null");
    return coldStage.open(profileKey, coldCap);
  }

  /**
   * Returns a {@link CacheStage} partition for backlog entries for the specified profile key.
   *
   * @param profileKey region or profile identifier
   * @return backlog cache stage partition
   */
  public CacheStage<GroupBacklogEntry> openBacklogStage(String profileKey) {
    Objects.requireNonNull(profileKey, "profileKey cannot be null");
    return backlogStage.open(profileKey, backlogCap);
  }

  /**
   * Creates a composite subspace {@link GroupSubspaceHotSink} for the given profile key
   * reporting the calculated or specified chunk footprint cost.
   *
   * @param profileKey region or profile identifier
   * @param chunkCostPerEntry chunk footprint cost per entry
   * @return composite subspace hot sink
   */
  public GroupSubspaceHotSink createHotSink(String profileKey, int chunkCostPerEntry) {
    CacheStage<GroupSubspace> hot = openHotStage(profileKey);
    CacheStage<GroupSubspace> cold = openColdStage(profileKey);
    return new GroupSubspaceHotSink("group-subspace:" + profileKey, hot, cold, chunkCostPerEntry);
  }

  /**
   * Polls the next hot subspace with active chunk reservations.
   *
   * @param profileKey region or profile identifier
   * @return a hot {@link GroupSubspace}, or {@code null} if empty
   */
  public GroupSubspace pollHot(String profileKey) {
    if (profileKey == null) return null;
    return hotStage.poll(profileKey).orElse(null);
  }

  /**
   * Offers a hot subspace with active chunk reservations into the hot stage.
   * If the stage is at or above capacity, the subspace is closed immediately to prevent ticket leaks (S-002).
   *
   * @param profileKey region or profile identifier
   * @param subspace the subspace to store
   * @return {@code true} if accepted into the hot stage, {@code false} if closed due to capacity
   */
  public boolean offerHot(String profileKey, GroupSubspace subspace) {
    if (profileKey == null || subspace == null) return false;
    return openHotStage(profileKey).offer(subspace);
  }

  /**
   * Polls a cold pre-verified subspace.
   *
   * @param profileKey region or profile identifier
   * @return a cold {@link GroupSubspace}, or {@code null} if empty
   */
  public GroupSubspace pollCold(String profileKey) {
    if (profileKey == null) return null;
    return coldStage.poll(profileKey).orElse(null);
  }

  /**
   * Offers a cold pre-verified subspace into the cold stage.
   *
   * @param profileKey region or profile identifier
   * @param subspace the cold subspace
   * @return {@code true} if accepted, {@code false} if full
   */
  public boolean offerCold(String profileKey, GroupSubspace subspace) {
    if (profileKey == null || subspace == null) return false;
    return openColdStage(profileKey).offer(subspace);
  }

  /**
   * Polls an unverified backlog entry.
   *
   * @param profileKey region or profile identifier
   * @return unverified {@link GroupBacklogEntry}, or {@code null} if empty
   */
  public GroupBacklogEntry pollBacklog(String profileKey) {
    if (profileKey == null) return null;
    return backlogStage.poll(profileKey).orElse(null);
  }

  /**
   * Offers an unverified candidate entry into the backlog.
   *
   * @param profileKey region or profile identifier
   * @param entry the candidate entry
   * @return {@code true} if accepted, {@code false} if full
   */
  public boolean offerBacklog(String profileKey, GroupBacklogEntry entry) {
    if (profileKey == null || entry == null) return false;
    return openBacklogStage(profileKey).offer(entry);
  }

  public int sizeHot(String profileKey) {
    if (profileKey == null) return 0;
    return hotStage.peek(profileKey).map(CacheStage::size).orElse(0);
  }

  public int sizeCold(String profileKey) {
    if (profileKey == null) return 0;
    return coldStage.peek(profileKey).map(CacheStage::size).orElse(0);
  }

  public int sizeBacklog(String profileKey) {
    if (profileKey == null) return 0;
    return backlogStage.peek(profileKey).map(CacheStage::size).orElse(0);
  }

  /**
   * Drains and clears all queue tiers across all profiles, deterministically closing
   * all active chunk reservations in the hot stage (S-002).
   */
  public void clear() {
    hotStage.close();
    coldStage.close();
    backlogStage.close();
  }
}
