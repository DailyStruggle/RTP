package io.github.dailystruggle.rtp.bukkit.commonBukkitImpl;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.bukkit.commonBukkitImpl.substitutions.BukkitRTPCommandSender;
import io.github.dailystruggle.rtp.bukkit.commonBukkitImpl.substitutions.BukkitRTPPlayer;
import io.github.dailystruggle.rtp.bukkit.commonBukkitImpl.substitutions.BukkitRTPWorld;
import io.github.dailystruggle.rtp.bukkit.tools.SendMessage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.substitutions.RTPPlayer;
import io.github.dailystruggle.rtp.common.substitutions.RTPWorld;
import org.bukkit.Bukkit;
import org.bukkit.block.Biome;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public class BukkitServerAccessor implements RTPServerAccessor {
    private String version = null;
    private Integer intVersion = null;

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
        return new BukkitRTPWorld(Bukkit.getWorld(name));
    }

    @Override
    public RTPWorld getRTPWorld(UUID id) {
        return new BukkitRTPWorld(Bukkit.getWorld(id));
    }

    @Override
    public void setShapeFunction(Function<String, Shape<?>> function) {

    }

    @Override
    public @Nullable Shape<?> getShape(String name) {
        Factory<Shape<?>> factory = (Factory<Shape<?>>) RTP.getInstance().factoryMap.get(RTP.factoryNames.shape);
        return (Shape<?>) factory.getOrDefault(name);
    }

    @Override
    public List<RTPWorld> getRTPWorlds() {
        return Bukkit.getWorlds().stream().map(BukkitRTPWorld::new).collect(Collectors.toList());
    }

    @Override
    public RTPPlayer getPlayer(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if(player == null) return null;
        return new BukkitRTPPlayer(player);
    }

    @Override
    public RTPCommandSender getSender(UUID uuid) {
        CommandSender commandSender = (uuid == CommandsAPI.serverId) ? Bukkit.getConsoleSender() : Bukkit.getPlayer(uuid);
        if(commandSender == null) return null;
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
        Player player = Bukkit.getPlayer(target2);
        if(sender!=null && player!=null) SendMessage.sendMessage(sender,player,message);
    }

    @Override
    public Set<String> allBiomes() {
        return Arrays.stream(Biome.values()).map(Enum::name).collect(Collectors.toSet());
    }
}
