package io.github.dailystruggle.commandsapi.bukkit;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Bukkit-family command registrar: binds a platform-neutral {@link TreeCommand}
 * root onto one or more legacy {@code PluginCommand}s as the
 * {@code CommandExecutor} / {@code TabCompleter}, without requiring the command
 * tree to subclass a Bukkit command type.
 *
 * <p>This relocates the registration concern that {@code BukkitTreeCommand}
 * previously baked into the command supertype: the {@code /rtp} root is authored
 * as a neutral {@code BaseRTPCmdImpl} (the same supertype Fabric / NeoForge use)
 * and this registrar wraps it. The legacy command-map path is preserved (still
 * Spigot-compatible); it merely moves from "the supertype you extend" to "a thing
 * the registrar wraps".</p>
 *
 * <p>Caller {@link UUID} resolution, the {@link CommandsAPI#getMessageSink()
 * MessageSink} reply consumer, and tab-completion mirror the former
 * {@code BukkitTreeCommand} behaviour exactly. The legacy {@code String[]}-args
 * command path is delegated to an optional {@link StringCommandDispatcher} so the
 * command tree can inject pre-dispatch behaviour (RTP's sender checks / cooldown /
 * waitlist guards); when none is supplied the generic {@code TreeCommand} args
 * dispatch is used.</p>
 */
public final class BukkitCommandRegistrar extends BukkitCommand {

  private final TreeCommand root;
  private final @Nullable StringCommandDispatcher dispatcher;

  /**
   * @param plugin     the owning plugin
   * @param root       the neutral command tree root to register
   * @param dispatcher optional legacy command-path dispatcher; {@code null} uses
   *                   the generic {@code TreeCommand} args dispatch
   */
  public BukkitCommandRegistrar(Plugin plugin, TreeCommand root,
                                @Nullable StringCommandDispatcher dispatcher) {
    super(plugin);
    this.root = root;
    this.dispatcher = dispatcher;
  }

  /**
   * Binds this registrar as the executor and tab-completer for each named
   * {@code PluginCommand} declared in {@code plugin.yml}. Names absent from the
   * command map are skipped silently (parity with the prior wiring).
   *
   * @param names the command names / aliases to bind (e.g. {@code "rtp"}, {@code "wild"})
   */
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
    if (sender.getName().equals(Bukkit.getConsoleSender().getName())) return CommandsAPI.serverId;
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

    // Generic fallback: prefer the platform-installed MessageSink (keyed by
    // caller UUID), else sender::sendMessage -- identical to the former
    // BukkitTreeCommand dispatch.
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
