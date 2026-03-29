package io.github.dailystruggle.rtp_glide.configuration;

import io.github.dailystruggle.rtp_glide.RTP_Glide;

/** Configuration manager for RTP_Glide */
public class Configs {
  private final RTP_Glide plugin;

  /** Worlds configuration */
  public Worlds worlds;

  /** Server version string */
  public String version;

  /**
   * Constructor for Configs
   *
   * @param plugin the plugin instance
   */
  public Configs(RTP_Glide plugin) {
    this.plugin = plugin;
    String name = plugin.getServer().getClass().getPackage().getName();
    version = name.substring(name.indexOf('-') + 1);
    worlds = new Worlds(plugin);
  }

  /** Refresh the configurations */
  public void refresh() {
    String name = plugin.getServer().getClass().getPackage().getName();
    version = name.substring(name.indexOf('-') + 1);
    worlds = new Worlds(plugin);
  }
}
