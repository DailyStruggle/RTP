package io.github.dailystruggle.rtp.common.substitutions;

import java.util.UUID;

public interface RTPCommandSender {
    UUID uuid();
    boolean hasPermission(String permission);
    void sendMessage(String message);
}
