package io.github.dailystruggle.rtp.bukkitplatform.commands;

import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;

/**
 * Base Bukkit command holder.
 */
public abstract class BukkitCommand implements CommandExecutor, TabCompleter {
    protected Plugin plugin;
    public BukkitCommand(Plugin plugin) {
        this.plugin = plugin;
    }
}
