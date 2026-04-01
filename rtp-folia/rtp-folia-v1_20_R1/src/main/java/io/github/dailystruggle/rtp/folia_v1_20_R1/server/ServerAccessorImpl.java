package io.github.dailystruggle.rtp.folia_v1_20_R1.server;

import io.github.dailystruggle.rtp.api.world.RTPChunkManager;
import io.github.dailystruggle.rtp.folia.server.AbstractFoliaServerAccessor;
import io.github.dailystruggle.rtp.folia.world.FoliaRTPChunkManager;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.block.Biome;
import org.jetbrains.annotations.NotNull;

public class ServerAccessorImpl extends AbstractFoliaServerAccessor {
  @Override
  public @NotNull RTPChunkManager getChunkManager() {
    return new FoliaRTPChunkManager();
  }

  @Override
  public @NotNull Set<String> getBiomes() {
    return Arrays.stream(Biome.values()).map(Enum::name).collect(Collectors.toSet());
  }
}
