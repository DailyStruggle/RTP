package io.github.dailystruggle.rtp.common.commands.setup;

import io.github.dailystruggle.rtp.common.commands.prefab.Prefab;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
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
        assertEquals("4096b", session.radiusChoice());
        assertEquals("survival", session.gameplayChoice());
        assertEquals("high", session.performanceChoice());
        assertTrue(session.addonToggles().get("openGuiOnRtp"));
        assertTrue(session.addonToggles().get("claimIntegrations"));

        session.setCurrentStage(SetupStage.GAMEPLAY);
        assertEquals(SetupStage.GAMEPLAY, session.currentStage());
        assertEquals(SetupStage.PERFORMANCE, session.currentStage().next());
        assertEquals(SetupStage.WORLD, session.currentStage().previous());

        session.setWorldChoice("multi");
        assertEquals("multi", session.worldChoice());

        session.setRadiusChoice("10km");
        assertEquals("10km", session.radiusChoice());

        session.setGameplayChoice("arena");
        assertEquals("arena", session.gameplayChoice());

        session.setPerformanceChoice("folia");
        assertEquals("folia", session.performanceChoice());

        boolean toggled = session.toggle("claimIntegrations");
        assertFalse(toggled);
        assertFalse(session.addonToggles().get("claimIntegrations"));

        boolean guiToggled = session.toggle("openGuiOnRtp");
        assertFalse(guiToggled);
        assertFalse(session.addonToggles().get("openGuiOnRtp"));
    }

    @Test
    @DisplayName("SetupSession validates radius via DistanceParser and supports reset, summaryLines, and toRecipe")
    void testRadiusValidationResetSummaryAndToRecipe() {
        SetupSession session = new SetupSession(UUID.randomUUID());

        for (String valid : List.of("2.5km", "5km", "10km", "25km", "4096b", "500c")) {
            assertTrue(SetupSession.isValidRadius(valid), "Expected valid radius: " + valid);
            session.setRadiusChoice(valid);
            assertEquals(valid, session.radiusChoice());
        }

        for (String invalid : List.of("", "   ", "0b", "-5km", "abc", "10xyz")) {
            assertFalse(SetupSession.isValidRadius(invalid), "Expected invalid radius: " + invalid);
            assertThrows(IllegalArgumentException.class, () -> session.setRadiusChoice(invalid));
        }

        session.setWorldChoice("multi");
        session.setRadiusChoice("25km");
        session.setToggle("openGuiOnRtp", false);

        List<String> summary = session.summaryLines();
        assertTrue(summary.stream().anyMatch(line -> line.contains("radius") && line.contains("25km")),
                "summaryLines must include radius choice");

        List<Prefab> recipe = session.toRecipe(List.of("world", "world_nether"));
        assertTrue(recipe.stream().anyMatch(p -> "setup-radius".equals(p.id())),
                "toRecipe must include setup-radius step");
        assertTrue(recipe.stream().anyMatch(p -> "addons/guimenu".equals(p.id())),
                "toRecipe must include addons/guimenu step when openGuiOnRtp is false");

        session.reset();
        assertEquals(SetupStage.WORLD, session.currentStage());
        assertEquals("single", session.worldChoice());
        assertEquals("4096b", session.radiusChoice());
        assertEquals("survival", session.gameplayChoice());
        assertEquals("high", session.performanceChoice());
        assertTrue(session.addonToggles().get("openGuiOnRtp"));
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
