package io.github.dailystruggle.rtp.common.serverSide.substitutions;

import java.util.UUID;

public interface RTPCommandSender {
    UUID uuid();
    boolean hasPermission(String permission);
    void sendMessage(String message);
}
