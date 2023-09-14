package io.github.dailystruggle.rtp.bukkit.commands;

import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.*;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.bukkit.events.TeleportCommandFailEvent;
import io.github.dailystruggle.rtp.bukkit.events.TeleportCommandSuccessEvent;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPCommandSender;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.RTPCmd;
import io.github.dailystruggle.rtp.common.commands.fill.FillCmd;
import io.github.dailystruggle.rtp.common.commands.help.HelpCmd;
import io.github.dailystruggle.rtp.common.commands.info.InfoCmd;
import io.github.dailystruggle.rtp.common.commands.parameters.RegionParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.ShapeParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.VertParameter;
import io.github.dailystruggle.rtp.common.commands.reload.ReloadCmd;
import io.github.dailystruggle.rtp.common.commands.update.UpdateCmd;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import org.bukkit.Bukkit;
import org.bukkit.block.Biome;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Predicate;
import java.util.logging.Level;

public class RTPCmdBukkit extends BukkitBaseRTPCmd implements RTPCmd {
    //for optimizing parameters,

    private final Semaphore senderChecksGuard = new Semaphore( 1 );
    private final List<Predicate<CommandSender>> senderChecks = new ArrayList<>();

    public RTPCmdBukkit( Plugin plugin ) {
        super( plugin, null );

        //region name parameter
        // filter by region exists and sender permission
        RegionParameter regionParameter = new RegionParameter( 
                "rtp.region",
                "select a region to teleport to",
                ( uuid, s ) -> RTP.selectionAPI.regionNames().contains( s ) && RTP.serverAccessor.getSender( uuid ).hasPermission( "rtp.regions." + s) );
        regionParameter.put( "world", new io.github.dailystruggle.rtp.common.commands.parameters.WorldParameter( "rtp.params", "modify xz selection",
                ( uuid, s ) -> ( Bukkit.getWorld( s ) != null ) & RTP.serverAccessor.getSender( uuid ).hasPermission( "rtp.worlds."+s)) );
        regionParameter.put( "price", new FloatParameter( "rtp.params", "modify xz selection",
                ( uuid, s ) -> {
                    try {
                        Double.parseDouble( s );
                        return true;
                    } catch ( NumberFormatException exception ) {
                        return false;
                    }
                }) );
        regionParameter.put( "worldborderoverride", new BooleanParameter( "rtp.params", "modify xz selection",
                ( uuid, s ) -> ( s.equalsIgnoreCase( "true" ) || s.equalsIgnoreCase( "false"))) );
        regionParameter.put( "shape", new ShapeParameter( "rtp.params", "modify xz selection",
                ( uuid, s ) -> RTP.factoryMap.get( RTP.factoryNames.shape ).contains( s)) );
        regionParameter.put( "vert", new VertParameter( "rtp.params", "modify y selection",
                ( uuid, s ) -> RTP.factoryMap.get( RTP.factoryNames.vert ).contains( s)) );

        addParameter( "region", regionParameter );

        addParameter( "biome", new EnumParameter<>( 
                "rtp.biome",
                "select a world to teleport to",
                ( sender, s ) -> {
                    try {
                        Biome.valueOf( s.toUpperCase() );
                    } catch ( IllegalArgumentException badBiome ) {
                        return false;
                    }
                    return sender.hasPermission( "rtp.biome.*" ) || sender.hasPermission( "rtp.biome." + s );
                },
                Biome.class )
         );

        //target player parameter
        // filter by player exists and player permission
        addParameter( "player", new OnlinePlayerParameter( 
                "rtp.other",
                "teleport someone else",
                ( sender, s ) -> {
                    if( !sender.hasPermission( "rtp.other") ) return false;
                    Player player = Bukkit.getPlayer( s );
                    return player != null && !player.hasPermission( "rtp.notme" );
                } )
         );

        //world name parameter
        // filter by world exists and sender permission
        addParameter( "world", new WorldParameter( 
                "rtp.world",
                "select a world to teleport to",
                ( sender, s ) -> Bukkit.getWorld( s ) != null && sender.hasPermission( "rtp.worlds." + s)) );

        addParameter( "toggletargetperms", new BooleanParameter( 
                "rtp.params",
                "check player's perms when running this command",
                ( sender, s ) -> sender.hasPermission( "rtp.params")) );


        addSubCommand( new ReloadCmd( this) );
        addSubCommand( new HelpCmd( this) );
        addSubCommand( new UpdateCmd( this) );
        addSubCommand( new FillCmd( this) );
        addSubCommand( new InfoCmd( this) );
    }

    public void addSenderCheck( Predicate<CommandSender> senderCheck ) {
        try {
            senderChecksGuard.acquire();
            senderChecks.add( senderCheck );
        } catch ( InterruptedException e ) {
            RTP.log( Level.WARNING, e.getMessage(), e );
        } finally {
            senderChecksGuard.release();
        }
    }

    @Override
    public boolean onCommand( CommandSender sender, Command command, String label, String[] args ) {
        boolean valid = true;
        for ( Predicate<CommandSender> commandSenderPredicate : senderChecks ) {
            valid &= commandSenderPredicate.test( sender );
        }
        if( !valid ) return false;
        return onCommand( new BukkitRTPCommandSender( sender ), this, label, args );
    }

    @Override
    public boolean onCommand( CommandSender sender, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand ) {
        boolean valid = true;
        for ( Predicate<CommandSender> commandSenderPredicate : senderChecks ) {
            valid &= commandSenderPredicate.test( sender );
        }
        if( !valid ) return false;
        return compute( new BukkitRTPCommandSender( sender ).uuid(), parameterValues, nextCommand );//todo:async
    }

    @Override
    public void successEvent( RTPCommandSender sender, RTPPlayer player ) {
        TeleportCommandSuccessEvent event = new TeleportCommandSuccessEvent( sender, player );
        Bukkit.getPluginManager().callEvent( event );
    }

    @Override
    public void failEvent( RTPCommandSender sender, String msg ) {
        TeleportCommandFailEvent event = new TeleportCommandFailEvent( sender, msg );
        Bukkit.getPluginManager().callEvent( event );
    }
}
