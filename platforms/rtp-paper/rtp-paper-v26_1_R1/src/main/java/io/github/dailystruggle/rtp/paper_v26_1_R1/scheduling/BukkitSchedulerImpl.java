package io.github.dailystruggle.rtp.paper_v26_1_R1.scheduling;

import org.bukkit.plugin.java.JavaPlugin;

public class BukkitSchedulerImpl
    extends io.github.dailystruggle.rtp.bukkitplatform.scheduling.BukkitSchedulerImpl {
  public BukkitSchedulerImpl(JavaPlugin plugin) {
    super(plugin);
  }

  /** Paper's chunk system prioritises player chunks over plugin loads. */
  @Override
  protected boolean holdsBackGeneration() {
    return false;
  }
}
