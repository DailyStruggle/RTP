package io.github.dailystruggle.rtp.bukkit.tools.softdepends;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

public class PAPIChecker {
    public static PlaceholderAPIPlugin getPAPI() {
        Plugin plugin = Bukkit.getServer().getPluginManager().getPlugin("PlaceholderAPI");

        boolean isPAPI = plugin instanceof PlaceholderAPIPlugin;
        if (plugin == null || !isPAPI) {
            return null;
        }
        return (PlaceholderAPIPlugin) plugin;
    }

    public static String fillPlaceholders(OfflinePlayer player, String input) {
        PlaceholderAPIPlugin placeholderAPIPlugin = getPAPI();
        if(placeholderAPIPlugin==null) return input;
        if(!placeholderAPIPlugin.isEnabled()) return input;
        return PlaceholderAPI.setPlaceholders(player,input);
    }
}
