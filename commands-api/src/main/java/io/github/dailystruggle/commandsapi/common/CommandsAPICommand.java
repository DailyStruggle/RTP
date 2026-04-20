package io.github.dailystruggle.commandsapi.common;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * a general and interchangeable command type, requiring tab completion and command execution
 */
public interface CommandsAPICommand {
    String name();
    String permission();
    String description();
    CommandsAPICommand parent();
    void msgBadParameter(UUID callerId, String parameterName, String parameterValue);
    default void msgBadParameter(UUID callerId, String parameterName, String parameterValue, Consumer<String> messageMethod) {
        msgBadParameter(callerId, parameterName, parameterValue);
    }
    void msgInvalidCommand(UUID callerId, String argument);
    default void msgInvalidCommand(UUID callerId, String argument, Consumer<String> messageMethod) {
        msgInvalidCommand(callerId, argument);
    }
    long avgTime();

    /**
     * onTabComplete - recursive tab completion
     *
     * @param callerId ID of caller
     * @param permissionCheckMethod a way to check command sender's permissions
     * @param args                  all additional arguments given with this command
     * @param i                     current index in args, in recursive calls
     * @param tempParameters        stored parameters, for recursive calls
     * @return list of possible values the player could be tabbing for
     */
    List<String> onTabComplete(@NotNull UUID callerId,
                               @NotNull Predicate<String> permissionCheckMethod,
                               @NotNull String[] args,
                               int i,
                               @Nullable Map<String,CommandParameter> tempParameters);

    /**
     * onCommand - recursive command execution
     *
     * @param callerId ID of caller
     * @param permissionCheckMethod a way to check command sender's permissions
     * @param messageMethod         a way to send the command sender a message
     * @param args                  all arguments given with this command
     * @param i                     current index in args, in recursive calls
     * @param tempParameters        stored parameters, for recursive calls
     * @return success or failure of permission check
     */
    CompletableFuture<Boolean> onCommand(@NotNull UUID callerId,
                                         @NotNull Predicate<String> permissionCheckMethod,
                                         @NotNull Consumer<String> messageMethod,
                                         @NotNull String[] args,
                                         int i,
                                         @Nullable Map<String,CommandParameter> tempParameters);

    /**
     * function to be run by each inheritor object
     *
     * @param callerId ID of caller
     * @param parameterValues parameters given for the current command
     * @param nextCommand next command in the chain, if any
     * @return command success, determining whether this api attempts to run nextCommand
     */
    boolean onCommand(UUID callerId, Map<String,List<String>> parameterValues, CommandsAPICommand nextCommand);

    default boolean onCommand(UUID callerId, Map<String,List<String>> parameterValues, CommandsAPICommand nextCommand, java.util.function.Consumer<String> messageMethod) {
        return onCommand(callerId, parameterValues, nextCommand);
    }

    List<String> help(UUID callerId, Predicate<String> permissionCheckMethod);
}
