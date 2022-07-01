package io.github.dailystruggle.rtp.bukkit.tools.softdepends;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

public class PAPIChecker {
    //stored object reference to skip plugin getting sometimes
    private static PlaceholderAPIPlugin placeholderAPIPlugin = null;

    /**
     * getPAPI - function to if PAPI exists and fill the above object reference accordingly
     */
    private static void getPAPI() {
        Plugin plugin = Bukkit.getServer().getPluginManager().getPlugin("PlaceholderAPI");

        if(plugin instanceof PlaceholderAPIPlugin papiPlugin && plugin.isEnabled()) {
            placeholderAPIPlugin = papiPlugin;
        }
        else placeholderAPIPlugin = null;
    }

    /**
     * fillPlaceholders - function to check for PAPI and call PAPI if it exists.
     * @param player target player for PAPI references
     * @param input a message to apply replacements to
     * @return message with replacements, or same message if replacements aren't possible
     */
    public static String fillPlaceholders(@Nullable OfflinePlayer player, String input) {
        //if I don't have a correct object reference for PAPI, try to get one.
        if(placeholderAPIPlugin == null || !placeholderAPIPlugin.isEnabled()) {
            getPAPI();
        }
        if(placeholderAPIPlugin == null) return input;

        return PlaceholderAPI.setPlaceholders(player,input);
    }
}
