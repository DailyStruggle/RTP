package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Task for forcing the generation of locations in regions
 */
public final class ForceQueue extends RTPRunnable {
    /**
     * Actions to perform before forcing the queue
     */
    public static final List<Consumer<ForceQueue>> preActions = new ArrayList<>();

    /**
     * Actions to perform after forcing the queue
     */
    public static final List<Consumer<ForceQueue>> postActions = new ArrayList<>();
    private final RTPCommandSender sender;
    private final Collection<Region> regions;

    /**
     * Constructor for ForceQueue using console as sender and all regions
     */
    public ForceQueue() {
        sender = RTP.serverAccessor.getSender( CommandsAPI.serverId );
        regions = RTP.selectionAPI.permRegionLookup.values();
    }

    /**
     * Constructor for ForceQueue with a specific sender and all regions
     * @param sender the command sender
     */
    public ForceQueue( RTPCommandSender sender ) {
        this.sender = sender;
        regions = RTP.selectionAPI.permRegionLookup.values();
    }

    /**
     * Constructor for ForceQueue with a specific sender and a specific set of regions
     * @param sender the command sender
     * @param regions the regions to force, or null for all regions
     */
    public ForceQueue( RTPCommandSender sender,
                      @Nullable Collection<Region> regions ) {
        this.sender = sender;
        if ( regions == null || regions.isEmpty() ) regions = RTP.selectionAPI.permRegionLookup.values();
        this.regions = regions;
    }

    @Override
    public void run() {
        preActions.forEach( consumer -> consumer.accept( this) );

        for ( Region region : regions ) {
            region.execute( Long.MAX_VALUE );
        }

        postActions.forEach( consumer -> consumer.accept( this) );
    }

    /**
     * Get the command sender who initiated the force
     * @return the command sender
     */
    public RTPCommandSender sender() {
        return sender;
    }

    /**
     * Get the regions being forced
     * @return the collection of regions
     */
    public Collection<Region> regions() {
        return regions;
    }

    @Override
    public boolean equals( Object obj ) {
        if ( obj == this ) return true;
        if ( obj == null || obj.getClass() != this.getClass() ) return false;
        ForceQueue that = ( ForceQueue ) obj;
        return Objects.equals( this.sender, that.sender ) &&
                Objects.equals( this.regions, that.regions );
    }

    @Override
    public int hashCode() {
        return Objects.hash( sender, regions );
    }

    @Override
    public String toString() {
        return "DoTeleport[" +
                "sender=" + sender + ", " +
                "regions=" + regions + ']';
    }
}


