package leafcraft.rtp.API.selection.region.selectors.shapes;

import leafcraft.rtp.API.selection.region.selectors.Shape;

public record Rectangle(long cx, long cz, long w, long h) implements Shape {
    @Override
    public String name() {
        return "Rectangle";
    }

    @Override
    public double xzToLocation(long x, long z) {
        return 0;
    }

    @Override
    public long[] locationToXZ(long location) {
        return new long[0];
    }
}
