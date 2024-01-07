package io.github.dailystruggle.rtp.bukkit.spigotListeners;

import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPCommandSender;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPPlayer;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.teleport.RTPTeleportCancel;
import io.github.dailystruggle.rtp.common.tasks.teleport.SetupTeleport;
import io.github.dailystruggle.rtp.common.tools.ParsePermissions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;

public class OnEventTeleports implements Listener {
    private final ConcurrentSkipListSet<UUID> respawningPlayers = new ConcurrentSkipListSet<>();
    private final Map<UUID, Double> playerMoveDistances = new HashMap<>();

    private static boolean checkPerms( Player player, String... permissions ) {
        return ParsePermissions.hasPerm( new BukkitRTPCommandSender( player ), "rtp.onEvent.", permissions );
    }

    private static void teleportAction( Player player ) {
        if ( RTP.getInstance().processingPlayers.contains( player.getUniqueId()) ) return;
        RTP.getInstance().processingPlayers.add( player.getUniqueId() );
        TeleportData teleportData = RTP.getInstance().latestTeleportData.get( player.getUniqueId() );
        if ( teleportData != null ) {
            if ( !teleportData.completed ) return;
            RTP.getInstance().priorTeleportData.put( player.getUniqueId(), teleportData );
        }
        RTP.getInstance().latestTeleportData.remove( player.getUniqueId() );
        BukkitRTPPlayer rtpPlayer = new BukkitRTPPlayer( player );
        SetupTeleport setupTeleport = new SetupTeleport( rtpPlayer, rtpPlayer, RTP.selectionAPI.getRegion( rtpPlayer ), null );
        setupTeleport.setDelay( 10 );
        RTP.getInstance().setupTeleportPipeline.add( setupTeleport );
    }

    @EventHandler( priority = EventPriority.NORMAL )
    public void onPlayerChangeWorld( PlayerChangedWorldEvent event ) {
        Player player = event.getPlayer();
        if ( checkPerms( event.getPlayer(), "changeworld") ) {
            ConfigParser<LoggingKeys> logging = ( ConfigParser<LoggingKeys> ) RTP.configs.getParser( LoggingKeys.class );
            boolean verbose = false;
            if ( logging != null ) {
                Object o = logging.getConfigValue( LoggingKeys.event_join, false );
                if ( o instanceof Boolean ) {
                    verbose = ( Boolean ) o;
                } else {
                    verbose = Boolean.parseBoolean( o.toString() );
                }
            }
            if ( verbose ) RTP.log( Level.INFO, "#0080FF[RTP] teleporting player:" + player + " on world change" );

            teleportAction( player );
        }
    }

    @EventHandler( priority = EventPriority.LOW )
    public void onPlayerDeath( PlayerDeathEvent event ) {
        Player player = event.getEntity();
        UUID id = player.getUniqueId();

        TeleportData senderData = RTP.getInstance().latestTeleportData.get( id );

        RTPCommandSender sender = RTP.serverAccessor.getSender( id );
        if ( senderData != null ) {
            if ( senderData.sender == null ) {
                senderData.sender = sender;
            }

            long dt = System.currentTimeMillis() - senderData.time;

            if ( dt < 0 ) dt = Long.MAX_VALUE + dt;

            if ( dt < sender.cooldown() ) {
                return;
            }
        }

        //cancel previous teleport
        new RTPTeleportCancel( id ).run();

        if ( !checkPerms( player, "respawn") ) return;

        ConfigParser<LoggingKeys> logging = ( ConfigParser<LoggingKeys> ) RTP.configs.getParser( LoggingKeys.class );
        boolean verbose = false;
        if ( logging != null ) {
            Object o = logging.getConfigValue( LoggingKeys.event_join, false );
            if ( o instanceof Boolean ) {
                verbose = ( Boolean ) o;
            } else {
                verbose = Boolean.parseBoolean( o.toString() );
            }
        }
        if ( verbose ) RTP.log( Level.INFO, "#0080FF[RTP] generating respawn location for player:" + player + " on death" );

        respawningPlayers.add( id );

        Region region = RTP.selectionAPI.getRegion( new BukkitRTPPlayer( player) );

        //I don't know how this can happen, but in case player dies twice, don't reprocess
        if ( region.fastLocations.containsKey( id) ) return;

        CompletableFuture<Map.Entry<RTPLocation, Long>> future = new CompletableFuture<>();
        region.fastLocations.put( id, future );

        if ( RTP.getInstance().latestTeleportData.containsKey( id) ) {
            RTP.getInstance().priorTeleportData.put( id, RTP.getInstance().latestTeleportData.get( id) );
        }

        //prep location so it's ready when they respawn or shortly after
        TeleportData teleportData = new TeleportData();
        RTP.getInstance().latestTeleportData.put( id, teleportData );
        region.miscPipeline.add( () -> {
            Map.Entry<RTPLocation, Long> location = null;
            int i = 0;
            for ( ; location == null && i < 10; i++ ) {
                location = region.getLocation(
                        new BukkitRTPCommandSender( Bukkit.getConsoleSender() ),
                        new BukkitRTPPlayer( player ),
                        null );
            }
            if ( location == null ) {
                RTP.log( Level.WARNING, "#0080FF[RTP] failed to generate respawn location" );
                return;
            }

            if ( location.getKey() == null ) {
                return;
            }

            teleportData.selectedLocation = location.getKey();
            teleportData.attempts = location.getValue();

            future.complete( location );
        } );
    }

    @EventHandler( priority = EventPriority.HIGH )
    public void onPlayerRespawn( PlayerRespawnEvent event ) {
        Player player = event.getPlayer();

        if ( !respawningPlayers.contains( player.getUniqueId()) ) return;
        respawningPlayers.remove( player.getUniqueId() );

        Region region = RTP.selectionAPI.getRegion( new BukkitRTPPlayer( player) );
        ConcurrentHashMap<UUID, CompletableFuture<Map.Entry<RTPLocation, Long>>> respawnLocations = region.fastLocations;
        if ( !respawnLocations.containsKey( player.getUniqueId()) ) {
            RTPTeleportCancel.refund( player.getUniqueId() );
            return;
        }

        CompletableFuture<Map.Entry<RTPLocation, Long>> future = respawnLocations.get( player.getUniqueId() );
        region.fastLocations.remove( player.getUniqueId() );

        if ( event.isAnchorSpawn() || event.isBedSpawn() ) {
            if ( future.isDone() ) {
                try {
                    region.locationQueue.add( future.get() );
                } catch ( InterruptedException | ExecutionException ignored ) {

                }
            } else {
                future.thenAccept( rtpLocationLongEntry -> region.locationQueue.add( rtpLocationLongEntry) );
            }
            RTPTeleportCancel.refund( player.getUniqueId() );
            return;
        }

        TeleportData teleportData = RTP.getInstance().latestTeleportData.get( player.getUniqueId() );
        if ( teleportData == null ) {
            teleportData = new TeleportData();
            RTP.getInstance().latestTeleportData.put( player.getUniqueId(), teleportData );
        }
        teleportData.completed = true;

        ConfigParser<LoggingKeys> logging = ( ConfigParser<LoggingKeys> ) RTP.configs.getParser( LoggingKeys.class );
        boolean verbose = false;
        if ( logging != null ) {
            Object o = logging.getConfigValue( LoggingKeys.event_respawn, false );
            if ( o instanceof Boolean ) {
                verbose = ( Boolean ) o;
            } else {
                verbose = Boolean.parseBoolean( o.toString() );
            }
        }

        if ( future.isDone() ) {
            Map.Entry<RTPLocation, Long> location;
            try {
                location = future.get();
            } catch ( InterruptedException | ExecutionException e ) {
                RTP.log( Level.WARNING, e.getMessage(), e );
                RTPTeleportCancel.refund( player.getUniqueId() );
                return;
            }

            if ( location == null ) {
                RTPTeleportCancel.refund( player.getUniqueId() );
                return;
            }

            if ( location.getKey() == null ) {
                RTPTeleportCancel.refund( player.getUniqueId() );
                return;
            }

            RTPLocation rtpLocation = location.getKey();

            RTPWorld rtpWorld = rtpLocation.world();
            if ( rtpWorld instanceof BukkitRTPWorld ) {
                if ( verbose ) RTP.log( Level.INFO, "#0080FF[RTP] updating respawn location for player:" + player );
                event.setRespawnLocation( new Location( ((BukkitRTPWorld ) rtpWorld ).world(), rtpLocation.x(), rtpLocation.y(), rtpLocation.z()) );
                RTP.getInstance().processingPlayers.remove( player.getUniqueId() );
            } else throw new IllegalStateException( "expected bukkit world" );
            return;
        }

        boolean finalVerbose = verbose;
        future.thenAccept( location -> {
            if ( finalVerbose )
                RTP.log( Level.INFO, "#0080FF[RTP] teleporting player:" + player.getName() + " on respawn" );
            SetupTeleport setupTeleport = new SetupTeleport(
                    new BukkitRTPCommandSender( Bukkit.getConsoleSender() ),
                    new BukkitRTPPlayer( player ),
                    region,
                    null
             );
            RTP.getInstance().latestTeleportData.get( player.getUniqueId() ).nextTask = setupTeleport;
            RTP.getInstance().setupTeleportPipeline.add( setupTeleport );
        } );
    }

    @EventHandler( priority = EventPriority.HIGH )
    public void onPlayerJoin( PlayerJoinEvent event ) {
        Player player = event.getPlayer();

        long start = System.nanoTime();

        BukkitRTPCommandSender sender = new BukkitRTPCommandSender( player );
        boolean hasFirstJoin = ParsePermissions.hasPerm( sender, "rtp.onevent.", "firstjoin" );
        boolean hasJoin = ParsePermissions.hasPerm( sender, "rtp.onevent.", "join" );

        long cooldownTime = new BukkitRTPCommandSender( event.getPlayer() ).cooldown();

        ConfigParser<LoggingKeys> logging = ( ConfigParser<LoggingKeys> ) RTP.configs.getParser( LoggingKeys.class );
        boolean verbose = false;
        if ( logging != null ) {
            Object o = logging.getConfigValue( LoggingKeys.event_join, false );
            if ( o instanceof Boolean ) {
                verbose = ( Boolean ) o;
            } else {
                verbose = Boolean.parseBoolean( o.toString() );
            }
        }

        if ( hasFirstJoin && !player.hasPlayedBefore() ) {
            if ( verbose ) RTP.log( Level.INFO, "#0080FF[RTP] teleporting player:" + player + " on first join" );
            teleportAction( player );
        } else if ( hasJoin ) {
            TeleportData data = RTP.getInstance().latestTeleportData.get( player.getUniqueId() );
            long time = ( data == null ) ? 0 : data.time;
            if ( !player.hasPermission( "rtp.nocooldown" ) && ( start - time ) < cooldownTime ) {
                RTP.serverAccessor.sendMessage( player.getUniqueId(), MessagesKeys.cooldownMessage );
                return;
            }
            if ( verbose ) RTP.log( Level.INFO, "#0080FF[RTP] teleporting player:" + player + " on join" );
            teleportAction( player );
        }
    }

    @EventHandler( priority = EventPriority.LOW )
    public void onPlayerMove( PlayerMoveEvent event ) {
        Location to = event.getTo();
        Location from = event.getFrom();
        if ( to == null ) return;
        if ( from == null ) return;
        if ( from.distance( to ) == 0.0d ) return;
        Player player = event.getPlayer();

        ConfigParser<?> parser = RTP.configs.configParserMap.get(ConfigKeys.class);
        if(!parser.myClass.equals(ConfigKeys.class)) {
            RTP.log(Level.SEVERE,"", new IllegalStateException("unexpected class for configParser"));
            return;
        }
        @SuppressWarnings("unchecked") ConfigParser<ConfigKeys> configParser = ( ConfigParser<ConfigKeys> ) parser;

        playerMoveDistances.putIfAbsent( player.getUniqueId(), 0D );
        playerMoveDistances.compute( player.getUniqueId(), ( uuid, aDouble ) -> aDouble += from.distance( to) );
        double distance = playerMoveDistances.get( player.getUniqueId() );
        if ( distance < configParser.getNumber( ConfigKeys.cancelDistance, Double.MAX_VALUE ).doubleValue() ) return;

        playerMoveDistances.put( player.getUniqueId(), 0D );

        if ( checkPerms( player, "move") ) {
            ConfigParser<LoggingKeys> logging = ( ConfigParser<LoggingKeys> ) RTP.configs.getParser( LoggingKeys.class );
            boolean verbose = false;
            if ( logging != null ) {
                Object o = logging.getConfigValue( LoggingKeys.event_move, false );
                if ( o instanceof Boolean ) {
                    verbose = ( Boolean ) o;
                } else {
                    verbose = Boolean.parseBoolean( o.toString() );
                }
            }
            if ( verbose ) RTP.log( Level.INFO, "#0080FF[RTP] teleporting player:" + player + " on move" );
            teleportAction( player );
        }
    }

    @EventHandler( priority = EventPriority.LOWEST )
    public void onPlayerTeleport( PlayerTeleportEvent event ) {
        Player player = event.getPlayer();
        if ( checkPerms( player, "teleport") ) {
            ConfigParser<LoggingKeys> logging = ( ConfigParser<LoggingKeys> ) RTP.configs.getParser( LoggingKeys.class );
            boolean verbose = false;
            if ( logging != null ) {
                Object o = logging.getConfigValue( LoggingKeys.event_move, false );
                if ( o instanceof Boolean ) {
                    verbose = ( Boolean ) o;
                } else {
                    verbose = Boolean.parseBoolean( o.toString() );
                }
            }
            if ( verbose ) RTP.log( Level.INFO, "#0080FF[RTP] teleporting player:" + player + " on teleport" );
            teleportAction( player );
        }
    }
}
