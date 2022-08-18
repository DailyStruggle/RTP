package io.github.dailystruggle.rtp.bukkit.server;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPCommandSender;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPPlayer;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPWorld;
import io.github.dailystruggle.rtp.bukkit.tools.SendMessage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LangKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import io.github.dailystruggle.rtp.common.serverSide.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.TPS;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class BukkitServerAccessor implements RTPServerAccessor {
    private final Map<UUID,RTPWorld> worldMap = new ConcurrentHashMap<>();
    private final Map<String,RTPWorld> worldMapStr = new ConcurrentHashMap<>();

    private long t = System.nanoTime();

    private String version = null;
    private Integer intVersion = null;

    Function<String,Shape<?>> shapeFunction;

    private final Map<UUID,RTPWorld> worlds = new ConcurrentHashMap<>(Bukkit.getWorlds().size());

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

        Region.addGlobalRegionVerifier(rtpLocation -> getWorldBorder(rtpLocation.world().name()).isInside().apply(rtpLocation));
    }

    @Override
    public String getServerVersion() {
        if(version == null) {
            version = RTPBukkitPlugin.getInstance().getServer().getClass().getPackage().getName();
            version = version.replaceAll("[-+^.a-zA-Z]*","");
        }

        return version;
    }

    @Override
    public Integer getServerIntVersion() {
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
            world = new BukkitRTPWorld(Bukkit.getWorld(name));
            if(world == null) return null;
            worldMapStr.put(name,world);
        }
        return world;
    }

    @Override
    public RTPWorld getRTPWorld(UUID id) {
        RTPWorld world = worldMap.get(id);
        if(world == null) {
            world = new BukkitRTPWorld(Bukkit.getWorld(id));
            if(world == null) return null;
            worldMap.put(id,world);
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
    public List<RTPWorld> getRTPWorlds() {
        return Bukkit.getWorlds().stream().map(world -> getRTPWorld(world.getUID())).filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Override
    public RTPPlayer getPlayer(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if(player == null) return null;
        return new BukkitRTPPlayer(player);
    }

    @Override
    public RTPPlayer getPlayer(String name) {
        Player player = Bukkit.getPlayer(name);
        if(player == null) return null;
        return new BukkitRTPPlayer(player);
    }

    @Override
    public RTPCommandSender getSender(UUID uuid) {
        CommandSender commandSender = (uuid == CommandsAPI.serverId) ? Bukkit.getConsoleSender() : Bukkit.getPlayer(uuid);
        if(commandSender == null) return null;
        if(commandSender instanceof Player player) return new BukkitRTPPlayer(player);
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
    public void sendMessage(UUID target, LangKeys msgType) {
        ConfigParser<LangKeys> parser = (ConfigParser<LangKeys>) RTP.getInstance().configs.getParser(LangKeys.class);
        if(parser == null) return;
        String msg = String.valueOf(parser.getConfigValue(msgType,""));
        if(msg == null || msg.isBlank()) return;
        sendMessage(target, msg);
    }

    @Override
    public void sendMessage(UUID target1, UUID target2, LangKeys msgType) {
        ConfigParser<LangKeys> parser = (ConfigParser<LangKeys>) RTP.getInstance().configs.getParser(LangKeys.class);
        String msg = String.valueOf(parser.getConfigValue(msgType,""));
        if(msg == null || msg.isBlank()) return;
        sendMessage(target1,target2,msg);
    }

    @Override
    public void sendMessage(UUID target, String message) {
        CommandSender sender = (target.equals(CommandsAPI.serverId))
                ? Bukkit.getConsoleSender()
                : Bukkit.getPlayer(target);
        if(sender!=null) SendMessage.sendMessage(sender,message);
    }

    @Override
    public void sendMessage(UUID target1, UUID target2, String message) {
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
        SendMessage.log(level,msg);
    }

    @Override
    public void log(Level level, String msg, Exception exception) {
        SendMessage.log(level,msg,exception);
    }

    @Override
    public Set<String> getBiomes() {
        return BukkitRTPWorld.getBiomes();
    }

    @Override
    public boolean isPrimaryThread() {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public void reset() {
        final RTPBukkitPlugin bukkitPlugin = RTPBukkitPlugin.getInstance();

        bukkitPlugin.commandTimer.cancel();
        bukkitPlugin.teleportTimer.cancel();

        if(bukkitPlugin.commandProcessing!=null) {
            bukkitPlugin.commandProcessing.cancel();
        }
        if(bukkitPlugin.syncTeleportProcessing!=null) {
            bukkitPlugin.syncTeleportProcessing.cancel();
            bukkitPlugin.syncTeleportProcessing = null;
        }
        if(bukkitPlugin.asyncTeleportProcessing!=null) {
            bukkitPlugin.asyncTeleportProcessing.cancel();
            bukkitPlugin.asyncTeleportProcessing = null;
        }

        bukkitPlugin.commandTimer = Bukkit.getScheduler().runTaskTimer(bukkitPlugin, () -> {
            long avgTime = TPS.timeSinceTick(20) / 20;
            long currTime = TPS.timeSinceTick(1);

            if(bukkitPlugin.commandProcessing == null) {
                bukkitPlugin.commandProcessing = Bukkit.getScheduler().runTaskAsynchronously(
                        RTPBukkitPlugin.getInstance(),
                        () -> {
                            CommandsAPI.execute(avgTime - currTime);
                            RTPBukkitPlugin.getInstance().commandProcessing = null;
                        }
                );
            }
        }, 40, 1);

        bukkitPlugin.teleportTimer = Bukkit.getScheduler().runTaskTimer(bukkitPlugin, () -> {
            long avgTime = TPS.timeSinceTick(20) / 20;
            long currTime = TPS.timeSinceTick(1);

            long availableTime = avgTime - currTime;
            availableTime = TimeUnit.MICROSECONDS.toNanos(availableTime);

            if(bukkitPlugin.asyncTeleportProcessing == null) {
                long finalAvailableTime = availableTime;
                bukkitPlugin.asyncTeleportProcessing = Bukkit.getScheduler().runTaskAsynchronously(
                        RTPBukkitPlugin.getInstance(),
                        () -> {
                            RTP.getInstance().executeAsyncTasks(finalAvailableTime);
                            RTPBukkitPlugin.getInstance().asyncTeleportProcessing = null;
                        }
                );
            }

            if(bukkitPlugin.syncTeleportProcessing == null) {
                long finalAvailableTime = availableTime;
                bukkitPlugin.syncTeleportProcessing = Bukkit.getScheduler().runTask(
                        RTPBukkitPlugin.getInstance(),
                        () -> {
                            RTP.getInstance().executeSyncTasks(finalAvailableTime);
                            RTPBukkitPlugin.getInstance().syncTeleportProcessing = null;
                        }
                );
            }
        }, 80, 1);
    }

    private Function<String,WorldBorder> worldBorderFunction = s -> {
        RTPWorld rtpWorld = getRTPWorld(s);
        if(rtpWorld instanceof BukkitRTPWorld bukkitRTPWorld) {
            World world = bukkitRTPWorld.world();
            org.bukkit.WorldBorder worldBorder = world.getWorldBorder();
            return new WorldBorder(
                    s1 -> (Shape<?>) RTP.factoryMap.get(RTP.factoryNames.shape).get("SQUARE"),
                    rtpLocation -> worldBorder.isInside(new Location(world,rtpLocation.x(),rtpLocation.y(),rtpLocation.z())));
        }
        return null;
    };

    @Override
    public WorldBorder getWorldBorder(String worldName) {
        return worldBorderFunction.apply(worldName);
    }

    @Override
    public void setWorldBorderFunction(Function<String, WorldBorder> function) {
        try {
            function.apply(getRTPWorlds().get(0).name());
            worldBorderFunction = function;
        } catch (Error | Exception ignored) {

        }

    }
}
