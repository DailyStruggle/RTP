package io.github.dailystruggle.commandsapi.common;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class CommandExecutor implements Runnable {
    private final CommandsAPICommand command;
    private final UUID callerId;
    private final Map<String, List<String>> parameterValues;
    private final CommandsAPICommand nextCommand;
    private final CompletableFuture<Boolean> result;

    public CommandExecutor(CommandsAPICommand command,
                           UUID callerId,
                           Map<String, List<String>> parameterValues,
                           CommandsAPICommand nextCommand,
                           CompletableFuture<Boolean> result){
        this.command = command;
        this.callerId = callerId;
        this.parameterValues = parameterValues;
        this.nextCommand = nextCommand;
        this.result = result;
    }

    public CommandsAPICommand command() {
        return command;
    }

    public UUID callerId() {
        return callerId;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        CommandExecutor that = (CommandExecutor) obj;
        return Objects.equals(this.command, that.command) &&
                Objects.equals(this.callerId, that.callerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(command, callerId);
    }

    @Override
    public String toString() {
        return "CommandExecutor[" +
                "command=" + command + ", " +
                "callerId=" + callerId + ']';
    }

    @Override
    public void run() {
        result.complete(command.onCommand(callerId,parameterValues,nextCommand));
    }
}