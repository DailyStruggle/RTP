package io.github.dailystruggle.rtp.groupaddon;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Manages the three-tier caching pipeline for group subspaces (Stage 4, leafrtp-group-addon-ADR-002):
 * <ul>
 *   <li><b>Group Hot (Kept):</b> Pre-verified subspaces with active {@link io.github.dailystruggle.rtp.api.world.ChunkReservation}
 *       tickets held for immediate teleport dispatch.</li>
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
    this(DEFAULT_HOT_CAP, DEFAULT_COLD_CAP, DEFAULT_BACKLOG_CAP);
  }

  public GroupSubspaceCache(int hotCap, int coldCap, int backlogCap) {
    this.hotCap = Math.max(1, hotCap);
    this.coldCap = Math.max(0, coldCap);
    this.backlogCap = Math.max(0, backlogCap);
  }

  private ProfileQueues getQueues(String profileKey) {
    return queuesByProfile.computeIfAbsent(profileKey, k -> new ProfileQueues());
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
   * Offers a hot subspace with active chunk reservations into the hot stage.
   * If the queue is at or above capacity, the subspace is closed immediately to prevent ticket leaks (S-002).
   *
   * @param profileKey region or profile identifier
   * @param subspace the subspace to store
   * @return {@code true} if accepted into the hot stage, {@code false} if closed due to capacity
   */
  public boolean offerHot(String profileKey, GroupSubspace subspace) {
    if (subspace == null) return false;
    ProfileQueues queues = getQueues(profileKey);
    if (queues.kept.size() >= hotCap) {
      subspace.close();
      return false;
    }
    queues.kept.offer(subspace);
    return true;
  }

  /**
   * Polls a cold pre-verified subspace.
   *
   * @param profileKey region or profile identifier
   * @return a cold {@link GroupSubspace}, or {@code null} if empty
   */
  public GroupSubspace pollCold(String profileKey) {
    return getQueues(profileKey).unkept.poll();
  }

  /**
   * Offers a cold pre-verified subspace into the cold stage.
   *
   * @param profileKey region or profile identifier
   * @param subspace the cold subspace
   * @return {@code true} if accepted, {@code false} if full
   */
  public boolean offerCold(String profileKey, GroupSubspace subspace) {
    if (subspace == null) return false;
    ProfileQueues queues = getQueues(profileKey);
    if (queues.unkept.size() >= coldCap) {
      return false;
    }
    queues.unkept.offer(subspace);
    return true;
  }

  /**
   * Polls an unverified backlog entry.
   *
   * @param profileKey region or profile identifier
   * @return unverified {@link GroupBacklogEntry}, or {@code null} if empty
   */
  public GroupBacklogEntry pollBacklog(String profileKey) {
    return getQueues(profileKey).backlog.poll();
  }

  /**
   * Offers an unverified candidate entry into the backlog.
   *
   * @param profileKey region or profile identifier
   * @param entry the candidate entry
   * @return {@code true} if accepted, {@code false} if full
   */
  public boolean offerBacklog(String profileKey, GroupBacklogEntry entry) {
    if (entry == null) return false;
    ProfileQueues queues = getQueues(profileKey);
    if (queues.backlog.size() >= backlogCap) {
      return false;
    }
    queues.backlog.offer(entry);
    return true;
  }

  public int sizeHot(String profileKey) {
    return getQueues(profileKey).kept.size();
  }

  public int sizeCold(String profileKey) {
    return getQueues(profileKey).unkept.size();
  }

  public int sizeBacklog(String profileKey) {
    return getQueues(profileKey).backlog.size();
  }

  /**
   * Drains and clears all queue tiers across all profiles, deterministically closing
   * all active chunk reservations in the hot stage (S-002).
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
