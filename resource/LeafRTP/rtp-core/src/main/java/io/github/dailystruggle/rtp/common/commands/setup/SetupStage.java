package io.github.dailystruggle.rtp.common.commands.setup;

/**
 * Stages of the interactive setup wizard per ADR-038.
 */
public enum SetupStage {
    WORLD(1, "World Topology"),
    GAMEPLAY(2, "Gameplay Style"),
    PERFORMANCE(3, "Performance Profile"),
    ADDONS(4, "Addons & Effects"),
    PREVIEW(5, "Preview & Apply");

    private final int stageNumber;
    private final String title;

    SetupStage(int stageNumber, String title) {
        this.stageNumber = stageNumber;
        this.title = title;
    }

    public int stageNumber() {
        return stageNumber;
    }

    public String title() {
        return title;
    }

    public SetupStage next() {
        return switch (this) {
            case WORLD -> GAMEPLAY;
            case GAMEPLAY -> PERFORMANCE;
            case PERFORMANCE -> ADDONS;
            case ADDONS -> PREVIEW;
            case PREVIEW -> PREVIEW;
        };
    }

    public SetupStage previous() {
        return switch (this) {
            case WORLD -> WORLD;
            case GAMEPLAY -> WORLD;
            case PERFORMANCE -> GAMEPLAY;
            case ADDONS -> PERFORMANCE;
            case PREVIEW -> ADDONS;
        };
    }
}
