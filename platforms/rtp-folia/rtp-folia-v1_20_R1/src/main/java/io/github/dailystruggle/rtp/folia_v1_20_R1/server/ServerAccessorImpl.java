package io.github.dailystruggle.rtp.folia_v1_20_R1.server;


import io.github.dailystruggle.rtp.folia.server.AbstractFoliaServerAccessor;

import org.jetbrains.annotations.NotNull;

public class ServerAccessorImpl extends AbstractFoliaServerAccessor {
  @Override
  public @NotNull java.util.Set<String> getBiomes() {
    // Emit both bare enum names (`BADLANDS`) and namespaced
    // (`minecraft:badlands`) forms so Brigadier-suggested namespaced ids
    // pass /rtp's biome param validator.
    java.util.Set<String> out = new java.util.HashSet<>();
    for (org.bukkit.block.Biome b : org.bukkit.block.Biome.values()) {
      out.add(b.name());
      out.add("minecraft:" + b.name().toLowerCase(java.util.Locale.ROOT));
    }
    return out;
  }

}
