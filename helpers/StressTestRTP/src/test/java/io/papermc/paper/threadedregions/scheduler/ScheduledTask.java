package io.papermc.paper.threadedregions.scheduler;

/** Test stand-in for Paper's public ScheduledTask interface (same FQN, cancel() only). */
public interface ScheduledTask {
    void cancel();
}
