package io.github.dailystruggle.rtp.common.selection.worldborder;

import java.util.function.Function;

public record WorldBorder(Function<String, Long> getRadius,
                          Function<String, long[]> getCenter,
                          Function<String, String> getShape) {

    public Long getRadius(String world) {
        return getRadius.apply(world);
    }

    public long[] getCenter(String world) {
        return getCenter.apply(world);
    }

    public String getShape(String world) {
        return getShape.apply(world);
    }
}
