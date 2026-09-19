package io.github.dailystruggle.rtp.groupaddon;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Manages the three-tier caching pipeline for group subspaces (Stage 4, leafrtp-group-addon-ADR-002):
 * <ul>
 *   <li><b>Group L1 (Hot / Kept):</b> Pre-verified subspaces with active {@link io.github.dailystruggle.rtp.api.world.ChunkReservation}
 *       tickets held for immediate teleport dispatch.</li>
 *   <li><b>Group L2 (Cold / Unkept):</b> Pre-verified subspace coordinates with chunk tickets released.</li>
 *   <li><b>Group L3 (Backlog Buffer):</b> FIFO queue of unverified candidate subspaces screened off-tick.</li>
 * </ul>
 */
public final class GroupSubspaceCache {
  public static final int DEFAULT_L1_KEPT_CAP = 2;
  public static final int DEFAULT_L2_COLD_CAP = 5;
  public static final int DEFAULT_L3_BACKLOG_CAP = 10;

  private final int l1KeptCap;
  private final int l2ColdCap;
  private final int l3BacklogCap;

  public static final class ProfileQueues {
    private final ConcurrentLinkedQueue<GroupSubspace> kept = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<GroupSubspace> unkept = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<GroupBacklogEntry> backlog = new ConcurrentLinkedQueue<>();

    public ConcurrentLinkedQueue<GroupSubspace> kept() {
      return kept;
    }

    public ConcurrentLinkedQueue<GroupSubspace> unkept() {
      return unkept;
    }

    public ConcurrentLinkedQueue<GroupBacklogEntry> backlog() {
      return backlog;
    }
  }

  private final Map<String, ProfileQueues> queuesByProfile = new ConcurrentHashMap<>();

  public GroupSubspaceCache() {
    this(DEFAULT_L1_KEPT_CAP, DEFAULT_L2_COLD_CAP, DEFAULT_L3_BACKLOG_CAP);
  }

  public GroupSubspaceCache(int l1KeptCap, int l2ColdCap, int l3BacklogCap) {
    this.l1KeptCap = Math.max(1, l1KeptCap);
    this.l2ColdCap = Math.max(0, l2ColdCap);
    this.l3BacklogCap = Math.max(0, l3BacklogCap);
  }

  private ProfileQueues getQueues(String profileKey) {
    return queuesByProfile.computeIfAbsent(profileKey, k -> new ProfileQueues());
  }

  public int getL1KeptCap() {
    return l1KeptCap;
  }

  public int getL2ColdCap() {
    return l2ColdCap;
  }

  public int getL3BacklogCap() {
    return l3BacklogCap;
  }

  /**
   * Polls the next hot subspace with active chunk reservations.
   *
   * @param profileKey region or profile identifier
   * @return a hot {@link GroupSubspace}, or {@code null} if empty
   */
  public GroupSubspace pollHot(String profileKey) {
    return getQueues(profileKey).kept.poll();
  }

  /**
   * Offers a hot subspace with active chunk reservations into L1.
   * If the queue is at or above capacity, the subspace is closed immediately to prevent ticket leaks (S-002).
   *
   * @param profileKey region or profile identifier
   * @param subspace the subspace to store
   * @return {@code true} if accepted into L1, {@code false} if closed due to capacity
   */
  public boolean offerHot(String profileKey, GroupSubspace subspace) {
    if (subspace == null) return false;
    ProfileQueues queues = getQueues(profileKey);
    if (queues.kept.size() >= l1KeptCap) {
      subspace.close();
      return false;
    }
    queues.kept.offer(subspace);
    return true;
  }

  /**
   * Polls a cold pre-verified subspace from L2.
   *
   * @param profileKey region or profile identifier
   * @return a cold {@link GroupSubspace}, or {@code null} if empty
   */
  public GroupSubspace pollCold(String profileKey) {
    return getQueues(profileKey).unkept.poll();
  }

  /**
   * Offers a cold pre-verified subspace into L2.
   *
   * @param profileKey region or profile identifier
   * @param subspace the cold subspace
   * @return {@code true} if accepted, {@code false} if full
   */
  public boolean offerCold(String profileKey, GroupSubspace subspace) {
    if (subspace == null) return false;
    ProfileQueues queues = getQueues(profileKey);
    if (queues.unkept.size() >= l2ColdCap) {
      return false;
    }
    queues.unkept.offer(subspace);
    return true;
  }

  /**
   * Polls an unverified backlog entry from L3.
   *
   * @param profileKey region or profile identifier
   * @return unverified {@link GroupBacklogEntry}, or {@code null} if empty
   */
  public GroupBacklogEntry pollBacklog(String profileKey) {
    return getQueues(profileKey).backlog.poll();
  }

  /**
   * Offers an unverified candidate entry into L3 backlog.
   *
   * @param profileKey region or profile identifier
   * @param entry the candidate entry
   * @return {@code true} if accepted, {@code false} if full
   */
  public boolean offerBacklog(String profileKey, GroupBacklogEntry entry) {
    if (entry == null) return false;
    ProfileQueues queues = getQueues(profileKey);
    if (queues.backlog.size() >= l3BacklogCap) {
      return false;
    }
    queues.backlog.offer(entry);
    return true;
  }

  public int sizeL1(String profileKey) {
    return getQueues(profileKey).kept.size();
  }

  public int sizeL2(String profileKey) {
    return getQueues(profileKey).unkept.size();
  }

  public int sizeL3(String profileKey) {
    return getQueues(profileKey).backlog.size();
  }

  /**
   * Drains and clears all queue tiers across all profiles, deterministically closing
   * all active chunk reservations in L1 (S-002).
   */
  public void clear() {
    for (ProfileQueues queues : queuesByProfile.values()) {
      queues.backlog.clear();
      queues.unkept.clear();
      GroupSubspace subspace;
      while ((subspace = queues.kept.poll()) != null) {
        subspace.close();
      }
    }
    queuesByProfile.clear();
  }
}
