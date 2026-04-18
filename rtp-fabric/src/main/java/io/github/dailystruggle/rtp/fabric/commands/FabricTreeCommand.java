package io.github.dailystruggle.rtp.fabric.commands;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public abstract class FabricTreeCommand implements TreeCommand, io.github.dailystruggle.rtp.common.commands.BaseRTPCmd {
    protected final Map<String, CommandParameter> parameterLookup = new ConcurrentHashMap<>();
    protected final Map<String, CommandsAPICommand> commandLookup = new ConcurrentHashMap<>();
    private final CommandsAPICommand parent;

    public FabricTreeCommand(CommandsAPICommand parent) {
        this.parent = parent;
        if (parent == null) {
            CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
                dispatcher.register(CommandManager.literal(name())
                    .executes(context -> {
                        onCommand(getCallerId(context.getSource()),
                                  perm -> context.getSource().hasPermissionLevel(2),
                                  msg -> context.getSource().sendFeedback(() -> Text.literal(msg), false),
                                  new String[0]);
                        return 1;
                    })
                    .then(CommandManager.argument("args", net.minecraft.command.argument.MessageArgumentType.message())
                        .executes(context -> {
                            String[] args = net.minecraft.command.argument.MessageArgumentType.getMessage(context, "args").getString().split(" ");
                            onCommand(getCallerId(context.getSource()),
                                      perm -> context.getSource().hasPermissionLevel(2),
                                      msg -> context.getSource().sendFeedback(() -> Text.literal(msg), false),
                                      args);
                            return 1;
                        })
                    )
                );
            });
        }
    }

    private UUID getCallerId(ServerCommandSource source) {
        try {
            return source.getPlayer().getUuid();
        } catch (Exception e) {
            return UUID.nameUUIDFromBytes("CONSOLE".getBytes());
        }
    }

    @Override
    public CommandsAPICommand parent() {
        return parent;
    }

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
        return 0;
    }
}
