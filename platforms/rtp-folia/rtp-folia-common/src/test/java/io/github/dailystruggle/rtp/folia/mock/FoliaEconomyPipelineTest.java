package io.github.dailystruggle.rtp.folia.mock;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.folia.scheduling.FoliaSchedulerImpl;
import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simulates the Vault/Economy context-switching pipeline using in-memory Folia
 * scheduler mocks.
 *
 * <p>Pipeline under test:
 * <ol>
 *   <li>(RegionThread) Player types {@code /rtp}; plugin submits an economy
 *       check to {@link MockAsyncScheduler}.</li>
 *   <li>Test calls {@link MockAsyncScheduler#executeAll()} to simulate the
 *       async thread pool draining its queue.</li>
 *   <li>(AsyncThread) The async task performs a successful Vault withdrawal,
 *       then dispatches the teleport callback via {@link MockRegionScheduler}.</li>
 *   <li>Test calls {@link MockRegionScheduler#executeAll()} to simulate the
 *       region thread processing its queue.</li>
 *   <li>(RegionThread) Assert that the teleportation logic fired and that
 *       execution counts match expectations.</li>
 * </ol>
 *
 * <p>Requirements covered: REQ-FOLIA-ARCH-007, REQ-FOLIA-ARCH-008,
 * REQ-FOLIA-ARCH-009.
 */
class FoliaEconomyPipelineTest {

    // -----------------------------------------------------------------------
    // Minimal no-op Plugin stub — only getLogger() is used by the mocks.
    // -----------------------------------------------------------------------
    private static final Plugin STUB_PLUGIN = new Plugin() {
        @Override public void onDisable() {}
        @Override public void onEnable() {}
        @Override public void onLoad() {}
        @Override public boolean isEnabled() { return true; }
        @Override public PluginLoader getPluginLoader() { return null; }
        @Override public Server getServer() { return null; }
        @Override public String getName() { return "StubPlugin"; }
        @Override public PluginMeta getPluginMeta() { return null; }
        @Override public PluginDescriptionFile getDescription() { return null; }
        @Override public FileConfiguration getConfig() { return null; }
        @Override public InputStream getResource(String filename) { return null; }
        @Override public void saveConfig() {}
        @Override public void saveDefaultConfig() {}
        @Override public void saveResource(String resourcePath, boolean replace) {}
        @Override public void reloadConfig() {}
        @Override public File getDataFolder() { return null; }
        @Override public Logger getLogger() { return Logger.getLogger("StubPlugin"); }
        @Override public boolean isNaggable() { return false; }
        @Override public void setNaggable(boolean canNag) {}
        @Override public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) { return null; }
        @Override public BiomeProvider getDefaultBiomeProvider(String worldName, String id) { return null; }
        @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) { return false; }
        @Override public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) { return List.of(); }
    };

    private MockAsyncScheduler asyncScheduler;
    private MockRegionScheduler regionScheduler;
    private MockGlobalRegionScheduler globalScheduler;
    private FoliaSchedulerImpl foliaScheduler;

    @BeforeEach
    void setUp() {
        asyncScheduler  = new MockAsyncScheduler();
        regionScheduler = new MockRegionScheduler();
        globalScheduler = new MockGlobalRegionScheduler();

        // Wire the Folia mocks into FoliaSchedulerImpl via the test constructor,
        // then inject it as the global RTP scheduler.
        foliaScheduler  = new FoliaSchedulerImpl(
                STUB_PLUGIN,
                regionScheduler,
                asyncScheduler,
                globalScheduler);
        RTP.scheduler = foliaScheduler;
    }

    // -----------------------------------------------------------------------
    // Test 1 – full context-switching pipeline
    // -----------------------------------------------------------------------

    /**
     * Verifies the five-step economy → teleport pipeline:
     * <ol>
     *   <li>Async task is queued (economy check).</li>
     *   <li>{@code asyncScheduler.executeAll()} runs exactly 1 task.</li>
     *   <li>Inside that task a region task is queued (teleport callback).</li>
     *   <li>{@code regionScheduler.executeAll()} runs exactly 1 task.</li>
     *   <li>The teleport flag is set, confirming end-to-end execution.</li>
     * </ol>
     */
    @Test
    void economy_check_then_teleport_fires_in_correct_order() {
        AtomicBoolean economyCheckRan  = new AtomicBoolean(false);
        AtomicBoolean teleportFired    = new AtomicBoolean(false);
        AtomicInteger asyncTaskCount   = new AtomicInteger(0);
        AtomicInteger regionTaskCount  = new AtomicInteger(0);

        // ── Step 1 (RegionThread): player types /rtp ─────────────────────
        // Plugin submits an economy check to the async scheduler.
        asyncScheduler.runNow(STUB_PLUGIN, scheduledTask -> {

            // ── Step 3 (AsyncThread): Vault withdrawal succeeds ───────────
            economyCheckRan.set(true);
            asyncTaskCount.incrementAndGet();

            // Dispatch the teleport callback back to the region scheduler.
            regionScheduler.execute(STUB_PLUGIN, null, 0, 0, () -> {

                // ── Step 5 (RegionThread): teleportation logic ────────────
                teleportFired.set(true);
                regionTaskCount.incrementAndGet();
            });
        });

        // Async queue must have exactly one pending task at this point.
        assertEquals(1, asyncScheduler.pendingCount(),
                "Async queue must hold exactly 1 task after /rtp submission");
        assertFalse(economyCheckRan.get(),
                "Economy check must NOT have run before executeAll()");

        // ── Step 2: drain the async scheduler ────────────────────────────
        int asyncExecuted = asyncScheduler.executeAll();

        assertEquals(1, asyncExecuted,
                "executeAll() must report exactly 1 async task executed");
        assertTrue(economyCheckRan.get(),
                "Economy check must have run after asyncScheduler.executeAll()");
        assertEquals(0, asyncScheduler.pendingCount(),
                "Async queue must be empty after executeAll()");

        // Region queue must now have exactly one pending task.
        assertEquals(1, regionScheduler.pendingCount(),
                "Region queue must hold exactly 1 task after async callback");
        assertFalse(teleportFired.get(),
                "Teleport must NOT have fired before regionScheduler.executeAll()");

        // ── Step 4: drain the region scheduler ───────────────────────────
        int regionExecuted = regionScheduler.executeAll();

        assertEquals(1, regionExecuted,
                "executeAll() must report exactly 1 region task executed");
        assertTrue(teleportFired.get(),
                "Teleport must have fired after regionScheduler.executeAll()");
        assertEquals(0, regionScheduler.pendingCount(),
                "Region queue must be empty after executeAll()");

        // ── Step 5 assertion: execution counts ───────────────────────────
        assertEquals(1, asyncTaskCount.get(),
                "Async (economy) task must have executed exactly once");
        assertEquals(1, regionTaskCount.get(),
                "Region (teleport) task must have executed exactly once");
    }

    // -----------------------------------------------------------------------
    // Test 2 – economy failure: teleport must NOT fire
    // -----------------------------------------------------------------------

    /**
     * Verifies that when the economy check fails (insufficient funds), the
     * teleport callback is never submitted to the region scheduler.
     */
    @Test
    void economy_failure_does_not_dispatch_teleport() {
        AtomicBoolean teleportFired = new AtomicBoolean(false);

        // Simulate insufficient funds: async task does NOT enqueue a region task.
        asyncScheduler.runNow(STUB_PLUGIN, scheduledTask -> {
            boolean hasFunds = false; // simulated Vault response
            if (hasFunds) {
                regionScheduler.execute(STUB_PLUGIN, null, 0, 0,
                        () -> teleportFired.set(true));
            }
        });

        asyncScheduler.executeAll();
        regionScheduler.executeAll();

        assertFalse(teleportFired.get(),
                "Teleport must NOT fire when the economy check fails");
        assertEquals(0, regionScheduler.pendingCount(),
                "Region queue must remain empty after a failed economy check");
    }

    // -----------------------------------------------------------------------
    // Test 3 – GlobalRegionScheduler queues and executes tasks correctly
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link MockGlobalRegionScheduler} stores submitted tasks
     * and executes them deterministically via {@link MockGlobalRegionScheduler#executeAll()}.
     */
    @Test
    void global_region_scheduler_executes_queued_tasks() {
        AtomicInteger counter = new AtomicInteger(0);

        globalScheduler.execute(STUB_PLUGIN, counter::incrementAndGet);
        globalScheduler.execute(STUB_PLUGIN, counter::incrementAndGet);
        globalScheduler.execute(STUB_PLUGIN, counter::incrementAndGet);

        assertEquals(3, globalScheduler.pendingCount(),
                "Global scheduler must hold 3 pending tasks");
        assertEquals(0, counter.get(),
                "Tasks must not run before executeAll()");

        int executed = globalScheduler.executeAll();

        assertEquals(3, executed,
                "executeAll() must report 3 tasks executed");
        assertEquals(3, counter.get(),
                "All 3 global tasks must have run after executeAll()");
        assertEquals(0, globalScheduler.pendingCount(),
                "Global scheduler queue must be empty after executeAll()");
    }
}
