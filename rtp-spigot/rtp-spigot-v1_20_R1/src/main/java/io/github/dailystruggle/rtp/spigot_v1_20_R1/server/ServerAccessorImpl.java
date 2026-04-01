package io.github.dailystruggle.rtp.spigot_v1_20_R1.server;

import io.github.dailystruggle.rtp.api.world.RTPChunkManager;
import io.github.dailystruggle.rtp.spigot.server.AbstractServerAccessor;
import io.github.dailystruggle.rtp.spigot.world.BukkitRTPChunkManager;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.Registry;
import org.jetbrains.annotations.NotNull;

public class ServerAccessorImpl extends AbstractServerAccessor {
  @Override
  public @NotNull RTPChunkManager getChunkManager() {
    return new BukkitRTPChunkManager();
  }

  @Override
  public @NotNull Set<String> getBiomes() {
    return Registry.BIOME.stream().map(biome -> biome.getKey().getKey().toUpperCase()).collect(Collectors.toSet());
  }
}
