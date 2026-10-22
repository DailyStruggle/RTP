package io.github.dailystruggle.rtp.bukkit.commands.test;

import java.util.List;

/** Plugin-side owners whose static BIT codes register in the core TEST and PROBE namespaces. */
public final class BukkitBitCodeOwners {
    public static final List<Class<?>> OWNERS = List.of(
            AsyncReplyTestJob.class,
            DisconnectTestJob.class,
            EconomyIsolationTestJob.class,
            FoliaOwnershipTestJob.class,
            NetworkSimulationTestJob.class,
            QueueStarvationTestJob.class,
            SafetyVerifierTestJob.class,
            TestCommandsCmd.class,
            TestDisconnectMidflightCmd.class,
            TestHologramCmd.class,
            TestStressCmd.class,
            TestTeleportHeatmapCmd.class);

    private BukkitBitCodeOwners() {}
}
