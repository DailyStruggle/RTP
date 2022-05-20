package leafcraft.rtp.bukkit.commonBukkitImpl;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import leafcraft.rtp.bukkit.commonBukkitImpl.substitutions.BukkitRTPCommandSender;
import leafcraft.rtp.bukkit.commonBukkitImpl.substitutions.BukkitRTPPlayer;
import leafcraft.rtp.common.RTP;
import leafcraft.rtp.common.RTPServerAccessor;
import leafcraft.rtp.common.factory.Factory;
import leafcraft.rtp.common.selection.region.selectors.shapes.Shape;
import leafcraft.rtp.common.substitutions.RTPCommandSender;
import leafcraft.rtp.common.substitutions.RTPPlayer;
import leafcraft.rtp.common.substitutions.RTPWorld;
import leafcraft.rtp.bukkit.RTPBukkitPlugin;
import leafcraft.rtp.bukkit.commonBukkitImpl.substitutions.BukkitRTPWorld;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Function;

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
    public RTPWorld getDefaultRTPWorld() {
        return new BukkitRTPWorld(Bukkit.getWorlds().get(0));
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
}
