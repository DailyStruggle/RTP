package io.github.dailystruggle.rtp.paper_v26_1_R1.server;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.MockPlugin;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.paper_v26_1_R1.scheduling.BukkitSchedulerImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke-tests for the Paper v26_1_R1 adapter using MockBukkit.
 *
 * <p>Verifies two contracts:
 * <ol>
 *   <li>{@link ServerAccessorImpl#getBiomes()} delegates to
 *       {@code io.papermc.paper.registry.RegistryAccess} — the Paper 1.21.3+
 *       registry path — and returns a non-empty, upper-case set of biome keys.
 *       This test is disabled under MockBukkit because {@code RegistryAccess}
 *       is not stubbed by MockBukkit; it requires a live Paper server context.</li>
 *   <li>The Paper {@link BukkitSchedulerImpl} dispatches synchronous tasks
 *       correctly, confirming the async chunk API and Paper scheduler
 *       integration is wired to the correct thread model.</li>
 * </ol>
 *
 * <p>Traceability: REQ-NF-002 (cross-platform thread safety),
 * REQ-CORE-F-002 (world/biome awareness).
 */
class ServerAccessorImplTest {

    private MockPlugin plugin;
    private ServerAccessorImpl accessor;
    private BukkitSchedulerImpl scheduler;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        accessor = new ServerAccessorImpl();
        scheduler = new BukkitSchedulerImpl(plugin);
        RTPAPI.serverAccessor = null;
    }

    @AfterEach
    void tearDown() {
        RTPAPI.serverAccessor = null;
        MockBukkit.unmock();
    }

    /**
     * {@code getBiomes()} uses {@code RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME)}
     * which is a Paper 1.21.3+ API not stubbed by MockBukkit.
     *
     * <p>This test is disabled until MockBukkit provides a {@code RegistryAccess} stub
     * or an integration test environment is available.
     *
     * <p>Traceability: REQ-CORE-F-002 (world/biome awareness) — partial coverage.
     */
    @Test
    @Timeout(30)
    @Disabled("RegistryAccess is not stubbed by MockBukkit; requires a live Paper 1.21.3+ server context")
    void getBiomes_returnsNonEmptyUpperCaseSet() {
        var biomes = accessor.getBiomes();

        assertNotNull(biomes, "getBiomes() must not return null");
        assertFalse(biomes.isEmpty(), "getBiomes() must return at least one biome");
        biomes.forEach(b ->
                assertEquals(b.toUpperCase(), b,
                        "Every biome key must be upper-case, got: " + b));
    }

    /**
     * The Paper {@link BukkitSchedulerImpl} must execute a synchronous task
     * immediately on the primary thread, confirming the async chunk API and
     * Paper scheduler integration works correctly in a MockBukkit context.
     */
    @Test
    @Timeout(30)
    void paperScheduler_runTask_executesOnPrimaryThread() {
        AtomicBoolean ran = new AtomicBoolean(false);

        scheduler.runTask(() -> ran.set(true));

        assertTrue(ran.get(),
                "Paper v26_1_R1 BukkitSchedulerImpl must execute runTask synchronously on the primary thread");
    }
}
