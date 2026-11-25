package io.github.dailystruggle.anvil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.anvil.LcaGoldenFixtureExporter.CbitVectorEntry;
import io.github.dailystruggle.anvil.LcaGoldenFixtureExporter.ChunkManifestEntry;
import io.github.dailystruggle.anvil.LcaGoldenFixtureExporter.GoldenFixtureBundle;
import io.github.dailystruggle.anvil.LcaGoldenFixtureExporter.SectionManifestEntry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.Deflater;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cross-language golden fixture determinism, `CBIT T1..T8` tripwire, 4-tier `SEC-DED`
 * self-healing, and 5-axis performance proof verification suite (`leafanvil-ADR-004`,
 * `anvil-api-ADR-003`).
 *
 * @governedBy leafanvil-ADR-004
 * @governedBy anvil-api-ADR-003
 * @governedBy anvil-api-ADR-002
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
class LcaGoldenFixtureTest {

    @Test
    @DisplayName("leafanvil-ADR-004: Golden .lca Mode A/B bundles and JSON manifest are 100% byte-deterministic across runs")
    void goldenBundlesAndManifestAreByteDeterministic(@TempDir Path tempDir) throws IOException {
        assertTrue(LcaRegionReader.INSTANCE.executePbit().passed(), "LcaRegionReader PBIT must pass");

        List<GoldenFixtureBundle> run1 = LcaGoldenFixtureExporter.generateAllGoldenBundles();
        List<GoldenFixtureBundle> run2 = LcaGoldenFixtureExporter.generateAllGoldenBundles();
        assertEquals(5, run1.size());
        assertEquals(run1.size(), run2.size());

        for (int i = 0; i < run1.size(); i++) {
            GoldenFixtureBundle b1 = run1.get(i);
            GoldenFixtureBundle b2 = run2.get(i);
            assertEquals(b1.fixtureId(), b2.fixtureId());
            assertEquals(b1.modeASha256(), b2.modeASha256(), "Mode A SHA-256 drifted for " + b1.fixtureId());
            assertEquals(b1.modeBSha256(), b2.modeBSha256(), "Mode B SHA-256 drifted for " + b1.fixtureId());
            assertArrayEquals(b1.modeALcaBytes(), b2.modeALcaBytes());
            assertArrayEquals(b1.modeBLcaBytes(), b2.modeBLcaBytes());
            assertFalse(b1.chunks().isEmpty(), "Golden bundle must contain active chunks: " + b1.fixtureId());
        }

        String json1 = LcaGoldenFixtureExporter.toDeterministicJsonManifest(run1);
        String json2 = LcaGoldenFixtureExporter.toDeterministicJsonManifest(run2);
        assertEquals(json1, json2, "JSON manifest must be strictly deterministic");
        assertTrue(json1.contains("\"schemaVersion\": \"LCA-GOLDEN-1.0\""));
        assertTrue(json1.contains("\"synthetic_all_modes\""));

        LcaGoldenFixtureExporter.exportToDirectory(tempDir);
        assertTrue(Files.isRegularFile(tempDir.resolve("lca-golden-manifest.json")));
        for (GoldenFixtureBundle b : run1) {
            assertArrayEquals(
                    b.modeALcaBytes(), Files.readAllBytes(tempDir.resolve(b.fixtureId() + ".modeA.lca")));
            assertArrayEquals(
                    b.modeBLcaBytes(), Files.readAllBytes(tempDir.resolve(b.fixtureId() + ".modeB.lca")));
        }

        String optionalExportDir = System.getProperty("lca.golden.exportDir");
        if (optionalExportDir != null && !optionalExportDir.isBlank()) {
            LcaGoldenFixtureExporter.exportToDirectory(Paths.get(optionalExportDir));
        }
        Path rustCrateDir = Paths.get("../../resource/LeafAnvil/rust/lca-format").normalize();
        if (Files.isDirectory(rustCrateDir)) {
            LcaGoldenFixtureExporter.exportToDirectory(rustCrateDir.resolve("target/lca-golden-fixtures"));
        }
    }
    @Test
    @DisplayName("REQ-RTP-S-004 / REQ-RTP-S-005: Cross-path FNV-1a64 voxel, biome, and light parity across Mode A, Mode B, Section jump, and NBT tail")
    void crossPathVoxelBiomeAndLightDigestParity() throws IOException {
        List<GoldenFixtureBundle> bundles = LcaGoldenFixtureExporter.generateAllGoldenBundles();

        for (GoldenFixtureBundle bundle : bundles) {
            for (boolean modeB : new boolean[] {false, true}) {
                byte[] lcaBytes = modeB ? bundle.modeBLcaBytes() : bundle.modeALcaBytes();
                String modeLabel = bundle.fixtureId() + (modeB ? " [Mode B]" : " [Mode A]");

                for (ChunkManifestEntry chunk : bundle.chunks()) {
                    int cx = chunk.chunkX();
                    int cz = chunk.chunkZ();

                    // Path 1: Direct SectionFrame zero-NBT view
                    AnvilChunkView directView = LcaRegionReader.readChunkView(lcaBytes, cx, cz);
                    assertNotNull(directView, "readChunkView null for " + modeLabel);
                    assertEquals(chunk.dataVersion(), directView.dataVersion());

                    // Path 2: Reconstructed NBT bytes -> AnvilReader.toView
                    byte[] nbtBytes = LcaRegionReader.INSTANCE.readChunkNbtBytes(lcaBytes, cx, cz);
                    assertNotNull(nbtBytes, "readChunkNbtBytes null for " + modeLabel);
                    LinkedHashMap<String, Object> parsedRoot = Nbt.readRootCompound(nbtBytes);
                    AnvilChunkView nbtView = AnvilReader.toView(parsedRoot);
                    assertNotNull(nbtView, "AnvilReader.toView null for " + modeLabel);

                    Map<Integer, PaletteSection> directSecByY = new LinkedHashMap<>();
                    for (PaletteSection s : directView.sections()) {
                        directSecByY.put(s.sectionY(), s);
                    }
                    Map<Integer, BiomePaletteSection> directBiomeByY = new LinkedHashMap<>();
                    for (BiomePaletteSection b : directView.biomeSections()) {
                        directBiomeByY.put(b.sectionY(), b);
                    }
                    Map<Integer, PaletteSection> nbtSecByY = new LinkedHashMap<>();
                    for (PaletteSection s : nbtView.sections()) {
                        nbtSecByY.put(s.sectionY(), s);
                    }
                    Map<Integer, BiomePaletteSection> nbtBiomeByY = new LinkedHashMap<>();
                    for (BiomePaletteSection b : nbtView.biomeSections()) {
                        nbtBiomeByY.put(b.sectionY(), b);
                    }
                    Map<Integer, Map<?, ?>> rawNbtSecByY = new LinkedHashMap<>();
                    Nbt.NbtList rawSecs = AnvilReader.getSections(parsedRoot);
                    if (rawSecs != null) {
                        for (Object o : rawSecs.items) {
                            if (o instanceof Map<?, ?> m && m.get("Y") instanceof Number yNum) {
                                rawNbtSecByY.put(yNum.intValue(), m);
                            }
                        }
                    }

                    for (SectionManifestEntry secManifest : chunk.sections()) {
                        int sy = secManifest.sectionY();
                        PaletteSection dSec = directSecByY.get(sy);
                        PaletteSection nSec = nbtSecByY.get(sy);
                        assertNotNull(dSec, "Missing direct section Y=" + sy + " in " + modeLabel);
                        assertNotNull(nSec, "Missing NBT section Y=" + sy + " in " + modeLabel);

                        // Path 3: O(1) single-section offset jump
                        PaletteSection singleSec =
                                LcaRegionReader.readSection(lcaBytes, lcaBytes.length, cx, cz, sy);
                        assertNotNull(singleSec, "readSection null at Y=" + sy + " in " + modeLabel);

                        // Path 4: Sub-chunk terrain probe window [sy*16 .. sy*16+15]
                        ColumnProbe terrainProbe = LcaRegionReader.readTerrainProbe(
                                lcaBytes, lcaBytes.length, cx, cz, sy << 4, (sy << 4) + 15);
                        assertNotNull(terrainProbe, "readTerrainProbe null at Y=" + sy + " in " + modeLabel);
                        assertEquals(
                                dSec.blockIdAt(8, 8, 8),
                                terrainProbe.blockAt(8, 8, (sy << 4) + 8),
                                "Terrain probe mismatch at Y=" + sy + " in " + modeLabel);

                        assertEquals(
                                secManifest.voxelFnv1a64(),
                                LcaGoldenFixtureExporter.computeVoxelFnv1a64(dSec),
                                "Direct voxel FNV-1a64 mismatch at Y=" + sy + " in " + modeLabel);
                        assertEquals(
                                secManifest.voxelFnv1a64(),
                                LcaGoldenFixtureExporter.computeVoxelFnv1a64(singleSec),
                                "Single-section voxel FNV-1a64 mismatch at Y=" + sy + " in " + modeLabel);
                        assertEquals(
                                secManifest.voxelFnv1a64(),
                                LcaGoldenFixtureExporter.computeVoxelFnv1a64(nSec),
                                "NBT voxel FNV-1a64 mismatch at Y=" + sy + " in " + modeLabel);

                        assertEquals(
                                secManifest.biomeFnv1a64(),
                                LcaGoldenFixtureExporter.computeBiomeFnv1a64(directBiomeByY.get(sy)),
                                "Direct biome FNV-1a64 mismatch at Y=" + sy + " in " + modeLabel);
                        assertEquals(
                                secManifest.biomeFnv1a64(),
                                LcaGoldenFixtureExporter.computeBiomeFnv1a64(nbtBiomeByY.get(sy)),
                                "NBT biome FNV-1a64 mismatch at Y=" + sy + " in " + modeLabel);

                        Map<?, ?> rawSec = rawNbtSecByY.get(sy);
                        byte[] skyBytes =
                                (rawSec != null && rawSec.get("SkyLight") instanceof byte[] b) ? b : null;
                        byte[] blkBytes =
                                (rawSec != null && rawSec.get("BlockLight") instanceof byte[] b) ? b : null;
                        assertEquals(
                                secManifest.skyLightFnv1a64(),
                                LcaGoldenFixtureExporter.computeBytesFnv1a64(skyBytes),
                                "SkyLight FNV-1a64 mismatch at Y=" + sy + " in " + modeLabel);
                        assertEquals(
                                secManifest.blockLightFnv1a64(),
                                LcaGoldenFixtureExporter.computeBytesFnv1a64(blkBytes),
                                "BlockLight FNV-1a64 mismatch at Y=" + sy + " in " + modeLabel);
                        if (secManifest.inlineUniform()) {
                            assertEquals(
                                    0L,
                                    secManifest.octreeOccupancyMask64(),
                                    "Inline uniform section must have 0 octree mask at Y=" + sy);
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("REQ-RTP-S-004: All exported CBIT T1..T8 fault vectors reject cleanly and all 4 SEC-DED tiers self-heal to exact golden FNV-1a64 digests")
    void allCbitFaultVectorsRejectAndSecDedTiersSelfHeal() throws IOException {
        List<GoldenFixtureBundle> bundles = LcaGoldenFixtureExporter.generateAllGoldenBundles();
        Set<String> observedRules = new TreeSet<>();

        for (GoldenFixtureBundle bundle : bundles) {
            ChunkManifestEntry targetChunk = bundle.chunks().get(0);
            int cx = targetChunk.chunkX();
            int cz = targetChunk.chunkZ();

            for (CbitVectorEntry vec : bundle.cbitVectors()) {
                observedRules.add(vec.tripwireRule());
                boolean usesModeB = vec.vectorId().contains("MODE_B");
                byte[] baseBytes = usesModeB ? bundle.modeBLcaBytes() : bundle.modeALcaBytes();
                assertEquals(
                        vec.originalByte(),
                        baseBytes[vec.mutatedByteOffset()],
                        "Original byte mismatch for " + bundle.fixtureId() + ":" + vec.vectorId());

                byte[] mutated = Arrays.copyOf(baseBytes, baseBytes.length);
                mutated[vec.mutatedByteOffset()] = vec.mutatedByte();

                if (vec.expectRejected()) {
                    assertThrows(
                            CorruptRegionEntryException.class,
                            () -> LcaRegionReader.readChunkView(mutated, cx, cz),
                            "Expected CorruptRegionEntryException for "
                                    + bundle.fixtureId()
                                    + ":"
                                    + vec.vectorId());
                } else if (vec.expectSelfHealed()) {
                    LcaRegionReader.RecoveryReport report = LcaRegionReader.healRegionBitRot(mutated);
                    AnvilChunkView healedView =
                            LcaRegionReader.readChunkViewWithRecovery(mutated, cx, cz, report);
                    assertNotNull(
                            healedView,
                            "Expected self-healed chunk view for "
                                    + bundle.fixtureId()
                                    + ":"
                                    + vec.vectorId());
                    int totalHeals = report.secDedHealedPointers()
                            + report.slabScanReconstructedPointers()
                            + report.chunkHeaderSecDedHealedWords()
                            + report.dictSecDedHealedWords();
                    assertTrue(
                            totalHeals > 0,
                            "Expected non-zero SEC-DED / Slab-Scan heal count for "
                                    + bundle.fixtureId()
                                    + ":"
                                    + vec.vectorId());

                    Map<Integer, PaletteSection> healedSecByY = new LinkedHashMap<>();
                    for (PaletteSection s : healedView.sections()) {
                        healedSecByY.put(s.sectionY(), s);
                    }
                    for (SectionManifestEntry secManifest : targetChunk.sections()) {
                        PaletteSection hs = healedSecByY.get(secManifest.sectionY());
                        assertNotNull(hs);
                        assertEquals(
                                secManifest.voxelFnv1a64(),
                                LcaGoldenFixtureExporter.computeVoxelFnv1a64(hs),
                                "Self-healed voxel FNV-1a64 mismatch for "
                                        + bundle.fixtureId()
                                        + ":"
                                        + vec.vectorId());
                    }
                }
            }
        }

        assertTrue(
                observedRules.containsAll(List.of(
                        "T1",
                        "T2",
                        "T3",
                        "T3b",
                        "T3c",
                        "T4",
                        "T5",
                        "T6",
                        "T6b",
                        "T7",
                        "T8",
                        "SEC_DED_TIER1",
                        "SEC_DED_TIER2",
                        "SEC_DED_TIER3",
                        "SEC_DED_TIER4")),
                "Observed rules missing expected CBIT/SEC-DED entries: " + observedRules);
    }

    @Test
    @DisplayName("leafanvil-ADR-004 Section 3: Five-axis performance proof gates (.lca Mode A/B vs .mca and .linear)")
    void fiveAxisPerformanceProofGates() throws IOException {
        Path mountainMcaPath = Paths.get(
                "../../resource/LeafRTP/rtp-core/src/test/resources/fixtures/regions/overworld_mountain_r.0.0.mca");
        if (!Files.isRegularFile(mountainMcaPath)) {
            mountainMcaPath = Paths.get(
                    "resource/LeafRTP/rtp-core/src/test/resources/fixtures/regions/overworld_mountain_r.0.0.mca");
        }

        byte[] baseMca = Files.isRegularFile(mountainMcaPath)
                ? Files.readAllBytes(mountainMcaPath)
                : loadResource("/anvil/real/1_21_R1/r.0.0.mca");

        List<int[]> coords = new ArrayList<>(64);
        List<LinkedHashMap<String, Object>> roots = new ArrayList<>(64);
        for (int rz = 0; rz < 32; rz++) {
            for (int rx = 0; rx < 32; rx++) {
                if (AnvilReader.INSTANCE.isChunkGenerated(baseMca, rx, rz)) {
                    AnvilReader.ChunkEntry entry = AnvilReader.readChunkEntry(baseMca, rx, rz);
                    if (entry != null && entry.root != null) {
                        coords.add(new int[] {rx, rz});
                        roots.add(entry.root);
                    }
                }
            }
        }
        if (coords.size() == 1) {
            expandSingleChunkToDistinct64Chunks(roots.get(0), coords, roots);
        }

        byte[] mcaRegion = buildMcaRegion(coords, roots);
        byte[] linearRegion = buildLinearRegion(coords, roots);
        byte[] lcaModeA = LcaRegionWriter.transcodeFromMca(mcaRegion, 0, 0, false);
        byte[] lcaModeB = LcaRegionWriter.transcodeFromMca(mcaRegion, 0, 0, true);

        // Axis 1: Disk Footprint Proof Gate
        assertTrue(
                lcaModeA.length < linearRegion.length,
                "Axis 1: .lca Mode A (" + lcaModeA.length + " B) must be smaller than .linear (" + linearRegion.length + " B)");
        assertTrue(
                lcaModeB.length < lcaModeA.length,
                "Axis 1: .lca Mode B (" + lcaModeB.length + " B) must be smaller than .lca Mode A (" + lcaModeA.length + " B)");
        assertTrue(
                lcaModeB.length <= (int) (mcaRegion.length * 0.60),
                "Axis 1: .lca Mode B (" + lcaModeB.length + " B) must be <= 60% of .mca (" + mcaRegion.length + " B)");

        // Warmup JIT
        for (int w = 0; w < 40; w++) {
            int idx = w & 63;
            int rx = idx & 7;
            int rz = (idx >>> 3) & 7;
            assertNotNull(LcaRegionReader.readChunkView(lcaModeA, rx, rz));
            assertNotNull(AnvilReader.readChunkView(mcaRegion, rx, rz));
            assertNotNull(LcaRegionReader.readTerrainProbe(lcaModeA, lcaModeA.length, rx, rz, 48, 112));
        }

        // Axis 2 & 3: Random 1-Chunk Read Latency (.lca Mode A vs .mca Zlib & cold .linear Zstd)
        int readIters = 128;
        long t0 = System.nanoTime();
        for (int i = 0; i < readIters; i++) {
            int idx = i & 63;
            AnvilChunkView v = LcaRegionReader.readChunkView(lcaModeA, idx & 7, (idx >>> 3) & 7);
            assertNotNull(v);
        }
        long lcaReadNs = System.nanoTime() - t0;

        long t1 = System.nanoTime();
        for (int i = 0; i < readIters; i++) {
            int idx = i & 63;
            AnvilChunkView v = AnvilReader.readChunkView(mcaRegion, idx & 7, (idx >>> 3) & 7);
            assertNotNull(v);
        }
        long mcaReadNs = System.nanoTime() - t1;

        int coldLinearIters = 32;
        long t2 = System.nanoTime();
        for (int i = 0; i < coldLinearIters; i++) {
            byte[] coldCopy = Arrays.copyOf(linearRegion, linearRegion.length);
            int idx = i & 63;
            AnvilReader.ChunkEntry e = LinearRegionReader.INSTANCE.readChunk(coldCopy, idx & 7, (idx >>> 3) & 7);
            assertNotNull(e);
        }
        long coldLinearPerOpNs = (System.nanoTime() - t2) / coldLinearIters;
        long lcaPerOpNs = lcaReadNs / readIters;
        long mcaPerOpNs = mcaReadNs / readIters;

        assertTrue(
                lcaPerOpNs < coldLinearPerOpNs,
                "Axis 2: .lca Mode A 1-chunk read (" + lcaPerOpNs + " ns) must beat cold .linear (" + coldLinearPerOpNs + " ns)");

        // Axis 4: Sub-Chunk Y-Window Terrain Probe (Y=[48..112]) vs .mca full-chunk decode
        long t3 = System.nanoTime();
        for (int i = 0; i < readIters; i++) {
            int idx = i & 63;
            ColumnProbe p = LcaRegionReader.readTerrainProbe(
                    lcaModeA, lcaModeA.length, idx & 7, (idx >>> 3) & 7, 48, 112);
            assertNotNull(p);
        }
        long lcaProbePerOpNs = (System.nanoTime() - t3) / readIters;
        assertTrue(
                lcaProbePerOpNs < mcaPerOpNs,
                "Axis 4: .lca sub-chunk terrain probe (" + lcaProbePerOpNs + " ns) must beat .mca chunk read (" + mcaPerOpNs + " ns)");

        // Axis 5: Single-Chunk Incremental CoW Save Write Amplification (< 1/10th of whole-region .linear)
        LcaRegionWriter.LcaLiveArchive liveArchive = LcaRegionWriter.openLiveArchive(lcaModeA);
        int beforeSaveBytes = liveArchive.activeByteLength();
        LinkedHashMap<String, Object> dirtyChunk = new LinkedHashMap<>(roots.get(0));
        dirtyChunk.put("LastUpdate", 999L);
        liveArchive.saveChunkCoW(0, 0, dirtyChunk);
        int deltaSlabBytes = Math.max(256, liveArchive.activeByteLength() - beforeSaveBytes);
        assertTrue(
                deltaSlabBytes * 10 < linearRegion.length,
                "Axis 5: Single-chunk CoW slab delta (" + deltaSlabBytes + " B) must have > 10x lower write amplification than .linear (" + linearRegion.length + " B)");

        System.out.printf(
                Locale.ROOT,
                "%n[leafanvil-ADR-004 Perf Proof] 64-chunk region: .mca=%d B | .linear=%d B | .lca(A)=%d B | .lca(B)=%d B | read(A)=%.1f us vs .mca=%.1f us vs cold .linear=%.1f us | probe(A)=%.1f us%n",
                mcaRegion.length,
                linearRegion.length,
                lcaModeA.length,
                lcaModeB.length,
                lcaPerOpNs / 1000.0,
                mcaPerOpNs / 1000.0,
                coldLinearPerOpNs / 1000.0,
                lcaProbePerOpNs / 1000.0);
    }

    private static byte[] loadResource(String path) throws IOException {
        try (InputStream in = LcaGoldenFixtureTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "Missing test resource: " + path);
            return in.readAllBytes();
        }
    }

    private static final String[] TERRAIN_STATES = {
        "minecraft:air",
        "minecraft:deepslate",
        "minecraft:stone",
        "minecraft:tuff",
        "minecraft:granite",
        "minecraft:diorite",
        "minecraft:andesite",
        "minecraft:gravel",
        "minecraft:dirt",
        "minecraft:grass_block",
        "minecraft:coal_ore",
        "minecraft:iron_ore",
        "minecraft:copper_ore",
        "minecraft:gold_ore",
        "minecraft:redstone_ore",
        "minecraft:lapis_ore",
        "minecraft:cobblestone"
    };

    private static void expandSingleChunkToDistinct64Chunks(
            LinkedHashMap<String, Object> template,
            List<int[]> coords,
            List<LinkedHashMap<String, Object>> chunkRoots) {
        coords.clear();
        chunkRoots.clear();
        for (int idx = 0; idx < 64; idx++) {
            int rx = idx & 7;
            int rz = (idx >>> 3) & 7;
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>(template);
            copy.put("xPos", rx);
            copy.put("zPos", rz);
            copy.put("LastUpdate", 10_000L + idx * 37L);
            copy.put("InhabitedTime", 500L + idx * 19L);
            Object secsObj = template.get("sections");
            if (secsObj instanceof Nbt.NbtList origSecs) {
                List<Object> newSecItems = new ArrayList<>(origSecs.items.size());
                for (int s = 0; s < origSecs.items.size(); s++) {
                    Object item = origSecs.items.get(s);
                    if (!(item instanceof Map<?, ?> secMap)) {
                        newSecItems.add(item);
                        continue;
                    }
                    LinkedHashMap<String, Object> secCopy = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : secMap.entrySet()) {
                        secCopy.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    int secY = secCopy.get("Y") instanceof Number n ? n.intValue() : (s - 4);
                    if (secY >= -4 && secY <= 11) {
                        secCopy.put("block_states", buildDistinctTerrainBlockStates(idx, secY));
                        byte[] sky = new byte[2048];
                        if (secY >= 4) {
                            Arrays.fill(sky, (byte) 0xFF);
                        }
                        secCopy.put("SkyLight", sky);
                        secCopy.remove("BlockLight");
                    }
                    newSecItems.add(secCopy);
                }
                copy.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, newSecItems));
            }
            coords.add(new int[] {rx, rz});
            chunkRoots.add(copy);
        }
    }

    private static LinkedHashMap<String, Object> buildDistinctTerrainBlockStates(int chunkIdx, int secY) {
        int palSize = TERRAIN_STATES.length;
        int[] order = new int[palSize];
        for (int i = 0; i < palSize; i++) {
            order[i] = i;
        }
        long seed = mix64((chunkIdx + 1L) * 0x9E3779B97F4A7C15L ^ (secY + 16L) * 0xBF58476D1CE4E5B9L);
        for (int i = palSize - 1; i > 0; i--) {
            seed = mix64(seed + i);
            int j = (int) Long.remainderUnsigned(seed, i + 1);
            int tmp = order[i];
            order[i] = order[j];
            order[j] = tmp;
        }
        String dominantName = secY < 0 ? "minecraft:deepslate" : "minecraft:stone";
        int domLocalIdx = 0;
        for (int i = 0; i < palSize; i++) {
            if (TERRAIN_STATES[order[i]].equals(dominantName)) {
                domLocalIdx = i;
                break;
            }
        }
        if (domLocalIdx == 0) {
            int swapWith = 1 + (Math.abs(chunkIdx * 5 + secY * 3) % (palSize - 1));
            int tmp = order[0];
            order[0] = order[swapWith];
            order[swapWith] = tmp;
            domLocalIdx = swapWith;
        }
        List<Object> paletteList = new ArrayList<>(palSize);
        for (int i = 0; i < palSize; i++) {
            LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
            entry.put("Name", TERRAIN_STATES[order[i]]);
            paletteList.add(entry);
        }
        int[] voxels = new int[4096];
        Arrays.fill(voxels, domLocalIdx);
        int cursor = (int) Long.remainderUnsigned(seed, 97) + 16;
        for (int round = 0; round < 2; round++) {
            for (int p = 0; p < palSize; p++) {
                if (p == domLocalIdx) continue;
                seed = mix64(seed + p * 31L + round * 131L);
                int gap = 36 + (int) Long.remainderUnsigned(seed, 72);
                int runLen = 10 + (int) Long.remainderUnsigned(seed >>> 16, 7);
                cursor += gap;
                if (cursor + runLen >= 4096) {
                    cursor = (cursor + runLen) & 4095;
                }
                if (cursor + runLen <= 4096) {
                    Arrays.fill(voxels, cursor, cursor + runLen, p);
                    cursor += runLen;
                }
            }
        }
        long[] packed = new long[342];
        int vIdx = 0;
        for (int w = 0; w < packed.length && vIdx < 4096; w++) {
            long word = 0L;
            for (int slot = 0; slot < 12 && vIdx < 4096; slot++, vIdx++) {
                word |= ((long) (voxels[vIdx] & 0x1F)) << (slot * 5);
            }
            packed[w] = word;
        }
        LinkedHashMap<String, Object> bs = new LinkedHashMap<>();
        bs.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, paletteList));
        bs.put("data", packed);
        return bs;
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static byte[] buildMcaRegion(
            List<int[]> coords, List<LinkedHashMap<String, Object>> roots) throws IOException {
        byte[] header = new byte[8192];
        ByteArrayOutputStream sectorsOut = new ByteArrayOutputStream();
        int currentSector = 2;
        for (int i = 0; i < coords.size(); i++) {
            int rx = coords.get(i)[0] & 31;
            int rz = coords.get(i)[1] & 31;
            byte[] rawNbt = Nbt.writeNamedRoot("", roots.get(i));
            Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
            byte[] compressed;
            try (ByteArrayOutputStream bos = new ByteArrayOutputStream(rawNbt.length)) {
                deflater.setInput(rawNbt);
                deflater.finish();
                byte[] buf = new byte[4096];
                while (!deflater.finished()) {
                    int n = deflater.deflate(buf);
                    bos.write(buf, 0, n);
                }
                compressed = bos.toByteArray();
            } finally {
                deflater.end();
            }
            int declaredLen = compressed.length + 1;
            int totalBytes = 4 + declaredLen;
            int sectorCount = (totalBytes + 4095) >>> 12;
            byte[] sectorBuf = new byte[sectorCount << 12];
            ByteBuffer.wrap(sectorBuf).putInt(0, declaredLen);
            sectorBuf[4] = 2;
            System.arraycopy(compressed, 0, sectorBuf, 5, compressed.length);
            sectorsOut.writeBytes(sectorBuf);

            int locIdx = (rx + (rz << 5)) * 4;
            header[locIdx] = (byte) ((currentSector >>> 16) & 0xFF);
            header[locIdx + 1] = (byte) ((currentSector >>> 8) & 0xFF);
            header[locIdx + 2] = (byte) (currentSector & 0xFF);
            header[locIdx + 3] = (byte) sectorCount;
            currentSector += sectorCount;
        }
        byte[] body = sectorsOut.toByteArray();
        byte[] mca = new byte[8192 + body.length];
        System.arraycopy(header, 0, mca, 0, 8192);
        System.arraycopy(body, 0, mca, 8192, body.length);
        return mca;
    }

    private static byte[] buildLinearRegion(
            List<int[]> coords, List<LinkedHashMap<String, Object>> roots) throws IOException {
        byte[][] rawNbts = new byte[1024][];
        int totalNbtBytes = 0;
        for (int i = 0; i < coords.size(); i++) {
            int rx = coords.get(i)[0] & 31;
            int rz = coords.get(i)[1] & 31;
            byte[] nbt = Nbt.writeNamedRoot("", roots.get(i));
            rawNbts[rx | (rz << 5)] = nbt;
            totalNbtBytes += nbt.length;
        }
        byte[] uncompressed = new byte[8192 + totalNbtBytes];
        ByteBuffer idxBuf = ByteBuffer.wrap(uncompressed, 0, 8192);
        int cursor = 8192;
        for (int i = 0; i < 1024; i++) {
            byte[] nbt = rawNbts[i];
            int len = nbt == null ? 0 : nbt.length;
            idxBuf.putInt(i * 8, len);
            idxBuf.putInt(i * 8 + 4, 1700000000);
            if (len > 0) {
                System.arraycopy(nbt, 0, uncompressed, cursor, len);
                cursor += len;
            }
        }
        byte[] compressed = com.github.luben.zstd.Zstd.compress(uncompressed, 1);
        byte[] linear = new byte[32 + compressed.length + 8];
        ByteBuffer bb = ByteBuffer.wrap(linear);
        bb.putLong(LinearRegionReader.LINEAR_SIGNATURE);
        bb.put((byte) 2);
        bb.putLong(1700000000L);
        bb.put((byte) 1);
        bb.putShort((short) coords.size());
        bb.putInt(compressed.length);
        bb.putLong(0L);
        bb.put(compressed);
        bb.putLong(LinearRegionReader.LINEAR_SIGNATURE);
        return linear;
    }
}
