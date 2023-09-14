package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear;

import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.BooleanParameter;
import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.IntegerParameter;
import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.GenericVerticalAdjustorKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPBlock;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPChunk;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class LinearAdjustor extends VerticalAdjustor<GenericVerticalAdjustorKeys> {
    protected static final Map<String, CommandParameter> subParameters = new ConcurrentHashMap<>();
    protected static final List<String> keys = Arrays.stream( GenericMemoryShapeParams.values() ).map( Enum::name ).collect( Collectors.toList() );
    private static final EnumMap<GenericVerticalAdjustorKeys, Object> defaults = new EnumMap<>( GenericVerticalAdjustorKeys.class );
    private static final Set<String> unsafeBlocks = new ConcurrentSkipListSet<>();
    private static final AtomicLong lastUpdate = new AtomicLong();

    private static final List<List<Integer>> testCoords = Arrays.asList( 
            Arrays.asList( 7,7 ),
            Arrays.asList( 2,2 ),
            Arrays.asList( 12,12 ),
            Arrays.asList( 2,12 ),
            Arrays.asList( 12,2 )
     );

    static {
        defaults.put( GenericVerticalAdjustorKeys.maxY, 127 );
        defaults.put( GenericVerticalAdjustorKeys.minY, 32 );
        defaults.put( GenericVerticalAdjustorKeys.direction, 0 );
        defaults.put( GenericVerticalAdjustorKeys.requireSkyLight, false );

        subParameters.put( "maxy", new IntegerParameter( "rtp.params", "highest possible location", ( sender, s ) -> true, 64, 92, 127, 256, 320) );
        subParameters.put( "miny", new IntegerParameter( "rtp.params", "lowest possible location", ( sender, s ) -> true, -64, 0, 64, 128) );
        subParameters.put( "direction", new IntegerParameter( "rtp.params", "which way to search for a valid location", ( sender, s ) -> true, 0, 1, 2, 3) );
        subParameters.put( "requireskylight", new BooleanParameter( "rtp.params", "require sky light for placement", ( sender, s ) -> true) );
    }

    public LinearAdjustor( List<Predicate<RTPBlock>> verifiers ) {
        super( GenericVerticalAdjustorKeys.class, "linear", verifiers, defaults );
    }

    @Override
    public List<String> keys() {
        return Arrays.stream( GenericVerticalAdjustorKeys.values() ).map( Enum::name ).collect( Collectors.toList() );
    }

    @Override
    public @Nullable
    RTPLocation adjust( @NotNull RTPChunk chunk ) {
        if ( chunk == null ) return null;

        int maxY = getNumber( GenericVerticalAdjustorKeys.maxY, 320L ).intValue();
        int minY = getNumber( GenericVerticalAdjustorKeys.minY, 0L ).intValue();
        int dir = getNumber( GenericVerticalAdjustorKeys.direction, 0 ).intValue();

        maxY = Math.min( maxY,chunk.getWorld().getMaxHeight() );

        boolean requireSkyLight;
        Object o = getData().getOrDefault( GenericVerticalAdjustorKeys.requireSkyLight, false );
        if ( o instanceof Boolean ) {
            requireSkyLight = ( Boolean ) o;
        } else requireSkyLight = Boolean.parseBoolean( o.toString() );

        long t = System.currentTimeMillis();
        long dt = t - lastUpdate.get();
        if ( dt > 5000 || dt < 0 ) {
            ConfigParser<SafetyKeys> safety = ( ConfigParser<SafetyKeys> ) RTP.configs.getParser( SafetyKeys.class );
            Object value = safety.getConfigValue( SafetyKeys.unsafeBlocks, new ArrayList<>() );
            unsafeBlocks.clear();
            if ( value instanceof Collection ) {
                unsafeBlocks.addAll( ((Collection<?> ) value ).stream().filter( Objects::nonNull ).map( Object::toString ).collect( Collectors.toSet()) );
            }
            lastUpdate.set( t );
        }

        for ( List<Integer> xz : testCoords ) {
            int x = xz.get( 0 );
            int z = xz.get( 1 );
            switch ( dir ) {
                case 0: { //bottom up
                    for ( int i = minY; i < maxY; i++ ) {
                        RTPBlock block1 = chunk.getBlockAt( x, i, z );
                        RTPBlock block2 = chunk.getBlockAt( x, i + 1, z );
                        int skylight = 15;
                        if ( requireSkyLight ) skylight = block2.skyLight();
                        if ( block1.isAir() && block2.isAir() && skylight > 7
                                && !unsafeBlocks.contains( block2.getMaterial() )
                                && !unsafeBlocks.contains( block1.getMaterial() )
                                && !unsafeBlocks.contains( chunk.getBlockAt( x, i - 1, z ).getMaterial()) ) {
                            return block1.getLocation();
                        }
                    }
                    break;
                }
                case 1: { //top down
                    for ( int i = maxY; i > minY; i-- ) {
                        RTPBlock block1 = chunk.getBlockAt( x, i, z );
                        RTPBlock block2 = chunk.getBlockAt( x, i + 1, z );
                        int skylight = 15;
                        if ( requireSkyLight ) skylight = block2.skyLight();
                        if ( block1.isAir() && block2.isAir() && skylight > 7
                                && !unsafeBlocks.contains( block2.getMaterial() )
                                && !unsafeBlocks.contains( block1.getMaterial() )
                                && !unsafeBlocks.contains( chunk.getBlockAt( x, i - 1, z ).getMaterial()) ) {
                            return block1.getLocation();
                        }
                    }
                    break;
                }
                case 2: { //middle out
                    int maxDistance = ( maxY - minY ) / 2; //dividing distance is more overflow-safe than simple average
                    int middle = minY + maxDistance;
                    for ( int i = 0; i <= maxDistance; i++ ) {
                        //try top
                        RTPBlock block1 = chunk.getBlockAt( x, middle + i, z );
                        RTPBlock block2 = chunk.getBlockAt( x, middle + i + 1, z );
                        int skylight = 15;
                        if ( requireSkyLight ) skylight = block2.skyLight();
                        if ( block1.isAir() && block2.isAir() && skylight > 7
                                && !unsafeBlocks.contains( block2.getMaterial() )
                                && !unsafeBlocks.contains( block1.getMaterial() )
                                && !unsafeBlocks.contains( chunk.getBlockAt( x, middle + i - 1, z ).getMaterial()) ) {
                            return block1.getLocation();
                        }

                        //try bottom
                        block1 = chunk.getBlockAt( x, middle - i, z );
                        block2 = chunk.getBlockAt( x, middle - i + 1, z );
                        skylight = 15;
                        if ( requireSkyLight ) skylight = block2.skyLight();
                        if ( block1.isAir() && block2.isAir() && skylight > 7
                                && !unsafeBlocks.contains( block2.getMaterial() )
                                && !unsafeBlocks.contains( block1.getMaterial() )
                                && !unsafeBlocks.contains( chunk.getBlockAt( x, middle - i - 1, z ).getMaterial()) ) {
                            return block1.getLocation();
                        }
                    }
                    break;
                }
                case 3: { //edges in
                    int maxDistance = ( maxY - minY ) / 2; //dividing distance is more overflow-safe than simple average
                    int middle = minY + maxDistance;
                    for ( int i = maxDistance; i >= 0; i-- ) {
                        //try top
                        RTPBlock block1 = chunk.getBlockAt( x, middle + i, z );
                        RTPBlock block2 = chunk.getBlockAt( x, middle + i + 1, z );
                        int skylight = 15;
                        if ( requireSkyLight ) skylight = block2.skyLight();
                        if ( block1.isAir() && block2.isAir() && skylight > 7
                                && !unsafeBlocks.contains( block2.getMaterial() )
                                && !unsafeBlocks.contains( block1.getMaterial() )
                                && !unsafeBlocks.contains( chunk.getBlockAt( x, middle + i - 1, z ).getMaterial()) ) {
                            return block1.getLocation();
                        }

                        //try bottom
                        block1 = chunk.getBlockAt( x, middle - i, z );
                        block2 = chunk.getBlockAt( x, middle - i + 1, z );
                        skylight = 15;
                        if ( requireSkyLight ) skylight = block2.skyLight();
                        if ( block1.isAir() && block2.isAir() && skylight > 7
                                && !unsafeBlocks.contains( block2.getMaterial() )
                                && !unsafeBlocks.contains( block1.getMaterial() )
                                && !unsafeBlocks.contains( chunk.getBlockAt( x, middle - i - 1, z ).getMaterial()) ) {
                            return block1.getLocation();
                        }
                    }
                    break;
                }
                default: { //random order
                    //load up a list of possible vertical indices
                    List<Integer> trials = new ArrayList<>( maxY - minY + 1 );
                    for ( int i = minY; i < maxY; i++ ) {
                        trials.add( i );
                    }

                    //randomize order
                    Collections.shuffle( trials );

                    //try each
                    for ( int i : trials ) {
                        RTPBlock block1 = chunk.getBlockAt( x, i, z );
                        RTPBlock block2 = chunk.getBlockAt( x, i + 1, z );
                        int skylight = 15;
                        if ( requireSkyLight ) skylight = block2.skyLight();
                        if ( block1.isAir() && block2.isAir() && skylight > 7
                                && !unsafeBlocks.contains( block2.getMaterial() )
                                && !unsafeBlocks.contains( block1.getMaterial() )
                                && !unsafeBlocks.contains( chunk.getBlockAt( x, i - 1, z ).getMaterial()) ) {
                            return block1.getLocation();
                        }
                    }
                }
            }
        }
        return null;
    }

    @Override
    public boolean testPlacement( @NotNull RTPBlock block ) {
        for ( Predicate<RTPBlock> rtpLocationPredicate : verifiers ) {
            if ( !rtpLocationPredicate.test( block) )
                return false;
        }
        return true;
    }

    @Override
    public Map<String, CommandParameter> getParameters() {
        return subParameters;
    }

    @Override
    public int minY() {
        return getNumber( GenericVerticalAdjustorKeys.minY, 0 ).intValue();
    }

    @Override
    public int maxY() {
        return getNumber( GenericVerticalAdjustorKeys.maxY, 256 ).intValue();
    }
}