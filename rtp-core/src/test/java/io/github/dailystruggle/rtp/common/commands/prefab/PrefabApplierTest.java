package io.github.dailystruggle.rtp.common.commands.prefab;

import io.github.dailystruggle.rtp.common.commands.prefab.builtin.HighPerformance;
import io.github.dailystruggle.rtp.common.commands.prefab.builtin.LowPerformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session 2 contract test for {@link PrefabApplier}.
 *
 * <p>Guards the pure merge contract from {@code PROPOSAL-admin-panel-prefabs.md}
 * §3.1: sparse merge, nested-map recursion, list-wholesale replacement,
 * idempotency, and the locked invariant that no prefab merge ever introduces
 * a {@code backlogCacheCap} key.
 */
class PrefabApplierTest {

    private static Map<String, Map<String, Object>> baseline() {
        Map<String, Map<String, Object>> trees = new LinkedHashMap<>();
        Map<String, Object> perf = new LinkedHashMap<>();
        perf.put("period", 20);
        perf.put("syncAllottedTime", 25);
        perf.put("asyncAllottedTime", 25);
        perf.put("loginCacheEnabled", true);
        perf.put("postTeleportQueueing", false);
        perf.put("minTPS", 19.0);
        perf.put("backlogCacheCap", 1000); // pro-jar default, must never be touched
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("foo", "bar");
        nested.put("baz", 7);
        perf.put("nested", nested);
        trees.put("performance", perf);

        Map<String, Object> region = new LinkedHashMap<>();
        region.put("cacheCap", 50);
        region.put("activeChunkCap", 20);
        region.put("world", "world");
        trees.put("regions/default", region);
        return trees;
    }

    @Test
    @DisplayName("rejects null inputs")
    void rejectsNulls() {
        assertThrows(NullPointerException.class,
                () -> PrefabApplier.apply(null, LowPerformance.INSTANCE));
        assertThrows(NullPointerException.class,
                () -> PrefabApplier.apply(baseline(), null));
    }

    @Test
    @DisplayName("identity overlay (survival-default) yields empty diff and leaves trees intact")
    void identityOverlayEmitsEmptyDiff() {
        Map<String, Map<String, Object>> base = baseline();
        Map<String, Map<String, Object>> snapshot = baseline();
        PrefabApplier.Result r = PrefabApplier.apply(base,
                PrefabRegistry.byId("survival-default").orElseThrow());
        assertTrue(r.perFileDiff().isEmpty(), "identity overlay must produce empty diff");
        assertEquals(snapshot, r.newTrees(), "identity overlay must preserve trees");
        // pure: input not mutated
        assertEquals(snapshot, base, "apply must not mutate currentTrees");
    }

    @Test
    @DisplayName("low-performance overlay overwrites listed keys and preserves the rest")
    void lowPerformanceMerge() {
        Map<String, Map<String, Object>> base = baseline();
        PrefabApplier.Result r = PrefabApplier.apply(base, LowPerformance.INSTANCE);

        Map<String, Object> newPerf = r.newTrees().get("performance");
        assertEquals(60, newPerf.get("period"));
        assertEquals(20, newPerf.get("syncAllottedTime"));
        assertEquals(false, newPerf.get("loginCacheEnabled"));
        // preserved keys
        assertEquals(false, newPerf.get("postTeleportQueueing"));
        assertEquals(1000, newPerf.get("backlogCacheCap"),
                "merge must never touch backlogCacheCap");
        // nested preserved
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) newPerf.get("nested");
        assertEquals("bar", nested.get("foo"));

        Map<String, Object> newRegion = r.newTrees().get("regions/default");
        assertEquals(10, newRegion.get("cacheCap"));
        assertEquals(4, newRegion.get("activeChunkCap"));
        assertEquals("world", newRegion.get("world"));

        // diff carries old + new values
        List<PrefabApplier.Change> perfDiff = r.perFileDiff().get("performance");
        assertEquals(5, perfDiff.size());
        PrefabApplier.Change periodChange = perfDiff.stream()
                .filter(c -> c.keyPath().equals("period")).findFirst().orElseThrow();
        assertEquals(20, periodChange.oldValue());
        assertEquals(60, periodChange.newValue());

        assertFalse(r.perFileDiff().get("regions/default").isEmpty());
    }

    @Test
    @DisplayName("apply is idempotent: applying twice equals applying once, second diff is empty")
    void idempotency() {
        Map<String, Map<String, Object>> base = baseline();
        PrefabApplier.Result once = PrefabApplier.apply(base, HighPerformance.INSTANCE);
        PrefabApplier.Result twice = PrefabApplier.apply(once.newTrees(), HighPerformance.INSTANCE);
        assertEquals(once.newTrees(), twice.newTrees(), "trees must converge after second apply");
        assertTrue(twice.perFileDiff().isEmpty(), "second apply must have empty diff");
    }

    @Test
    @DisplayName("no prefab merge ever introduces backlogCacheCap into any file")
    void noBacklogCacheCapAfterMerge() {
        for (Prefab p : PrefabRegistry.list()) {
            Map<String, Map<String, Object>> base = baseline();
            // remove the seed so we are sure the prefab can't smuggle it in
            base.get("performance").remove("backlogCacheCap");
            PrefabApplier.Result r = PrefabApplier.apply(base, p);
            for (Map<String, Object> tree : r.newTrees().values()) {
                assertFalse(containsKeyDeep(tree, "backlogCacheCap"),
                        p.id() + " must not introduce backlogCacheCap");
            }
            for (List<PrefabApplier.Change> changes : r.perFileDiff().values()) {
                for (PrefabApplier.Change c : changes) {
                    assertFalse(c.keyPath().contains("backlogCacheCap"),
                            p.id() + " diff must not name backlogCacheCap");
                }
            }
        }
    }

    @Test
    @DisplayName("lists are replaced wholesale (no element-wise merge)")
    void listsReplacedWholesale() {
        Map<String, Map<String, Object>> base = new LinkedHashMap<>();
        Map<String, Object> perf = new LinkedHashMap<>();
        perf.put("items", new ArrayList<>(List.of("a", "b", "c")));
        base.put("performance", perf);

        Prefab overlayPrefab = new Prefab(
                "test-list-overlay", "k", "k", "d",
                Map.of("items", List.of("x", "y")),
                Map.of(),
                false
        );
        PrefabApplier.Result r = PrefabApplier.apply(base, overlayPrefab);
        assertEquals(List.of("x", "y"), r.newTrees().get("performance").get("items"));
    }

    @Test
    @DisplayName("new region file id is synthesised when absent from currentTrees")
    void synthesisesMissingRegionFile() {
        Map<String, Map<String, Object>> base = new LinkedHashMap<>();
        Prefab newRegionPrefab = new Prefab(
                "test-new-region", "k", "k", "d",
                Map.of(),
                Map.of("brandNew", Map.of("world", "nether", "cacheCap", 5)),
                false
        );
        PrefabApplier.Result r = PrefabApplier.apply(base, newRegionPrefab);
        Map<String, Object> created = r.newTrees().get("regions/brandNew");
        assertEquals("nether", created.get("world"));
        assertEquals(5, created.get("cacheCap"));
        List<PrefabApplier.Change> diff = r.perFileDiff().get("regions/brandNew");
        assertEquals(2, diff.size());
        // old values must be null for previously absent keys
        for (PrefabApplier.Change c : diff) {
            assertNull(c.oldValue(), "new-key old value must be null");
        }
    }

    @Test
    @DisplayName("returned trees are independent copies (input mutation safe)")
    void returnedTreesAreIndependent() {
        Map<String, Map<String, Object>> base = baseline();
        PrefabApplier.Result r = PrefabApplier.apply(base, LowPerformance.INSTANCE);
        assertNotSame(base.get("performance"), r.newTrees().get("performance"));
        // mutate the result and ensure the original baseline-rebuilt tree is unaffected
        r.newTrees().get("performance").put("period", -1);
        assertEquals(20, baseline().get("performance").get("period"));
    }

    private static boolean containsKeyDeep(Map<String, Object> map, String needle) {
        if (map.containsKey(needle)) return true;
        for (Object v : map.values()) {
            if (v instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> child = (Map<String, Object>) m;
                if (containsKeyDeep(child, needle)) return true;
            }
        }
        return false;
    }
}
