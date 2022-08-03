package io.github.dailystruggle.rtp.common.serverSide.substitutions;

public record RTPLocation(RTPWorld world, int x, int y, int z) implements Cloneable {
    public long distanceSquared(RTPLocation that) {
        if (!this.world.equals(that.world)) return Long.MAX_VALUE; // another world is pretty far away
        long dx = this.x - that.x;
        long dy = this.y - that.y;
        long dz = this.z - that.z;
        return (long) (Math.pow(dx, 2) + Math.pow(dy, 2) + Math.pow(dz, 2));
    }

    public long distanceSquaredXZ(RTPLocation that) {
        if (!this.world.equals(that.world)) return Long.MAX_VALUE; // another world is pretty far away
        long dx = this.x - that.x;
        long dz = this.z - that.z;
        return (long) (Math.pow(dx, 2) + Math.pow(dz, 2));
    }

    @Override
    public RTPLocation clone() {
        return new RTPLocation(world,x,y,z);
    }
}
