package leafcraft.rtp.api.playerData;

import leafcraft.rtp.api.selection.RegionParams;

import java.util.UUID;

public class TeleportData {
    public UUID sender;

    public long[] originalLocation = new long[3];
    public long[] selectedLocation = new long[3];

    public RegionParams givenParams;

    public long time;
    public double cost;

    public boolean completed = false;
}
