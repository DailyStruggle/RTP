package io.github.dailystruggle.rtp.bukkitplatform.v26_1_R1.server;


import io.github.dailystruggle.rtp.bukkitplatform.server.AbstractServerAccessor;

import org.jetbrains.annotations.NotNull;

public class ServerAccessorImpl extends AbstractServerAccessor {
  @Override
  public @NotNull java.util.Set<String> getBiomes() {
    return org.bukkit.Registry.BIOME.stream().map(biome -> biome.getKey().getKey().toUpperCase()).collect(java.util.stream.Collectors.toSet());
  }

}
