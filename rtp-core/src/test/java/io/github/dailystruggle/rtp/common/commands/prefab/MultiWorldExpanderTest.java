package io.github.dailystruggle.rtp.common.commands.prefab;

import io.github.dailystruggle.rtp.common.commands.prefab.builtin.LowPerformance;
import io.github.dailystruggle.rtp.common.commands.prefab.builtin.MultiWorld;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session 3 unit coverage for {@link MultiWorldExpander}: per-world cloning
 * of the {@code regions/default} template, idempotent re-apply behaviour,
 * existing-world preservation, defensive deep-copy, and error paths
 * (missing default, null/empty inputs).
 */
class MultiWorldExpanderTest {

    private static Map<String, Object> defaultRegion() {
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("name", "CIRCLE");
        shape.put("radius", 1000);
        Map<String, Object> region = new LinkedHashMap<>();
        region.put("world", "world");
        region.put("shape", shape);
        region.put("biomes", List.of("PLAINS", "FOREST"));
        return region;
    }

    private static Map<String, Map<String, Object>> currentRegionsWithDefault() {
        Map<String, Map<String, Object>> regions = new LinkedHashMap<>();
        regions.put("default", defaultRegion());
        return regions;
    }

    @Test
    @DisplayName("non-expand prefab: regionOverlays returned as a deep copy verbatim, no world synthesis")
    void nonExpandPassthrough() {
        Map<String, Map<String, Object>> out = MultiWorldExpander.expand(
                LowPerformance.INSTANCE,
                currentRegionsWithDefault(),
                List.of("world", "world_nether")
        );
        // Non-expand path: no per-world synthesis, only the prefab's own overlays carry through.
        assertEquals(LowPerformance.INSTANCE.regionOverlays().keySet(), out.keySet(),
                "non-expand prefab passthrough preserves overlay key set verbatim");
        assertFalse(out.containsKey("world_nether"), "no per-world synthesis on non-expand prefabs");
        // Deep-copy contract: mutating the returned overlay must not bleed into the prefab constant.
        Map<String, Object> defaultOverlay = out.get("default");
        assertNotSame(LowPerformance.INSTANCE.regionOverlays().get("default"), defaultOverlay);
    }

    @Test
    @DisplayName("expansion: one synthesised overlay per non-default world, cloned from default with world set")
    void expandsAcrossWorlds() {
        Map<String, Map<String, Object>> regions = currentRegionsWithDefault();
        Map<String, Map<String, Object>> out = MultiWorldExpander.expand(
                MultiWorld.INSTANCE,
                regions,
                List.of("world", "world_nether", "world_the_end")
        );
        assertEquals(3, out.size(), "one synthesised overlay per world");
        for (String world : List.of("world", "world_nether", "world_the_end")) {
            Map<String, Object> overlay = out.get(world);
            assertEquals(world, overlay.get("world"), "world key rewritten to " + world);
            assertEquals("CIRCLE", ((Map<?, ?>) overlay.get("shape")).get("name"));
            assertEquals(1000, ((Map<?, ?>) overlay.get("shape")).get("radius"));
            assertEquals(List.of("PLAINS", "FOREST"), overlay.get("biomes"));
        }
    }

    @Test
    @DisplayName("idempotency: existing per-world region in currentRegions is not overwritten")
    void existingWorldRegionPreserved() {
        Map<String, Map<String, Object>> regions = currentRegionsWithDefault();
        Map<String, Object> existing = new LinkedHashMap<>();
        existing.put("world", "world_nether");
        existing.put("shape", Map.of("name", "SQUARE", "radius", 250));
        regions.put("world_nether", existing);

        Map<String, Map<String, Object>> out = MultiWorldExpander.expand(
                MultiWorld.INSTANCE,
                regions,
                List.of("world", "world_nether", "world_the_end")
        );
        assertFalse(out.containsKey("world_nether"),
                "existing per-world region must not appear in synthesised overlays");
        assertTrue(out.containsKey("world"));
        assertTrue(out.containsKey("world_the_end"));
        // The original regions map is untouched; the existing entry remains.
        assertSame(existing, regions.get("world_nether"));
    }

    @Test
    @DisplayName("idempotency: re-apply with the prior synthesised regions merged in yields no new overlays")
    void idempotentReApply() {
        Map<String, Map<String, Object>> regions = currentRegionsWithDefault();
        Map<String, Map<String, Object>> first = MultiWorldExpander.expand(
                MultiWorld.INSTANCE,
                regions,
                List.of("world", "world_nether")
        );
        // Simulate the first apply having landed: copy the synthesised overlays
        // into the regions tree, then ask the expander again.
        for (Map.Entry<String, Map<String, Object>> e : first.entrySet()) {
            regions.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        }
        Map<String, Map<String, Object>> second = MultiWorldExpander.expand(
                MultiWorld.INSTANCE,
                regions,
                List.of("world", "world_nether")
        );
        assertTrue(second.isEmpty(), "second expand against the post-apply tree must be a no-op");
    }

    @Test
    @DisplayName("deep copy: mutating a synthesised overlay does not touch the default template")
    void deepCopyIsolation() {
        Map<String, Map<String, Object>> regions = currentRegionsWithDefault();
        Map<String, Map<String, Object>> out = MultiWorldExpander.expand(
                MultiWorld.INSTANCE,
                regions,
                List.of("world")
        );
        Map<String, Object> synthesised = out.get("world");
        assertNotSame(regions.get("default"), synthesised);
        assertNotSame(regions.get("default").get("shape"), synthesised.get("shape"));
        // Mutate the clone; default must stay pristine.
        ((Map<String, Object>) synthesised.get("shape")).put("radius", 9999);
        assertEquals(1000, ((Map<?, ?>) regions.get("default").get("shape")).get("radius"));
    }

    @Test
    @DisplayName("missing 'default' region raises IllegalStateException when expansion is requested")
    void missingDefaultRejected() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> MultiWorldExpander.expand(
                        MultiWorld.INSTANCE,
                        Collections.emptyMap(),
                        List.of("world")
                )
        );
        assertTrue(ex.getMessage().contains("default"));
    }

    @Test
    @DisplayName("null arguments rejected")
    void nullArgumentsRejected() {
        assertThrows(NullPointerException.class,
                () -> MultiWorldExpander.expand(null, currentRegionsWithDefault(), List.of("world")));
        assertThrows(NullPointerException.class,
                () -> MultiWorldExpander.expand(MultiWorld.INSTANCE, null, List.of("world")));
        assertThrows(NullPointerException.class,
                () -> MultiWorldExpander.expand(MultiWorld.INSTANCE, currentRegionsWithDefault(), null));
    }

    @Test
    @DisplayName("empty world name rejected")
    void emptyWorldNameRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> MultiWorldExpander.expand(
                        MultiWorld.INSTANCE,
                        currentRegionsWithDefault(),
                        List.of("")
                ));
    }

    @Test
    @DisplayName("PrefabApplier 3-arg overload synthesises regions/<world> trees in the result")
    void applierOverloadIntegration() {
        Map<String, Map<String, Object>> trees = new LinkedHashMap<>();
        trees.put("performance", new LinkedHashMap<>());
        trees.put("regions/default", defaultRegion());

        PrefabApplier.Result result = PrefabApplier.apply(
                trees,
                MultiWorld.INSTANCE,
                List.of("world", "world_nether")
        );
        assertTrue(result.newTrees().containsKey("regions/world"));
        assertTrue(result.newTrees().containsKey("regions/world_nether"));
        assertEquals("world", result.newTrees().get("regions/world").get("world"));
        assertEquals("world_nether", result.newTrees().get("regions/world_nether").get("world"));
        // Diff lists every key the synthesis introduced for each new file.
        assertTrue(result.perFileDiff().containsKey("regions/world"));
        assertTrue(result.perFileDiff().containsKey("regions/world_nether"));
        // Source trees untouched.
        assertNull(trees.get("regions/world"));
    }

    @Test
    @DisplayName("PrefabApplier 3-arg overload on a non-expand prefab is equivalent to the 2-arg overload")
    void applierOverloadNonExpandPassthrough() {
        Map<String, Map<String, Object>> trees = new LinkedHashMap<>();
        trees.put("performance", new LinkedHashMap<>(Map.of("threads", 4)));

        PrefabApplier.Result twoArg = PrefabApplier.apply(trees, LowPerformance.INSTANCE);
        PrefabApplier.Result threeArg = PrefabApplier.apply(trees, LowPerformance.INSTANCE, List.of("world"));
        assertEquals(twoArg.newTrees(), threeArg.newTrees());
        assertEquals(twoArg.perFileDiff().keySet(), threeArg.perFileDiff().keySet());
    }
}
