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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for {@link MultiWorldExpander}: per-world cloning
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
    @DisplayName("expansion: one synthesised overlay per world not already mapped by an existing region")
    void expandsAcrossWorlds() {
        Map<String, Map<String, Object>> regions = currentRegionsWithDefault();
        Map<String, Map<String, Object>> out = MultiWorldExpander.expand(
                MultiWorld.INSTANCE,
                regions,
                List.of("world", "world_nether", "world_the_end")
        );
        // The default template already maps to "world" (the overworld), so that
        // world is left alone rather than cloned into a duplicate regions/world.
        assertEquals(2, out.size(), "one synthesised overlay per unmapped world");
        assertFalse(out.containsKey("world"),
                "world already targeted by the default region must not be duplicated");
        for (String world : List.of("world_nether", "world_the_end")) {
            Map<String, Object> overlay = out.get(world);
            assertEquals(world, overlay.get("world"), "world key rewritten to " + world);
            assertEquals("CIRCLE", ((Map<?, ?>) overlay.get("shape")).get("name"));
            assertEquals(1000, ((Map<?, ?>) overlay.get("shape")).get("radius"));
            assertEquals(List.of("PLAINS", "FOREST"), overlay.get("biomes"));
        }
    }

    private static Map<String, Object> defaultRegionWithOverworldVert() {
        Map<String, Object> region = defaultRegion();
        Map<String, Object> vert = new LinkedHashMap<>();
        vert.put("name", "LINEAR");
        vert.put("minY", 32);
        vert.put("maxY", 255);
        vert.put("direction", 2);
        vert.put("requireSkyLight", true);
        region.put("vert", vert);
        return region;
    }

    @Test
    @DisplayName("injection: the RegionOverlayAmender is invoked on each synthesised overlay and its mutations are reflected")
    void amenderInvokedPerSynthesisedWorld() {
        Map<String, Map<String, Object>> regions = new LinkedHashMap<>();
        regions.put("default", defaultRegionWithOverworldVert());

        java.util.List<String> seen = new java.util.ArrayList<>();
        MultiWorldExpander.RegionOverlayAmender amender = (world, overlay) -> {
            seen.add(world);
            // Stand-in for the canonical NetherEndConfigAmender: drop the
            // sky-light requirement on nether worlds so the test can assert
            // the mutation lands in the returned overlay.
            if (world.endsWith("_nether")) {
                ((Map<String, Object>) overlay.get("vert")).put("requireSkyLight", false);
            }
        };

        Map<String, Map<String, Object>> out = MultiWorldExpander.expand(
                MultiWorld.INSTANCE,
                regions,
                List.of("world", "world_nether", "world_the_end"),
                amender
        );

        // Invoked once per synthesised (unmapped) world; the default already
        // maps "world", so the overworld is not visited.
        assertEquals(List.of("world_nether", "world_the_end"), seen,
                "amender runs on every synthesised overlay, in world order");
        assertEquals(false,
                ((Map<?, ?>) out.get("world_nether").get("vert")).get("requireSkyLight"),
                "amender's in-place mutation must be reflected in the returned overlay");
        // The end overlay was visited but the test amender left it alone.
        assertEquals(true,
                ((Map<?, ?>) out.get("world_the_end").get("vert")).get("requireSkyLight"));
        // The default template stays pristine (deep-copy isolation).
        assertEquals(true,
                ((Map<?, ?>) regions.get("default").get("vert")).get("requireSkyLight"));
    }

    @Test
    @DisplayName("injection: the pure 3-arg expand applies no repair (overlays cloned verbatim)")
    void nullAmenderLeavesCloneUntouched() {
        Map<String, Map<String, Object>> regions = new LinkedHashMap<>();
        regions.put("default", defaultRegionWithOverworldVert());

        Map<String, Map<String, Object>> out = MultiWorldExpander.expand(
                MultiWorld.INSTANCE,
                regions,
                List.of("world", "world_nether")
        );
        Map<?, ?> vert = (Map<?, ?>) out.get("world_nether").get("vert");
        assertEquals(true, vert.get("requireSkyLight"),
                "with no injected amender the cloned overworld vert is returned verbatim");
        assertEquals(255, vert.get("maxY"));
    }

    @Test
    @DisplayName("placeholder: default region with world '[0]' is recognised as mapping the main world, no duplicate")
    void indexPlaceholderResolvesMainWorld() {
        Map<String, Map<String, Object>> regions = new LinkedHashMap<>();
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("name", "CIRCLE");
        shape.put("radius", 256);
        Map<String, Object> region = new LinkedHashMap<>();
        // The shipped default region targets the main world via the "[0]"
        // index placeholder rather than a literal name.
        region.put("world", "[0]");
        region.put("shape", shape);
        regions.put("default", region);

        Map<String, Map<String, Object>> out = MultiWorldExpander.expand(
                MultiWorld.INSTANCE,
                regions,
                List.of("world", "world_nether", "world_the_end")
        );
        // "[0]" resolves to the first loaded world ("world"), so it must not be
        // cloned into a redundant regions/world overlay.
        assertEquals(2, out.size(), "one synthesised overlay per unmapped world");
        assertFalse(out.containsKey("world"),
                "world targeted by the '[0]' placeholder must not be duplicated");
        assertTrue(out.containsKey("world_nether"));
        assertTrue(out.containsKey("world_the_end"));
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
        // The default region already maps to "world", so it is left alone too.
        assertFalse(out.containsKey("world"),
                "world already targeted by the default region must not be duplicated");
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
                List.of("world_custom")
        );
        Map<String, Object> synthesised = out.get("world_custom");
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
    @DisplayName("PrefabApplier 3-arg overload synthesises definitions/regions/<world> trees in the result")
    void applierOverloadIntegration() {
        Map<String, Map<String, Object>> trees = new LinkedHashMap<>();
        trees.put("advanced/performance", new LinkedHashMap<>());
        trees.put("definitions/regions/default", defaultRegion());

        PrefabApplier.Result result = PrefabApplier.apply(
                trees,
                MultiWorld.INSTANCE,
                List.of("world", "world_nether")
        );
        // The default region already maps to "world", so no duplicate is made.
        assertFalse(result.newTrees().containsKey("definitions/regions/world"));
        assertTrue(result.newTrees().containsKey("definitions/regions/world_nether"));
        assertEquals("world_nether", result.newTrees().get("definitions/regions/world_nether").get("world"));
        // Diff lists every key the synthesis introduced for each new file.
        assertTrue(result.perFileDiff().containsKey("definitions/regions/world_nether"));
        // Source trees untouched.
        assertNull(trees.get("definitions/regions/world_nether"));
    }

    @Test
    @DisplayName("PrefabApplier 3-arg overload repoints definitions/worlds/<world>.yml region at each synthesised region")
    void applierOverloadRepointsWorldFiles() {
        Map<String, Map<String, Object>> trees = new LinkedHashMap<>();
        trees.put("definitions/regions/default", defaultRegion());
        // Existing world file pointing at the shared default region.
        Map<String, Object> netherWorld = new LinkedHashMap<>();
        netherWorld.put("region", "default");
        netherWorld.put("requirePermission", false);
        trees.put("definitions/worlds/world_nether", netherWorld);

        PrefabApplier.Result result = PrefabApplier.apply(
                trees,
                MultiWorld.INSTANCE,
                List.of("world", "world_nether", "world_the_end")
        );
        // Each synthesised world's definitions/worlds/<world>.yml points at its own region.
        assertEquals("world_nether",
                result.newTrees().get("definitions/worlds/world_nether").get("region"));
        assertEquals("world_the_end",
                result.newTrees().get("definitions/worlds/world_the_end").get("region"));
        // Existing unrelated key survives the sparse merge.
        assertEquals(false,
                result.newTrees().get("definitions/worlds/world_nether").get("requirePermission"));
        // The default-mapped overworld is not synthesised, so its world file is untouched.
        assertFalse(result.newTrees().containsKey("definitions/worlds/world"));
        // Diff records the repoint.
        assertTrue(result.perFileDiff().containsKey("definitions/worlds/world_nether"));
        // Source trees untouched.
        assertEquals("default", trees.get("definitions/worlds/world_nether").get("region"));
    }

    @Test
    @DisplayName("PrefabApplier 3-arg overload on a non-expand prefab is equivalent to the 2-arg overload")
    void applierOverloadNonExpandPassthrough() {
        Map<String, Map<String, Object>> trees = new LinkedHashMap<>();
        trees.put("advanced/performance", new LinkedHashMap<>(Map.of("threads", 4)));

        PrefabApplier.Result twoArg = PrefabApplier.apply(trees, LowPerformance.INSTANCE);
        PrefabApplier.Result threeArg = PrefabApplier.apply(trees, LowPerformance.INSTANCE, List.of("world"));
        assertEquals(twoArg.newTrees(), threeArg.newTrees());
        assertEquals(twoArg.perFileDiff().keySet(), threeArg.perFileDiff().keySet());
    }

    @Test
    @DisplayName("Prefab with explicit per-world regionOverlays merges over cloned template in MultiWorldExpander")
    void explicitPerWorldOverlayMergesOverTemplate() {
        Map<String, Map<String, Object>> regions = currentRegionsWithDefault();
        Map<String, Object> vertOverlay = new LinkedHashMap<>();
        vertOverlay.put("requireSkyLight", false);
        vertOverlay.put("maxY", 128);
        Map<String, Map<String, Object>> explicitOverlays = Map.of(
                "world_nether", Map.of("vert", vertOverlay)
        );

        Prefab prefab = new Prefab(
                "custom-multi",
                "custom",
                "custom",
                "custom",
                Map.of(),
                Map.of(),
                explicitOverlays,
                true
        );

        Map<String, Map<String, Object>> out = MultiWorldExpander.expand(
                prefab,
                regions,
                List.of("world", "world_nether", "world_the_end")
        );

        assertTrue(out.containsKey("world_nether"));
        Map<String, Object> nether = out.get("world_nether");
        assertEquals("world_nether", nether.get("world"), "world key must be set to destination world");
        assertEquals("CIRCLE", ((Map<?, ?>) nether.get("shape")).get("name"), "cloned template fields must be preserved");
        Map<?, ?> vert = (Map<?, ?>) nether.get("vert");
        assertNotNull(vert);
        assertEquals(false, vert.get("requireSkyLight"), "explicit overlay requireSkyLight must be merged");
        assertEquals(128, vert.get("maxY"), "explicit overlay maxY must be merged");

        assertTrue(out.containsKey("world_the_end"));
        Map<String, Object> end = out.get("world_the_end");
        assertEquals("world_the_end", end.get("world"));
        assertEquals("CIRCLE", ((Map<?, ?>) end.get("shape")).get("name"));
    }

    @Test
    @DisplayName("empty placeholder in currentRegions does not prevent full per-world synthesis")
    void emptyPlaceholderInCurrentRegionsDoesNotPreventSynthesis() {
        Map<String, Map<String, Object>> regions = currentRegionsWithDefault();
        // Simulate a placeholder entry from snapshotLive on a non-existent file
        regions.put("world_the_end", new LinkedHashMap<>());

        Map<String, Object> vertOverlay = new LinkedHashMap<>();
        vertOverlay.put("requireSkyLight", false);
        Map<String, Map<String, Object>> explicitOverlays = Map.of(
                "world_the_end", Map.of("vert", vertOverlay)
        );

        Prefab prefab = new Prefab(
                "custom-multi",
                "custom",
                "custom",
                "custom",
                Map.of(),
                Map.of(),
                explicitOverlays,
                true
        );

        Map<String, Map<String, Object>> out = MultiWorldExpander.expand(
                prefab,
                regions,
                List.of("world", "world_the_end")
        );

        assertTrue(out.containsKey("world_the_end"), "must synthesise overlay for world_the_end");
        Map<String, Object> end = out.get("world_the_end");
        assertEquals("world_the_end", end.get("world"), "world key must be set to destination world");
        assertNotNull(end.get("shape"), "shape must not be omitted");
        assertEquals("CIRCLE", ((Map<?, ?>) end.get("shape")).get("name"), "cloned template shape must be present");
        Map<?, ?> vert = (Map<?, ?>) end.get("vert");
        assertNotNull(vert, "vert must be present");
        assertEquals(false, vert.get("requireSkyLight"), "merged vert override must take effect");
    }
}
