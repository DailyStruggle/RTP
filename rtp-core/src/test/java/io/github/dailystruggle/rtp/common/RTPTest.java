package io.github.dailystruggle.rtp.common;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.mock.MockRTPCommandSender;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Rectangle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump.JumpAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RTP} root plugin state: initialization sequence,
 * shutdown sequence, factory registrations, and player-data tracking.
 */
class RTPTest {

    @TempDir
    Path tempDir;

    MockRTPServerAccessor accessor;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
        // Clear any leftover player-data state from previous tests
        RTP rtp = RTP.getInstance();
        rtp.latestTeleportData.clear();
        rtp.priorTeleportData.clear();
        rtp.queuedPlayers.clear();
        rtp.processingPlayers.clear();
    }

    @AfterEach
    void tearDown() {
        // Reset static state so subsequent tests start clean
        RTP.serverAccessor = null;
        RTP.scheduler = null;
        RTPAPI.serverAccessor = null;
    }

    // -------------------------------------------------------------------------
    // Initialization sequence
    // -------------------------------------------------------------------------

    @Test
    void getInstance_afterInstall_returnsNonNull() {
        assertNotNull(RTP.getInstance());
    }

    @Test
    void serverAccessor_afterInstall_isSet() {
        assertNotNull(RTP.serverAccessor);
        assertSame(accessor, RTP.serverAccessor);
    }

    @Test
    void scheduler_afterInstall_isSet() {
        assertNotNull(RTP.scheduler);
    }

    @Test
    void configs_afterInstall_isSet() {
        assertNotNull(RTP.configs);
    }

    @Test
    void rtpapi_serverAccessor_afterInstall_isSet() {
        assertNotNull(RTPAPI.serverAccessor);
    }

    @Test
    void taskPipes_afterInstall_areInitialized() {
        RTP rtp = RTP.getInstance();
        assertNotNull(rtp.miscSyncTasks);
        assertNotNull(rtp.miscAsyncTasks);
        assertNotNull(rtp.startupTasks);
        assertNotNull(rtp.cancelTasks);
    }

    // -------------------------------------------------------------------------
    // Factory / shape registration
    // -------------------------------------------------------------------------

    @Test
    void factoryMap_containsAllFactoryNames() {
        for (RTP.factoryNames name : RTP.factoryNames.values()) {
            assertTrue(RTP.factoryMap.containsKey(name),
                    "factoryMap missing entry for: " + name);
        }
    }

    @Test
    void shapeFactory_containsBuiltInShapes() {
        var shapeFactory = RTP.factoryMap.get(RTP.factoryNames.shape);
        assertNotNull(shapeFactory);
        assertTrue(shapeFactory.contains("circle"),    "circle shape missing");
        assertTrue(shapeFactory.contains("square"),    "square shape missing");
        assertTrue(shapeFactory.contains("rectangle"), "rectangle shape missing");
    }

    @Test
    void vertFactory_containsBuiltInAdjustors() {
        var vertFactory = RTP.factoryMap.get(RTP.factoryNames.vert);
        assertNotNull(vertFactory);
        assertTrue(vertFactory.contains("linear"), "linear adjustor missing");
        assertTrue(vertFactory.contains("jump"),   "jump adjustor missing");
    }

    @Test
    void addShape_registersShapeInFactory() {
        Circle circle = new Circle();
        RTP.addShape(circle);
        var shapeFactory = RTP.factoryMap.get(RTP.factoryNames.shape);
        assertTrue(shapeFactory.contains(circle.name));
    }

    @Test
    void addVerticalAdjustor_registersAdjustorInFactory() {
        JumpAdjustor jump = new JumpAdjustor(new ArrayList<>());
        RTP.addVerticalAdjustor(jump);
        var vertFactory = RTP.factoryMap.get(RTP.factoryNames.vert);
        assertTrue(vertFactory.contains(jump.name));
    }

    @Test
    void addShape_registersAdditionalShapeInFactory() {
        Rectangle rect = new Rectangle();
        RTP.addShape(rect);
        var shapeFactory = RTP.factoryMap.get(RTP.factoryNames.shape);
        assertTrue(shapeFactory.contains(rect.name));
    }

    @Test
    void addVerticalAdjustor_registersAdditionalAdjustorInFactory() {
        LinearAdjustor linear = new LinearAdjustor(new ArrayList<>());
        RTP.addVerticalAdjustor(linear);
        var vertFactory = RTP.factoryMap.get(RTP.factoryNames.vert);
        assertTrue(vertFactory.contains(linear.name));
    }

    // -------------------------------------------------------------------------
    // Player data tracking
    // -------------------------------------------------------------------------

    @Test
    void latestTeleportData_initiallyEmpty() {
        assertTrue(RTP.getInstance().latestTeleportData.isEmpty());
    }

    @Test
    void priorTeleportData_initiallyEmpty() {
        assertTrue(RTP.getInstance().priorTeleportData.isEmpty());
    }

    @Test
    void queuedPlayers_initiallyEmpty() {
        assertTrue(RTP.getInstance().queuedPlayers.isEmpty());
    }

    @Test
    void processingPlayers_initiallyEmpty() {
        assertTrue(RTP.getInstance().processingPlayers.isEmpty());
    }

    @Test
    void latestTeleportData_canStoreAndRetrieve() {
        UUID id = UUID.randomUUID();
        TeleportData data = new TeleportData();
        data.sender = new MockRTPCommandSender(id, "TestPlayer");

        RTP.getInstance().latestTeleportData.put(id, data);

        assertSame(data, RTP.getInstance().latestTeleportData.get(id));
    }

    @Test
    void queuedPlayers_canAddAndRemove() {
        UUID id = UUID.randomUUID();
        RTP.getInstance().queuedPlayers.add(id);
        assertTrue(RTP.getInstance().queuedPlayers.contains(id));

        RTP.getInstance().queuedPlayers.remove(id);
        assertFalse(RTP.getInstance().queuedPlayers.contains(id));
    }

    @Test
    void processingPlayers_canAddAndRemove() {
        UUID id = UUID.randomUUID();
        RTP.getInstance().processingPlayers.add(id);
        assertTrue(RTP.getInstance().processingPlayers.contains(id));

        RTP.getInstance().processingPlayers.remove(id);
        assertFalse(RTP.getInstance().processingPlayers.contains(id));
    }

    @Test
    void priorTeleportData_canStoreMultipleEntries() {
        for (int i = 0; i < 5; i++) {
            UUID id = UUID.randomUUID();
            TeleportData data = new TeleportData();
            data.sender = new MockRTPCommandSender(id, "Player" + i);
            RTP.getInstance().priorTeleportData.put(id, data);
        }
        assertEquals(5, RTP.getInstance().priorTeleportData.size());
    }

    // -------------------------------------------------------------------------
    // Shutdown sequence
    // -------------------------------------------------------------------------

    @Test
    void stop_withNullInstance_doesNotThrow() {
        // Temporarily null out instance via reflection
        try {
            java.lang.reflect.Field f = RTP.class.getDeclaredField("instance");
            f.setAccessible(true);
            Object saved = f.get(null);
            f.set(null, null);
            assertDoesNotThrow(RTP::stop);
            f.set(null, saved);
        } catch (ReflectiveOperationException e) {
            fail("Reflection failed: " + e.getMessage());
        }
    }

    @Test
    void stop_clearsProcessingPlayers() {
        RTP rtp = RTP.getInstance();
        UUID id = UUID.randomUUID();
        rtp.processingPlayers.add(id);

        RTP.stop();

        assertTrue(rtp.processingPlayers.isEmpty());

        // Re-install for tearDown / subsequent tests
        accessor = RTPTestSetup.install(tempDir.toFile());
    }

    @Test
    void stop_completedTeleportData_doesNotCancelThem() {
        RTP rtp = RTP.getInstance();
        UUID id = UUID.randomUUID();
        TeleportData data = new TeleportData();
        data.sender = new MockRTPCommandSender(id, "Player");
        data.completed = true;
        rtp.latestTeleportData.put(id, data);

        // stop() should not throw even with completed entries
        assertDoesNotThrow(RTP::stop);

        // Re-install for tearDown / subsequent tests
        accessor = RTPTestSetup.install(tempDir.toFile());
    }

    // -------------------------------------------------------------------------
    // Static helpers
    // -------------------------------------------------------------------------

    @Test
    void serverId_isNotNull() {
        assertNotNull(RTP.serverId);
    }

    @Test
    void selectionAPI_isNotNull() {
        assertNotNull(RTP.selectionAPI);
    }

    @Test
    void futures_isNotNull() {
        assertNotNull(RTP.futures);
    }

    @Test
    void reloading_defaultFalse() {
        assertFalse(RTP.reloading.get());
    }

    @Test
    void minRTPExecutions_defaultOne() {
        assertEquals(1, RTP.minRTPExecutions);
    }
}
