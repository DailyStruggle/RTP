package io.github.dailystruggle.rtp.common.substitutions;

public interface RTPChunk {
    int x();
    int z();
    RTPBlock getBlockAt(int x, int y, int z);
    RTPBlock getBlockAt(RTPLocation location);
    void keep(boolean keep);
}
