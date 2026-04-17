package io.github.dailystruggle.commandsapi.bukkit.localCommands;

import io.github.dailystruggle.commandsapi.bukkit.BukkitCommand;
import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public abstract class BukkitTreeCommand extends BukkitCommand implements TreeCommand {
    protected long avgTime = 0;

    protected final Map<String, CommandParameter> parameterLookup = new ConcurrentHashMap<>();

    private final CommandsAPICommand parent;

    // key: command name
    // value: command object
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

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        UUID senderId;
        if(sender instanceof Player) {
            senderId = ((Player) sender).getUniqueId();
        }
        else if(sender.getName().equals(Bukkit.getConsoleSender().getName())) senderId = CommandsAPI.serverId;
        else {
            sender.sendMessage("alternate command senders not currently supported");
            return false;
        }

        CompletableFuture<Boolean> future = onCommand(senderId, sender::hasPermission, sender::sendMessage, args);

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        UUID senderId;
        if(sender instanceof Player) {
            senderId = ((Player) sender).getUniqueId();
        }
        else if(sender.getName().equals(Bukkit.getConsoleSender().getName())) senderId = CommandsAPI.serverId;
        else return null;

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

        //        long stop = System.nanoTime();

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
