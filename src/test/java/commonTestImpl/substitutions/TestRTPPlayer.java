package commonTestImpl.substitutions;

import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class TestRTPPlayer extends TestRTPCommandSender implements RTPPlayer {
    @Override
    public CompletableFuture<Boolean> setLocation(RTPLocation to) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        future.complete(true);
        return future;
    }

    @Override
    public RTPLocation getLocation() {
        return new RTPLocation(new TestRTPWorld(),0,64,0);
    }

    @Override
    public boolean isOnline() {
        return true;
    }
}
