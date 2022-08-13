package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class ForceQueue extends RTPRunnable {
    public static final List<Consumer<ForceQueue>> preActions = new ArrayList<>();
    public static final List<Consumer<ForceQueue>> postActions = new ArrayList<>();
    private final RTPCommandSender sender;
    private Collection<Region> regions;

    public ForceQueue() {
        sender = RTP.serverAccessor.getSender(CommandsAPI.serverId);
        regions = RTP.getInstance().selectionAPI.permRegionLookup.values();
    }

    public ForceQueue(RTPCommandSender sender) {
        this.sender = sender;
        regions = RTP.getInstance().selectionAPI.permRegionLookup.values();
    }

    public ForceQueue(RTPCommandSender sender,
                      @Nullable Collection<Region> regions) {
        this.sender = sender;
        if(regions == null || regions.size()==0) regions = RTP.getInstance().selectionAPI.permRegionLookup.values();
        this.regions = regions;
    }

    @Override
    public void run() {
        preActions.forEach(consumer -> consumer.accept(this));

        for(Region region : regions) {
            region.execute(Long.MAX_VALUE);
        }

        postActions.forEach(consumer -> consumer.accept(this));
    }

    public RTPCommandSender sender() {
        return sender;
    }

    public Collection<Region> regions() {
        return regions;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (ForceQueue) obj;
        return Objects.equals(this.sender, that.sender) &&
                Objects.equals(this.regions, that.regions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sender, regions);
    }

    @Override
    public String toString() {
        return "DoTeleport[" +
                "sender=" + sender + ", " +
                "regions=" + regions + ']';
    }
}
