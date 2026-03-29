package io.github.dailystruggle.rtp.paper_v1_20_R1.server;

import io.github.dailystruggle.rtp.api.world.RTPChunkManager;
import io.github.dailystruggle.rtp.paper.world.PaperRTPChunkManager;
import io.github.dailystruggle.rtp.spigot.server.AbstractServerAccessor;
import org.jetbrains.annotations.NotNull;

public class ServerAccessorImpl extends AbstractServerAccessor {
  @Override
  public @NotNull RTPChunkManager getChunkManager() {
    return new PaperRTPChunkManager();
  }
}
