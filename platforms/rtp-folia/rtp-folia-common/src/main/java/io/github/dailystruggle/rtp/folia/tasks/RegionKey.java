package io.github.dailystruggle.rtp.folia.tasks;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import java.util.UUID;

public record RegionKey(UUID worldId, int regionX, int regionZ) {
    public static RegionKey from(RTPLocation loc) {
        if (loc == null || loc.world() == null) return null;
        return new RegionKey(loc.world().id(), loc.x() >> 9, loc.z() >> 9);
    }
}
