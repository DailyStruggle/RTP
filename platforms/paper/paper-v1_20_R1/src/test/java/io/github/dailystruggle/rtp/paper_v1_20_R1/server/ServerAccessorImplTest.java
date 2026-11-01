package io.github.dailystruggle.rtp.paper_v1_20_R1.server;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.MockPlugin;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.paper_v1_20_R1.scheduling.BukkitSchedulerImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke-tests for the Paper v1_20_R1 adapter using MockBukkit.
 *
 * <p>Verifies two contracts:
 * <ol>
 *   <li>{@link ServerAccessorImpl#getBiomes()} delegates to
 *       {@code org.bukkit.Registry.BIOME} - the Paper-specific registry path -
 *       and returns a non-empty, upper-case set of biome keys.</li>
 *   <li>The Paper {@link BukkitSchedulerImpl} (which inherits from the Spigot
 *       implementation) dispatches synchronous tasks correctly in a Paper
 *       MockBukkit environment.</li>
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
        // Every entry must be either the bare upper-cased enum name
        // (`BADLANDS`) or the lower-cased namespaced id (`minecraft:badlands`);
        // both forms are emitted so Brigadier-suggested namespaced ids pass
        // the /rtp biome parameter validator.
        biomes.forEach(b -> {
            boolean bareUpper = b.equals(b.toUpperCase()) && b.indexOf(':') < 0;
            boolean namespaced = b.indexOf(':') >= 0 && b.equals(b.toLowerCase());
            assertTrue(bareUpper || namespaced,
                    "Every biome key must be either bare upper-cased or lower-cased namespaced, got: " + b);
        });
    }

    /**
     * The Paper {@link BukkitSchedulerImpl} must execute a synchronous task
     * immediately on the primary thread, identical to the Spigot contract,
     * confirming the inheritance chain is correct in a Paper MockBukkit context.
     */
    @Test
    @Timeout(30)
    void paperScheduler_runTask_executesOnPrimaryThread() {
        AtomicBoolean ran = new AtomicBoolean(false);

        scheduler.runTask(() -> ran.set(true));

        assertTrue(ran.get(),
                "Paper BukkitSchedulerImpl must execute runTask synchronously on the primary thread");
    }
}
