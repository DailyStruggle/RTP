package io.github.dailystruggle.rtp.common.playerData;

import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

import java.util.Set;

public class TeleportData {
    public RTPCommandSender sender;

    public RTPLocation originalLocation = null;
    public RTPLocation selectedLocation = null;

    public Region targetRegion;

    //latest command time
    public long time = System.nanoTime();

    public double cost = 0;

    public boolean completed = false;

    public long delay = 0;

    public Set<String> biomes = null;

    public RTPRunnable nextTask = null;

    public long numAttempts = 0;

    public long queueLocation = 0;
}
