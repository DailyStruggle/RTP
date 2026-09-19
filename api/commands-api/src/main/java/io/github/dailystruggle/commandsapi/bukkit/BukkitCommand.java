package io.github.dailystruggle.commandsapi.bukkit;

import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;

/**
 * a command needs to have some sort of
 */
public abstract class BukkitCommand implements CommandExecutor, TabCompleter {
    protected Plugin plugin;
    public BukkitCommand(Plugin plugin) {
        this.plugin = plugin;
    }
}
