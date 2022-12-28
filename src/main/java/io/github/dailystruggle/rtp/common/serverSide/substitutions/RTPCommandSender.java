package io.github.dailystruggle.rtp.common.serverSide.substitutions;

import java.util.Set;
import java.util.UUID;

public interface RTPCommandSender extends Cloneable {
    UUID uuid();
    boolean hasPermission(String permission);
    void sendMessage(String message);

    long cooldown();
    long delay();

    String name();

    Set<String> getEffectivePermissions();

    RTPCommandSender clone();
}
