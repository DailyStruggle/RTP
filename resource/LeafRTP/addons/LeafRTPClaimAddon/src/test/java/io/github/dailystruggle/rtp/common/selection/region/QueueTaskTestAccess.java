package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;

import java.util.concurrent.CompletableFuture;

/**
 * Test bridge providing package-private access to {@link QueueTask} for integration tests.
 */
public final class QueueTaskTestAccess {
  private QueueTaskTestAccess() {}

  public static CompletableFuture<GenerationResult> executeQueueTask(
      Region region,
      RTPCommandSender sender,
      RTPPlayer player,
      java.util.Set<String> biomeNames) {
    CompletableFuture<GenerationResult> result = new CompletableFuture<>();
    QueueTask task = new QueueTask(region, sender, player, biomeNames, result);
    task.start();
    return result;
  }
}
