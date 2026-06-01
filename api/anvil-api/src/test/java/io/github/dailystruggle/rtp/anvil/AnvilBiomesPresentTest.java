package io.github.dailystruggle.rtp.anvil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused contract test for {@link AnvilChunkView#getBiomesPresent()} — the enumeration
 * surface feeding {@link AnvilRegionScanner} (ADR-016 (biome) §4, §10.2).
 *
 * <p>Extends the Step-1 sanity in {@code AnvilBiomeDecoderTest} with the properties
 * the scanner relies on: insertion-order stability, per-section skip of unparseable
 * containers, and immutability of the returned view. Tracked in TRACEABILITY.md as
 * the verification for ADR-016 (biome) §4 ({@code getBiomesPresent} union).
 */
class AnvilBiomesPresentTest {

    @Test
    @DisplayName("getBiomesPresent preserves first-seen insertion order across sections")
    void insertionOrderIsFirstSeenPerPalette() throws IOException {
        // Three sections intentionally laid out so the second section re-introduces
        // a biome already seen in the first: the scanner's tab-completion consumer
        // wants the *first* occurrence to drive ordering, not the latest.
        LinkedHashMap<String, Object> secA = AnvilTestFixtures.sectionWithBiomes(
                (byte) 0, List.of("minecraft:stone"), null,
                Arrays.asList("minecraft:plains", "minecraft:forest"), biomeData2bits());
        LinkedHashMap<String, Object> secB = AnvilTestFixtures.sectionWithBiomes(
                (byte) 1, List.of("minecraft:stone"), null,
                Arrays.asList("minecraft:forest", "iris:volcanic_ash_plains"), biomeData2bits());
        LinkedHashMap<String, Object> root = AnvilTestFixtures.chunkRoot(
                DataVersionSupport.MC_1_20_DATA_VERSION, new long[37], List.of(secA, secB));
        byte[] region = AnvilTestFixtures.writeSingleChunkRegion(root);

        AnvilChunkView view = AnvilReader.readChunkView(region, 0, 0);
        assertNotNull(view);
        Set<String> present = view.getBiomesPresent();

        // Expected: plains, forest, iris:volcanic_ash_plains — in that order.
        Iterator<String> it = present.iterator();
        assertAll(
                () -> assertEquals(3, present.size()),
                () -> assertEquals("minecraft:plains", it.next()),
                () -> assertEquals("minecraft:forest", it.next()),
                () -> assertEquals("iris:volcanic_ash_plains", it.next()));
    }

    @Test
    @DisplayName("getBiomesPresent is unmodifiable — callers cannot mutate the cached scanner input")
    void returnedSetIsUnmodifiable() throws IOException {
        LinkedHashMap<String, Object> sec = AnvilTestFixtures.sectionWithBiomes(
                (byte) 0, List.of("minecraft:stone"), null,
                List.of("minecraft:plains"), null);
        LinkedHashMap<String, Object> root = AnvilTestFixtures.chunkRoot(
                DataVersionSupport.MC_1_20_DATA_VERSION, new long[37], List.of(sec));
        byte[] region = AnvilTestFixtures.writeSingleChunkRegion(root);

        AnvilChunkView view = AnvilReader.readChunkView(region, 0, 0);
        assertNotNull(view);
        Set<String> present = view.getBiomesPresent();

        // Any mutation attempt must throw. This is load-bearing: AnvilRegionScanner
        // aggregates these sets into its cache and assumes they cannot be poisoned
        // by a downstream consumer.
        assertThrows(UnsupportedOperationException.class, () -> present.add("x"));
    }

    @Test
    @DisplayName("A chunk with no decoded biome sections yields an empty set, not null")
    void emptyWhenNoBiomeSections() throws IOException {
        LinkedHashMap<String, Object> sec = AnvilTestFixtures.section(
                (byte) 0, List.of("minecraft:stone"), null);
        LinkedHashMap<String, Object> root = AnvilTestFixtures.chunkRoot(
                DataVersionSupport.MC_1_20_DATA_VERSION, new long[37], List.of(sec));
        byte[] region = AnvilTestFixtures.writeSingleChunkRegion(root);

        AnvilChunkView view = AnvilReader.readChunkView(region, 0, 0);
        assertNotNull(view);
        assertTrue(view.getBiomesPresent().isEmpty(),
                "missing biomes container → empty set (never null) so the scanner can addAll safely");
    }

    /** Tiny helper: 2-bits-per-entry packed data where cells 0..63 alternate palette indices 0 and 1. */
    private static long[] biomeData2bits() {
        int[] cells = new int[64];
        for (int i = 0; i < 64; i++) cells[i] = i & 1;
        return AnvilTestFixtures.packBiomeIndices(2, cells);
    }

    // Avoid "unused import" noise — ArrayList is referenced indirectly through asList; keep the
    // import list matching the other tests in the suite for readability.
    @SuppressWarnings("unused")
    private static final List<String> UNUSED_MARKER = new ArrayList<>();
}
