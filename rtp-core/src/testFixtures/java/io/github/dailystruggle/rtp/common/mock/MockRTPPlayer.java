package io.github.dailystruggle.rtp.common.mock;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPLocation;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Minimal in-memory implementation of {@link RTPPlayer} for use in unit tests. */
public class MockRTPPlayer extends MockRTPCommandSender implements RTPPlayer {

    private RTPLocation location;
    private boolean online;

    public MockRTPPlayer(UUID uuid, String name, RTPLocation initialLocation) {
        super(uuid, name);
        this.location = initialLocation;
        this.online = true;
    }

    public MockRTPPlayer() {
        this(UUID.randomUUID(), "MockPlayer", new RTPLocation(new MockRTPWorld("default"), 0, 0, 0));
    }

    @Override
    public CompletableFuture<Boolean> setLocation(RTPLocation to) {
        this.location = to;
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public RTPLocation getLocation() {
        return location;
    }

    @Override
    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }
}
