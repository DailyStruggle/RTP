package io.github.dailystruggle.rtp.api.economy;

import java.util.UUID;

public interface RTPEconomy {
    void give(UUID playerId, double money);

    boolean take(UUID playerId, double money);

    double bal(UUID playerId);
}

