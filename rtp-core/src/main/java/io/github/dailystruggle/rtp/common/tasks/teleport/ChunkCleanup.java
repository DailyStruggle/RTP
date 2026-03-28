package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Task for cleaning up chunks after a teleportation
 */
public final class ChunkCleanup extends RTPRunnable {
    /**
     * Actions to perform before chunk cleanup
     */
    public static final List<Consumer<ChunkCleanup>> preActions = new ArrayList<>();

    /**
     * Actions to perform after chunk cleanup
     */
    public static final List<Consumer<ChunkCleanup>> postActions = new ArrayList<>();
    private final RTPLocation location;
    private final Region region;

    /**
     * Constructor for ChunkCleanup
     *
     * @param location the location of the teleportation
     * @param region   the region of the teleportation
     */
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

    /**
     * Get the location of the teleportation
     *
     * @return the location
     */
    public RTPLocation location() {
        return location;
    }

    /**
     * Get the region of the teleportation
     *
     * @return the region
     */
    public Region region() {
        return region;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        ChunkCleanup that = (ChunkCleanup) obj;
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


