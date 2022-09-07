package io.github.dailystruggle.rtp.bukkit.tools.softdepends;

import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPCommandSender;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPPlayer;
import io.github.dailystruggle.rtp.bukkit.tools.SendMessage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LangKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.DoTeleport;
import io.github.dailystruggle.rtp.common.tasks.LoadChunks;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tasks.SetupTeleport;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

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
        return "2.0.0-BETA";
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

        if(SendMessage.placeholders.containsKey(identifier)) return SendMessage.placeholders.get(identifier).apply(player.getUniqueId());

        return "";
    }
}
