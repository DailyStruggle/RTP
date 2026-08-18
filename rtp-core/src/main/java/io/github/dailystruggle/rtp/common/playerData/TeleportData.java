package io.github.dailystruggle.rtp.common.playerData;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import java.util.HashSet;
import java.util.Set;

/**
 * Tracks the state of a single teleport request through the processing pipeline.
 */
public class TeleportData implements Cloneable {
  /** The command sender who initiated the teleport. */
  public RTPCommandSender sender;

  /** The player's location when the command was executed. */
  public RTPCoords originalCoords = null;
  /** The final safe location selected by the generation pipeline. */
  public RTPCoords selectedCoords = null;

  /** The target region for the teleport. */
  public Region targetRegion;

  /** The timestamp (in milliseconds) when the command was initiated. */
  public long time = System.currentTimeMillis();

  /** The economy cost charged for this teleport. */
  public double cost = 0;

  /** Flag indicating whether the teleport has successfully completed. */
  public boolean completed = false;

  /** The delay (in server ticks) before the teleport is executed. */
  public long delay = 0;

  /** An optional set of biome names to filter the location selection. */
  public Set<String> biomes = null;

  /** The next task in the asynchronous pipeline. */
  public RTPRunnable nextTask = null;

  /** The number of location selection attempts made. */
  public long attempts = 0;

  /** The position in the pre-generation queue where the location was found. */
  public long queueLocation = 0;

  /** The total time (in milliseconds) spent processing this teleport request. */
  public long processingTime = 0;

  /** Flag indicating whether the teleport data has been written to the database. */
  public boolean written = false;

  /** Optional one-shot completion callback for API teleports (REQ-RTP-S-004). */
  public transient java.util.function.Consumer<TeleportData> onComplete = null;

  private transient final java.util.concurrent.atomic.AtomicBoolean completionDelivered =
      new java.util.concurrent.atomic.AtomicBoolean(false);

  public TeleportData() {
  }

  /**
   * Fire the {@link #onComplete} callback at most once. Safe to call from any
   * thread and on any number of pipeline exit paths; only the first call has any
   * effect. A callback failure is swallowed so it can never abort teleport
   * teardown.
   */
  public void fireOnComplete() {
    java.util.function.Consumer<TeleportData> cb = onComplete;
    if (cb != null && completionDelivered.compareAndSet(false, true)) {
      try {
        cb.accept(this);
      } catch (Throwable ignored) {
        // never let an API consumer's callback break pipeline cleanup
      }
    }
  }

  @Override
  public TeleportData clone() {
    try {
      TeleportData clone = (TeleportData) super.clone();
      clone.sender = sender.clone();
      clone.originalCoords = (originalCoords == null) ? null : originalCoords;
      clone.selectedCoords = (selectedCoords == null) ? null : selectedCoords;
      clone.targetRegion = targetRegion;
      clone.time = time;
      clone.cost = cost;
      clone.completed = completed;
      clone.delay = delay;
      clone.biomes = (biomes == null) ? null : new HashSet<>(biomes);
      clone.nextTask = nextTask;
      clone.attempts = attempts;
      clone.queueLocation = queueLocation;
      clone.processingTime = processingTime;
      clone.written = written;
      // History/prior-data clones must never fire an API completion callback.
      clone.onComplete = null;
      return clone;
    } catch (CloneNotSupportedException e) {
      throw new AssertionError();
    }
  }
}
