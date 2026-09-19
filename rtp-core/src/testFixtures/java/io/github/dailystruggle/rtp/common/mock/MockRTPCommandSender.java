package io.github.dailystruggle.rtp.common.mock;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Minimal in-memory implementation of {@link RTPCommandSender} for use in unit tests. */
public class MockRTPCommandSender implements RTPCommandSender {

    private final UUID uuid;
    private final String name;
    public final List<String> sentMessages = Collections.synchronizedList(new ArrayList<>());
    public final List<String> performedCommands = new ArrayList<>();

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
        return true;
    }

    @Override
    public void sendMessage(String message) {
        sentMessages.add(message);
    }

    @Override
    public long cooldown() {
        return 0L;
    }

    @Override
    public long delay() {
        return 0L;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Set<String> getEffectivePermissions() {
        return new HashSet<>();
    }

    @Override
    public void performCommand(RTPPlayer player, String command) {
        performedCommands.add(command);
    }

    @Override
    public RTPCommandSender clone() {
        return new MockRTPCommandSender(uuid, name);
    }
}
