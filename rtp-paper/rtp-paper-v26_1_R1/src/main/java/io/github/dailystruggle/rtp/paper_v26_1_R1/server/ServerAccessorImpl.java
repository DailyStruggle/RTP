package io.github.dailystruggle.rtp.paper_v26_1_R1.server;

import io.github.dailystruggle.rtp.api.world.RTPChunkManager;
import io.github.dailystruggle.rtp.paper.world.PaperRTPChunkManager;
import io.github.dailystruggle.rtp.spigot.server.AbstractServerAccessor;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.Registry;
import org.jetbrains.annotations.NotNull;

public class ServerAccessorImpl extends AbstractServerAccessor {
  @Override
  public @NotNull RTPChunkManager getChunkManager() {
    return new PaperRTPChunkManager();
  }

  @Override
  public @NotNull Set<String> getBiomes() {
    return Registry.BIOME.stream().map(biome -> biome.getKey().getKey().toUpperCase()).collect(Collectors.toSet());
  }
}
