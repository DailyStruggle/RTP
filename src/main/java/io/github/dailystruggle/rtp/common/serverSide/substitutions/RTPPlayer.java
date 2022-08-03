package io.github.dailystruggle.rtp.common.serverSide.substitutions;

import java.util.concurrent.CompletableFuture;

public interface RTPPlayer extends RTPCommandSender{
    CompletableFuture<Boolean> setLocation(RTPLocation to);
    RTPLocation getLocation();
    boolean isOnline();
}
