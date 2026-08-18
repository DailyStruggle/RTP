package io.github.dailystruggle.rtp.common.commands.prefab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for {@link PrefabRegistry}: curated list ordering, id uniqueness,
 * overlay immutability, exclusion of backlogCacheCap, and lookup behavior.
 */
class PrefabRegistryTest {

    private static final List<String> EXPECTED_ORDER = List.of(
            "survival-default",
            "low-performance",
            "high-performance",
            "folia-tuned",
            "multi-world",
            "skyblock",
            "oneblock"
    );

    @Test
    @DisplayName("registry ships exactly seven prefabs in the curated panel order")
    void registryOrderAndSize() {
        List<Prefab> prefabs = PrefabRegistry.list();
        assertEquals(7, prefabs.size(), "expected exactly seven bundled prefabs");
        assertEquals(EXPECTED_ORDER, prefabs.stream().map(Prefab::id).toList(),
                "panel order does not match the locked curated ordering");
    }

    @Test
    @DisplayName("every prefab id is unique")
    void idsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (Prefab p : PrefabRegistry.list()) {
            assertTrue(seen.add(p.id()), "duplicate prefab id: " + p.id());
        }
    }

    @Test
    @DisplayName("every prefab has non-null fields and immutable overlay maps")
    void fieldsNonNullAndOverlaysImmutable() {
        for (Prefab p : PrefabRegistry.list()) {
            assertNotNull(p.id());
            assertNotNull(p.displayKey(), "displayKey null for " + p.id());
            assertNotNull(p.hoverKey(), "hoverKey null for " + p.id());
            assertNotNull(p.description(), "description null for " + p.id());
            assertNotNull(p.performanceOverlay(), "performanceOverlay null for " + p.id());
            assertNotNull(p.regionOverlays(), "regionOverlays null for " + p.id());

            assertThrows(UnsupportedOperationException.class,
                    () -> p.performanceOverlay().put("__probe__", "x"),
                    "performanceOverlay of " + p.id() + " must be immutable");
            assertThrows(UnsupportedOperationException.class,
                    () -> p.regionOverlays().put("__probe__", Map.of()),
                    "regionOverlays of " + p.id() + " must be immutable");

            for (Map.Entry<String, Map<String, Object>> e : p.regionOverlays().entrySet()) {
                assertNotNull(e.getValue(), "null region overlay for " + p.id() + "/" + e.getKey());
                assertThrows(UnsupportedOperationException.class,
                        () -> e.getValue().put("__probe__", "x"),
                        "region overlay of " + p.id() + "/" + e.getKey() + " must be immutable");
            }
        }
    }

    @Test
    @DisplayName("no prefab overlay touches backlogCacheCap (pro-vs-lite assembly-time knob)")
    void noBacklogCacheCapAnywhere() {
        for (Prefab p : PrefabRegistry.list()) {
            assertFalse(p.performanceOverlay().containsKey("backlogCacheCap"),
                    p.id() + ".performanceOverlay must not touch backlogCacheCap");
            for (Map.Entry<String, Map<String, Object>> e : p.regionOverlays().entrySet()) {
                assertFalse(e.getValue().containsKey("backlogCacheCap"),
                        p.id() + ".regionOverlays[" + e.getKey() + "] must not touch backlogCacheCap");
            }
        }
    }

    @Test
    @DisplayName("only multi-world sets expandPerWorld")
    void expandPerWorldOnlyOnMultiWorld() {
        for (Prefab p : PrefabRegistry.list()) {
            if (p.id().equals("multi-world")) {
                assertTrue(p.expandPerWorld(), "multi-world must set expandPerWorld");
            } else {
                assertFalse(p.expandPerWorld(), p.id() + " must not set expandPerWorld");
            }
        }
    }

    @Test
    @DisplayName("survival-default is an identity overlay (empty performance + region overlays)")
    void survivalDefaultIsIdentity() {
        Prefab sd = PrefabRegistry.byId("survival-default").orElseThrow();
        assertTrue(sd.performanceOverlay().isEmpty(), "survival-default.performanceOverlay must be empty");
        assertTrue(sd.regionOverlays().isEmpty(), "survival-default.regionOverlays must be empty");
    }

    @Test
    @DisplayName("byId round-trips known ids and rejects unknown / null")
    void byIdLookup() {
        for (Prefab p : PrefabRegistry.list()) {
            assertEquals(p, PrefabRegistry.byId(p.id()).orElseThrow());
        }
        assertTrue(PrefabRegistry.byId("does-not-exist").isEmpty());
        assertTrue(PrefabRegistry.byId(null).isEmpty());
    }
}
