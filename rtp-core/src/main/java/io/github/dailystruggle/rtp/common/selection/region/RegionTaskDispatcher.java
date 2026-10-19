package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import java.util.UUID;

/**
 * Pipeline work a {@link Region} starts: scan resume, teleport dispatch and teleport cancel.
 *
 * <p>Inverts the task dependency (ADR-115): {@code selection.region} owns this interface and the
 * composition root installs the Tier 4 implementation with {@link Region#setTaskDispatcher}, so
 * the region package does not import {@code tasks.scan} or {@code tasks.teleport}. Do not create
 * pipeline tasks in {@code selection.region} directly; add a method here instead.
 *
 * <p>{@link #NONE} fails closed on teleport methods: a waiting player is never dropped without a
 * reported failure (S-004).
 */
public interface RegionTaskDispatcher {

  /** Used until the composition root installs a dispatcher: no scan resume, teleports throw. */
  RegionTaskDispatcher NONE = new RegionTaskDispatcher() {
    @Override
    public void resumeScan(Region region) {}

    @Override
    public void dispatchTeleport(TeleportData data, GenerationContext context, Region region,
        RTPCoords coords, ChunkReservation reservation) {
      throw new IllegalStateException("no RegionTaskDispatcher installed; RTP core not initialised");
    }

    @Override
    public void cancelTeleport(UUID playerId, TeleportData data, boolean cancelNow) {
      throw new IllegalStateException("no RegionTaskDispatcher installed; RTP core not initialised");
    }

    @Override
    public int teleportViewDistance() {
      return 0;
    }
  };

  /**
   * Resumes the persisted scan of {@code region} if one stopped part-way, once the region's
   * shape data has loaded. Does nothing when no unfinished scan is recorded.
   *
   * @param region the region whose scan progress to resume; never {@code null}
   */
  void resumeScan(Region region);

  /**
   * Creates the teleport pipeline task for a location paired with a waiting player, stores it in
   * {@link TeleportData#nextTask}, sets it to the load phase and then schedules it asynchronously.
   * The task is stored before it is scheduled, so it never races its own later phases.
   *
   * @param data        teleport data of the waiting player; never {@code null}
   * @param context     sender and player of the teleport
   * @param region      region that produced the location
   * @param coords      selected coordinates; never {@code null}
   * @param reservation chunks held for the teleport, released by the task on every exit (S-002)
   */
  void dispatchTeleport(TeleportData data, GenerationContext context, Region region,
      RTPCoords coords, ChunkReservation reservation);

  /**
   * Cancels the teleport of a player that left the queue: marks the pending pipeline task in
   * {@code data} cancelled so it runs its cleanup, then reports the cancel (S-004).
   *
   * @param playerId  player whose teleport is cancelled
   * @param data      latest teleport data of the player, or {@code null}
   * @param cancelNow {@code true} to run the cancel report on the calling thread,
   *                  {@code false} to schedule it
   */
  void cancelTeleport(UUID playerId, TeleportData data, boolean cancelNow);

  /**
   * Returns the chunk radius kept loaded around a teleport destination.
   *
   * @return radius in chunks, at least 0
   */
  int teleportViewDistance();
}
