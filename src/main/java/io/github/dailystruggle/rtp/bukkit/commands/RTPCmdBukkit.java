package io.github.dailystruggle.rtp.bukkit.commands;

import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.EnumParameter;
import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.OnlinePlayerParameter;
import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.WorldParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.bukkit.commands.help.HelpCmdBukkit;
import io.github.dailystruggle.rtp.bukkit.commands.reload.ReloadCmdBukkit;
import io.github.dailystruggle.rtp.bukkit.events.TeleportCommandFailEvent;
import io.github.dailystruggle.rtp.bukkit.events.TeleportCommandSuccessEvent;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPCommandSender;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPPlayer;
import io.github.dailystruggle.rtp.bukkit.tools.SendMessage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.RTPCmd;
import io.github.dailystruggle.rtp.common.commands.parameters.RegionParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.ShapeParameter;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LangKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.SetupTeleport;
import org.bukkit.Bukkit;
import org.bukkit.block.Biome;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Level;

public class RTPCmdBukkit extends BukkitBaseRTPCmd implements RTPCmd {
    //for optimizing parameters,
    private final Factory<Shape<?>> shapeFactory
            = (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);

    private final Semaphore senderChecksGuard = new Semaphore(1);
    private final List<Predicate<CommandSender>> senderChecks = new ArrayList<>();

    public void addSenderCheck(Predicate<CommandSender> senderCheck) {
        try {
            senderChecksGuard.acquire();
            senderChecks.add(senderCheck);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        finally {
            senderChecksGuard.release();
        }
    }

    public RTPCmdBukkit(Plugin plugin) {
        super(plugin, null);

        //region name parameter
        // filter by region exists and sender permission
        addParameter("region", new RegionParameter(
                "rtp.region",
                "select a region to teleport to",
                (uuid, s) -> RTP.getInstance().selectionAPI.regionNames().contains(s) && RTP.serverAccessor.getSender(uuid).hasPermission("rtp.regions."+s)));

        //target player parameter
        // filter by player exists and player permission
        addParameter("player", new OnlinePlayerParameter(
                "rtp.other",
                "teleport someone else",
                (sender, s) -> {
                    Player player = Bukkit.getPlayer(s);
                    return player != null && !player.hasPermission("rtp.notme");
                }));

        //world name parameter
        // filter by world exists and sender permission
        addParameter("world", new WorldParameter(
                "rtp.world",
                "select a world to teleport to",
                (sender, s) -> Bukkit.getWorld(s)!=null && sender.hasPermission("rtp.worlds." + s)));

        //biome name parameter
        // filter by biome exists and sender permission
        addParameter("biome", new EnumParameter<>(
                "rtp.biome",
                "select a world to teleport to",
                (sender, s) -> Biome.valueOf(s.toUpperCase())!=null && sender.hasPermission("rtp.biome." + s),
                Biome.class));

        //wbo parameter
        addParameter("worldBorderOverride", new RegionParameter(
                "rtp.params",
                "override shape with worldborder",
                (sender, s) -> true));
        ShapeParameter shapeParameter = new ShapeParameter(
                "rtp.params",
                "adjust shape of target region",
                (sender, s) -> this.shapeFactory.contains(s));
        addParameter("shape", shapeParameter);
        Bukkit.getScheduler().runTask(RTPBukkitPlugin.getInstance(),()->{
            Factory<Shape<?>> factory = (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
            for(var e : factory.map.entrySet()) {
                shapeParameter.putShape(e.getKey(),e.getValue().getParameters());
            }
        });

        addSubCommand(new ReloadCmdBukkit(plugin,this));
        addSubCommand(new HelpCmdBukkit(plugin,this));
    }

    //async command component
    @Override
    public boolean onCommand(CommandSender sender, Map<String, List<String>> rtpArgs, CommandsAPICommand nextCommand) {
        return compute(new BukkitRTPCommandSender(sender).uuid(),rtpArgs,nextCommand);
    }

    @Override
    public void successEvent(RTPCommandSender sender, RTPPlayer player) {
        TeleportCommandSuccessEvent event = new TeleportCommandSuccessEvent(sender, player);
        Bukkit.getPluginManager().callEvent(event);
    }

    @Override
    public void failEvent(RTPCommandSender sender, String msg) {
        TeleportCommandFailEvent event = new TeleportCommandFailEvent(sender, msg);
        Bukkit.getPluginManager().callEvent(event);
    }
}
