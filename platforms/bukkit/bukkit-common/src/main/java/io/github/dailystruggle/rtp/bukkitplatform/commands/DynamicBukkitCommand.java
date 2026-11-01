package io.github.dailystruggle.rtp.bukkitplatform.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * Dynamic Bukkit command wrapper for commands registered at runtime into {@link org.bukkit.command.CommandMap}
 * without explicit pre-declaration in {@code plugin.yml}.
 */
public final class DynamicBukkitCommand extends Command {

    private final CommandExecutor executor;
    private final @Nullable TabCompleter tabCompleter;

    public DynamicBukkitCommand(@NotNull String name,
                                @Nullable String description,
                                @Nullable String usageMessage,
                                @Nullable List<String> aliases,
                                @NotNull CommandExecutor executor,
                                @Nullable TabCompleter tabCompleter) {
        super(name,
              description != null ? description : "",
              usageMessage != null ? usageMessage : "/" + name,
              aliases != null ? aliases : Collections.emptyList());
        this.executor = executor;
        this.tabCompleter = tabCompleter;
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
        return executor.onCommand(sender, this, commandLabel, args);
    }

    @NotNull
    @Override
    public List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args)
            throws IllegalArgumentException {
        if (tabCompleter != null) {
            List<String> completions = tabCompleter.onTabComplete(sender, this, alias, args);
            if (completions != null) {
                return completions;
            }
        }
        return super.tabComplete(sender, alias, args);
    }
}
