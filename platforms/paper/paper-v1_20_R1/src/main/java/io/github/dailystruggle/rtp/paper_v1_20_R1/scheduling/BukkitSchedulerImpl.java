package io.github.dailystruggle.rtp.paper_v1_20_R1.scheduling;

import io.github.dailystruggle.rtp.common.tools.GenerationBudget;
import org.bukkit.plugin.java.JavaPlugin;

public class BukkitSchedulerImpl
    extends io.github.dailystruggle.rtp.bukkitplatform.scheduling.BukkitSchedulerImpl {
  public BukkitSchedulerImpl(JavaPlugin plugin) {
    super(plugin);
    GenerationBudget.configureServerAdmission(false);
  }

  /** Paper's chunk system prioritises player chunks over plugin loads. */
  @Override
  protected boolean holdsBackGeneration() {
    return false;
  }
}
