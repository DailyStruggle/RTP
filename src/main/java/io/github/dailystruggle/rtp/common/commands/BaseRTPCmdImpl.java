package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public abstract class BaseRTPCmdImpl implements BaseRTPCmd {
    protected long avgTime = 0;

    protected final Map<String, CommandParameter> parameterLookup = new ConcurrentHashMap<>();

    private final CommandsAPICommand parent;

    // key: command name
    // value: command object
    protected final Map<String, CommandsAPICommand> commandLookup = new ConcurrentHashMap<>();

    public BaseRTPCmdImpl(@Nullable CommandsAPICommand parent) {
        this.parent = parent;
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
        return avgTime;
    }
}
