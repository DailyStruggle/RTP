package io.github.dailystruggle.rtp.api.world;

public class MutableRTPCoords {
    public final String worldName;
    public int x;
    public final int y;
    public int z;

    public MutableRTPCoords(String worldName, int x, int y, int z) {
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public void setXZ(int x, int z) {
        this.x = x;
        this.z = z;
    }

    public RTPCoords toImmutable() {
        return new RTPCoords(worldName, x, y, z);
    }
}
