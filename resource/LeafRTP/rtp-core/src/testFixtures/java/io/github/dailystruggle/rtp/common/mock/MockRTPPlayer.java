package io.github.dailystruggle.rtp.common.mock;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPLocation;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Minimal in-memory implementation of {@link RTPPlayer} for use in unit tests. */
public class MockRTPPlayer extends MockRTPCommandSender implements RTPPlayer {

    private RTPLocation location;
    private boolean online;
    /** Per-player view distance; -1 models a platform without a per-player view-distance API. */
    private int viewDistance = -1;
    /** Per-player send view distance; -1 means "follow the tracking/world default" (unpinned). */
    private int sendViewDistance = -1;
    private RTPLocation respawnLocation;
    private final AtomicBoolean failSetLocation = new AtomicBoolean(false);
    public final Map<String, Double> progressBars = new ConcurrentHashMap<>();
    public final Map<RTPLocation, String> clientBlockChanges = new ConcurrentHashMap<>();

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
        if (failSetLocation.get()) {
            return CompletableFuture.completedFuture(false);
        }
        this.location = to;
        return CompletableFuture.completedFuture(true);
    }

    public void setFailSetLocation(boolean fail) {
        this.failSetLocation.set(fail);
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

    @Override
    public void setRespawnLocation(RTPLocation location) {
        this.respawnLocation = location;
    }

    public RTPLocation getRespawnLocation() {
        return respawnLocation;
    }

    @Override
    public void showProgressBar(String id, String title, double progress) {
        progressBars.put(id, progress);
    }

    @Override
    public void clearProgressBar(String id) {
        progressBars.remove(id);
    }

    @Override
    public void sendClientBlockChange(RTPLocation location, String blockData) {
        clientBlockChanges.put(location, blockData);
    }

    @Override
    public void sendClientBlockChanges(Map<RTPLocation, String> changes) {
        if (changes != null) {
            clientBlockChanges.putAll(changes);
        }
    }

    @Override
    public int getViewDistance() {
        return viewDistance;
    }

    @Override
    public void setViewDistance(int viewDistance) {
        this.viewDistance = viewDistance;
    }

    @Override
    public int getSendViewDistance() {
        return sendViewDistance;
    }

    @Override
    public void setSendViewDistance(int viewDistance) {
        this.sendViewDistance = viewDistance;
    }
}
