package io.github.dailystruggle.rtp.spigot_v1_20_R1.server;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.help.SendMessage;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.Mode;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import io.github.dailystruggle.rtp.common.tasks.TPS;
import io.github.dailystruggle.rtp.spigot_v1_20_R1.entity.BukkitRTPCommandSender;
import io.github.dailystruggle.rtp.spigot_v1_20_R1.entity.BukkitRTPPlayer;
import io.github.dailystruggle.rtp.spigot_v1_20_R1.world.BukkitRTPChunkManager;
import io.github.dailystruggle.rtp.spigot_v1_20_R1.world.BukkitRTPWorld;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ServerAccessorImpl implements RTPServerAccessor {
    private final Map<UUID, RTPWorld<?>> worldMap = new ConcurrentHashMap<>();
    private final Map<String, RTPWorld<?>> worldMapStr = new ConcurrentHashMap<>();
    Function<String, Shape<?>> shapeFunction;
    private String version = null;
    private Integer intVersion = null;
    private Function<RTPWorld<?>, Set<String>> biomes = BukkitRTPWorld::getBiomes;

    private Function<String, WorldBorder> worldBorderFunction = s -> {
        RTPWorld rtpWorld = getRTPWorld( s );
        if ( rtpWorld instanceof BukkitRTPWorld ) {
            World world = ( (BukkitRTPWorld ) rtpWorld ).world();
            org.bukkit.WorldBorder worldBorder = world.getWorldBorder();
            return new WorldBorder(
                    () -> {
                        Shape<?> shape = (Shape<?>) RTP.serverAccessor.getShape(s);
                        if( shape == null || !shape.name.equalsIgnoreCase("SQUARE") )
                            shape = (Shape<?>) RTP.factoryMap.get(RTP.factoryNames.shape).get("SQUARE");
                        Square square = (Square) shape;
                        square.set(GenericMemoryShapeParams.radius, ((long) worldBorder.getSize()*0.9) / 32);
                        square.set(GenericMemoryShapeParams.centerRadius, 0L);
                        square.set(GenericMemoryShapeParams.centerX,worldBorder.getCenter().getBlockX()/16);
                        square.set(GenericMemoryShapeParams.centerZ,worldBorder.getCenter().getBlockZ()/16);
                        square.set(GenericMemoryShapeParams.expand,false);
                        square.set(GenericMemoryShapeParams.weight,1);
                        square.set(GenericMemoryShapeParams.mode, Mode.NEAREST);
                        square.set(GenericMemoryShapeParams.uniquePlacements,false);
                        return shape;
                    },
                    rtpLocation -> {
                        if ( getServerIntVersion() > 10 )
                            return worldBorder.isInside( new Location( world, rtpLocation.x(), rtpLocation.y(), rtpLocation.z()) );
                        Location center = worldBorder.getCenter();
                        double radius = worldBorder.getSize() / 2;
                        RTPLocation c = new RTPLocation( rtpWorld, center.getBlockX(), center.getBlockY(), center.getBlockZ() );
                        return c.distanceSquaredXZ( rtpLocation ) < Math.pow( radius, 2 );
                    } );
        }
        return null;
    };

    public ServerAccessorImpl() {
        shapeFunction = s -> {
            World world = Bukkit.getWorld( s );
            if ( world == null ) return null;
            Region region = RTP.selectionAPI.getRegion( getRTPWorld( world.getUID()) );
            if ( region == null ) throw new IllegalStateException();
            Object o = region.getData( RegionKeys.shape );
            if ( !(o instanceof Shape<?>) ) throw new IllegalStateException();
            return ( Shape<?> ) o;
        };
    }

    private static final Pattern versionPattern = Pattern.compile( "[-+^.a-zA-Z]*",Pattern.CASE_INSENSITIVE );
    @Override
    public @NotNull String getServerVersion() {
        if ( version == null ) {
            version = Bukkit.getServer().getClass().getPackage().getName();
            if(!version.contains("1_")) {
                String bukkitVersion = Bukkit.getServer().getBukkitVersion();
                int end = bukkitVersion.indexOf("-R");
                if(end < 0) return "1_13_2";
                bukkitVersion = bukkitVersion.substring(0,end).replaceAll("\\.","_");
                return bukkitVersion;
            }
            else version = versionPattern.matcher( version ).replaceAll( "" );
        }
        return version;
    }

    @Override
    public @NotNull Integer getServerIntVersion() {
        if ( intVersion == null ) {
            String[] splitVersion = getServerVersion().split( "_" );
            if ( splitVersion.length == 0 ) {
                intVersion = 1;
            } else if ( splitVersion.length == 1 ) {
                try {
                    intVersion = Integer.valueOf( splitVersion[0] );
                } catch (NumberFormatException e) {
                    intVersion = 1;
                }
            } else {
                try {
                    intVersion = Integer.valueOf( splitVersion[1] );
                } catch (NumberFormatException e) {
                    intVersion = 1;
                }
            }
        }
        return intVersion;
    }

    @Override
    public RTPWorld<?> getRTPWorld( String name ) {
        RTPWorld<?> world = worldMapStr.get( name );
        World bukkitWorld = Bukkit.getWorld( name );
        if ( world == null && bukkitWorld !=null ) {
            world = new BukkitRTPWorld(bukkitWorld);
            worldMap.put( world.id(), world );
            worldMapStr.put( world.name(), world );
        }
        return world;
    }

    @Override
    public @Nullable RTPWorld<?> getRTPWorld( UUID id ) {
        RTPWorld<?> world = worldMap.get( id );
        World bukkitWorld = Bukkit.getWorld(id);
        if ( world == null && bukkitWorld !=null ) {
            world = new BukkitRTPWorld(bukkitWorld);
            worldMap.put( world.id(), world );
            worldMapStr.put( world.name(), world );
        }
        return world;
    }

    @Override
    public io.github.dailystruggle.rtp.api.world.RTPChunkManager getChunkManager() {
        return new BukkitRTPChunkManager();
    }

    @Override
    public @Nullable Object getShape( String name ) {
        return shapeFunction.apply( name );
    }

    @Override
    public boolean isPrimaryThread() {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public @Nullable Object getWorldBorder( String worldName ) {
        return worldBorderFunction.apply( worldName );
    }

    @Override
    public @NotNull List<RTPWorld<?>> getRTPWorlds() {
        return Bukkit.getWorlds().stream().map( world -> getRTPWorld( world.getUID()) ).filter( Objects::nonNull ).collect( Collectors.toList() );
    }

    @Override
    public @Nullable RTPPlayer getPlayer( UUID uuid ) {
        Player player = Bukkit.getPlayer( uuid );
        if ( player == null ) return null;
        return new BukkitRTPPlayer( player );
    }

    @Override
    public @Nullable RTPPlayer getPlayer( String name ) {
        Player player = Bukkit.getPlayer( name );
        if ( player == null ) return null;
        return new BukkitRTPPlayer( player );
    }

    @Override
    public @Nullable RTPCommandSender getSender( UUID uuid ) {
        CommandSender commandSender = ( uuid.equals(RTPAPI.serverId) ) ? Bukkit.getConsoleSender() : Bukkit.getPlayer( uuid );
        if ( commandSender == null ) return null;
        if ( commandSender instanceof Player ) return new BukkitRTPPlayer( (Player ) commandSender );
        return new BukkitRTPCommandSender( commandSender );
    }

    @Override
    public long overTime() {
        return 0;
    }

    @Override
    public File getPluginDirectory() {
        return Bukkit.getPluginManager().getPlugin("RTP").getDataFolder();
    }

    @Override
    public void sendMessage( UUID target, MessagesKeys msgType ) {
        ConfigParser<MessagesKeys> parser = ( ConfigParser<MessagesKeys> ) RTP.configs.getParser( MessagesKeys.class );
        if ( parser == null ) return;
        String msg = String.valueOf( parser.getConfigValue( msgType, "") );
        if ( msg == null || msg.isEmpty() ) return;
        sendMessage( target, msg );
    }

    @Override
    public void sendMessage( UUID target1, UUID target2, MessagesKeys msgType ) {
        ConfigParser<MessagesKeys> parser = ( ConfigParser<MessagesKeys> ) RTP.configs.getParser( MessagesKeys.class );
        String msg = String.valueOf( parser.getConfigValue( msgType, "") );
        if ( msg == null || msg.isEmpty() ) return;
        sendMessage( target1, target2, msg );
    }

    @Override
    public void sendMessage( UUID target, String message ) {
        CommandSender sender = ( target.equals( RTPAPI.serverId) )
                ? Bukkit.getConsoleSender()
                : Bukkit.getPlayer( target );
        if ( sender != null ) SendMessage.sendMessage( getSender(target), message );
    }

    @Override
    public void sendMessageAndSuggest( UUID target, String message, String suggestion ) {
        SendMessage.sendMessage( getSender( target ), message, suggestion, suggestion );
    }

    @Override
    public void sendMessage( UUID target1, UUID target2, String message ) {
        CommandSender sender = ( target1.equals( RTPAPI.serverId) )
                ? Bukkit.getConsoleSender()
                : Bukkit.getPlayer( target1 );
        CommandSender player = ( target2.equals( RTPAPI.serverId) )
                ? Bukkit.getConsoleSender()
                : Bukkit.getPlayer( target2 );

        if ( sender != null && player != null ) SendMessage.sendMessage( getSender(target1), getSender(target2), message );
    }

    @Override
    public void log( Level level, String msg ) {
        Bukkit.getLogger().log( level, msg );
    }

    @Override
    public void log( Level level, String msg, Throwable throwable ) {
        Bukkit.getLogger().log( level, msg, throwable );
    }

    @Override
    public void announce( String msg, String permission ) {
        SendMessage.sendMessage( Bukkit.getConsoleSender(), msg );
        for ( Player p : Bukkit.getOnlinePlayers().stream().filter( player -> player.hasPermission( permission) ).collect( Collectors.toSet()) ) {
            SendMessage.sendMessage( p, msg );
        }
    }

    @Override
    public Set<String> getBiomes( RTPWorld<?> rtpWorld ) {
        return biomes.apply( rtpWorld );
    }

    @Override
    public Set<String> materials() {
        return Arrays.stream( Material.values() ).map( Enum::name ).collect( Collectors.toSet() );
    }

    @Override
    public void stop() {
        // Implementation logic
    }

    @Override
    public boolean setShapeFunction( Function<String, ?> shapeFunction ) {
        this.shapeFunction = (Function<String, Shape<?>>) shapeFunction;
        return true;
    }

    @Override
    public boolean setWorldBorderFunction( Function<String, ?> function ) {
        this.worldBorderFunction = (Function<String, WorldBorder>) function;
        return true;
    }

    @Override
    public void start() {
        // Implementation logic
    }

    @Override
    public void setBiomeGetter(Function<RTPLocation, String> getter) {
        BukkitRTPWorld.setBiomeGetter(location -> {
            RTPWorld<?> rtpWorld = getRTPWorld(location.getWorld().getUID());
            return getter.apply(new RTPLocation(rtpWorld, location.getBlockX(), location.getBlockY(), location.getBlockZ()));
        });
    }

    @Override
    public void setBiomesGetter(Function<RTPWorld<?>, Set<String>> getter) {
        BukkitRTPWorld.setBiomesGetter(getter);
    }
}

