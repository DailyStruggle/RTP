package io.github.dailystruggle.rtp.bukkitplatform.commands;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Bukkit-family command registrar: binds a platform-neutral {@link TreeCommand}
 * root onto one or more legacy {@code PluginCommand}s as the
 * {@code CommandExecutor} / {@code TabCompleter}.
 */
public final class BukkitCommandRegistrar extends BukkitCommand {

  private final TreeCommand root;
  private final @Nullable StringCommandDispatcher dispatcher;

  public BukkitCommandRegistrar(Plugin plugin, TreeCommand root,
                                @Nullable StringCommandDispatcher dispatcher) {
    super(plugin);
    this.root = root;
    this.dispatcher = dispatcher;
  }

  public void register(String... names) {
    for (String name : names) {
      PluginCommand command = Bukkit.getPluginCommand(name);
      if (command != null) {
        command.setExecutor(this);
        command.setTabCompleter(this);
      }
    }
  }

  private static UUID resolveSenderId(CommandSender sender) {
    if (sender instanceof Player) return ((Player) sender).getUniqueId();
    if (sender instanceof ConsoleCommandSender
        || sender instanceof RemoteConsoleCommandSender) {
      return CommandsAPI.serverId;
    }
    try {
      CommandSender console = Bukkit.getConsoleSender();
      if (console != null && sender.getName().equals(console.getName())) {
        return CommandsAPI.serverId;
      }
    } catch (Exception ignored) {
      // Console sender lookup may fail during early bootstrap; fall through
    }
    return null;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    UUID senderId = resolveSenderId(sender);
    if (senderId == null) {
      sender.sendMessage("alternate command senders not currently supported");
      return false;
    }

    if (dispatcher != null) {
      return dispatcher.dispatch(senderId, label, args);
    }

    Consumer<String> fallback = sender::sendMessage;
    Consumer<String> messageMethod = CommandsAPI.messageMethodFor(senderId, fallback);
    root.onCommand(senderId, sender::hasPermission, messageMethod, args);
    return true;
  }

  @Override
  public List<String> onTabComplete(CommandSender sender, Command command, String alias,
                                    String[] args) {
    UUID senderId = resolveSenderId(sender);
    if (senderId == null) return null;
    return root.onTabComplete(senderId, sender::hasPermission, args);
  }
}
