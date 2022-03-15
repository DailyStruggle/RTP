package leafcraft.rtp.API.selection.region.selectors;

public interface Shape {
    String name();
    double xzToLocation(long x, long z);
    long[] locationToXZ(long location);
}
