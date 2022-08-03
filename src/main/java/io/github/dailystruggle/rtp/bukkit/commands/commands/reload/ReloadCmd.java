package io.github.dailystruggle.rtp.bukkit.commands.commands.reload;

import io.github.dailystruggle.commandsapi.bukkit.localCommands.BukkitTreeCommand;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.bukkit.commands.commands.BaseRTPCmd;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPWorld;
import io.github.dailystruggle.rtp.bukkit.tools.SendMessage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LangKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.TPS;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class ReloadCmd extends BaseRTPCmd {
    public ReloadCmd(Plugin plugin, @Nullable CommandsAPICommand parent) {
        super(plugin, parent);
        Bukkit.getScheduler().runTaskLater(plugin, this::addCommands,10);
    }

    @Override
    public boolean onCommand(CommandSender sender, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        addCommands();

        if(nextCommand!=null) return true;

        for(var v : commandLookup.values()) {
            SendMessage.sendMessage(sender,v.name());
            if(v instanceof BukkitTreeCommand cmd) {
                SendMessage.sendMessage(sender,cmd.name());
                cmd.onCommand(sender,parameterValues,null);
            }
        }
        return true;
    }

    void addCommands() {
        for (ConfigParser<?> value : RTP.getInstance().configs.configParserMap.values()) {
            String name = value.name.replace(".yml","");
            if(getCommandLookup().containsKey(name)) continue;
            addSubCommand(new BaseRTPCmd(plugin, this) {
                @Override
                public boolean onCommand(CommandSender sender, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
                    SendMessage.sendMessage(sender,name());
                    ConfigParser<LangKeys> lang = (ConfigParser<LangKeys>) RTP.getInstance().configs.getParser(LangKeys.class);
                    String configValue = String.valueOf(lang.getConfigValue(LangKeys.reloading, ""));
                    RTP.log(Level.CONFIG,configValue);
                    SendMessage.sendMessage(sender,configValue);
                    value.check(value.version, value.pluginDirectory, null);
                    configValue = String.valueOf(lang.getConfigValue(LangKeys.reloaded, ""));
                    SendMessage.sendMessage(sender,configValue);
                    return true;
                }

                @Override
                public String name() {
                    return name;
                }

                @Override
                public String permission() {
                    return "rtp.reload";
                }

                @Override
                public String description() {
                    return "reload file - " + name();
                }
            });
        }

        for (Map.Entry<Class<?>, MultiConfigParser<?>> e : RTP.getInstance().configs.multiConfigParserMap.entrySet()) {
            MultiConfigParser<? extends Enum<?>> value = e.getValue();
            if(getCommandLookup().containsKey(value.name)) continue;
            addSubCommand(new BaseRTPCmd(plugin,this) {
                @Override
                public boolean onCommand(CommandSender sender, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
                    SendMessage.sendMessage(sender,name());
                    ConfigParser<LangKeys> lang = (ConfigParser<LangKeys>) RTP.getInstance().configs.getParser(LangKeys.class);
                    String configValue = String.valueOf(lang.getConfigValue(LangKeys.reloading, ""));
                    SendMessage.sendMessage(sender,configValue);

                    final RTP instance = RTP.getInstance();
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

                    CommandsAPI.commandPipeline.clear();

                    instance.setupTeleportPipeline.clear();
                    instance.loadChunksPipeline.clear();
                    instance.teleportPipeline.clear();
                    instance.chunkCleanupPipeline.execute(Long.MAX_VALUE);
                    instance.selectionAPI.permRegionLookup.values().forEach(Region::shutDown);
                    instance.selectionAPI.tempRegions.values().forEach(Region::shutDown);
                    instance.latestTeleportData.clear();
                    instance.priorTeleportData.clear();
                    instance.processingPlayers.clear();
                    instance.forceLoads.forEach((ints, chunk) -> chunk.keep(false));
                    instance.forceLoads.clear();

                    MultiConfigParser<?> newParser = new MultiConfigParser<>(value.myClass, value.name, "1.0", value.pluginDirectory);
                    if(value.myClass.equals(RegionKeys.class)) {
                        for(ConfigParser<?> regionConfig : newParser.configParserFactory.map.values()) {
                            EnumMap<RegionKeys, Object> data = (EnumMap<RegionKeys, Object>) regionConfig.getData();

                            String worldName = String.valueOf(data.get(RegionKeys.world));
                            RTPWorld world;
                            if(worldName.startsWith("[") && worldName.endsWith("]")) {
                                int num = Integer.parseInt(worldName.substring(1,worldName.length()-1));
                                world = new BukkitRTPWorld(Bukkit.getWorlds().get(num));
                            }
                            else world = RTP.getInstance().serverAccessor.getRTPWorld(worldName);
                            if(world == null) {
                                new IllegalArgumentException("world not found - " + worldName).printStackTrace(); //don't need to throw
                                continue;
                            }
                            data.put(RegionKeys.world,world);

                            Object shapeObj = data.get(RegionKeys.shape);
                            if(shapeObj instanceof Map shapeMap) {
                                String shapeName = String.valueOf(shapeMap.get("name"));
                                Factory<Shape<?>> factory = (Factory<Shape<?>>) RTP.getInstance().factoryMap.get(RTP.factoryNames.shape);
                                Shape<?> shape = (Shape<?>) factory.getOrDefault(shapeName);
                                EnumMap<?, Object> shapeData = shape.getData();
                                for(var e : shapeData.entrySet()) {
                                    String name = e.getKey().name();
                                    if(shapeMap.containsKey(name)) {
                                        e.setValue(shapeMap.get(name));
                                    }
                                    else {
                                        String altName = shape.language_mapping.get(name);
                                        if(altName!=null && shapeMap.containsKey(altName)) {
                                            e.setValue(shapeMap.get(altName));
                                        }
                                    }
                                }
                                shape.setData(shapeData);
                                data.put(RegionKeys.shape,shape);
                            }
                            else throw new IllegalArgumentException();

                            Object vertObj = data.get(RegionKeys.vert);
                            if(vertObj instanceof Map vertMap) {
                                String shapeName = String.valueOf(vertMap.get("name"));
                                Factory<VerticalAdjustor<?>> factory = (Factory<VerticalAdjustor<?>>) RTP.getInstance().factoryMap.get(RTP.factoryNames.vert);
                                VerticalAdjustor<?> vert = (VerticalAdjustor<?>) factory.getOrDefault(shapeName);
                                EnumMap<?, Object> vertData = vert.getData();
                                for(var e : vertData.entrySet()) {
                                    String name = e.getKey().name();
                                    if(vertMap.containsKey(name)) {
                                        e.setValue(vertMap.get(name));
                                    }
                                    else {
                                        String altName = vert.language_mapping.get(name);
                                        if(altName!=null && vertMap.containsKey(altName)) {
                                            e.setValue(vertMap.get(altName));
                                        }
                                    }
                                }
                                vert.setData(vertData);
                                data.put(RegionKeys.vert, vert);
                            }
                            else throw new IllegalArgumentException();

                            Region region = new Region(regionConfig.name.replace(".yml",""), data);
                            RTP.getInstance().selectionAPI.permRegionLookup.put(region.name,region);
                        }
                    }
                    else if(value.myClass.equals(WorldKeys.class)) {
                        for(World world : Bukkit.getWorlds()) {
                            newParser.getParser(world.getName());
                        }
                    }
                    e.setValue(newParser);

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

                    configValue = String.valueOf(lang.getConfigValue(LangKeys.reloaded, ""));
                    SendMessage.sendMessage(sender,configValue);

                    return true;
                }

                @Override
                public String name() {
                    return value.name.replace(".yml","");
                }

                @Override
                public String permission() {
                    return "rtp.reload";
                }

                @Override
                public String description() {
                    return "reload files in folder - " + name();
                }
            });
        }
    }

    @Override
    public String name() {
        return "reload";
    }

    @Override
    public String permission() {
        return "rtp.reload";
    }

    @Override
    public String description() {
        return "reload configuration files";
    }
}
