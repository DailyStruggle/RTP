package leafcraft.rtp.api.tasks;

import leafcraft.rtp.api.playerData.TeleportData;

import java.util.function.Consumer;

public interface RTPTask extends Runnable{
    void compute();
    void onRun(Consumer<TeleportData> action);
}
