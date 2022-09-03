package io.github.dailystruggle.rtp.common.selection.worldborder;

import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

public final class WorldBorder {
    private final Supplier<Shape<?>> getShape;
    private final Function<RTPLocation, Boolean> isInside;

    public WorldBorder(Supplier<Shape<?>> getShape,
                       Function<RTPLocation, Boolean> isInside) {
        this.getShape = getShape;
        this.isInside = isInside;
    }

    public Supplier<Shape<?>> getShape() {
        return getShape;
    }

    public Function<RTPLocation, Boolean> isInside() {
        return isInside;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        WorldBorder that = (WorldBorder) obj;
        return Objects.equals(this.getShape, that.getShape) &&
                Objects.equals(this.isInside, that.isInside);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getShape, isInside);
    }

    @Override
    public String toString() {
        return "WorldBorder[" +
                "getShape=" + getShape + ", " +
                "isInside=" + isInside + ']';
    }

}
