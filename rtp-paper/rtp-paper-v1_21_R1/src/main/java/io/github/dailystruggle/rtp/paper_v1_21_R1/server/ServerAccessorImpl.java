package io.github.dailystruggle.rtp.paper_v1_21_R1.server;



import io.github.dailystruggle.rtp.spigot.server.AbstractServerAccessor;
import org.jetbrains.annotations.NotNull;

public class ServerAccessorImpl extends AbstractServerAccessor {
  @Override
  public @NotNull java.util.Set<String> getBiomes() {
    return org.bukkit.Registry.BIOME.stream().map(biome -> biome.getKey().getKey().toUpperCase()).collect(java.util.stream.Collectors.toSet());
  }

}
