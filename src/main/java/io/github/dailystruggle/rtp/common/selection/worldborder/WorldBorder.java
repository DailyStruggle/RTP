package io.github.dailystruggle.rtp.common.selection.worldborder;

import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;

import java.util.function.BiFunction;
import java.util.function.Function;

public record WorldBorder(Function<String, Shape<?>> getShape,
                          Function<RTPLocation, Boolean> isInside) {
}
