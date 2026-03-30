package io.github.dailystruggle.rtp.api.world;

public class MutableRTPCoords {
    public String worldName;
    public int x;
    public int y;
    public int z;

    public MutableRTPCoords(int x, int z) {
        this.worldName = "";
        this.x = x;
        this.y = 0;
        this.z = z;
    }

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

    public void setY(int y) {
        this.y = y;
    }

    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }

    public RTPCoords toImmutable() {
        return new RTPCoords(worldName, x, y, z);
    }
}
