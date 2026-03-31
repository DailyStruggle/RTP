package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPCoords;

public class CachedLocation {
    private RTPCoords coords;
    private long attempts;

    public CachedLocation(RTPCoords coords, long attempts) {
        this.coords = coords;
        this.attempts = attempts;
    }

    public RTPCoords getCoords() {
        return coords;
    }

    public void setCoords(RTPCoords coords) {
        this.coords = coords;
    }

    public long getAttempts() {
        return attempts;
    }

    public void setAttempts(long attempts) {
        this.attempts = attempts;
    }
}
