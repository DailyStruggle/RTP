package leafcraft.rtp.common.substitutions;

public interface RTPChunk {
    RTPBlock getBlockAt(int x, int y, int z);
    RTPBlock getBlockAt(RTPLocation location);
}
