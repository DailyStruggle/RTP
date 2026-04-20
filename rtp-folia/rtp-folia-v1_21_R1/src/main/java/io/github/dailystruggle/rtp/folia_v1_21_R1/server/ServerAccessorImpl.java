package io.github.dailystruggle.rtp.folia_v1_21_R1.server;


import io.github.dailystruggle.rtp.folia.server.AbstractFoliaServerAccessor;

import org.jetbrains.annotations.NotNull;

public class ServerAccessorImpl extends AbstractFoliaServerAccessor {
  @Override
  public @NotNull java.util.Set<String> getBiomes() {
    return org.bukkit.Registry.BIOME.stream().map(biome -> biome.getKey().getKey().toUpperCase()).collect(java.util.stream.Collectors.toSet());
  }

}
