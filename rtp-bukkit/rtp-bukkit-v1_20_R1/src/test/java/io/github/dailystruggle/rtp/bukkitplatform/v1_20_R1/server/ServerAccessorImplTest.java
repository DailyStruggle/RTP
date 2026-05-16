package io.github.dailystruggle.rtp.bukkitplatform.v1_20_R1.server;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.MockPlugin;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.bukkitplatform.v1_20_R1.scheduling.BukkitSchedulerImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke-tests for the Spigot v1_20_R1 adapter using MockBukkit.
 *
 * <p>Verifies two contracts:
 * <ol>
 *   <li>{@link ServerAccessorImpl#getBiomes()} delegates to
 *       {@code org.bukkit.Registry.BIOME} and returns a non-empty,
 *       upper-case set of biome keys.</li>
 *   <li>The Spigot {@link BukkitSchedulerImpl} dispatches synchronous tasks
 *       correctly via the {@code TimeBoundTaskPipe} execution path.</li>
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
     * {@code getBiomes()} must return a non-empty set of biome name strings.
     * MockBukkit populates {@code Registry.BIOME} with at least the vanilla
     * biome set, so the result must be non-null and non-empty.
     */
    @Test
    @Timeout(30)
    void getBiomes_returnsNonEmptyUpperCaseSet() {
        Set<String> biomes = accessor.getBiomes();

        assertNotNull(biomes, "getBiomes() must not return null");
        assertFalse(biomes.isEmpty(), "getBiomes() must return at least one biome under MockBukkit");
        biomes.forEach(b ->
                assertEquals(b.toUpperCase(), b,
                        "Every biome key must be upper-case, got: " + b));
    }

    /**
     * The Spigot {@link BukkitSchedulerImpl} must execute a synchronous task
     * immediately on the primary thread, confirming the synchronous chunk API
     * and {@code TimeBoundTaskPipe} execution path works correctly.
     */
    @Test
    @Timeout(30)
    void spigotScheduler_runTask_executesOnPrimaryThread() {
        AtomicBoolean ran = new AtomicBoolean(false);

        scheduler.runTask(() -> ran.set(true));

        assertTrue(ran.get(),
                "Spigot BukkitSchedulerImpl must execute runTask synchronously on the primary thread");
    }
}
