package commonTestImpl.substitutions;

import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPChunk;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class TestRTPWorld implements RTPWorld {
    private static UUID id = UUID.randomUUID();

    @Override
    public String name() {
        return "TEST";
    }

    @Override
    public UUID id() {
        return id;
    }

    @Override
    public CompletableFuture<RTPChunk> getChunkAt(long chunkX, long chunkZ) {
        return null;
    }

    @Override
    public String getBiome(int x, int y, int z) {
        return null;
    }

    public static Set<String> getBiomes() {
        HashSet<String> res = new HashSet<>();
        res.add("PLAINS");
        return res;
    }
}
