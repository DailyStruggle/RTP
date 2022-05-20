package leafcraft.rtp.common.playerData;

import leafcraft.rtp.common.selection.region.Region;
import leafcraft.rtp.common.substitutions.RTPLocation;

import java.util.UUID;

public class TeleportData {
    public UUID sender;

    public RTPLocation originalLocation = null;
    public RTPLocation selectedLocation = null;

    public Region targetRegion;

    //latest command time
    public long time;

    //in case of cancellation, time refund may be an option
    public long priorTime;

    public double cost;

    public boolean completed = false;
}
