package io.github.dailystruggle.rtp.folia_v1_20_R1.server;

import io.github.dailystruggle.rtp.api.world.RTPChunkManager;
import io.github.dailystruggle.rtp.folia.server.AbstractFoliaServerAccessor;
import io.github.dailystruggle.rtp.folia.world.FoliaRTPChunkManager;
import org.jetbrains.annotations.NotNull;

public class ServerAccessorImpl extends AbstractFoliaServerAccessor {
    @Override
    public @NotNull RTPChunkManager getChunkManager() {
        return new FoliaRTPChunkManager();
    }
}
