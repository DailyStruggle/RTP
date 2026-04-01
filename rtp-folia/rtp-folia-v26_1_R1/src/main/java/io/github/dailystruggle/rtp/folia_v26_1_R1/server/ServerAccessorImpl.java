package io.github.dailystruggle.rtp.folia_v26_1_R1.server;

import io.github.dailystruggle.rtp.api.world.RTPChunkManager;
import io.github.dailystruggle.rtp.folia.server.AbstractFoliaServerAccessor;
import io.github.dailystruggle.rtp.folia.world.FoliaRTPChunkManager;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.Registry;
import org.jetbrains.annotations.NotNull;

public class ServerAccessorImpl extends AbstractFoliaServerAccessor {
  @Override
  public @NotNull RTPChunkManager getChunkManager() {
    return new FoliaRTPChunkManager();
  }

  @Override
  public @NotNull Set<String> getBiomes() {
    return Registry.BIOME.stream().map(biome -> biome.getKey().getKey().toUpperCase()).collect(Collectors.toSet());
  }
}
