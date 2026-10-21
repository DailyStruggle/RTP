package io.github.dailystruggle.rtp.common.lifecycle;

import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionTaskDispatcher;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.tasks.scan.ScanTask;
import io.github.dailystruggle.rtp.common.tasks.scan.ScanTasks;
import io.github.dailystruggle.rtp.common.tasks.teleport.RTPTeleportCancel;
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;
import java.util.UUID;

/**
 * Tier 4 region task dispatcher (ADR-115): creates the scan and teleport pipeline tasks a
 * {@link Region} starts.
 *
 * <p>Installed with {@link Region#setTaskDispatcher} by the composition root ({@link RTP}). Keeps
 * {@code selection.region} free of dependencies on {@code tasks.scan} and {@code tasks.teleport}.
 */
public final class RegionTaskCoordinator implements RegionTaskDispatcher {

  @Override
  public void resumeScan(Region region) {
    // The configured shape field, not getShape(), which can substitute a world-border shape.
    if (!(region.shape instanceof MemoryShape<?> ms)) return;
    long[] progress = ScanTask.loadProgress(region.name, region.cacheKey());
    if (progress == null) return;
    long iter = progress[0];
    if (iter <= 0 || iter >= ms.getRange()) return;
    ScanTask task = new ScanTask(region, iter);
    ScanTasks.current().put(region.name, task);
    ms.getLoadFuture().whenComplete((v, t) -> RTPRuntime.scheduler().runTaskAsynchronously(task));
  }

  @Override
  public void dispatchTeleport(TeleportData data, GenerationContext context, Region region,
      RTPCoords coords, ChunkReservation reservation) {
    TeleportPipelineTask pipelineTask = new TeleportPipelineTask(context, region, coords, reservation);
    data.nextTask = pipelineTask;
    pipelineTask.setPhase(TeleportPipelineTask.Phase.LOAD);
    RTPRuntime.scheduler().runTaskAsynchronously(pipelineTask);
  }

  /**
   * Cancelled pipeline tasks run once more on the thread that owns their destination chunk, so
   * their cleanup releases the reservation (S-002); {@link RTPTeleportCancel} reports the cancel
   * (S-004).
   */
  @Override
  public void cancelTeleport(UUID playerId, TeleportData data, boolean cancelNow) {
    if (data != null && !data.completed && data.nextTask instanceof TeleportPipelineTask task) {
      task.setCancelled(true);
      if (task.coords() != null) {
        RTPRuntime.scheduler().runTask(
            task.region().getWorld(), task.coords().x() >> 4, task.coords().z() >> 4, task);
      } else {
        RTPRuntime.scheduler().runTask(task);
      }
    }

    if (cancelNow) {
      new RTPTeleportCancel(playerId).run();
    } else {
      RTPRuntime.scheduler().runTask(new RTPTeleportCancel(playerId));
    }
  }

  @Override
  public int teleportViewDistance() {
    return (int) TeleportPipelineTask.ConfigCache.viewDistanceTeleport;
  }
}
