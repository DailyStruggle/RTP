package io.github.dailystruggle.rtp.api.scheduling;

import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import java.util.UUID;

public class TrackedRTPTask extends RTPRunnable {
  private final RTPRunnable task;
  private final String trackingId;
  private final long queuedTime;
  private long startTime = -1;
  private long endTime = -1;

  public TrackedRTPTask(RTPRunnable task, UUID trackingId) {
    this(task, trackingId.toString());
  }

  public TrackedRTPTask(RTPRunnable task, String trackingId) {
    this.task = task;
    this.trackingId = trackingId;
    this.queuedTime = System.currentTimeMillis();
  }

  @Override
  public boolean isCancelled() {
    return task.isCancelled();
  }

  @Override
  public void setCancelled(boolean cancel) {
    task.setCancelled(cancel);
  }

  @Override
  public long getDelay() {
    return task.getDelay();
  }

  @Override
  public void setDelay(long delay) {
    task.setDelay(delay);
  }

  @Override
  public boolean isRunning() {
    return task.isRunning();
  }

  @Override
  public void run() {
    this.startTime = System.currentTimeMillis();
    try {
      task.run();
    } finally {
      this.endTime = System.currentTimeMillis();
      if (io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor != null) {
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor.removeAction(trackingId);
      }
      RTPRunnable.untrackHook.accept(this);
    }
  }

  public TaskState getState() {
    if (endTime != -1) return TaskState.COMPLETED;
    if (startTime != -1) return TaskState.RUNNING;
    return TaskState.PENDING;
  }

  public long getDuration() {
    if (startTime == -1) return 0;
    if (endTime == -1) return System.currentTimeMillis() - startTime;
    return endTime - startTime;
  }

  public long getQueuedTime() {
    return queuedTime;
  }

  public long getStartTime() {
    return startTime;
  }

  public long getEndTime() {
    return endTime;
  }

  public String getTrackingId() {
    return trackingId;
  }

  public RTPRunnable getTask() {
    return task;
  }

  public enum TaskState {
    PENDING,
    RUNNING,
    COMPLETED
  }
}
