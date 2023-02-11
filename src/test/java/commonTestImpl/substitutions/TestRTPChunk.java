package commonTestImpl.substitutions;

import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPBlock;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPChunk;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;

public class TestRTPChunk implements RTPChunk {

    @Override
    public int x() {
        return 0;
    }

    @Override
    public int z() {
        return 0;
    }

    @Override
    public RTPBlock getBlockAt(int x, int y, int z) {
        return null;
    }

    @Override
    public RTPBlock getBlockAt(RTPLocation location) {
        return null;
    }

    @Override
    public RTPWorld getWorld() {
        return null;
    }

    @Override
    public void keep(boolean keep) {

    }

    @Override
    public void unload() {

    }
}
