package io.github.dailystruggle.rtp.common.serverSide.substitutions;

import java.util.UUID;

public interface RTPEconomy {
    boolean give(UUID playerId, double money);
    boolean take(UUID playerId, double money);
}
