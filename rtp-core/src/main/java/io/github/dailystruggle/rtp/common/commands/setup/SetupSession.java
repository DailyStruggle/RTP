package io.github.dailystruggle.rtp.common.commands.setup;

import io.github.dailystruggle.rtp.common.commands.prefab.Prefab;
import io.github.dailystruggle.rtp.common.selection.region.util.DistanceParser;
import io.github.dailystruggle.rtp.common.selection.region.util.SpatialUnit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Ephemeral session state for an admin navigating the Setup Wizard.
 * Tracks user selections across each stage before compiling into a prefab recipe.
 */
public final class SetupSession {

    public static final UUID CONSOLE_CALLER_ID = new UUID(0L, 0L);
    public static final String DEFAULT_WORLD_CHOICE = "single";
    public static final String DEFAULT_RADIUS_CHOICE = "4096b";
    public static final String DEFAULT_GAMEPLAY_CHOICE = "survival";
    public static final String DEFAULT_PERFORMANCE_CHOICE = "high";

    private final UUID callerId;
    private SetupStage currentStage = SetupStage.WORLD;
    private long lastActivityMillis;

    // Stage 1: World topology ("single", "multi") and region radius (e.g. "4096b", "5km")
    private String worldChoice = DEFAULT_WORLD_CHOICE;
    private String radiusChoice = DEFAULT_RADIUS_CHOICE;

    // Stage 2: Gameplay style ("survival", "arena", "skyblock", "oneblock")
    private String gameplayChoice = DEFAULT_GAMEPLAY_CHOICE;

    // Stage 3: Performance profile ("low", "high", "folia")
    private String performanceChoice = DEFAULT_PERFORMANCE_CHOICE;

    // Stage 4: Addons & Effects toggles
    private final Map<String, Boolean> addonToggles = new LinkedHashMap<>();

    public SetupSession(UUID callerId) {
        this.callerId = Objects.requireNonNull(callerId, "callerId");
        reset();
    }

    /**
     * Validates a distance token via {@link DistanceParser} with block fallback unit.
     *
     * @param input raw distance string (e.g. "2.5km", "5km", "10km", "25km", "4096b", "500c")
     * @return true if input parses to a finite positive distance
     */
    public static boolean isValidRadius(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        DistanceParser.ParsedDistance parsed = DistanceParser.parse(input.trim(), SpatialUnit.BLOCK);
        return parsed != null
                && parsed.magnitude() > 0
                && Double.isFinite(parsed.magnitude())
                && parsed.toBlocks() > 0;
    }

    /**
     * Resets all wizard selections, stage progress, radius, and addon toggles to their initial defaults.
     */
    public void reset() {
        this.currentStage = SetupStage.WORLD;
        this.worldChoice = DEFAULT_WORLD_CHOICE;
        this.radiusChoice = DEFAULT_RADIUS_CHOICE;
        this.gameplayChoice = DEFAULT_GAMEPLAY_CHOICE;
        this.performanceChoice = DEFAULT_PERFORMANCE_CHOICE;
        this.addonToggles.clear();
        this.addonToggles.put("openGuiOnRtp", true);
        this.addonToggles.put("cinematicEffects", false);
        this.addonToggles.put("claimIntegrations", true);
        this.addonToggles.put("economyIntegration", true);
        touch();
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

    public String radiusChoice() {
        return radiusChoice;
    }

    public String radius() {
        return radiusChoice;
    }

    public void setRadiusChoice(String radiusChoice) {
        Objects.requireNonNull(radiusChoice, "radiusChoice");
        String trimmed = radiusChoice.trim();
        if (!isValidRadius(trimmed)) {
            throw new IllegalArgumentException("Invalid radius distance: " + radiusChoice);
        }
        this.radiusChoice = trimmed;
        touch();
    }

    public void setRadius(String radiusChoice) {
        setRadiusChoice(radiusChoice);
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

    /**
     * Returns human-readable summary lines of the current session selections, including radius.
     */
    public List<String> summaryLines() {
        List<String> lines = new ArrayList<>();
        lines.add("World Topology: " + worldChoice);
        lines.add("Region Radius (radius): " + radiusChoice);
        lines.add("Gameplay Style: " + gameplayChoice);
        lines.add("Performance Profile: " + performanceChoice);
        lines.add("Addons & Effects: " + addonToggles);
        return Collections.unmodifiableList(lines);
    }

    /**
     * Compiles the current session selections into an ordered prefab recipe using runtime worlds.
     */
    public List<Prefab> toRecipe() {
        return SetupRecipe.compileRecipe(this, SetupHandlerSupport.collectWorldNames());
    }

    /**
     * Compiles the current session selections into an ordered prefab recipe for the given world names.
     */
    public List<Prefab> toRecipe(List<String> worldNames) {
        return SetupRecipe.compileRecipe(this, worldNames);
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
