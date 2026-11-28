package io.github.dailystruggle.anvil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Lossless parity and fail-closed corruption gate for Minecraft 26.4 ({@code 16x16x16} per-block
 * biomes, {@code DataVersion = 5131}, lowercase {@code status}) encoded inside LCA V3
 * ({@code 0x0003}).
 */
@DisplayName("MC 26.4 16x16x16 per-block biome LCA V3 parity and CBIT guards")
class Mc264BiomeLcaParityTest {

    @Test
    @DisplayName("Real 26.4-pre-1 r.0.0.mca round-trips bit-identically across LCA V3 Mode A, Mode B, transcodeToMca, and LcaWorldStorageSession")
    void real26_4FixtureFullVoxelAndBiomeRoundTrip(@TempDir Path tempDir) throws IOException {
        byte[] mcaBytes = loadReal264Fixture();
        LcaRegionReader.register();

        // Verify the real 26.4-pre-1 fixture indeed contains 16x16x16 multi-biome sections
        int perBlockSectionCount = 0;
        for (int cz : new int[] {0, 1}) {
            AnvilChunkView mcaView = AnvilReader.readChunkView(mcaBytes, 0, cz);
            assertNotNull(mcaView, "real 26.4 chunk (0," + cz + ") must decode");
            assertEquals(DataVersionSupport.MC_26_4_DATA_VERSION, mcaView.dataVersion());
            for (BiomePaletteSection bs : mcaView.biomeSections()) {
                if (bs.cellsPerAxis() == BiomePaletteSection.CELLS_PER_AXIS_16X16X16) {
                    perBlockSectionCount++;
                }
            }
        }
        assertTrue(perBlockSectionCount >= 20,
                "real 26.4 fixture must contain >= 20 multi-biome 16x16x16 sections, found " + perBlockSectionCount);

        for (boolean modeB : new boolean[] {false, true}) {
            byte[] lcaBytes = LcaRegionWriter.transcodeFromMca(mcaBytes, 0, 0, modeB);
            assertEquals(LcaRegionReader.LCA_FORMAT_VERSION_V3, LcaSectionCodec.readUint16BE(lcaBytes, 0x08),
                    "26.4 biomes must stay on LCA V3 (0x0003)");

            // Export back to .mca and verify full voxel + per-block biome parity
            byte[] exportedMca = LcaRegionWriter.transcodeToMca(lcaBytes);

            for (int cz : new int[] {0, 1}) {
                AnvilChunkView expected = AnvilReader.readChunkView(mcaBytes, 0, cz);
                AnvilChunkView fromLca = LcaRegionReader.readChunkView(lcaBytes, 0, cz);
                AnvilChunkView fromExportedMca = AnvilReader.readChunkView(exportedMca, 0, cz);
                assertNotNull(expected);
                assertNotNull(fromLca);
                assertNotNull(fromExportedMca);

                assertChunkViewsVoxelAndBiomeEqual("modeB=" + modeB + " lcaView (0," + cz + ")", expected, fromLca);
                assertChunkViewsVoxelAndBiomeEqual(
                        "modeB=" + modeB + " exportedMca (0," + cz + ")", expected, fromExportedMca);

                // Verify direct NBT byte path preserves DataVersion, lowercase status, and exact biomes
                byte[] directNbt = LcaRegionReader.INSTANCE.readChunkNbtBytes(lcaBytes, 0, cz);
                assertNotNull(directNbt);
                LinkedHashMap<String, Object> directRoot = Nbt.readRootCompound(directNbt);
                assertEquals(DataVersionSupport.MC_26_4_DATA_VERSION, AnvilReader.getDataVersion(directRoot));
                assertEquals("minecraft:full", directRoot.get("status"));
                assertNull(directRoot.get("Status"));
            }
        }

        // Verify streaming NBT encoder (LcaWorldStorageSession) preserves real 26.4 chunks identically
        Path streamDir = Files.createDirectories(tempDir.resolve("streaming_26_4"));
        try (LcaRegionFileStorage.LcaWorldStorageSession ws = new LcaRegionFileStorage.LcaWorldStorageSession(
                streamDir, -4, 24, DataVersionSupport.MC_26_4_DATA_VERSION, 0, true)) {
            for (int cz : new int[] {0, 1}) {
                AnvilReader.ChunkEntry src = AnvilReader.readChunkEntry(mcaBytes, 0, cz);
                assertNotNull(src);
                byte[] rawNbt = Nbt.writeNamedRoot("", src.root);
                ws.voxelStorage().writeChunkNbtBytes(0, cz, rawNbt);

                byte[] roundTrippedNbt = ws.voxelStorage().readChunkNbtBytes(0, cz);
                assertNotNull(roundTrippedNbt);
                LinkedHashMap<String, Object> rtRoot = Nbt.readRootCompound(roundTrippedNbt);
                assertEquals("minecraft:full", rtRoot.get("status"));
                assertNull(rtRoot.get("Status"));

                byte[] rtMca = AnvilTestFixtures.writeSingleChunkRegion(rtRoot);
                AnvilChunkView expected = AnvilReader.readChunkView(mcaBytes, 0, cz);
                AnvilChunkView actual = AnvilReader.readChunkView(rtMca, 0, 0);
                assertChunkViewsVoxelAndBiomeEqual("streaming (0," + cz + ")", expected, actual);
            }
        }
    }

    @Test
    @DisplayName("Real 26.4-pre-1 proto-chunks preserve sections[*].noise_biomes bit-identically across LCA V3 Map and streaming paths")
    void real26_4ProtoChunkNoiseBiomesRoundTrip(@TempDir Path tempDir) throws IOException {
        byte[] mcaBytes = loadReal264Fixture();
        LcaRegionReader.register();

        int[] protoXs = new int[] {5, 6}; // (5,0) = minecraft:terrain, (6,0) = minecraft:noise_biomes
        Map<Integer, Map<Integer, Map<String, Object>>> expectedByChunk = new LinkedHashMap<>();
        for (int cx : protoXs) {
            AnvilReader.ChunkEntry src = AnvilReader.readChunkEntry(mcaBytes, cx, 0);
            assertNotNull(src, "real 26.4 proto-chunk (" + cx + ",0) must decode");
            Map<Integer, Map<String, Object>> noiseByY = extractNoiseBiomesBySectionY(src.root);
            assertTrue(noiseByY.size() >= 20,
                    "proto-chunk (" + cx + ",0) must contain >= 20 sections with noise_biomes, found " + noiseByY.size());
            expectedByChunk.put(cx, noiseByY);
        }

        for (boolean modeB : new boolean[] {false, true}) {
            byte[] lcaBytes = LcaRegionWriter.transcodeFromMca(mcaBytes, 0, 0, modeB);
            assertEquals(LcaRegionReader.LCA_FORMAT_VERSION_V3, LcaSectionCodec.readUint16BE(lcaBytes, 0x08),
                    "26.4 proto-chunks with noise_biomes must stay on LCA V3 (0x0003)");
            byte[] exportedMca = LcaRegionWriter.transcodeToMca(lcaBytes);

            for (int cx : protoXs) {
                Map<Integer, Map<String, Object>> expected = expectedByChunk.get(cx);

                // 1. LcaRegionReader#readChunk
                AnvilReader.ChunkEntry fromLca = LcaRegionReader.INSTANCE.readChunk(lcaBytes, cx, 0);
                assertNotNull(fromLca);
                assertNull(fromLca.root.get("_lca_section_extras"),
                        "internal _lca_section_extras key must never leak onto decoded root");
                assertNoiseBiomesBySectionEqual("modeB=" + modeB + " readChunk (" + cx + ",0)",
                        expected, extractNoiseBiomesBySectionY(fromLca.root));

                // 2. LcaRegionReader#readChunkNbtBytes
                byte[] nbtBytes = LcaRegionReader.INSTANCE.readChunkNbtBytes(lcaBytes, cx, 0);
                assertNotNull(nbtBytes);
                LinkedHashMap<String, Object> nbtRoot = Nbt.readRootCompound(nbtBytes);
                assertNull(nbtRoot.get("_lca_section_extras"),
                        "internal _lca_section_extras key must never leak onto readChunkNbtBytes root");
                assertNoiseBiomesBySectionEqual("modeB=" + modeB + " readChunkNbtBytes (" + cx + ",0)",
                        expected, extractNoiseBiomesBySectionY(nbtRoot));

                // 3. LcaRegionWriter#transcodeToMca
                AnvilReader.ChunkEntry fromExportedMca = AnvilReader.readChunkEntry(exportedMca, cx, 0);
                assertNotNull(fromExportedMca);
                assertNull(fromExportedMca.root.get("_lca_section_extras"));
                assertNoiseBiomesBySectionEqual("modeB=" + modeB + " transcodeToMca (" + cx + ",0)",
                        expected, extractNoiseBiomesBySectionY(fromExportedMca.root));
            }

            // Full chunks (0,0) and (0,1) have no noise_biomes and must never emit _lca_section_extras
            for (int cz : new int[] {0, 1}) {
                byte[] fullNbt = LcaRegionReader.INSTANCE.readChunkNbtBytes(lcaBytes, 0, cz);
                assertNotNull(fullNbt);
                LinkedHashMap<String, Object> fullRoot = Nbt.readRootCompound(fullNbt);
                assertNull(fullRoot.get("_lca_section_extras"));
                assertTrue(extractNoiseBiomesBySectionY(fullRoot).isEmpty());
            }
        }

        // 4. Streaming NBT encoder path (LcaWorldStorageSession)
        Path streamDir = Files.createDirectories(tempDir.resolve("streaming_26_4_proto"));
        try (LcaRegionFileStorage.LcaWorldStorageSession ws = new LcaRegionFileStorage.LcaWorldStorageSession(
                streamDir, -4, 24, DataVersionSupport.MC_26_4_DATA_VERSION, 0, true)) {
            for (int cx : protoXs) {
                AnvilReader.ChunkEntry src = AnvilReader.readChunkEntry(mcaBytes, cx, 0);
                assertNotNull(src);
                byte[] rawNbt = Nbt.writeNamedRoot("", src.root);
                ws.voxelStorage().writeChunkNbtBytes(cx, 0, rawNbt);

                byte[] roundTrippedNbt = ws.voxelStorage().readChunkNbtBytes(cx, 0);
                assertNotNull(roundTrippedNbt);
                LinkedHashMap<String, Object> rtRoot = Nbt.readRootCompound(roundTrippedNbt);
                assertNull(rtRoot.get("_lca_section_extras"),
                        "internal _lca_section_extras key must never leak onto streaming round-trip root");
                assertNoiseBiomesBySectionEqual("streaming (" + cx + ",0)",
                        expectedByChunk.get(cx), extractNoiseBiomesBySectionY(rtRoot));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Map<String, Object>> extractNoiseBiomesBySectionY(Map<String, Object> root) {
        Map<Integer, Map<String, Object>> out = new LinkedHashMap<>();
        Object sectionsObj = root.get("sections");
        List<?> sections = (sectionsObj instanceof Nbt.NbtList nl) ? nl.items
                : (sectionsObj instanceof List<?> list ? list : List.of());
        for (Object item : sections) {
            if (!(item instanceof Map<?, ?> sec)) continue;
            Object yObj = sec.get("Y");
            Object nbObj = sec.get("noise_biomes");
            if (yObj instanceof Number yNum && nbObj instanceof Map<?, ?> nbMap) {
                out.put(yNum.intValue(), (Map<String, Object>) nbMap);
            }
        }
        return out;
    }

    private static void assertNoiseBiomesBySectionEqual(
            String label,
            Map<Integer, Map<String, Object>> expected,
            Map<Integer, Map<String, Object>> actual
    ) {
        assertEquals(expected.keySet(), actual.keySet(), label + ": section Y keys with noise_biomes");
        for (Map.Entry<Integer, Map<String, Object>> entry : expected.entrySet()) {
            int sy = entry.getKey();
            Map<String, Object> expNb = entry.getValue();
            Map<String, Object> actNb = actual.get(sy);
            assertNotNull(actNb, label + ": missing noise_biomes at Y=" + sy);
            assertEquals(expNb.keySet(), actNb.keySet(), label + ": noise_biomes keys at Y=" + sy);

            Object expPalObj = expNb.get("palette");
            Object actPalObj = actNb.get("palette");
            List<?> expPal = (expPalObj instanceof Nbt.NbtList nl) ? nl.items : (List<?>) expPalObj;
            List<?> actPal = (actPalObj instanceof Nbt.NbtList nl) ? nl.items : (List<?>) actPalObj;
            assertEquals(expPal, actPal, label + ": noise_biomes.palette at Y=" + sy);

            Object expData = expNb.get("data");
            Object actData = actNb.get("data");
            if (expData == null) {
                assertNull(actData, label + ": noise_biomes.data expected null at Y=" + sy);
            } else {
                assertTrue(actData instanceof long[], label + ": noise_biomes.data must be long[] at Y=" + sy);
                assertTrue(Arrays.equals((long[]) expData, (long[]) actData),
                        label + ": noise_biomes.data mismatch at Y=" + sy);
            }
        }
    }

    @Test
    @DisplayName("Synthetic 16x16x16 biome edge cases (uniform, 2/8/12/70 palettes, long RLE, 3D checkerboard raw ceiling, 24-section growth) round-trip losslessly in LCA V3")
    void synthetic4096BiomeEdgeCasesRoundTrip(@TempDir Path tempDir) throws IOException {
        LcaRegionReader.register();
        long[] heightmap = new long[37];

        // Build a 24-section chunk exercising every 4,096-biome encoder branch + dynamic buffer growth (> 16 KiB)
        List<LinkedHashMap<String, Object>> sections = new ArrayList<>(24);
        List<BiomePaletteSection> expectedBiomes = new ArrayList<>(24);

        for (int i = 0; i < 24; i++) {
            int sy = -4 + i;
            List<String> bPal;
            int[] cells4096 = null;
            if (i == 0) {
                // Uniform single-biome section (resolution-invariant)
                bPal = List.of("minecraft:plains");
            } else if (i == 1) {
                // 2-biome section with long RLE runs (> 31, triggering lenCode=31 + varInt(ext))
                bPal = List.of("minecraft:plains", "minecraft:forest");
                cells4096 = new int[4096];
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            cells4096[BiomePaletteSection.biomeVoxelIndex(x, y, z)] = (x < 10) ? 0 : 1;
                        }
                    }
                }
            } else if (i == 2) {
                // 8-biome boundary (compact 1-byte RLE mode with runs > 31 and runs == 1)
                bPal = makeBiomePalette(8);
                cells4096 = new int[4096];
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            cells4096[BiomePaletteSection.biomeVoxelIndex(x, y, z)] =
                                    (z < 12) ? (x / 2) : ((x + y + z) & 7);
                        }
                    }
                }
            } else if (i == 3) {
                // 12-biome section (actualBLen > 8 varint RLE)
                bPal = makeBiomePalette(12);
                cells4096 = new int[4096];
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            cells4096[BiomePaletteSection.biomeVoxelIndex(x, y, z)] = ((x / 4) * 3 + (z / 6)) % 12;
                        }
                    }
                }
            } else if (i == 4) {
                // 70-biome section (> 64 biomes, strictly impossible in 4x4x4)
                bPal = makeBiomePalette(70);
                cells4096 = new int[4096];
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            cells4096[BiomePaletteSection.biomeVoxelIndex(x, y, z)] = (y * 5 + z * 3 + x) % 70;
                        }
                    }
                }
            } else if (i == 5) {
                // Adversarial 3D checkerboard (2 biomes, 4,096 runs -> forces modeByte=0x01 raw packed ceiling)
                bPal = List.of("minecraft:desert", "minecraft:badlands");
                cells4096 = new int[4096];
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            cells4096[BiomePaletteSection.biomeVoxelIndex(x, y, z)] = (x ^ y ^ z) & 1;
                        }
                    }
                }
            } else {
                // Remaining 18 sections: distinct multi-biome 16x16x16 checker/gradient patterns to force
                // slab-local palette & section staging buffer growth well past 16 KiB
                int pSize = 4 + (i % 13);
                bPal = makeBiomePalette(pSize);
                cells4096 = new int[4096];
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            cells4096[BiomePaletteSection.biomeVoxelIndex(x, y, z)] =
                                    (x * 3 + y * 5 + z * 7 + i) % pSize;
                        }
                    }
                }
            }

            long[] packedBiomeLongs = (cells4096 == null) ? null : packBiome4096(cells4096, bPal.size());
            expectedBiomes.add(new BiomePaletteSection(sy, bPal, packedBiomeLongs));
            sections.add(buildSectionWithBiomes((byte) sy, List.of("minecraft:stone"), bPal, packedBiomeLongs));
        }

        // Verify raw ceiling size property directly on the adversarial 3D checkerboard (i == 5)
        BiomePaletteSection checker = expectedBiomes.get(5);
        byte[] checkerEncoded = new byte[LcaSectionCodec.maxEncodedBiomeBytes(new int[] {0, 1}, checker.data())];
        int checkerLen = LcaSectionCodec.encodeBiomesToBuf(
                checkerEncoded, 0, new int[] {0, 1}, checker.data(), -1, LcaSectionCodec.scratch());
        // Header: marker(1) + bLen(1) + dictIds(2) + mode(1) + raw(64*8=512) = 517 bytes
        assertEquals(LcaSectionCodec.BIOME_4096_MARKER, checkerEncoded[0] & 0xFF);
        assertEquals(LcaSectionCodec.BIOME_4096_MODE_RAW_PACKED, checkerEncoded[4] & 0xFF,
                "3D checkerboard must select raw packed ceiling mode 0x01");
        assertEquals(5 + (64 * 8), checkerLen, "3D checkerboard must stay strictly at raw packed ceiling");

        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", DataVersionSupport.MC_26_4_DATA_VERSION);
        root.put("xPos", 0);
        root.put("zPos", 0);
        root.put("yPos", -4);
        root.put("status", "minecraft:full");
        root.put("LastUpdate", 100L);
        root.put("InhabitedTime", 50L);
        LinkedHashMap<String, Object> hm = new LinkedHashMap<>();
        hm.put("MOTION_BLOCKING_NO_LEAVES", heightmap);
        root.put("Heightmaps", hm);
        root.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, new ArrayList<>(sections)));

        byte[] mcaBytes = AnvilTestFixtures.writeSingleChunkRegion(root);

        for (boolean modeB : new boolean[] {false, true}) {
            byte[] lcaBytes = LcaRegionWriter.transcodeFromMca(mcaBytes, 0, 0, modeB);
            assertEquals(LcaRegionReader.LCA_FORMAT_VERSION_V3, LcaSectionCodec.readUint16BE(lcaBytes, 0x08));

            AnvilChunkView lcaView = LcaRegionReader.readChunkView(lcaBytes, 0, 0);
            assertNotNull(lcaView);
            assertEquals(24, lcaView.biomeSections().size());
            for (int i = 0; i < 24; i++) {
                assertBiomeSectionVoxelEqual("modeB=" + modeB + " view secIdx=" + i,
                        expectedBiomes.get(i), lcaView.biomeSections().get(i));
            }

            // Verify readChunkNbtBytes + transcodeToMca dynamic nbtOutBuf growth
            byte[] nbtBytes = LcaRegionReader.INSTANCE.readChunkNbtBytes(lcaBytes, 0, 0);
            assertNotNull(nbtBytes);
            LinkedHashMap<String, Object> nbtRoot = Nbt.readRootCompound(nbtBytes);
            assertEquals("minecraft:full", nbtRoot.get("status"));
            assertNull(nbtRoot.get("Status"));

            byte[] reMca = LcaRegionWriter.transcodeToMca(lcaBytes);
            AnvilChunkView reMcaView = AnvilReader.readChunkView(reMca, 0, 0);
            assertNotNull(reMcaView);
            for (int i = 0; i < 24; i++) {
                assertBiomeSectionVoxelEqual("modeB=" + modeB + " reMca secIdx=" + i,
                        expectedBiomes.get(i), reMcaView.biomeSections().get(i));
            }
        }

        // Verify LcaWorldStorageSession streaming encoder with all 24 high-entropy 4,096-biome sections
        Path streamDir = Files.createDirectories(tempDir.resolve("streaming_synth"));
        try (LcaRegionFileStorage.LcaWorldStorageSession ws = new LcaRegionFileStorage.LcaWorldStorageSession(
                streamDir, -4, 24, DataVersionSupport.MC_26_4_DATA_VERSION, 0, true)) {
            byte[] rawNbt = Nbt.writeNamedRoot("", root);
            ws.voxelStorage().writeChunkNbtBytes(0, 0, rawNbt);
            byte[] readBack = ws.voxelStorage().readChunkNbtBytes(0, 0);
            assertNotNull(readBack);
            byte[] rtMca = AnvilTestFixtures.writeSingleChunkRegion(Nbt.readRootCompound(readBack));
            AnvilChunkView rtView = AnvilReader.readChunkView(rtMca, 0, 0);
            assertNotNull(rtView);
            for (int i = 0; i < 24; i++) {
                assertBiomeSectionVoxelEqual("streaming secIdx=" + i,
                        expectedBiomes.get(i), rtView.biomeSections().get(i));
            }
        }
    }

    @Test
    @DisplayName("CBIT T5/T6/T7 fail-closed guards reject malformed BIOME_4096_MARKER section frames")
    void cbitFailClosedGuardsRejectMalformed4096BiomeFrames() {
        List<String> dictStates = List.of("minecraft:air");
        List<String> dictBiomes = List.of("minecraft:plains", "minecraft:desert", "minecraft:forest");

        // 1. Invalid biome marker bLen = 66 (> 64 and != 65)
        byte[] badMarker = buildUniformSectionWithBiomeTail(new byte[] {66});
        assertThrows(CorruptRegionEntryException.class, () -> decodeTestSection(badMarker, dictStates, dictBiomes));

        // 2. BIOME_4096_MARKER with actualBLen = 1 (< 2)
        byte[] tooSmallPal = buildUniformSectionWithBiomeTail(new byte[] {65, 1, 0, 0, 1, 0});
        assertThrows(CorruptRegionEntryException.class, () -> decodeTestSection(tooSmallPal, dictStates, dictBiomes));

        // 3. BIOME_4096_MARKER with actualBLen = 4097 (> 4096)
        byte[] tooLargePal = buildUniformSectionWithBiomeTail(varIntBytes(65, 4097));
        assertThrows(CorruptRegionEntryException.class, () -> decodeTestSection(tooLargePal, dictStates, dictBiomes));

        // 4. Out-of-bounds biome dictId (CBIT T7)
        byte[] badDictId = buildUniformSectionWithBiomeTail(new byte[] {65, 2, 0, 9, 0, 1, 31});
        assertThrows(CorruptRegionEntryException.class, () -> decodeTestSection(badDictId, dictStates, dictBiomes));

        // 5. Invalid modeByte = 2
        byte[] badMode = buildUniformSectionWithBiomeTail(new byte[] {65, 2, 0, 1, 2});
        assertThrows(CorruptRegionEntryException.class, () -> decodeTestSection(badMode, dictStates, dictBiomes));

        // 6. Truncated RLE stream (runCount=1, missing run byte)
        byte[] truncRle = buildUniformSectionWithBiomeTail(new byte[] {65, 2, 0, 1, 0, 1});
        assertThrows(CorruptRegionEntryException.class, () -> decodeTestSection(truncRle, dictStates, dictBiomes));

        // 7. RLE cursor underflow (total run length = 10 != 4096)
        byte[] underflowRle = buildUniformSectionWithBiomeTail(new byte[] {65, 2, 0, 1, 0, 1, 9});
        assertThrows(CorruptRegionEntryException.class, () -> decodeTestSection(underflowRle, dictStates, dictBiomes));

        // 8. RLE cursor overflow (total run length = 4097 > 4096 via lenCode=31 + ext=4065)
        byte[] overflowRle = buildUniformSectionWithBiomeTail(concat(
                new byte[] {65, 2, 0, 1, 0, 1, 31}, varIntBytes(4065)));
        assertThrows(CorruptRegionEntryException.class, () -> decodeTestSection(overflowRle, dictStates, dictBiomes));

        // 9. Truncated raw packed long array (mode=1, needs 64*8=512 bytes, provide 16)
        byte[] truncRaw = buildUniformSectionWithBiomeTail(concat(
                new byte[] {65, 2, 0, 1, 1}, new byte[16]));
        assertThrows(CorruptRegionEntryException.class, () -> decodeTestSection(truncRaw, dictStates, dictBiomes));

        // 10. Out-of-range palette index in raw packed mode (bLen=3 -> 2 bits/cell, index 3 is invalid)
        byte[] rawOobPayload = new byte[128 * 8];
        rawOobPayload[7] = 0b11; // first cell (LSB of big-endian long[0]) has palette index 3 >= 3
        byte[] oobRaw = buildUniformSectionWithBiomeTail(concat(
                new byte[] {65, 3, 0, 1, 2, 1}, rawOobPayload));
        assertThrows(CorruptRegionEntryException.class, () -> decodeTestSection(oobRaw, dictStates, dictBiomes));
    }

    // ---------------------------------------------------------------------------- helpers

    private static LcaSectionCodec.DecodedSection decodeTestSection(
            byte[] frame, List<String> dictStates, List<String> dictBiomes
    ) throws CorruptRegionEntryException {
        return LcaSectionCodec.decodeSectionFrame(
                frame, 0, frame.length, 0, dictStates, dictStates, dictBiomes,
                List.of(), false, false, LcaRegionReader.LCA_FORMAT_VERSION_V3);
    }

    private static byte[] buildUniformSectionWithBiomeTail(byte[] biomeTail) {
        byte[] out = new byte[3 + biomeTail.length];
        out[0] = 0x00; // curve=0, blockMode=BLOCK_MODE_UNIFORM(0), sky=0, blk=0
        out[1] = 1;    // paletteLen=1
        out[2] = 0;    // dictId=0 (minecraft:air)
        System.arraycopy(biomeTail, 0, out, 3, biomeTail.length);
        return out;
    }

    private static byte[] varIntBytes(int... values) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (int v : values) {
            int x = v;
            while ((x & ~0x7F) != 0) {
                baos.write((x & 0x7F) | 0x80);
                x >>>= 7;
            }
            baos.write(x);
        }
        return baos.toByteArray();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static List<String> makeBiomePalette(int count) {
        List<String> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add("minecraft:biome_" + i);
        }
        return list;
    }

    private static long[] packBiome4096(int[] cells4096, int paletteSize) {
        int bits = BiomePaletteSection.biomeBitsPerEntry(paletteSize);
        int perLong = 64 / bits;
        int numLongs = BiomePaletteSection.requiredLongs(paletteSize, BiomePaletteSection.CELL_COUNT_16X16X16);
        long mask = (1L << bits) - 1L;
        long[] words = new long[numLongs];
        for (int i = 0; i < BiomePaletteSection.CELL_COUNT_16X16X16; i++) {
            words[i / perLong] |= (cells4096[i] & mask) << ((i % perLong) * bits);
        }
        return words;
    }

    private static LinkedHashMap<String, Object> buildSectionWithBiomes(
            byte sectionY, List<String> blockPalette, List<String> biomePalette, long[] biomeData
    ) throws IOException {
        LinkedHashMap<String, Object> sec = AnvilTestFixtures.section(sectionY, blockPalette);
        LinkedHashMap<String, Object> biomes = new LinkedHashMap<>();
        biomes.put("palette", new Nbt.NbtList(Nbt.TAG_STRING, new ArrayList<>(biomePalette)));
        if (biomeData != null) {
            biomes.put("data", biomeData);
        }
        sec.put("biomes", biomes);
        return sec;
    }

    private static void assertChunkViewsVoxelAndBiomeEqual(String label, AnvilChunkView expected, AnvilChunkView actual) {
        assertEquals(expected.dataVersion(), actual.dataVersion(), label + ": dataVersion");
        assertEquals(expected.sections().size(), actual.sections().size(), label + ": section count");
        assertEquals(expected.biomeSections().size(), actual.biomeSections().size(), label + ": biomeSection count");

        for (int s = 0; s < expected.sections().size(); s++) {
            PaletteSection expSec = expected.sections().get(s);
            PaletteSection actSec = actual.sections().get(s);
            assertEquals(expSec.sectionY(), actSec.sectionY(), label + ": block sectionY[" + s + "]");
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        assertEquals(expSec.blockIdAt(x, y, z), actSec.blockIdAt(x, y, z),
                                label + ": block mismatch at sy=" + expSec.sectionY() + " (" + x + "," + y + "," + z + ")");
                    }
                }
            }
        }

        for (int s = 0; s < expected.biomeSections().size(); s++) {
            assertBiomeSectionVoxelEqual(label + ": biomeSec[" + s + "]",
                    expected.biomeSections().get(s), actual.biomeSections().get(s));
        }
    }

    private static void assertBiomeSectionVoxelEqual(
            String label, BiomePaletteSection expected, BiomePaletteSection actual
    ) {
        assertEquals(expected.sectionY(), actual.sectionY(), label + ": sectionY");
        assertEquals(expected.cellsPerAxis(), actual.cellsPerAxis(), label + ": cellsPerAxis");
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    assertEquals(expected.biomeIdAt(x, y, z), actual.biomeIdAt(x, y, z),
                            label + ": biome mismatch at (" + x + "," + y + "," + z + ")");
                }
            }
        }
    }

    private static byte[] loadReal264Fixture() throws IOException {
        String resource = "/anvil/real/26_4_R1/r.0.0.mca";
        try (InputStream in = Mc264BiomeLcaParityTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                fail("Real 26.4-pre-1 fixture not found on classpath: " + resource);
            }
            return in.readAllBytes();
        }
    }
}
