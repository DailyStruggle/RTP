package io.github.dailystruggle.helpers.stresstestrtp;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Platform-neutral view of a server world/level exposed by {@link StressHost}
 * (ADR-122 Decision 2).
 */
public interface StressWorld {

    record BlockSample(String typeName, boolean solid, boolean passable) {
        public BlockSample {
            typeName = typeName != null ? typeName : "AIR";
        }
    }

    String name();

    Path worldFolder();

    int loadedChunkCount();

    boolean isChunkLoaded(int cx, int cz);

    boolean isChunkGenerated(int cx, int cz);

    default int minHeight() {
        return -64;
    }

    default int highestBlockYAt(int x, int z) {
        return 64;
    }

    default BlockSample blockAt(int x, int y, int z) {
        return null;
    }

    default void save() {
    }

    default boolean loadChunkSync(int cx, int cz) {
        return false;
    }

    default CompletableFuture<Boolean> loadChunkAsync(int cx, int cz, boolean generate) {
        return null;
    }

    default void unloadChunkRequest(int cx, int cz) {
    }
}
