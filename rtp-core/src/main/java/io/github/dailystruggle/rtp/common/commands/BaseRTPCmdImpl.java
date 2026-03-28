package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base implementation for RTP commands
 */
public abstract class BaseRTPCmdImpl implements BaseRTPCmd {
    /**
     * Map of command parameters
     */
    protected final Map<String, CommandParameter> parameterLookup = new ConcurrentHashMap<>();

    /**
     * Map of subcommands
     */
    protected final Map<String, CommandsAPICommand> commandLookup = new ConcurrentHashMap<>();
    private final CommandsAPICommand parent;

    /**
     * Average time taken to execute the command
     */
    protected long avgTime = 0;

    /**
     * Constructor for BaseRTPCmdImpl
     * @param parent the parent command
     */
    public BaseRTPCmdImpl( @Nullable CommandsAPICommand parent ) {
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


