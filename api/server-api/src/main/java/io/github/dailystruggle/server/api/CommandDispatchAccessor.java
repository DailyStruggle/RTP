package io.github.dailystruggle.server.api;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Platform-neutral command registration, execution, and output-capture contract.
 *
 * @governedBy ADR-115
 * @governedBy ADR-116
 */
public interface CommandDispatchAccessor {
  /**
   * Registers a platform-neutral command tree with the underlying server runtime.
   *
   * @param rootCommand the command tree root
   * @param aliases aliases to bind
   */
  default void registerCommands(Object rootCommand, String... aliases) {}

  /**
   * Dispatches a raw command line as if entered by the given sender.
   *
   * @param senderId the caller UUID
   * @param commandLine full command string
   * @return {@code true} if dispatched, {@code false} otherwise
   */
  default boolean executeCommand(UUID senderId, String commandLine) {
    return false;
  }

  /**
   * Executes a console command and captures emitted messages/feedback lines into a consumer.
   *
   * @param commandLine the command string to execute
   * @param lineConsumer consumer receiving each line of output
   * @return {@code true} if the command was successfully dispatched, {@code false} otherwise
   */
  default boolean executeCommandWithCapture(String commandLine, Consumer<String> lineConsumer) {
    return false;
  }

  /**
   * Executes a console command and captures emitted messages/feedback lines into a {@link
   * CompletableFuture}.
   *
   * @param commandLine the command string to execute
   * @return future completing with captured output lines
   */
  default CompletableFuture<List<String>> executeCommandWithCapture(String commandLine) {
    CompletableFuture<List<String>> future = new CompletableFuture<>();
    List<String> lines = new CopyOnWriteArrayList<>();
    boolean dispatched = executeCommandWithCapture(commandLine, lines::add);
    if (!dispatched) {
      future.complete(Collections.emptyList());
    } else {
      future.complete(lines);
    }
    return future;
  }

  /**
   * Dispatches a console command on the server runtime (ADR-116 Decision 2).
   *
   * @param command command string to execute
   */
  default void dispatchConsoleCommand(String command) {
    if (command != null && !command.isEmpty()) {
      executeCommand(null, command);
    }
  }
}
