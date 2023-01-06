package io.github.dailystruggle.rtp.common.playerData;

import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

import java.util.HashSet;
import java.util.Set;

public class TeleportData implements Cloneable {
    public RTPCommandSender sender;

    public RTPLocation originalLocation = null;
    public RTPLocation selectedLocation = null;

    public Region targetRegion;

    //latest command time
    public long time = System.currentTimeMillis();

    public double cost = 0;

    public boolean completed = false;

    public long delay = 0;

    public Set<String> biomes = null;

    public RTPRunnable nextTask = null;

    public long attempts = 0;

    public long queueLocation = 0;

    public long processingTime = 0;

    public boolean written = false;

    @Override
    public TeleportData clone() {
        try {
            TeleportData clone = (TeleportData) super.clone();
            // TODO: copy mutable state here, so the clone can't change the internals of the original
            clone.sender = sender.clone();
            clone.originalLocation = originalLocation.clone();
            clone.selectedLocation = selectedLocation.clone();
            clone.targetRegion = targetRegion;
            clone.time = time;
            clone.cost = cost;
            clone.completed = completed;
            clone.delay = delay;
            clone.biomes = new HashSet<>(biomes);
            clone.nextTask = nextTask;
            clone.attempts = attempts;
            clone.queueLocation = queueLocation;
            clone.processingTime = processingTime;
            clone.written = written;
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }
}
