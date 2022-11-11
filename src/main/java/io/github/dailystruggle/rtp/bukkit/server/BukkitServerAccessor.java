package io.github.dailystruggle.rtp.bukkit.server;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPCommandSender;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPPlayer;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPWorld;
import io.github.dailystruggle.rtp.bukkit.tools.SendMessage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import io.github.dailystruggle.rtp.common.serverSide.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.TPS;
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
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class BukkitServerAccessor implements RTPServerAccessor {
    private final Map<UUID,RTPWorld> worldMap = new ConcurrentHashMap<>();
    private final Map<String,RTPWorld> worldMapStr = new ConcurrentHashMap<>();

    private final long t = System.nanoTime();

    private String version = null;
    private Integer intVersion = null;

    Function<String,Shape<?>> shapeFunction;

    public BukkitServerAccessor() {
        //run later to ensure RTP instance exists
        // configs are initialized in tick 1, so reference them at 2 or later
        // command processing timer is delayed to ensure this is set up before it's used

        shapeFunction = s -> {
            World world = Bukkit.getWorld(s);
            if(world == null) return null;
            Region region = RTP.getInstance().selectionAPI.getRegion(getRTPWorld(world.getUID()));
            if(region == null) throw new IllegalStateException();
            Object o = region.getData().get(RegionKeys.shape);
            if(!(o instanceof Shape<?>)) throw new IllegalStateException();
            return (Shape<?>) o;
        };
    }

    @Override
    public @NotNull String getServerVersion() {
        if(version == null) {
            version = RTPBukkitPlugin.getInstance().getServer().getClass().getPackage().getName();
            version = version.replaceAll("[-+^.a-zA-Z]*","");
        }

        return version;
    }

    @Override
    public @NotNull Integer getServerIntVersion() {
        if(intVersion == null) {
            String[] splitVersion = getServerVersion().split("_");
            if(splitVersion.length == 0) {
                intVersion = 0;
            }
            else if (splitVersion.length == 1) {
                intVersion = Integer.valueOf(splitVersion[0]);
            }
            else {
                intVersion = Integer.valueOf(splitVersion[1]);
            }
        }
        return intVersion;
    }

    @Override
    public RTPWorld getRTPWorld(String name) {
        RTPWorld world = worldMapStr.get(name);
        if(world == null) {
            World bukkitWorld = Bukkit.getWorld(name);
            if(bukkitWorld==null) return null;
            world = new BukkitRTPWorld(bukkitWorld);
            if(world == null) return null;
            worldMapStr.put(name,world);
            worldMap.put(world.id(),world);
        }
        else if(!world.isActive()) {
            worldMap.remove(world.id());
            worldMapStr.remove(world.name());
            return null;
        }
        return world;
    }

    @Override
    public @Nullable RTPWorld getRTPWorld(UUID id) {
        RTPWorld world = worldMap.get(id);
        if(world == null) {
            world = new BukkitRTPWorld(Bukkit.getWorld(id));
            if(world == null) return null;
            worldMap.put(id,world);
            worldMapStr.put(world.name(),world);
        }
        else if(!world.isActive()) {
            worldMap.remove(world.id());
            worldMapStr.remove(world.name());
            return null;
        }
        return world;
    }

    @Override
    public @Nullable Shape<?> getShape(String name) {
        return shapeFunction.apply(name);
    }

    @Override
    public boolean setShapeFunction(Function<String, Shape<?>> shapeFunction) {
        boolean works = true;
        for(World world : Bukkit.getWorlds()) {
            try {
                Shape<?> shape = shapeFunction.apply(world.getName());
                shape.select();
            }
            catch (Exception exception) {
                works = false;
                break;
            }
        }
        if(works) {
            this.shapeFunction = shapeFunction;
        }

        return works;
    }

    @Override
    public @NotNull List<RTPWorld> getRTPWorlds() {
        return Bukkit.getWorlds().stream().map(world -> getRTPWorld(world.getUID())).filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Override
    public @Nullable RTPPlayer getPlayer(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if(player == null) return null;
        return new BukkitRTPPlayer(player);
    }

    @Override
    public @Nullable RTPPlayer getPlayer(String name) {
        Player player = Bukkit.getPlayer(name);
        if(player == null) return null;
        return new BukkitRTPPlayer(player);
    }

    @Override
    public @Nullable RTPCommandSender getSender(UUID uuid) {
        CommandSender commandSender = (uuid == CommandsAPI.serverId) ? Bukkit.getConsoleSender() : Bukkit.getPlayer(uuid);
        if(commandSender == null) return null;
        if(commandSender instanceof Player) return new BukkitRTPPlayer((Player) commandSender);
        return new BukkitRTPCommandSender(commandSender);
    }

    @Override
    public long overTime() {
        return 0;
    }

    @Override
    public File getPluginDirectory() {
        return RTPBukkitPlugin.getInstance().getDataFolder();
    }

    @Override
    public void sendMessage(UUID target, MessagesKeys msgType) {
        if(RTPBukkitPlugin.getInstance()==null || !RTPBukkitPlugin.getInstance().isEnabled()) return;
        ConfigParser<MessagesKeys> parser = (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
        if(parser == null) return;
        String msg = String.valueOf(parser.getConfigValue(msgType,""));
        if(msg == null || msg.isEmpty()) return;
        sendMessage(target, msg);
    }

    @Override
    public void sendMessage(UUID target1, UUID target2, MessagesKeys msgType) {
        if(RTPBukkitPlugin.getInstance()==null || !RTPBukkitPlugin.getInstance().isEnabled()) return;
        ConfigParser<MessagesKeys> parser = (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
        String msg = String.valueOf(parser.getConfigValue(msgType,""));
        if(msg == null || msg.isEmpty()) return;
        sendMessage(target1,target2,msg);
    }

    @Override
    public void sendMessage(UUID target, String message) {
        if(RTPBukkitPlugin.getInstance()==null || !RTPBukkitPlugin.getInstance().isEnabled()) return;
        CommandSender sender = (target.equals(CommandsAPI.serverId))
                ? Bukkit.getConsoleSender()
                : Bukkit.getPlayer(target);
        if(sender!=null) SendMessage.sendMessage(sender,message);
    }

    @Override
    public void sendMessageAndSuggest(UUID target, String message, String suggestion) {
        if(RTPBukkitPlugin.getInstance()==null || !RTPBukkitPlugin.getInstance().isEnabled()) return;
        SendMessage.sendMessage(getSender(target),message,suggestion,suggestion);
    }

    @Override
    public void sendMessage(UUID target1, UUID target2, String message) {
        if(RTPBukkitPlugin.getInstance()==null || !RTPBukkitPlugin.getInstance().isEnabled()) return;
        CommandSender sender = (target1.equals(CommandsAPI.serverId))
                ? Bukkit.getConsoleSender()
                : Bukkit.getPlayer(target1);
        CommandSender player = (target2.equals(CommandsAPI.serverId))
                ? Bukkit.getConsoleSender()
                : Bukkit.getPlayer(target2);

        if(sender!=null && player!=null) SendMessage.sendMessage(sender,player,message);
    }

    @Override
    public void log(Level level, String msg) {
        if(RTPBukkitPlugin.getInstance()==null || !RTPBukkitPlugin.getInstance().isEnabled()) return;
        SendMessage.log(level,msg);
    }

    @Override
    public void log(Level level, String msg, Exception exception) {
        if(RTPBukkitPlugin.getInstance()==null || !RTPBukkitPlugin.getInstance().isEnabled()) return;
        SendMessage.log(level,msg,exception);
    }

    @Override
    public void announce(String msg, String permission) {
        if(RTPBukkitPlugin.getInstance()==null || !RTPBukkitPlugin.getInstance().isEnabled()) return;
        SendMessage.sendMessage(Bukkit.getConsoleSender(), msg);
        for(Player p : Bukkit.getOnlinePlayers().stream().filter(player -> player.hasPermission(permission)).collect(Collectors.toSet())) {
            SendMessage.sendMessage(p, msg);
        }
    }

    private Supplier<Set<String>> biomes = BukkitRTPWorld::getBiomes;
    public void setBiomes(Supplier<Set<String>> biomes) {
        try {
            biomes.get();
        } catch (Exception exception) {
            exception.printStackTrace();
            return;
        }
        this.biomes = biomes;
    }

    @Override
    public Set<String> getBiomes() {
        return biomes.get();
    }

    @Override
    public boolean isPrimaryThread() {
        return Bukkit.isPrimaryThread();
    }

    private Function<String,WorldBorder> worldBorderFunction = s -> {
        RTPWorld rtpWorld = getRTPWorld(s);
        if(rtpWorld instanceof BukkitRTPWorld) {
            World world = ((BukkitRTPWorld) rtpWorld).world();
            org.bukkit.WorldBorder worldBorder = world.getWorldBorder();
            return new WorldBorder(
                    () -> (Shape<?>) RTP.factoryMap.get(RTP.factoryNames.shape).get("SQUARE"),
                    rtpLocation -> {
                        if(RTP.serverAccessor.getServerIntVersion()>10)
                            return worldBorder.isInside(new Location(world,rtpLocation.x(),rtpLocation.y(),rtpLocation.z()));
                        Location center = worldBorder.getCenter();
                        double radius = worldBorder.getSize()/2;
                        RTPLocation c = new RTPLocation(rtpWorld,center.getBlockX(),center.getBlockY(),center.getBlockZ());
                        return c.distanceSquaredXZ(rtpLocation)<Math.pow(radius,2);
                    });
        }
        return null;
    };

    @Override
    public @Nullable WorldBorder getWorldBorder(String worldName) {
        return worldBorderFunction.apply(worldName);
    }

    @Override
    public boolean setWorldBorderFunction(Function<String, WorldBorder> function) {
        try {
            for(RTPWorld world : getRTPWorlds()) {
                WorldBorder border = function.apply(getRTPWorlds().get(0).name());
                int[] select = border.getShape().get().select();
                border.isInside().apply(new RTPLocation(world,select[0],92,select[1]));
            }
            worldBorderFunction = function;
        } catch (Error | Exception ignored) {
            return false;
        }

        return true;
    }

    @Override
    public Set<String> materials() {
        return Arrays.stream(Material.values()).map(Enum::name).collect(Collectors.toSet());
    }

    @Override
    public void stop() {
        getRTPWorlds().forEach(RTPWorld::forgetChunks);

        RTPBukkitPlugin plugin = RTPBukkitPlugin.getInstance();
        plugin.commandTimer.cancel();
        plugin.syncTimer.cancel();
        plugin.asyncTimer.cancel();

        worldMap.clear();
        worldMapStr.clear();
    }

    @Override
    public void start() {
        RTPBukkitPlugin plugin = RTPBukkitPlugin.getInstance();

        plugin.commandTimer = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            long avgTime = TPS.timeSinceTick(20) / 20;
            long currTime = TPS.timeSinceTick(1);
            CommandsAPI.execute(avgTime - currTime);
        }, 40, 1);

        plugin.syncTimer = new SyncTeleportProcessing().runTaskTimer(plugin,20,1);
        plugin.asyncTimer = new AsyncTeleportProcessing().runTaskTimerAsynchronously(plugin,20,1);

        Bukkit.getScheduler().runTaskAsynchronously(plugin,this::getRTPWorlds);
    }
}
