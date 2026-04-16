package io.github.dailystruggle.rtp.folia.mock;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.Plugin;

/**
 * Minimal in-memory {@link ScheduledTask} used by the mock Folia schedulers.
 * Cancellation is tracked via a simple flag; no real threading is involved.
 */
public class MockScheduledTask implements ScheduledTask {

    private final Plugin plugin;
    private final boolean repeating;
    private ExecutionState state = ExecutionState.IDLE;

    public MockScheduledTask(Plugin plugin, boolean repeating) {
        this.plugin = plugin;
        this.repeating = repeating;
    }

    @Override
    public Plugin getOwningPlugin() {
        return plugin;
    }

    @Override
    public boolean isRepeatingTask() {
        return repeating;
    }

    @Override
    public CancelledState cancel() {
        if (state == ExecutionState.CANCELLED) {
            return CancelledState.CANCELLED_ALREADY;
        }
        if (state == ExecutionState.CANCELLED_RUNNING) {
            return CancelledState.NEXT_RUNS_CANCELLED_ALREADY;
        }
        if (state == ExecutionState.RUNNING) {
            state = ExecutionState.CANCELLED_RUNNING;
            return CancelledState.NEXT_RUNS_CANCELLED;
        }
        if (state == ExecutionState.FINISHED) {
            return CancelledState.ALREADY_EXECUTED;
        }
        state = ExecutionState.CANCELLED;
        return CancelledState.CANCELLED_BY_CALLER;
    }

    @Override
    public ExecutionState getExecutionState() {
        return state;
    }

    /** Called by the mock scheduler just before invoking the consumer. */
    void markRunning() {
        state = ExecutionState.RUNNING;
    }

    /** Called by the mock scheduler after the consumer returns. */
    void markFinished() {
        if (state != ExecutionState.CANCELLED_RUNNING) {
            state = ExecutionState.FINISHED;
        }
    }
}
