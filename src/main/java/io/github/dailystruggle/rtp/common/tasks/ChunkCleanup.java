package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class ChunkCleanup extends RTPRunnable {
    public static final List<Consumer<ChunkCleanup>> preActions = new ArrayList<>();
    public static final List<Consumer<ChunkCleanup>> postActions = new ArrayList<>();
    private final RTPLocation location;
    private final Region region;

    public ChunkCleanup(RTPLocation location, Region region) {
        this.location = location;
        this.region = region;
    }

    @Override
    public void run() {
        preActions.forEach(consumer -> consumer.accept(this));
        region.removeChunks(location);
        postActions.forEach(consumer -> consumer.accept(this));
    }

    public RTPLocation location() {
        return location;
    }

    public Region region() {
        return region;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (ChunkCleanup) obj;
        return Objects.equals(this.location, that.location) &&
                Objects.equals(this.region, that.region);
    }

    @Override
    public int hashCode() {
        return Objects.hash(location, region);
    }

    @Override
    public String toString() {
        return "ChunkCleanup[" +
                "location=" + location + ", " +
                "region=" + region + ']';
    }

}
