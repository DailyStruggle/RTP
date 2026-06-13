package io.github.dailystruggle.rtp.common.commands.prefab;

import io.github.dailystruggle.rtp.common.commands.prefab.builtin.SurvivalDefault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drift guard: {@code SurvivalDefault} is documented as the
 * identity overlay ("reset to shipped defaults"). If a future commit adds
 * a key to {@link SurvivalDefault#INSTANCE}'s overlay maps - whether by
 * mistake or to "document" a default - the prefab stops being an identity
 * and this test fails loudly.
 *
 * <p>Identity is verified two ways:
 * <ol>
 *   <li>Structurally: both overlay maps on the {@link Prefab} are empty.</li>
 *   <li>Behaviourally: applying the prefab to a representative trees map
 *       (covering {@code performance} and {@code regions/default}) produces
 *       an empty per-file diff and leaves the input trees structurally
 *       equal to the output trees.</li>
 * </ol>
 */
class PrefabIdentityOverlayTest {

    @Test
    @DisplayName("SurvivalDefault.INSTANCE has empty performance and region overlays")
    void structurallyIdentity() {
        assertTrue(SurvivalDefault.INSTANCE.performanceOverlay().isEmpty(),
                "survival-default.performanceOverlay must remain empty (identity overlay drift)");
        assertTrue(SurvivalDefault.INSTANCE.regionOverlays().isEmpty(),
                "survival-default.regionOverlays must remain empty (identity overlay drift)");
    }

    @Test
    @DisplayName("applying SurvivalDefault to a representative trees map yields no diff")
    void behaviourallyIdentity() {
        Map<String, Map<String, Object>> trees = representativeTrees();
        Map<String, Map<String, Object>> snapshot = representativeTrees();

        PrefabApplier.Result r = PrefabApplier.apply(trees, SurvivalDefault.INSTANCE);

        assertTrue(r.perFileDiff().isEmpty(),
                "survival-default must produce an empty per-file diff (identity overlay drift)");
        assertEquals(snapshot, r.newTrees(),
                "survival-default must leave the trees unchanged (identity overlay drift)");
        assertEquals(snapshot, trees,
                "PrefabApplier.apply must not mutate currentTrees");
    }

    /**
     * Synthetic stand-in for an on-disk {@code performance.yml} +
     * {@code regions/default.yml}. Identity-overlay correctness does not
     * depend on these specific values; any non-empty trees would do. Using
     * a fixed, representative shape catches accidental "default overlay
     * with an empty map under a new top-level key" drift too.
     */
    private static Map<String, Map<String, Object>> representativeTrees() {
        Map<String, Map<String, Object>> t = new LinkedHashMap<>();

        Map<String, Object> perf = new LinkedHashMap<>();
        perf.put("period", 20);
        perf.put("syncAllottedTime", 25);
        perf.put("asyncAllottedTime", 25);
        perf.put("minTPS", 19.0);
        perf.put("loginCacheEnabled", true);
        perf.put("postTeleportQueueing", false);
        perf.put("backlogCacheCap", 1000);
        t.put("performance", perf);

        Map<String, Object> region = new LinkedHashMap<>();
        region.put("world", "world");
        region.put("cacheCap", 50);
        region.put("activeChunkCap", 20);
        region.put("shape", "square");
        t.put("regions/default", region);

        return t;
    }
}
