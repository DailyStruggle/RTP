package io.github.dailystruggle.rtp_iris_integration;

import com.volmit.iris.core.tools.IrisToolbelt;
import com.volmit.iris.engine.object.IrisBiome;
import com.volmit.iris.engine.platform.PlatformChunkGenerator;
import com.volmit.iris.util.collection.KList;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPWorld;
import io.github.dailystruggle.rtp.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Biome;

import java.util.*;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class IrisBiomeParameter extends CommandParameter {
    public IrisBiomeParameter() {
        super( "rtp.biome", "iris biomes", ( uuid, s ) -> {
            s = s.toUpperCase();
            RTPCommandSender sender = Objects.requireNonNull( RTP.serverAccessor.getSender( uuid) );
            if( !sender.hasPermission( "rtp.biome." + s) ) return false;
            for( RTPWorld world : RTP.serverAccessor.getRTPWorlds() ) {
                Set<String> set = RTP_Iris_integration.getBiomes( world );
                if( set.contains( s) ) return true;
            }
            return false;
        } );
    }

    private static final Pattern invalidCharacters = Pattern.compile( "[ :,]" );
    @Override
    public Set<String> values() {
        Set<String> res = new HashSet<>();
        for( RTPWorld rtpWorld : RTP.serverAccessor.getRTPWorlds() ) {
            if( !(rtpWorld instanceof BukkitRTPWorld) ) {
                continue;
            }
            BukkitRTPWorld world = ( BukkitRTPWorld ) rtpWorld;
            PlatformChunkGenerator access = IrisToolbelt.access( world.world() );
            if( access == null ) {
                res.addAll( Arrays.stream( Biome.values() ).map( Enum::name ).collect( Collectors.toSet()) );
                continue;
            }
            KList<IrisBiome> allBiomes = access.getEngine().getAllBiomes();
            for( IrisBiome irisBiome : allBiomes ) {
                String s = irisBiome.getName().toUpperCase();
                res.add( invalidCharacters.matcher( s ).replaceAll( "_") );
            }
        }
        return res;
    }

    @Override
    public Set<String> relevantValues( UUID senderId ) {
        Set<String> res = new HashSet<>();

        RTPCommandSender sender = RTP.serverAccessor.getSender( senderId );
        if( sender == null ) return res;

        World world;
        if( sender instanceof RTPPlayer ) {
            RTPPlayer player = ( RTPPlayer ) sender;

            Set<String> worldsAttempted = new HashSet<>();
            String worldName = player.getLocation().world().name();
            MultiConfigParser<WorldKeys> worldParsers = ( MultiConfigParser<WorldKeys> ) RTP.configs.multiConfigParserMap.get( WorldKeys.class );
            ConfigParser<WorldKeys> worldParser = worldParsers.getParser( worldName );

            for ( boolean requirePermission = Boolean.parseBoolean( worldParser.getConfigValue( WorldKeys.requirePermission, false ).toString() );
                 requirePermission && !player.hasPermission( "rtp.worlds." + worldName );
                 requirePermission = Boolean.parseBoolean( worldParser.getConfigValue( WorldKeys.requirePermission, false ).toString() )
             ) {
                if ( worldsAttempted.contains( worldName) ) {
                    throw new IllegalStateException( "infinite override loop detected at world - " + worldName );
                }

                worldsAttempted.add( worldName );
                worldName = String.valueOf( worldParser.getConfigValue( WorldKeys.override, "default") );
                worldParser = worldParsers.getParser( worldName );
            }

            world = Bukkit.getWorld( worldName );
            if( world == null ) world = Bukkit.getWorlds().get( 0 );
        }
        else world = Bukkit.getWorlds().get( 0 );

        PlatformChunkGenerator access = IrisToolbelt.access( world );
        if( access == null ) {
            return Arrays.stream( Biome.values() ).map( Enum::name ).collect( Collectors.toSet() );
        }
        KList<IrisBiome> allBiomes = access.getEngine().getAllBiomes();
        for( IrisBiome irisBiome : allBiomes ) {
            String s = irisBiome.getName().toUpperCase();
            res.add( invalidCharacters.matcher( s ).replaceAll( "_") );
        }
        return res;
    }
}
