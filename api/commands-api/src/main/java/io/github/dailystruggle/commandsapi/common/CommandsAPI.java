package io.github.dailystruggle.commandsapi.common;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 *
 * todo: command spam detection??
 * todo: processing time metrics
 * todo: run consumers on command processing
 *          events?
 */
public class CommandsAPI {
    /**
     * Single-character separator between a parameter name and its value
     * (e.g. {@code worldid=overworld}). Fixed at {@code '='} since the
     * {@code ':'} -> {@code '='} migration; previously mutable for embedders
     * that wanted a different grammar, now {@code final} because the
     * configurability layer is no longer load-bearing. Kept as a named
     * constant so call sites and the Brigadier bridge can reference one
     * symbol rather than scattering {@code '='} literals across modules.
     */
    public static final char parameterDelimiter = '=';

    /**
     * Single-character separator between multiple values bound to one
     * parameter (e.g. {@code biome=plains,forest}). Fixed at {@code ','}
     * for the same reason as {@link #parameterDelimiter}.
     */
    public static final char multiParameterDelimiter = ',';
    public static final UUID serverId = new UUID(0,0);

    //todo: use these somehow?
    public static final Factory commandFactory = new Factory();
    public static final Factory parameterFactory = new Factory();

    public static final ConcurrentLinkedQueue<CommandExecutor> commandPipeline = new ConcurrentLinkedQueue<>();

    /**
     * Platform-supplied delivery seam for command reply messages. Installed once
     * per plugin so dispatch and command {@code msgInvalidCommand} /
     * {@code msgBadParameter} defaults can route reply text through the platform
     * formatter / auditor without each command carrying a platform-specific
     * {@code messageMethodFactory} or subclassing a platform command type.
     * {@code null} until installed, in which case callers fall back to their
     * default delivery (typically {@code sender::sendMessage}).
     */
    private static volatile MessageSink messageSink = null;

    public CommandsAPI() {

    }

    /**
     * Installs the platform {@link MessageSink}. Idempotent; the most recent
     * installation wins.
     *
     * @param sink the platform message sink, or {@code null} to clear
     */
    public static void setMessageSink(MessageSink sink) {
        messageSink = sink;
    }

    /**
     * @return the installed {@link MessageSink}, or {@code null} if none is set
     */
    public static MessageSink getMessageSink() {
        return messageSink;
    }

    /**
     * Resolves the reply {@link java.util.function.Consumer} for {@code callerId}.
     * When a {@link MessageSink} is installed, returns a consumer that routes raw
     * templates through it; otherwise returns {@code fallback} so existing
     * default delivery is preserved.
     *
     * @param callerId the command sender's UUID
     * @param fallback the consumer to use when no sink is installed
     * @return a non-null reply consumer
     */
    public static java.util.function.Consumer<String> messageMethodFor(
            UUID callerId, java.util.function.Consumer<String> fallback) {
        MessageSink sink = messageSink;
        if (sink == null) return fallback;
        return raw -> sink.send(callerId, raw);
    }

    /**
     * run for up to 50ms, or one game tick
     * @return remaining commands in the pipeline
     */
    public static long execute() {
        return execute(TimeUnit.MILLISECONDS.toNanos(50));
    }

    /**
     * @param availableTime when to stop, in nanos
     * @return remaining commands
     */
    public static long execute(long availableTime) {
        if(commandPipeline.size()==0) return 0;

        long start = System.nanoTime();

        long dt;
        CommandExecutor commandExecutor;
        long avgTime;

        do { //do at least one
            commandExecutor = commandPipeline.poll();
            avgTime = Objects.requireNonNull(commandExecutor).command().avgTime();

            commandExecutor.run();


            long t = System.nanoTime();
            if(t<start) start = -(Long.MAX_VALUE-start); //overflow correction
            dt = t-start;
        } while (commandPipeline.size()>0 && dt+avgTime< availableTime);

        return commandPipeline.size();
    }
}
