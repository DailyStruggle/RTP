package io.github.dailystruggle.rtp.folia_v1_20_R1.scheduling;

import org.bukkit.plugin.java.JavaPlugin;

public class FoliaSchedulerImpl
    extends io.github.dailystruggle.rtp.folia.scheduling.FoliaSchedulerImpl {
  public FoliaSchedulerImpl(JavaPlugin plugin) {
    super(plugin);
  }

  public FoliaSchedulerImpl(
      JavaPlugin plugin,
      io.papermc.paper.threadedregions.scheduler.RegionScheduler rs,
      io.papermc.paper.threadedregions.scheduler.AsyncScheduler as,
      io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler gs) {
    super(plugin, rs, as, gs);
  }
}
