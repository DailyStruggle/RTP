package io.github.dailystruggle.rtp.bukkit.tools.softdepends;

import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPCommandSender;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPPlayer;
import io.github.dailystruggle.rtp.bukkit.tools.SendMessage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LangKeys;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.DoTeleport;
import io.github.dailystruggle.rtp.common.tasks.LoadChunks;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tasks.SetupTeleport;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;

public class PAPI_expansion extends PlaceholderExpansion{
	@Override
    public boolean persist(){
        return true;
    }

	@Override
    public boolean canRegister(){
        return true;
    }

	@Override
	public @NotNull String getAuthor() {
		return RTPBukkitPlugin.getInstance().getDescription().getAuthors().toString();
	}

	@Override
	public @NotNull String getIdentifier() {
		return "rtp";
	}

	@Override
    public @NotNull String getVersion(){
        return "1.3.23";
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        return onPlaceholderRequest(player.getPlayer(),params);
    }

	@Override
    public String onPlaceholderRequest(Player player, @NotNull String identifier){
		if(player == null){
            return "";
        }

        TeleportData data = RTP.getInstance().latestTeleportData.get(player.getUniqueId());
        ConfigParser<LangKeys> lang = (ConfigParser<LangKeys>) RTP.getInstance().configs.getParser(LangKeys.class);

        // %rtp_player_status%
        if(identifier.equalsIgnoreCase("player_status")){
            if(data == null) return SendMessage.formatDry(player, lang.getConfigValue(LangKeys.PLAYER_AVAILABLE, "").toString());;
            if(data.completed) {
                BukkitRTPCommandSender sender = new BukkitRTPCommandSender(player);
                long dt = System.nanoTime()-data.time;
                if(dt < 0) dt = Long.MAX_VALUE+dt;
                if(dt < sender.cooldown()) {
                    return SendMessage.formatDry(player, lang.getConfigValue(LangKeys.PLAYER_COOLDOWN, "").toString());
                }

                return SendMessage.formatDry(player, lang.getConfigValue(LangKeys.PLAYER_AVAILABLE, "").toString());
            }

            RTPRunnable nextTask = data.nextTask;
            if(nextTask instanceof DoTeleport)
                return SendMessage.formatDry(player, lang.getConfigValue(LangKeys.PLAYER_TELEPORTING, "").toString());
            if(nextTask instanceof LoadChunks)
                return SendMessage.formatDry(player, lang.getConfigValue(LangKeys.PLAYER_LOADING, "").toString());
            if(nextTask instanceof SetupTeleport)
                return SendMessage.formatDry(player, lang.getConfigValue(LangKeys.PLAYER_SETUP, "").toString());
        }

        if(identifier.equalsIgnoreCase("total_queue_length")) {
            Region region = RTP.getInstance().selectionAPI.getRegion(new BukkitRTPPlayer(player));
            if(region==null) return "0";
            return String.valueOf(region.getTotalQueueLength(player.getUniqueId()));
        }

        if(identifier.equalsIgnoreCase("public_queue_length")) {
            Region region = RTP.getInstance().selectionAPI.getRegion(new BukkitRTPPlayer(player));
            if(region==null) return "0";
            return String.valueOf(region.getPublicQueueLength());
        }

        if(identifier.equalsIgnoreCase("personal_queue_length")) {
            Region region = RTP.getInstance().selectionAPI.getRegion(new BukkitRTPPlayer(player));
            if(region==null) return "0";
            return String.valueOf(region.getPersonalQueueLength(player.getUniqueId()));
        }

        if(identifier.equalsIgnoreCase("teleport_world")) {
            return data.selectedLocation.world().name();
        }

        if(identifier.equalsIgnoreCase("teleport_x")) {
            return String.valueOf(data.selectedLocation.x());
        }

        if(identifier.equalsIgnoreCase("teleport_y")) {
            return String.valueOf(data.selectedLocation.y());
        }

        if(identifier.equalsIgnoreCase("teleport_z")) {
            return String.valueOf(data.selectedLocation.z());
        }

        if(identifier.equalsIgnoreCase("teleport_biome")) {
            return data.selectedLocation.world().getBiome(
                    data.selectedLocation.x(),
                    data.selectedLocation.y(),
                    data.selectedLocation.z()
            );
        }

        return "";
    }
}
