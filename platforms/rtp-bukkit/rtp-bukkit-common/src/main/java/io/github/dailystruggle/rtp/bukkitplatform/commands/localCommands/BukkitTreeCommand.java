package io.github.dailystruggle.rtp.bukkitplatform.commands.localCommands;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.bukkitplatform.commands.BukkitCommand;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

public abstract class BukkitTreeCommand extends BukkitCommand implements TreeCommand {
    protected long avgTime = 0;

    protected final Map<String, CommandParameter> parameterLookup = new ConcurrentHashMap<>();

    protected Function<CommandSender, Consumer<String>> messageMethodFactory = sender -> sender::sendMessage;

    private final CommandsAPICommand parent;

    protected final Map<String, CommandsAPICommand> commandLookup = new ConcurrentHashMap<>();

    public BukkitTreeCommand(Plugin plugin, @Nullable CommandsAPICommand parent) {
        super(plugin);
        CommandsAPICommand p = parent;
        StringBuilder name = new StringBuilder(name());
        while (p!=null) {
            name.insert(0, p.name() + " ");
            p = p.parent();
        }
        PluginCommand command = Bukkit.getPluginCommand(name.toString());
        if(command!=null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        this.parent = parent;
    }

    @Override
    public CommandsAPICommand parent() {
        return parent;
    }

    private static UUID resolveSenderId(CommandSender sender) {
        if (sender instanceof Player) {
            return ((Player) sender).getUniqueId();
        }
        if (sender instanceof ConsoleCommandSender
                || sender instanceof RemoteConsoleCommandSender) {
            return CommandsAPI.serverId;
        }
        try {
            if (Bukkit.getServer() != null && sender.getName().equals(Bukkit.getConsoleSender().getName())) {
                return CommandsAPI.serverId;
            }
        } catch (Throwable ignored) {
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

        Consumer<String> fallback = messageMethodFactory != null
                ? messageMethodFactory.apply(sender)
                : sender::sendMessage;
        Consumer<String> messageMethod = CommandsAPI.messageMethodFor(senderId, fallback);
        CompletableFuture<Boolean> future = onCommand(senderId, sender::hasPermission, messageMethod, args);

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        UUID senderId = resolveSenderId(sender);
        if (senderId == null) return null;

        return onTabComplete(senderId,sender::hasPermission,args);
    }

    @Override
    public boolean onCommand(UUID callerId,
                             Map<String,List<String>> parameterValues,
                             CommandsAPICommand nextCommand){
        CommandSender commandSender;
        if(callerId.equals(CommandsAPI.serverId)) {
            commandSender = Bukkit.getConsoleSender();
        }
        else {
            commandSender = Bukkit.getPlayer(callerId);
            if(commandSender == null) return false;
        }

        return onCommand(commandSender,parameterValues,nextCommand);
    }

    public abstract boolean onCommand(CommandSender sender,
                              Map<String,List<String>> parameterValues,
                              CommandsAPICommand nextCommand);

    @Override
    public Map<String, CommandParameter> getParameterLookup() {
        return parameterLookup;
    }

    @Override
    public Map<String, CommandsAPICommand> getCommandLookup() {
        return commandLookup;
    }

    @Override
    public long avgTime() {
        return avgTime;
    }
}
