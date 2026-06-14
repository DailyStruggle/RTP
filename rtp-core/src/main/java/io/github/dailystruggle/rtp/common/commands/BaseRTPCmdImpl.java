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
   * Subclass-accessible bridge to {@link BaseRTPCmd}'s default args-form
   * dispatch (inherited from
   * {@link io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand}).
   *
   * <p>Java forbids {@code Iface.super.method()} when the interface is
   * reached transitively through a superclass — a subclass of
   * {@code BaseRTPCmdImpl} cannot write {@code BaseRTPCmd.super.onCommand(...)}
   * because {@code BaseRTPCmd} is not its <em>direct</em> superinterface.
   * This bridge is the only legal way for subclasses (e.g. the runtime
   * test command tree's {@code TestCmd}) to wrap the default dispatch
   * with cross-cutting behaviour such as the per-caller test-isolation
   * semaphore.
   *
   * @param callerId             UUID of the command sender
   * @param permissionCheckMethod predicate that tests a permission node for the sender
   * @param messageMethod        consumer that sends a message to the sender
   * @param args                 raw command arguments
   * @param i                    current argument index
   * @param tempParameters       per-invocation parameter overrides, or {@code null}
   * @return a future that resolves to {@code true} on success
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
