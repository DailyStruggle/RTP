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
    public static char parameterDelimiter = ':';
    public static char multiParameterDelimiter = ',';
    public static final UUID serverId = new UUID(0,0);

    //todo: use these somehow?
    public static final Factory commandFactory = new Factory();
    public static final Factory parameterFactory = new Factory();

    public static final ConcurrentLinkedQueue<CommandExecutor> commandPipeline = new ConcurrentLinkedQueue<>();

    public CommandsAPI() {

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
