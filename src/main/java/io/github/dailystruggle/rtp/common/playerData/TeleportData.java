package io.github.dailystruggle.rtp.common.playerData;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.substitutions.RTPLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class TeleportData {
    public UUID sender = CommandsAPI.serverId;

    public RTPLocation originalLocation = null;
    public RTPLocation selectedLocation = null;

    public Region targetRegion;

    //latest command time
    public long time = 0;

    //in case of cancellation, time refund may be an option
    public long priorTime = 0;

    public double cost = 0;

    public boolean completed = false;

    public Set<String> biomes = null;
}
