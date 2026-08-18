package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Base implementation for RTP commands */
public abstract class BaseRTPCmdImpl implements BaseRTPCmd {
  /** Map of command parameters */
  protected final Map<String, CommandParameter> parameterLookup = new ConcurrentHashMap<>();

  /** Map of subcommands */
  protected final Map<String, CommandsAPICommand> commandLookup = new ConcurrentHashMap<>();

  private final CommandsAPICommand parent;

  /** Average time taken to execute the command */
  protected long avgTime = 0;

  /**
   * Constructor for BaseRTPCmdImpl
   *
   * @param parent the parent command
   */
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

  /**
   * Subclass bridge to {@link BaseRTPCmd}'s default {@code onCommand} dispatch.
   *
   * @param callerId              command sender UUID
   * @param permissionCheckMethod permission test predicate
   * @param messageMethod         message feedback sink
   * @param args                  command arguments
   * @param i                     current argument index
   * @param tempParameters        per-invocation parameter overrides or null
   * @return future resolving to true on completion
   */
  protected final CompletableFuture<Boolean> defaultOnCommand(
      @NotNull java.util.UUID callerId,
      @NotNull Predicate<String> permissionCheckMethod,
      @NotNull Consumer<String> messageMethod,
      @NotNull String[] args,
      int i,
      @Nullable Map<String, CommandParameter> tempParameters) {
    return BaseRTPCmd.super.onCommand(
        callerId, permissionCheckMethod, messageMethod, args, i, tempParameters);
  }
}
