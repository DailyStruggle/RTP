package io.github.dailystruggle.rtp.common.commands.setup;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Ephemeral session state for an admin navigating the Setup Wizard.
 * Tracks user selections across each stage before compiling into a prefab recipe.
 */
public final class SetupSession {

    public static final UUID CONSOLE_CALLER_ID = new UUID(0L, 0L);

    private final UUID callerId;
    private SetupStage currentStage = SetupStage.WORLD;
    private long lastActivityMillis;

    // Stage 1: World topology ("single", "multi")
    private String worldChoice = "single";

    // Stage 2: Gameplay style ("survival", "arena", "skyblock", "oneblock")
    private String gameplayChoice = "survival";

    // Stage 3: Performance profile ("low", "high", "folia")
    private String performanceChoice = "high";

    // Stage 4: Addons & Effects toggles
    private final Map<String, Boolean> addonToggles = new LinkedHashMap<>();

    public SetupSession(UUID callerId) {
        this.callerId = Objects.requireNonNull(callerId, "callerId");
        this.lastActivityMillis = System.currentTimeMillis();
        // Defaults for toggles
        addonToggles.put("cinematicEffects", false);
        addonToggles.put("claimIntegrations", true);
        addonToggles.put("economyIntegration", true);
    }

    public UUID callerId() {
        return callerId;
    }

    public SetupStage currentStage() {
        return currentStage;
    }

    public void setCurrentStage(SetupStage currentStage) {
        this.currentStage = Objects.requireNonNull(currentStage, "currentStage");
        touch();
    }

    public String worldChoice() {
        return worldChoice;
    }

    public void setWorldChoice(String worldChoice) {
        this.worldChoice = Objects.requireNonNull(worldChoice, "worldChoice");
        touch();
    }

    public String gameplayChoice() {
        return gameplayChoice;
    }

    public void setGameplayChoice(String gameplayChoice) {
        this.gameplayChoice = Objects.requireNonNull(gameplayChoice, "gameplayChoice");
        touch();
    }

    public String performanceChoice() {
        return performanceChoice;
    }

    public void setPerformanceChoice(String performanceChoice) {
        this.performanceChoice = Objects.requireNonNull(performanceChoice, "performanceChoice");
        touch();
    }

    public Map<String, Boolean> addonToggles() {
        return Collections.unmodifiableMap(addonToggles);
    }

    public void setToggle(String key, boolean value) {
        addonToggles.put(key, value);
        touch();
    }

    public boolean toggle(String key) {
        boolean next = !addonToggles.getOrDefault(key, false);
        addonToggles.put(key, next);
        touch();
        return next;
    }

    public long lastActivityMillis() {
        return lastActivityMillis;
    }

    public void touch() {
        this.lastActivityMillis = System.currentTimeMillis();
    }

    public boolean isExpired(long ttlMillis) {
        return (System.currentTimeMillis() - lastActivityMillis) > ttlMillis;
    }
}
