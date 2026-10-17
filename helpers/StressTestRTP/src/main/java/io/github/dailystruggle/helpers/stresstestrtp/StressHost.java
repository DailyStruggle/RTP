package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.plugin.Plugin;

import java.io.IOException;

/**
 * Harness services {@link StressCommand} drives. Implemented by {@link StressTestRTPPlugin}, which
 * registers the command, so the command does not depend on the plugin class (no class cycle).
 * Extends {@link Plugin} for config, logger and scheduler ownership.
 */
interface StressHost extends Plugin {

    /** Rolls a new run CSV and rewires the recorder; called on start/burst/sequence. */
    void beginRun() throws IOException;

    Runner runner();

    MetricsRecorder recorder();

    TpsMsptHeapSampler sampler();

    LeakProbe leakProbe();

    CoverageProbe coverageProbe();

    HarnessTeleporter harnessTeleporter();

    TicketFootprintProbe ticketFootprintProbe();
}
