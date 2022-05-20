package leafcraft.rtp.common.substitutions;

import java.util.concurrent.CompletableFuture;

public interface RTPPlayer extends RTPCommandSender{
    CompletableFuture<Boolean> setLocation(RTPLocation to);
    RTPLocation getLocation();
}
