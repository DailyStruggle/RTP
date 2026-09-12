package io.github.dailystruggle.rtp.groupaddon;

import io.github.dailystruggle.rtp.api.addon.RTPAddon;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;

/**
 * LeafRTP group addon: multi-entity subspace teleportation via parent region memory capture.
 *
 * <p>Discovered by {@code rtp-core} through {@link java.util.ServiceLoader}.
 * Profiles are configured as separate {@code .yml} files under {@code definitions/groups/}
 * and managed by {@link MultiConfigParser}. Maintains a 3-tiered group subspace cache
 * (hot/cold/backlog).
 */
public final class RTPGroupAddon implements RTPAddon {
  private static GroupSubspaceCache cache;
  private static GroupCacheWorker worker;
  private static Object scheduledTaskHandle;

  public static GroupSubspaceCache getCache() {
    return cache;
  }

  public static GroupCacheWorker getWorker() {
    return worker;
  }

  @Override
  public void onLoad() {
    registerGroupProfiles();
    Configs.onReload(this::registerGroupProfiles);

    cache = new GroupSubspaceCache();
    worker = new GroupCacheWorker(cache);
    if (RTP.scheduler != null) {
      scheduledTaskHandle = RTP.scheduler.runTaskTimerAsynchronously(worker, 20L, 20L);
    }
  }

  /**
   * Builds and registers {@link MultiConfigParser} for {@link GroupKeys} targeting
   * {@code definitions/groups/} inside the RTP plugin directory.
   */
  private void registerGroupProfiles() {
    MultiConfigParser<GroupKeys> groups =
        new MultiConfigParser<>(
            GroupKeys.class,
            "groups",
            "1.0",
            RTP.serverAccessor.getPluginDirectory(),
            this.getClass().getClassLoader(),
            "definitions/groups");
    RTP.configs.multiConfigParserMap.put(GroupKeys.class, groups);
  }

  @Override
  public void onUnload() {
    if (scheduledTaskHandle != null && RTP.scheduler != null) {
      try {
        RTP.scheduler.cancelTask(scheduledTaskHandle);
      } catch (Throwable ignored) {
      }
      scheduledTaskHandle = null;
    }
    if (cache != null) {
      cache.clear();
      cache = null;
    }
    worker = null;
    RTP.configs.multiConfigParserMap.remove(GroupKeys.class);
  }
}
