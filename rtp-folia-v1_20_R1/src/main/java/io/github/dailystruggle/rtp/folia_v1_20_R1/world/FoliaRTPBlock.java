package io.github.dailystruggle.rtp.folia_v1_20_R1.world;

import io.github.dailystruggle.rtp.common.RTP;

import io.github.dailystruggle.rtp.common.configuration.ConfigParser;

import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;

import io.github.dailystruggle.rtp.api.world.RTPBlock;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import org.bukkit.block.Block;

import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Collectors;

/**
 * Folia implementation of RTPBlock
 */
public final class FoliaRTPBlock extends RTPBlock<Block> {
    private static Set<String> airBlocks = new ConcurrentSkipListSet<>();
    private static long lastUpdate = 0;

    static {
        airBlocks.add( "AIR" );
    }

    /**
     * Constructor for FoliaRTPBlock
     * @param block the Bukkit block to wrap
     */
    public FoliaRTPBlock( Block block ) {
        super( block );
    }

    @Override
    public boolean isAir() {
        long t = System.currentTimeMillis();
        long dt = t - lastUpdate;
        if ( dt > 5000 || dt < 0 ) {
            ConfigParser<SafetyKeys> safety = ( ConfigParser<SafetyKeys> ) RTP.configs.getParser( SafetyKeys.class );
            Object o = safety.getConfigValue( SafetyKeys.airBlocks, new ArrayList<>() );
            airBlocks = ( (o instanceof Collection ) ? ( Collection<?> ) o : new ArrayList<>() )
                    .stream().map( o1 -> o1.toString().toUpperCase() ).collect( Collectors.toSet() );
            if ( airBlocks.size() < 1 ) airBlocks.add( "AIR" );
            lastUpdate = t;
        }

        String material = getMaterial();

        return airBlocks.contains( material );
    }

    @Override
    public int x() {
        return block.getX();
    }

    @Override
    public int y() {
        return block.getY();
    }

    @Override
    public int z() {
        return block.getZ();
    }

    @Override
    public RTPWorld<?> world() {
        return RTP.serverAccessor.getRTPWorld( block.getWorld().getUID() );
    }

    @Override
    public int skyLight() {
        return block.getLightFromSky();
    }

    @Override
    public String getMaterial() {
        return block.getType().name().toUpperCase();
    }
}

