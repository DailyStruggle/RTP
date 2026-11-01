package io.github.dailystruggle.rtp.common.mock;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Minimal in-memory implementation of {@link RTPCommandSender} for use in unit tests. */
public class MockRTPCommandSender implements RTPCommandSender {

    private final UUID uuid;
    private final String name;
    public final List<String> sentMessages = new CopyOnWriteArrayList<>();
    public final List<String> performedCommands = new CopyOnWriteArrayList<>();
    private final Map<String, Boolean> permissions = new ConcurrentHashMap<>();
    private long cooldown = 0L;
    private long delay = 0L;

    public MockRTPCommandSender(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public MockRTPCommandSender() {
        this(UUID.randomUUID(), "MockSender");
    }

    @Override
    public UUID uuid() {
        return uuid;
    }

    @Override
    public boolean hasPermission(String permission) {
        Boolean perm = permissions.get(permission);
        if (perm != null) {
            return perm;
        }
        return true;
    }

    public void setPermission(String permission, boolean value) {
        permissions.put(permission, value);
    }

    public void clearPermissions() {
        permissions.clear();
    }

    @Override
    public void sendMessage(String message) {
        sentMessages.add(message);
    }

    @Override
    public long cooldown() {
        return cooldown;
    }

    public void setCooldown(long cooldown) {
        this.cooldown = cooldown;
    }

    @Override
    public long delay() {
        return delay;
    }

    public void setDelay(long delay) {
        this.delay = delay;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Set<String> getEffectivePermissions() {
        return new HashSet<>(permissions.keySet());
    }

    @Override
    public void performCommand(RTPPlayer player, String command) {
        performedCommands.add(command);
    }

    @Override
    public RTPCommandSender clone() {
        MockRTPCommandSender clone = new MockRTPCommandSender(uuid, name);
        clone.permissions.putAll(this.permissions);
        clone.cooldown = this.cooldown;
        clone.delay = this.delay;
        return clone;
    }
}
