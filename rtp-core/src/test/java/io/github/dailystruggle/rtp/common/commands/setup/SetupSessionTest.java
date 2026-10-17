package io.github.dailystruggle.rtp.common.commands.setup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SetupSessionTest {

    @Test
    @DisplayName("SetupSession tracks defaults and stage transitions")
    void testSessionDefaultsAndTransitions() {
        UUID callerId = UUID.randomUUID();
        SetupSession session = new SetupSession(callerId);

        assertEquals(callerId, session.callerId());
        assertEquals(SetupStage.WORLD, session.currentStage());
        assertEquals("single", session.worldChoice());
        assertEquals("survival", session.gameplayChoice());
        assertEquals("high", session.performanceChoice());
        assertTrue(session.addonToggles().get("claimIntegrations"));

        session.setCurrentStage(SetupStage.GAMEPLAY);
        assertEquals(SetupStage.GAMEPLAY, session.currentStage());
        assertEquals(SetupStage.PERFORMANCE, session.currentStage().next());
        assertEquals(SetupStage.WORLD, session.currentStage().previous());

        session.setWorldChoice("multi");
        assertEquals("multi", session.worldChoice());

        session.setGameplayChoice("arena");
        assertEquals("arena", session.gameplayChoice());

        session.setPerformanceChoice("folia");
        assertEquals("folia", session.performanceChoice());

        boolean toggled = session.toggle("claimIntegrations");
        assertFalse(toggled);
        assertFalse(session.addonToggles().get("claimIntegrations"));
    }

    @Test
    @DisplayName("SetupSessionRegistry respects TTL eviction")
    void testSessionRegistryTtl() {
        long shortTtl = 50; // 50ms
        SetupSessionRegistry registry = new SetupSessionRegistry(shortTtl);

        UUID callerId = UUID.randomUUID();
        SetupSession session = registry.getOrCreate(callerId);
        assertNotNull(session);
        assertEquals(1, registry.activeSessionCount());
        assertTrue(registry.get(callerId).isPresent());

        // Wait for TTL expiry
        try {
            Thread.sleep(70);
        } catch (InterruptedException ignored) {
        }

        assertTrue(registry.get(callerId).isEmpty());
        assertEquals(0, registry.activeSessionCount());
    }
}
