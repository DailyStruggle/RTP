package io.github.dailystruggle.anvil;

import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.PbitResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.io.TempDir;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Differential parity, CBIT/PBIT tripwire verification, Copy-on-Write (`CoW`) concurrency torture test,
 * and empirical benchmark suite for `.lca` ({@link LcaRegionReader} and {@link LcaRegionWriter},
 * `anvil-api-ADR-003`, `REQ-RTP-S-004`, `REQ-RTP-S-005`).
 */
@DisplayName("anvil-api-ADR-003: LcaRegionReader & LcaRegionWriter Parity, CBIT, and Empirical Benchmark")
class LcaRegionCodecTest {

    @BeforeEach
    @AfterEach
    void resetRegistry() {
        RegionFormatRegistry.reset();
    }

    private static byte[] loadFixture(String resourcePath) throws IOException {
        try (InputStream in = LcaRegionCodecTest.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                fail("Fixture not found on classpath: " + resourcePath);
            }
            return in.readAllBytes();
        }
    }

    private static Stream<Arguments> realFixtures() {
        return Stream.of(
                Arguments.of("1_20_R1", 3465),
                Arguments.of("1_21_R1", 4671),
                Arguments.of("26_1_R1", 4788),
                Arguments.of("26_3_R1", 5023));
    }

    @Test
    @DisplayName("REQ-RTP-S-004: LcaRegionReader PBIT micro-canary passes and .lca registers in RegionFormatRegistry")
    void pbitAndRegistryIntegration() {
        PbitResult pbit = LcaRegionReader.INSTANCE.executePbit();
        assertNotNull(pbit);
        assertTrue(pbit.passed(), () -> "PBIT failed: " + pbit.diagnosticMessage());
        assertEquals(BitHealth.HEALTHY, LcaRegionReader.INSTANCE.getHealthStatus());

        LcaRegionReader.register();
        assertTrue(RegionFormatRegistry.isRegistered(".lca"));
        assertEquals(LcaRegionReader.INSTANCE, RegionFormatRegistry.getReader(".lca"));
    }

    @ParameterizedTest(name = "real fixture {0} (DataVersion={1}) Mode A & Mode B 100% voxel/biome/probe parity")
    @MethodSource("realFixtures")
    @DisplayName("REQ-RTP-S-005: Real .mca fixtures transcoded to .lca Mode A & Mode B match AnvilReader across all voxels")
    void realMcaTranscodeFullParity(String dirName, int expectedDv) throws IOException {
        byte[] mcaBytes = loadFixture("/anvil/real/" + dirName + "/r.0.0.mca");
        for (boolean modeB : new boolean[] {false, true}) {

            byte[] lcaBytes = LcaRegionWriter.transcodeFromMca(mcaBytes, 0, 0, modeB);
            assertTrue(LcaRegionReader.INSTANCE.isChunkGenerated(lcaBytes, 0, 0));
            assertFalse(LcaRegionReader.INSTANCE.isChunkGenerated(lcaBytes, 1, 0));
            assertFalse(LcaRegionReader.INSTANCE.isChunkGenerated(lcaBytes, 31, 31));
            assertNull(LcaRegionReader.INSTANCE.readChunk(lcaBytes, 1, 0));
            assertEquals(1, LcaRegionReader.readGenerationToken(lcaBytes, 0, 0));
            assertEquals(-1, LcaRegionReader.readGenerationToken(lcaBytes, 1, 0));

            AnvilReader.ChunkEntry mcaEntry = AnvilReader.readChunkEntry(mcaBytes, 0, 0);
            AnvilReader.ChunkEntry lcaEntry = LcaRegionReader.INSTANCE.readChunk(lcaBytes, 0, 0);
            assertNotNull(mcaEntry);
            assertNotNull(lcaEntry);

            assertEquals(expectedDv, AnvilReader.getDataVersion(lcaEntry.root));
            assertEquals(AnvilReader.getDataVersion(mcaEntry.root), AnvilReader.getDataVersion(lcaEntry.root));
            assertEquals(mcaEntry.root.get("xPos"), lcaEntry.root.get("xPos"));
            assertEquals(mcaEntry.root.get("zPos"), lcaEntry.root.get("zPos"));

            Nbt.NbtList mcaSections = AnvilReader.getSections(mcaEntry.root);
            Nbt.NbtList lcaSections = AnvilReader.getSections(lcaEntry.root);
            assertEquals(mcaSections.items.size(), lcaSections.items.size(), "NBT sections list count parity");

            // Compare both NBT-derived AnvilChunkView and direct zero-NBT LcaRegionReader.readChunkView
            AnvilChunkView mcaView = AnvilReader.toView(mcaEntry.root);
            AnvilChunkView lcaNbtView = AnvilReader.toView(lcaEntry.root);
            AnvilChunkView lcaDirectView = LcaRegionReader.readChunkView(lcaBytes, 0, 0);

            assertNotNull(lcaDirectView);
            assertEquals(mcaView.minHeight(), lcaNbtView.minHeight());
            assertEquals(mcaView.minHeight(), lcaDirectView.minHeight());
            assertEquals(mcaView.sections().size(), lcaNbtView.sections().size());
            assertEquals(mcaView.sections().size(), lcaDirectView.sections().size());
            assertEquals(mcaView.getBiomesPresent(), lcaNbtView.getBiomesPresent());
            assertEquals(mcaView.getBiomesPresent(), lcaDirectView.getBiomesPresent());

            // Verify every single (x, y, z) voxel across all sections + single-section partial reader
            for (PaletteSection mcaSec : mcaView.sections()) {
                int sy = mcaSec.sectionY();
                PaletteSection partialSec = LcaRegionReader.readSection(lcaBytes, lcaBytes.length, 0, 0, sy);
                assertNotNull(partialSec, "Partial section read must not be null for sy=" + sy);
                for (int ly = 0; ly < 16; ly++) {
                    int worldY = (sy << 4) + ly;
                    for (int lz = 0; lz < 16; lz++) {
                        for (int lx = 0; lx < 16; lx++) {
                            String expectedBlock = mcaSec.blockIdAt(lx, ly, lz);
                            assertEquals(expectedBlock, partialSec.blockIdAt(lx, ly, lz),
                                    "Partial section mismatch at (" + lx + "," + worldY + "," + lz + ")");
                            assertEquals(expectedBlock, lcaDirectView.blockIdAt(lx, worldY, lz),
                                    "Direct ChunkView mismatch at (" + lx + "," + worldY + "," + lz + ")");
                            assertEquals(expectedBlock, lcaNbtView.blockIdAt(lx, worldY, lz),
                                    "NBT ChunkView mismatch at (" + lx + "," + worldY + "," + lz + ")");
                        }
                    }
                }
            }

            // Verify ColumnProbe and TerrainProbe parity
            ColumnProbe mcaProbe = AnvilReader.readColumnProbe(mcaBytes, 0, 0, -64, 319);
            ColumnProbe lcaProbe = LcaRegionReader.readColumnProbe(lcaBytes, 0, 0, -64, 319);
            ColumnProbe lcaTerrain = LcaRegionReader.readTerrainProbe(lcaBytes, lcaBytes.length, 0, 0, 20, 120);
            assertNotNull(mcaProbe);
            assertNotNull(lcaProbe);
            assertNotNull(lcaTerrain);
            assertEquals(mcaProbe.heightmapTopY(), lcaProbe.heightmapTopY());
            for (int y = -64; y <= 319; y++) {
                assertEquals(mcaProbe.blockAt(y), lcaProbe.blockAt(y), "ColumnProbe blockAt mismatch at y=" + y);
                assertEquals(mcaProbe.biomeAt(y), lcaProbe.biomeAt(y), "ColumnProbe biomeAt mismatch at y=" + y);
                if (y >= 20 && y <= 120) {
                    assertEquals(mcaProbe.blockAt(y), lcaTerrain.blockAt(y), "TerrainProbe blockAt mismatch at y=" + y);
                }
            }
        }
    }

    @Test
    @DisplayName("anvil-api-ADR-003 Section 3.1: Dynamically shape-able chained dictionary links overflow segments via nextSegmentLinkTrailer")
    void dynamicallyChainedDictionaryOverflowAndReopen() throws IOException {
        LcaRegionWriter.LcaLiveArchive archive =
                LcaRegionWriter.createEmptyLiveArchive(0, 0, -4, 24, 4671, false, 256);

        List<String> customStates = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            String state = "minecraft:custom_block_" + i + "[facing=north,waterlogged=false,power=" + (i % 16) + "]";
            customStates.add(state);
        }

        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", 4671);
        root.put("xPos", 3);
        root.put("zPos", 5);
        root.put("Status", "minecraft:full");

        LinkedHashMap<String, Object> sec = new LinkedHashMap<>();
        sec.put("Y", (byte) 4);
        List<Object> palItems = new ArrayList<>();
        for (String s : customStates) {
            palItems.add(LcaRegionReader.parseCanonicalStateToNbtCompound(s));
        }
        int[] yzx = new int[4096];
        for (int i = 0; i < 4096; i++) {
            yzx[i] = i % customStates.size();
        }
        LinkedHashMap<String, Object> bs = new LinkedHashMap<>();
        bs.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, palItems));
        bs.put("data", LcaSectionCodec.packBlockLongs(yzx, customStates.size()));
        sec.put("block_states", bs);
        root.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(sec)));

        archive.saveChunkCoW(3, 5, root);
        assertTrue(archive.dictionarySegmentCount() >= 3,
                "Expected chained dictionary to allocate >= 3 continuation segments, got " + archive.dictionarySegmentCount());

        byte[] serialized = archive.toByteArray();
        LcaRegionWriter.LcaLiveArchive reopened = LcaRegionWriter.openLiveArchive(serialized);
        assertEquals(archive.dictionarySegmentCount(), reopened.dictionarySegmentCount());
        assertEquals(archive.totalDictionaryStates(), reopened.totalDictionaryStates());

        AnvilReader.ChunkEntry decoded = LcaRegionReader.INSTANCE.readChunk(serialized, 3, 5);
        assertNotNull(decoded);
        AnvilChunkView view = AnvilReader.toView(decoded.root);
        for (int i = 0; i < 64; i++) {
            int lx = i & 15;
            int lz = (i >>> 4) & 15;
            String expectedBase = LcaRegionReader.stripBlockProperties(customStates.get(i % customStates.size()));
            assertEquals(expectedBase, view.blockIdAt(lx, 64, lz));
        }
    }

    @Test
    @DisplayName("anvil-api-ADR-003 Section 4.1: Inline uniform section skyLight2 4-state contract & globalDictId >= 8192 overflow fallback")
    void inlineUniformSkyLightStatesAndDictId8192OverflowFallback() throws IOException {
        LcaRegionWriter.LcaLiveArchive archive =
                LcaRegionWriter.createEmptyLiveArchive(0, 0, -4, 24, 4671, false, 4096);

        // Register 8,200 unique block states to cross the 13-bit (8192) inline uniform threshold
        String highDictBlock = "minecraft:high_dict_block_8195";
        int highId = -1;
        for (int i = 0; i < 8200; i++) {
            String s = (i == 8195) ? highDictBlock : ("minecraft:state_" + i);
            int id = archive.registerOrGetBlockStateId(s);
            if (i == 8195) highId = id;
        }
        assertTrue(highId >= 8192, "High dictionary ID must be >= 8192, got " + highId);
        assertTrue(archive.dictionarySegmentCount() <= LcaRegionReader.MAX_DICT_CHAIN_SEGMENTS,
                "Geometric segment growth must stay within MAX_DICT_CHAIN_SEGMENTS, got " + archive.dictionarySegmentCount());

        List<Object> sections = new ArrayList<>();
        sections.add(buildUniformSectionNbt(-4, "minecraft:stone", 0, 0));
        sections.add(buildUniformSectionNbt(-3, "minecraft:air", 15, 0));
        sections.add(buildUniformSectionNbt(-2, "minecraft:glowstone", 15, 15));
        sections.add(buildUniformSectionNbt(-1, "minecraft:lava", 0, 15));
        sections.add(buildUniformSectionNbt(0, highDictBlock, 0, 0));

        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", 4671);
        root.put("xPos", 0);
        root.put("zPos", 0);
        root.put("Status", "minecraft:full");
        root.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, sections));

        archive.saveChunkCoW(0, 0, root);
        byte[] lcaBytes = archive.toByteArray();

        AnvilChunkView view = LcaRegionReader.readChunkView(lcaBytes, 0, 0);
        assertNotNull(view);
        assertEquals("minecraft:stone", view.blockIdAt(0, -64, 0));
        assertEquals("minecraft:air", view.blockIdAt(0, -48, 0));
        assertEquals("minecraft:glowstone", view.blockIdAt(0, -32, 0));
        assertEquals("minecraft:lava", view.blockIdAt(0, -16, 0));
        assertEquals(highDictBlock, view.blockIdAt(0, 0, 0));

        AnvilReader.ChunkEntry entry = LcaRegionReader.INSTANCE.readChunk(lcaBytes, 0, 0);
        Nbt.NbtList decSecs = AnvilReader.getSections(entry.root);
        assertEquals(5, decSecs.items.size());
    }

    private static LinkedHashMap<String, Object> buildUniformSectionNbt(int sy, String blockName, int skyVal, int blkVal) {
        LinkedHashMap<String, Object> sec = new LinkedHashMap<>();
        sec.put("Y", (byte) sy);
        LinkedHashMap<String, Object> bs = new LinkedHashMap<>();
        LinkedHashMap<String, Object> palEntry = new LinkedHashMap<>();
        palEntry.put("Name", blockName);
        bs.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(palEntry)));
        sec.put("block_states", bs);
        if (skyVal > 0) {
            byte[] sky = new byte[2048];
            Arrays.fill(sky, (byte) ((skyVal << 4) | skyVal));
            sec.put("SkyLight", sky);
        }
        if (blkVal > 0) {
            byte[] blk = new byte[2048];
            Arrays.fill(blk, (byte) ((blkVal << 4) | blkVal));
            sec.put("BlockLight", blk);
        }
        return sec;
    }

    @Test
    @DisplayName("REQ-RTP-S-004: CBIT tripwires T1..T7 reject corrupted .lca headers, pointers, and section payloads")
    void cbitTripwiresRejectCorruptInputs() throws IOException {
        byte[] mcaBytes = loadFixture("/anvil/real/1_21_R1/r.0.0.mca");
        byte[] validLca = LcaRegionWriter.transcodeFromMca(mcaBytes, 0, 0, false);
        LcaRegionReader reader = LcaRegionReader.INSTANCE;
        assertEquals(0x0002, LcaRegionReader.LCA_FORMAT_VERSION);
        assertEquals(0x0002, LcaSectionCodec.readUint16BE(validLca, 0x08));

        // T1: Unsupported formatVersion (0x0000, legacy 0x0001, or future > 0x0002)
        byte[] badVersionZero = Arrays.copyOf(validLca, validLca.length);
        ByteBuffer.wrap(badVersionZero).putShort(0x08, (short) 0x0000);
        assertThrows(CorruptRegionEntryException.class, () -> reader.readChunk(badVersionZero, 0, 0));
        byte[] badVersionOne = Arrays.copyOf(validLca, validLca.length);
        ByteBuffer.wrap(badVersionOne).putShort(0x08, (short) 0x0001);
        assertThrows(CorruptRegionEntryException.class, () -> reader.readChunk(badVersionOne, 0, 0));
        byte[] badVersionFuture = Arrays.copyOf(validLca, validLca.length);
        ByteBuffer.wrap(badVersionFuture).putShort(0x08, (short) 0x0003);
        assertThrows(CorruptRegionEntryException.class, () -> reader.readChunk(badVersionFuture, 0, 0));

        // T1: Bad superblock magic
        byte[] badMagic = Arrays.copyOf(validLca, validLca.length);
        badMagic[0] ^= (byte) 0xFF;
        assertThrows(CorruptRegionEntryException.class, () -> reader.readChunk(badMagic, 0, 0));
        assertFalse(reader.isChunkGenerated(badMagic, 0, 0));

        // T1: Bad footer canary
        byte[] badFooter = Arrays.copyOf(validLca, validLca.length);
        badFooter[badFooter.length - 1] ^= (byte) 0xFF;
        assertThrows(CorruptRegionEntryException.class, () -> reader.readChunk(badFooter, 0, 0));
        assertFalse(reader.isChunkGenerated(badFooter, 0, 0));

        // T2: Superblock checksum mismatch
        byte[] badChecksum = Arrays.copyOf(validLca, validLca.length);
        badChecksum[0x0C] ^= 0x01;
        assertThrows(CorruptRegionEntryException.class, () -> reader.readChunk(badChecksum, 0, 0));

        // T3: Out-of-bounds chunk slab pointer
        byte[] badPtr = Arrays.copyOf(validLca, validLca.length);
        long hugePtr = LcaRegionWriter.packChunkPointer64(999_999L, false, 1024, 1);
        ByteBuffer.wrap(badPtr).putLong(LcaRegionReader.POINTER_DIRECTORY_OFFSET, hugePtr);
        assertThrows(CorruptRegionEntryException.class, () -> reader.readChunk(badPtr, 0, 0));
        assertFalse(reader.isChunkGenerated(badPtr, 0, 0));

        // T5: Cyclic dictionary continuation link
        byte[] cyclicDict = Arrays.copyOf(validLca, validLca.length);
        int rootDictCap = ByteBuffer.wrap(cyclicDict).getInt(0x20);
        int trailerPos = LcaRegionReader.DICT_ROOT_OFFSET + rootDictCap - 8;
        long loopForward = (1L << 32) | (1L << 8) | 1L;
        long loopBackward = (((long) -1 & 0xFFFF_FFFFL) << 32) | (16L << 8) | 1L;
        ByteBuffer.wrap(cyclicDict).putLong(trailerPos, loopForward);
        int secondSegTrailer = (LcaRegionReader.DICT_ROOT_OFFSET & ~0xFF) + 256 + 256 - 8;
        ByteBuffer.wrap(cyclicDict).putLong(secondSegTrailer, loopBackward);
        assertThrows(CorruptRegionEntryException.class, () -> reader.readChunk(cyclicDict, 0, 0));
    }

    @Test
    @DisplayName("anvil-api-ADR-003 Section 7.2: Multi-tier bit-rot & offset-variation self-healing (SEC-DED, Slab Reverse-Scan, Octree Syndrome, CoW Rollback)")
    void multiTierBitRotAndOffsetVariationSelfHealing() throws IOException {
        byte[] mcaBytes = loadFixture("/anvil/real/1_21_R1/r.0.0.mca");
        AnvilReader.ChunkEntry origin = AnvilReader.readChunkEntry(mcaBytes, 0, 0);
        assertNotNull(origin);

        LcaRegionWriter.LcaLiveArchive archive =
                LcaRegionWriter.createEmptyLiveArchive(0, 0, -4, 24, 4671, false, 4096);
        LinkedHashMap<String, Object> c00 = new LinkedHashMap<>(origin.root);
        c00.put("xPos", 0);
        c00.put("zPos", 0);
        LinkedHashMap<String, Object> c10 = new LinkedHashMap<>(origin.root);
        c10.put("xPos", 1);
        c10.put("zPos", 0);
        archive.saveChunkInitial(0, 0, c00);
        archive.saveChunkInitial(1, 0, c10);

        byte[] cleanLca = archive.toByteArray();
        AnvilChunkView baselineView = LcaRegionReader.readChunkView(cleanLca, 0, 0);
        assertNotNull(baselineView);

        // Tier 1: Extended Hamming (72, 64) SEC-DED heals a single-bit flip in the 64-bit pointer directory
        for (int bit = 0; bit < 64; bit += 9) {
            byte[] singleBitRot = Arrays.copyOf(cleanLca, cleanLca.length);
            int ptrOff = LcaRegionReader.POINTER_DIRECTORY_OFFSET;
            long origPtr = ByteBuffer.wrap(singleBitRot).getLong(ptrOff);
            ByteBuffer.wrap(singleBitRot).putLong(ptrOff, origPtr ^ (1L << bit));

            LcaRegionReader.RecoveryReport report = LcaRegionReader.healRegionBitRot(singleBitRot);
            assertEquals(1, report.secDedHealedPointers(), "SEC-DED must heal 1-bit flip at bit " + bit);
            assertFalse(report.diagnostics().isEmpty());
            assertEquals(origPtr, ByteBuffer.wrap(singleBitRot).getLong(ptrOff));
            AnvilChunkView healedView = LcaRegionReader.readChunkView(singleBitRot, singleBitRot.length, 0, 0);
            assertEquals(baselineView.blockIdAt(8, 64, 8), healedView.blockIdAt(8, 64, 8));
        }

        // Tier 1b: 1-bit flip inside the stored syndrome byte itself (data word already clean)
        byte[] synBitRot = Arrays.copyOf(cleanLca, cleanLca.length);
        int secDedOff = (int) (ByteBuffer.wrap(synBitRot).getLong(0x28) & 0xFFFF_FFFFL);
        synBitRot[secDedOff] ^= 0x01;
        LcaRegionReader.RecoveryReport synReport = LcaRegionReader.healRegionBitRot(synBitRot);
        assertEquals(0, synReport.secDedHealedPointers());

        // Tier 1c -> Tier 2: Double-bit flip (DED) in pointer word detected by SEC-DED and healed by Slab Reverse-Scan
        byte[] doubleBitRot = Arrays.copyOf(cleanLca, cleanLca.length);
        long origPtr0 = ByteBuffer.wrap(doubleBitRot).getLong(LcaRegionReader.POINTER_DIRECTORY_OFFSET);
        ByteBuffer.wrap(doubleBitRot).putLong(LcaRegionReader.POINTER_DIRECTORY_OFFSET, origPtr0 ^ 0b11L);
        LcaRegionReader.RecoveryReport dedReport = LcaRegionReader.healRegionBitRot(doubleBitRot);
        assertEquals(1, dedReport.slabScanReconstructedPointers());
        assertEquals(origPtr0, ByteBuffer.wrap(doubleBitRot).getLong(LcaRegionReader.POINTER_DIRECTORY_OFFSET));

        // Tier 2: Offset variation (chunk (0,0) pointer overwritten to point to chunk (1,0)'s slab or wiped)
        byte[] offsetVariation = Arrays.copyOf(cleanLca, cleanLca.length);
        long ptr10 = ByteBuffer.wrap(offsetVariation).getLong(LcaRegionReader.POINTER_DIRECTORY_OFFSET + 8);
        // Point chunk (0,0) at chunk (1,0)'s slab -> CBIT T3b detects self-describing chunkIdx mismatch!
        ByteBuffer.wrap(offsetVariation).putLong(LcaRegionReader.POINTER_DIRECTORY_OFFSET, ptr10);
        assertThrows(CorruptRegionEntryException.class, () -> LcaRegionReader.readChunkView(offsetVariation, 0, 0));
        // Update syndrome byte to match ptr10 so Tier 1 passes and Tier 1's CBIT T3b self-describing check triggers Tier 2
        offsetVariation[secDedOff] = offsetVariation[secDedOff + 1];

        // Now run healRegionBitRot -> Tier-2 Slab Pool Reverse-Scan finds self-describing 0x5800_0000 | (0 << 16) slab!
        LcaRegionReader.RecoveryReport scanReport = LcaRegionReader.healRegionBitRot(offsetVariation);
        assertEquals(1, scanReport.slabScanReconstructedPointers());
        AnvilChunkView recoveredFromSlabScan = LcaRegionReader.readChunkView(offsetVariation, 0, 0);
        assertNotNull(recoveredFromSlabScan);
        assertEquals(baselineView.blockIdAt(8, 64, 8), recoveredFromSlabScan.blockIdAt(8, 64, 8));

        // Tier 3: Section-level octreeOccupancyMask64 syndrome mismatch (CBIT T6b) + dominant palette[0] quarantine
        byte[] sectionBitRot = Arrays.copyOf(cleanLca, cleanLca.length);
        long ptr00 = ByteBuffer.wrap(sectionBitRot).getLong(LcaRegionReader.POINTER_DIRECTORY_OFFSET);
        int frameStart = (int) ((ptr00 >>> 29) << LcaRegionReader.SLAB_SHIFT);
        int chunkFlags00 = sectionBitRot[frameStart + 21] & 0xFF;
        int unitShift00 = ((chunkFlags00 & 4) != 0) ? 2 : 0;
        // Find first non-uniform section with an explicit octreeOccupancyMask64 (blockMode == 1 or 2) in chunk (0,0)
        int targetSecIdx = -1;
        int targetRelStart = -1;
        int sectionDirEnd = 24 + (24 << 1);
        for (int i = 0; i < 24; i++) {
            int entry16 = ByteBuffer.wrap(sectionBitRot).getShort(frameStart + 24 + (i << 1)) & 0xFFFF;
            if (entry16 != 0 && (entry16 & 0x8000) == 0) {
                int relStart = sectionDirEnd + ((entry16 - 1) << unitShift00);
                int secHdr = sectionBitRot[frameStart + relStart] & 0xFF;
                int bMode = (secHdr >>> 4) & 3;
                if (bMode == LcaSectionCodec.BLOCK_MODE_DELTA_ROARING
                        || bMode == LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK) {
                    targetSecIdx = i;
                    targetRelStart = relStart;
                    break;
                }
            }
        }
        assertTrue(targetSecIdx >= 0);
        // Flip octreeOccupancyMask64 at (frameStart + targetRelStart + 1..8) to trigger CBIT T6b syndrome mismatch
        sectionBitRot[frameStart + targetRelStart + 1] ^= (byte) 0xFF;
        assertThrows(CorruptRegionEntryException.class, () -> LcaRegionReader.readChunkView(sectionBitRot, 0, 0));

        LcaRegionReader.RecoveryReport secReport = new LcaRegionReader.RecoveryReport();
        AnvilChunkView partialHealed = LcaRegionReader.readChunkViewWithRecovery(sectionBitRot, 0, 0, secReport);
        assertNotNull(partialHealed);
        assertEquals(1, secReport.quarantinedSections(), "Only the 1 corrupted section should be quarantined");
        assertEquals(baselineView.sections().size(), partialHealed.sections().size(),
                "All other 23 sections remain intact and the damaged section falls back to dominant palette[0]");

        // Tier 4: Copy-on-Write (CoW) prior-generation slab rollback
        c00.put("InhabitedTime", 999L);
        archive.saveChunkCoW(0, 0, c00);
        assertTrue(archive.rollbackCorruptChunkToPriorCoW(0, 0));
        AnvilChunkView rolledBack = LcaRegionReader.readChunkView(archive.toByteArray(), 0, 0);
        assertEquals(baselineView.blockIdAt(8, 64, 8), rolledBack.blockIdAt(8, 64, 8));
    }

    @Test
    @DisplayName("anvil-api-ADR-003 Sections 4-5: FlatBitPack ceiling, TAG_BITMAP, TAG_DELTA_ARRAY escape, light modes, curves, Mode B readSection, and slab coalescing")
    void exhaustiveBranchAndContainerModeCoverage() throws IOException {
        LcaRegionWriter.LcaLiveArchive archive =
                LcaRegionWriter.createEmptyLiveArchive(0, 0, -4, 24, 4671, false, 4096);

        // 1. Section Y=-4: High-entropy 2-state checkerboard (> 256 runs -> TAG_BITMAP + FlatBitPack ceiling!)
        LinkedHashMap<String, Object> secChecker = new LinkedHashMap<>();
        secChecker.put("Y", (byte) -4);
        LinkedHashMap<String, Object> bsChecker = new LinkedHashMap<>();
        bsChecker.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(
                LcaRegionReader.parseCanonicalStateToNbtCompound("minecraft:stone"),
                LcaRegionReader.parseCanonicalStateToNbtCompound("minecraft:cobblestone"),
                LcaRegionReader.parseCanonicalStateToNbtCompound("minecraft:granite"),
                LcaRegionReader.parseCanonicalStateToNbtCompound("minecraft:diorite"))));
        int[] yzxChecker = new int[4096];
        for (int i = 0; i < 4096; i++) {
            yzxChecker[i] = (i * 7 + (i >>> 4) * 3 + (i >>> 8) * 5) & 3;
        }
        bsChecker.put("data", LcaSectionCodec.packBlockLongs(yzxChecker, 4));
        secChecker.put("block_states", bsChecker);
        // Constant non-0/non-15 SkyLight (7) and BlockLight (9) -> LIGHT_MODE_CONST
        byte[] constSky7 = new byte[2048];
        Arrays.fill(constSky7, (byte) 0x77);
        byte[] constBlk9 = new byte[2048];
        Arrays.fill(constBlk9, (byte) 0x99);
        secChecker.put("SkyLight", constSky7);
        secChecker.put("BlockLight", constBlk9);

        // 2. Section Y=-3: Sparse singletons with gap >= 255 -> TAG_DELTA_ARRAY with 0xFF escape + TAG_BITMAP for state 2
        LinkedHashMap<String, Object> secSparse = new LinkedHashMap<>();
        secSparse.put("Y", (byte) -3);
        LinkedHashMap<String, Object> bsSparse = new LinkedHashMap<>();
        bsSparse.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(
                LcaRegionReader.parseCanonicalStateToNbtCompound("minecraft:stone"),
                LcaRegionReader.parseCanonicalStateToNbtCompound("minecraft:emerald_ore"),
                LcaRegionReader.parseCanonicalStateToNbtCompound("minecraft:gold_ore"))));
        int[] yzxSparse = new int[4096];
        // State 1 (emerald_ore): 4 isolated singletons separated by huge gaps in curve order -> TAG_DELTA_ARRAY with 0xFF escape
        yzxSparse[0] = 1;
        yzxSparse[1024] = 1;
        yzxSparse[2048] = 1;
        yzxSparse[3500] = 1;
        // State 2 (gold_ore): 300 isolated singletons -> > 256 runs -> TAG_BITMAP (while total roaring < flatBytes)
        for (int r = 0; r < 300; r++) {
            int pos = 10 + r * 3;
            if (pos < 4096 && yzxSparse[pos] == 0) {
                yzxSparse[pos] = 2;
            }
        }
        bsSparse.put("data", LcaSectionCodec.packBlockLongs(yzxSparse, 3));
        secSparse.put("block_states", bsSparse);
        // High-entropy incompressible light -> LIGHT_MODE_RAW fallback (>= 2048 bytes in Gray planes)
        byte[] noisySky = new byte[2048];
        byte[] noisyBlk = new byte[2048];
        Random lightRnd = new Random(99);
        lightRnd.nextBytes(noisySky);
        lightRnd.nextBytes(noisyBlk);
        secSparse.put("SkyLight", noisySky);
        secSparse.put("BlockLight", noisyBlk);

        // 3. Section Y=-2: Vertical columns -> CURVE_XZY + multi-biome RLE (3 biomes) + long delta-run escape (>= 15)
        LinkedHashMap<String, Object> secVertical = new LinkedHashMap<>();
        secVertical.put("Y", (byte) -2);
        LinkedHashMap<String, Object> bsVertical = new LinkedHashMap<>();
        bsVertical.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(
                LcaRegionReader.parseCanonicalStateToNbtCompound("minecraft:air"),
                LcaRegionReader.parseCanonicalStateToNbtCompound("custommod:kelp_column[age=12]"),
                LcaRegionReader.parseCanonicalStateToNbtCompound("minecraft:magma_block[custom=1]"))));
        int[] yzxVertical = new int[4096];
        for (int y = 0; y < 16; y++) {
            yzxVertical[(y << 8) | (2 << 4) | 2] = 1;
            yzxVertical[(y << 8) | (10 << 4) | 10] = 1;
        }
        // State 2: short 2-voxel runs separated by medium/large gaps -> triggers 6|2 run mode (gap < 63, gap >= 63 < 303, gap >= 303, len >= 4)
        int[] curveToYzxXzy = LcaSectionCodec.CURVE_TO_YZX[LcaSectionCodec.CURVE_XZY];
        yzxVertical[curveToYzxXzy[20]] = 2;
        yzxVertical[curveToYzxXzy[21]] = 2;
        yzxVertical[curveToYzxXzy[60]] = 2;
        yzxVertical[curveToYzxXzy[61]] = 2;
        yzxVertical[curveToYzxXzy[150]] = 2;
        yzxVertical[curveToYzxXzy[151]] = 2;
        for (int k = 0; k < 5; k++) {
            yzxVertical[curveToYzxXzy[600 + k]] = 2;
        }
        bsVertical.put("data", LcaSectionCodec.packBlockLongs(yzxVertical, 3));
        secVertical.put("block_states", bsVertical);

        LinkedHashMap<String, Object> biomeMap = new LinkedHashMap<>();
        biomeMap.put("palette", new Nbt.NbtList(Nbt.TAG_STRING, List.of(
                "minecraft:warm_ocean", "minecraft:custom_vanilla_biome", "custommod:mystic_abyss",
                "minecraft:plains", "minecraft:desert", "minecraft:forest",
                "minecraft:taiga", "minecraft:swamp", "minecraft:savanna")));
        biomeMap.put("data", new long[] {0x0807060504030201L, 0x0102030405060708L, 0L, 0L});
        secVertical.put("biomes", biomeMap);

        // 4. Section Y=-1: Octant-compacted H_3, TAG_SINGLETON subTags 1/2/3, 5|3 & 7|1 TAG_DELTA_RUN, Word-Masked TAG_BITMAP, and single-run Gray planes
        LinkedHashMap<String, Object> secSubModes = new LinkedHashMap<>();
        secSubModes.put("Y", (byte) -1);
        LinkedHashMap<String, Object> bsSubModes = new LinkedHashMap<>();
        bsSubModes.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(
                LcaRegionReader.parseCanonicalStateToNbtCompound("minecraft:stone"),
                LcaRegionReader.parseCanonicalStateToNbtCompound("minecraft:copper_ore"),
                LcaRegionReader.parseCanonicalStateToNbtCompound("minecraft:lapis_ore"),
                LcaRegionReader.parseCanonicalStateToNbtCompound("minecraft:redstone_ore"),
                LcaRegionReader.parseCanonicalStateToNbtCompound("minecraft:coal_ore"))));
        int[] yzxSub = new int[4096];
        int[] h3ToYzx = LcaSectionCodec.CURVE_TO_YZX[LcaSectionCodec.CURVE_H3];
        // State 1: single contiguous run of 300 voxels -> TAG_SINGLETON subTag 2 (2B length)
        for (int k = 0; k < 300; k++) {
            yzxSub[h3ToYzx[k]] = 1;
        }
        // State 2: single contiguous run of 12 voxels -> TAG_SINGLETON subTag 1 (1B length)
        for (int k = 320; k < 332; k++) {
            yzxSub[h3ToYzx[k]] = 2;
        }
        // State 3: two runs of length 5 and 6 separated by gap 300 -> TAG_SINGLETON subTag 3 (two-run container)
        for (int k = 350; k < 355; k++) {
            yzxSub[h3ToYzx[k]] = 3;
        }
        for (int k = 655; k < 661; k++) {
            yzxSub[h3ToYzx[k]] = 3;
        }
        // State 4: dense alternating pattern within 2 octants -> Word-Masked TAG_BITMAP
        for (int k = 704; k < 832; k += 2) {
            yzxSub[h3ToYzx[k]] = 4;
        }
        bsSubModes.put("data", LcaSectionCodec.packBlockLongs(yzxSub, 5));
        secSubModes.put("block_states", bsSubModes);
        byte[] singleRunSky = new byte[2048];
        // Prefix run on plane 0, interior run on plane 1, tail run on plane 3
        for (int i = 0; i < 256; i++) singleRunSky[i] = 0x11;
        for (int i = 512; i < 768; i++) singleRunSky[i] = 0x33;
        for (int i = 1536; i < 2048; i++) singleRunSky[i] = (byte) 0xFF;
        secSubModes.put("SkyLight", singleRunSky);

        // 5. Boundary light-only sections at Y=-5 and Y=20
        LinkedHashMap<String, Object> secLowBound = new LinkedHashMap<>();
        secLowBound.put("Y", (byte) -5);
        secLowBound.put("SkyLight", new byte[2048]);
        LinkedHashMap<String, Object> secHighBound = new LinkedHashMap<>();
        secHighBound.put("Y", (byte) 20);
        byte[] fullSky = new byte[2048];
        Arrays.fill(fullSky, (byte) 0xFF);
        secHighBound.put("SkyLight", fullSky);

        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", 4671);
        root.put("xPos", 1);
        root.put("zPos", 1);
        root.put("Status", "full");
        LinkedHashMap<String, Object> hmCustom = new LinkedHashMap<>();
        hmCustom.put("MOTION_BLOCKING_NO_LEAVES", new long[37]);
        long[] customHmLongs = new long[37];
        customHmLongs[0] = 5L | (200L << 9);
        hmCustom.put("CUSTOM_MOD_HM", customHmLongs);
        root.put("Heightmaps", hmCustom);
        root.put("CustomSidecarTag", "test_value");
        root.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND,
                List.of(secLowBound, secChecker, secSparse, secVertical, secSubModes, secHighBound)));

        archive.saveChunkInitial(1, 1, root);
        archive.saveChunkInitial(2, 1, root);
        archive.saveChunkInitial(3, 1, root);
        // Trigger CoW saves on chunks (1,1), (2,1), (3,1) to exercise prev+next free-slab coalescing
        for (int step = 0; step < 8; step++) {
            archive.saveChunkCoW(1 + (step % 3), 1, root);
        }

        byte[] lcaBytes = archive.toByteArray();
        LcaRegionWriter.LcaLiveArchive reopened = LcaRegionWriter.openLiveArchive(lcaBytes);
        reopened.saveChunkCoW(2, 1, root);
        byte[] finalBytes = reopened.toByteArray();

        AnvilReader.ChunkEntry decEntry = LcaRegionReader.INSTANCE.readChunk(finalBytes, 1, 1);
        assertNotNull(decEntry);
        assertEquals("full", decEntry.root.get("Status"));
        AnvilChunkView view = LcaRegionReader.readChunkView(finalBytes, 1, 1);
        assertNotNull(view);

        for (int i = 0; i < 4096; i++) {
            int lx = i & 15;
            int lz = (i >>> 4) & 15;
            int ly = (i >>> 8) & 15;
            String expectedChecker = switch (yzxChecker[i]) {
                case 0 -> "minecraft:stone";
                case 1 -> "minecraft:cobblestone";
                case 2 -> "minecraft:granite";
                default -> "minecraft:diorite";
            };
            assertEquals(expectedChecker, view.blockIdAt(lx, -64 + ly, lz));
        }
        assertEquals("minecraft:emerald_ore", view.blockIdAt(0, -48, 0));
        assertEquals("custommod:kelp_column", view.blockIdAt(2, -32 + 7, 2));

        // Also test Mode B readSection for both uniform and non-uniform sections + incomplete proto-chunk probe
        {
            LcaRegionWriter.LcaLiveArchive modeB =
                    LcaRegionWriter.createEmptyLiveArchive(0, 0, -4, 24, 4671, true, 1024);
            LinkedHashMap<String, Object> protoRoot = new LinkedHashMap<>(root);
            protoRoot.put("Status", "minecraft:noise");
            modeB.saveChunkInitial(1, 1, root);
            modeB.saveChunkInitial(2, 2, protoRoot);
            byte[] modeBBytes = modeB.toByteArray();

            PaletteSection secModeB = LcaRegionReader.readSection(modeBBytes, modeBBytes.length, 1, 1, -4);
            assertNotNull(secModeB);
            assertNull(LcaRegionReader.readSection(modeBBytes, modeBBytes.length, 1, 1, 10));
            assertNull(LcaRegionReader.readSection(modeBBytes, modeBBytes.length, 1, 1, 99));
            assertNull(LcaRegionReader.readColumnProbe(modeBBytes, 2, 2, -64, 319));
            assertNull(LcaRegionReader.readTerrainProbe(modeBBytes, modeBBytes.length, 2, 2, -64, 319));
        }

        // Verify coordinate and window argument guards
        assertThrows(IllegalArgumentException.class, () -> LcaRegionReader.INSTANCE.readChunk(finalBytes, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> LcaRegionReader.INSTANCE.readChunk(finalBytes, 0, 32));
        assertThrows(IllegalArgumentException.class, () -> LcaRegionReader.readColumnProbe(finalBytes, 1, 1, 100, 20));
        assertThrows(IllegalArgumentException.class, () -> LcaRegionReader.readTerrainProbe(finalBytes, finalBytes.length, 1, 1, 100, 20));

        // Verify status ordinal table round-trip
        for (String status : List.of(
                "minecraft:full", "full", "minecraft:empty", "minecraft:structure_starts",
                "minecraft:structure_references", "minecraft:biomes", "minecraft:noise",
                "minecraft:surface", "minecraft:carvers", "minecraft:features",
                "minecraft:initialize_light", "minecraft:light", "minecraft:spawn")) {
            int ord = LcaRegionReader.encodeStatusOrdinal(status);
            assertNotNull(LcaRegionReader.decodeStatusOrdinal(ord));
        }
    }

    @Test
    @DisplayName("anvil-api-ADR-003 Sections 2.3 & 7: 10,000 PaperMC-style dirty chunk saves + concurrent lock-free CBIT T8 readers")
    void tenThousandLiveCoWSavesWithConcurrentReaders() throws Exception {
        byte[] mcaBytes = loadFixture("/anvil/real/1_21_R1/r.0.0.mca");
        AnvilReader.ChunkEntry origin = AnvilReader.readChunkEntry(mcaBytes, 0, 0);
        assertNotNull(origin);

        LcaRegionWriter.LcaLiveArchive archive =
                LcaRegionWriter.createEmptyLiveArchive(0, 0, -4, 24, 4671, false, 4096);
        for (int i = 0; i < 64; i++) {
            int rx = i & 7;
            int rz = (i >>> 3) & 7;
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>(origin.root);
            copy.put("xPos", rx);
            copy.put("zPos", rz);
            archive.saveChunkInitial(rx, rz, copy);
        }
        // Pre-seed 256 sub-slabs (64 KiB) in the TreeMap free-slab pool for the 4-epoch CoW grace ring + 4 hazard-pinned readers
        archive.reserveCoWHeadroomSlabs(256);
        int initialBytes = archive.activeByteLength();

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger totalReads = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch readersStarted = new CountDownLatch(4);
        List<Future<?>> readerFutures = new ArrayList<>();

        for (int t = 0; t < 4; t++) {
            final int threadId = t;
            readerFutures.add(pool.submit(() -> {
                readersStarted.countDown();
                Random rnd = new Random(1000 + threadId);
                while (running.get()) {
                    int idx = rnd.nextInt(64);
                    int rx = idx & 7;
                    int rz = (idx >>> 3) & 7;
                    byte[] buf = archive.liveBuffer();
                    int len = archive.activeByteLength();
                    if (len <= buf.length) {
                        PaletteSection sec = LcaRegionReader.readSection(buf, len, rx, rz, 4);
                        assertNotNull(sec);
                        assertNotNull(sec.blockIdAt(8, 8, 8));
                        totalReads.incrementAndGet();
                    }
                }
                return null;
            }));
        }

        readersStarted.await();
        Random saveRnd = new Random(42);
        long saveStartNs = System.nanoTime();
        for (int save = 0; save < 10_000; save++) {
            int idx = saveRnd.nextInt(64);
            int rx = idx & 7;
            int rz = (idx >>> 3) & 7;
            LinkedHashMap<String, Object> mutated = new LinkedHashMap<>(origin.root);
            mutated.put("xPos", rx);
            mutated.put("zPos", rz);
            mutated.put("LastUpdate", (long) save);
            mutated.put("InhabitedTime", (long) (save * 20));
            archive.saveChunkCoW(rx, rz, mutated);
        }
        long totalSaveNs = System.nanoTime() - saveStartNs;
        running.set(false);
        for (Future<?> f : readerFutures) {
            f.get();
        }
        pool.shutdownNow();

        int finalBytes = archive.activeByteLength();
        double reuseRatio = archive.slabReuseRatio();
        double avgSaveUs = (totalSaveNs / 1000.0) / 10_000.0;

        System.out.printf(Locale.ROOT,
                "%n[LCA-CoW-10K] 10,000 PaperMC-style dirty saves: avgSave=%.1f us | slabReuse=%.2f%% (%d/%d) | initial=%d B -> final=%d B | concurrentLockFreeReads=%d (0 torn reads)%n",
                avgSaveUs, reuseRatio * 100.0, archive.reusedSlabSaves(), archive.totalSaves(),
                initialBytes, finalBytes, totalReads.get());

        assertTrue(reuseRatio >= 0.95,
                "Expected >= 95% 256B sub-slab reuse across 10,000 saves, got " + (reuseRatio * 100.0) + "%");
        assertTrue(finalBytes <= initialBytes + 32_768,
                "CoW slab pool must not bloat across 10,000 saves: initial=" + initialBytes + " final=" + finalBytes);
        assertTrue(totalReads.get() > 100, "Concurrent readers must have completed lock-free reads during saves");
    }

    @Test
    @DisplayName("anvil-api-ADR-003 Empirical Head-to-Head Benchmark: .mca vs .linear vs .lca (Full Region & Sub-Chunk Probes)")
    void empiricalHeadToHeadBenchmarkMcaLinearLca() throws Exception {
        Path mountainMcaPath = Paths.get("../../resource/LeafRTP/rtp-core/src/test/resources/fixtures/regions/overworld_mountain_r.0.0.mca");
        if (!Files.isRegularFile(mountainMcaPath)) {
            mountainMcaPath = Paths.get("resource/LeafRTP/rtp-core/src/test/resources/fixtures/regions/overworld_mountain_r.0.0.mca");
        }

        byte[] mcaBytes;
        String datasetLabel;
        if (Files.isRegularFile(mountainMcaPath)) {
            mcaBytes = Files.readAllBytes(mountainMcaPath);
            datasetLabel = "overworld_mountain_r.0.0.mca (multi-chunk)";
        } else {
            mcaBytes = loadFixture("/anvil/real/1_21_R1/r.0.0.mca");
            datasetLabel = "1_21_R1/r.0.0.mca";
        }

        List<int[]> coords = new ArrayList<>();
        List<LinkedHashMap<String, Object>> chunkRoots = new ArrayList<>();
        for (int rz = 0; rz < 32 && coords.size() < 64; rz++) {
            for (int rx = 0; rx < 32 && coords.size() < 64; rx++) {
                if (AnvilReader.INSTANCE.isChunkGenerated(mcaBytes, rx, rz)) {
                    AnvilReader.ChunkEntry e = AnvilReader.readChunkEntry(mcaBytes, rx, rz);
                    if (e != null && e.root != null) {
                        coords.add(new int[] {rx, rz});
                        chunkRoots.add(e.root);
                    }
                }
            }
        }
        assertFalse(coords.isEmpty(), "Benchmark fixture must contain at least one generated chunk");
        if (coords.size() < 64) {
            expandSingleChunkFixtureTo64Chunks(coords, chunkRoots);
            datasetLabel = datasetLabel + " (expanded 64-chunk mountain fixture)";
        }

        runEmpiricalBenchmarkOnRoots(datasetLabel, coords, chunkRoots, 600, 500);

        // Also verify that when `overworld_mountain_r.0.0.mca` is absent (such as in standalone downstream SDK exports
        // that only ship `/anvil/real/1_21_R1/r.0.0.mca`), the 64-chunk fallback expansion satisfies all size and latency invariants.
        if (Files.isRegularFile(mountainMcaPath)) {
            byte[] fallbackMcaRaw = loadFixture("/anvil/real/1_21_R1/r.0.0.mca");
            AnvilReader.ChunkEntry fallbackOrigin = AnvilReader.readChunkEntry(fallbackMcaRaw, 0, 0);
            assertNotNull(fallbackOrigin);
            List<int[]> fbCoords = new ArrayList<>();
            List<LinkedHashMap<String, Object>> fbRoots = new ArrayList<>();
            fbCoords.add(new int[] {0, 0});
            fbRoots.add(fallbackOrigin.root);
            expandSingleChunkFixtureTo64Chunks(fbCoords, fbRoots);
            assertEquals(64, fbCoords.size());
            runEmpiricalBenchmarkOnRoots("1_21_R1/r.0.0.mca (expanded 64-chunk mountain fixture)", fbCoords, fbRoots, 200, 200);
        }
    }

    private static void runEmpiricalBenchmarkOnRoots(
            String datasetLabel,
            List<int[]> coords,
            List<LinkedHashMap<String, Object>> chunkRoots,
            int warmupIters,
            int measureIters)
            throws Exception {
        byte[] benchMca = buildMcaFromRoots(coords, chunkRoots);
        byte[] benchLinear = buildLinearFromRoots(coords, chunkRoots);
        LcaRegionWriter.LcaLiveArchive lcaModeAArchive =
                LcaRegionWriter.createEmptyLiveArchive(0, 0, -4, 24, AnvilReader.getDataVersion(chunkRoots.get(0)), false);
        LcaRegionWriter.LcaLiveArchive lcaModeBArchive =
                LcaRegionWriter.createEmptyLiveArchive(0, 0, -4, 24, AnvilReader.getDataVersion(chunkRoots.get(0)), true);
        for (int i = 0; i < coords.size(); i++) {
            int[] c = coords.get(i);
            lcaModeAArchive.saveChunkInitial(c[0], c[1], chunkRoots.get(i));
            lcaModeBArchive.saveChunkInitial(c[0], c[1], chunkRoots.get(i));
        }
        byte[] benchLcaA = lcaModeAArchive.toByteArray();
        byte[] benchLcaB = lcaModeBArchive.toByteArray();

        for (int i = 0; i < Math.min(8, coords.size()); i++) {
            int rx = coords.get(i)[0];
            int rz = coords.get(i)[1];
            AnvilChunkView vMca = AnvilReader.readChunkView(benchMca, rx, rz);
            AnvilChunkView vLin = AnvilReader.toView(LinearRegionReader.INSTANCE.readChunk(benchLinear, rx, rz).root);
            AnvilChunkView vLcaA = LcaRegionReader.readChunkView(benchLcaA, rx, rz);
            AnvilChunkView vLcaB = LcaRegionReader.readChunkView(benchLcaB, rx, rz);
            for (int y = -64; y < 200; y += 16) {
                assertEquals(vMca.blockIdAt(8, y, 8), vLin.blockIdAt(8, y, 8));
                assertEquals(vMca.blockIdAt(8, y, 8), vLcaA.blockIdAt(8, y, 8));
                assertEquals(vMca.blockIdAt(8, y, 8), vLcaB.blockIdAt(8, y, 8));
            }
        }

        long sink = 0;

        for (int i = 0; i < warmupIters; i++) {
            int idx = i % coords.size();
            int[] c = coords.get(idx);
            sink += AnvilReader.readChunkView(benchMca, c[0], c[1]).sections().size();
            sink += AnvilReader.toView(LinearRegionReader.INSTANCE.readChunk(benchLinear, c[0], c[1]).root).sections().size();
            sink += LcaRegionReader.readChunkView(benchLcaA, c[0], c[1]).sections().size();
            sink += LcaRegionReader.readChunkView(benchLcaB, c[0], c[1]).sections().size();
            sink += LcaRegionReader.readColumnProbe(benchLcaA, c[0], c[1], 48, 112).sections().size();
            sink += LcaRegionReader.readSection(benchLcaA, benchLcaA.length, c[0], c[1], -4).palette().size();
            sink += LcaRegionReader.readSection(benchLcaA, benchLcaA.length, c[0], c[1], 4).palette().size();
            sink += lcaModeAArchive.saveChunkCoW(c[0], c[1], chunkRoots.get(idx));
            if ((i & 7) == 0) {
                byte[] warmNbt = Nbt.writeNamedRoot("", chunkRoots.get(idx));
                ByteArrayOutputStream warmComp = new ByteArrayOutputStream(4096);
                Deflater warmDef = new Deflater(Deflater.DEFAULT_COMPRESSION);
                try (DeflaterOutputStream dos = new DeflaterOutputStream(warmComp, warmDef)) {
                    dos.write(warmNbt);
                } finally {
                    warmDef.end();
                }
                sink += warmComp.size();
            }
        }

        // Pre-build a deterministic random permutation of chunk indices for random-access workloads
        int numChunks = coords.size();
        int[] randomOrder = new int[measureIters];
        Random benchRnd = new Random(0x4C4341_5632L);
        for (int i = 0; i < measureIters; i++) {
            randomOrder[i] = benchRnd.nextInt(numChunks);
        }

        // Run a clean GC before timed batches and use median-of-5 batch timing so an asynchronous
        // 20 ms G1GC stop-the-world pause cannot distort a 2-5 ms measurement window.
        System.gc();

        // =========================================================================
        // 1. RANDOM READ BENCHMARK (Cold Uncached Region vs Warm Cached Region)
        // =========================================================================
        int batches = 5;
        int batchRandomIters = measureIters / batches;
        long[] batchNs = new long[batches];

        for (int b = 0; b < batches; b++) {
            long start = System.nanoTime();
            int base = b * batchRandomIters;
            for (int i = 0; i < batchRandomIters; i++) {
                int[] c = coords.get(randomOrder[base + i]);
                sink += AnvilReader.readChunkView(benchMca, c[0], c[1]).sections().size();
            }
            batchNs[b] = System.nanoTime() - start;
        }
        Arrays.sort(batchNs);
        double mcaFullReadUs = (batchNs[batches / 2] / 1000.0) / batchRandomIters;

        int coldRandomIters = Math.min(48, measureIters);
        byte[] coldLinearBuf = Arrays.copyOf(benchLinear, benchLinear.length);
        ByteBuffer coldLinearBb = ByteBuffer.wrap(coldLinearBuf);

        long tLinCold = System.nanoTime();
        for (int i = 0; i < coldRandomIters; i++) {
            int[] c = coords.get(randomOrder[i]);
            coldLinearBb.putLong(9, 1700000000L + i);
            sink += AnvilReader.toView(LinearRegionReader.INSTANCE.readChunk(coldLinearBuf, c[0], c[1]).root).sections().size();
        }
        double linearColdReadUs = (System.nanoTime() - tLinCold) / 1000.0 / coldRandomIters;

        for (int b = 0; b < batches; b++) {
            long start = System.nanoTime();
            int base = b * batchRandomIters;
            for (int i = 0; i < batchRandomIters; i++) {
                int[] c = coords.get(randomOrder[base + i]);
                LcaRegionReader.INSTANCE.invalidateFastDictCache();
                sink += LcaRegionReader.readChunkView(benchLcaA, c[0], c[1]).sections().size();
            }
            batchNs[b] = System.nanoTime() - start;
        }
        Arrays.sort(batchNs);
        double lcaModeAColdRandomUs = (batchNs[batches / 2] / 1000.0) / batchRandomIters;

        for (int b = 0; b < batches; b++) {
            long start = System.nanoTime();
            int base = b * batchRandomIters;
            for (int i = 0; i < batchRandomIters; i++) {
                int[] c = coords.get(randomOrder[base + i]);
                LcaRegionReader.INSTANCE.invalidateFastDictCache();
                sink += LcaRegionReader.readChunkView(benchLcaB, c[0], c[1]).sections().size();
            }
            batchNs[b] = System.nanoTime() - start;
        }
        Arrays.sort(batchNs);
        double lcaModeBColdRandomUs = (batchNs[batches / 2] / 1000.0) / batchRandomIters;

        for (int b = 0; b < batches; b++) {
            long start = System.nanoTime();
            int base = b * batchRandomIters;
            for (int i = 0; i < batchRandomIters; i++) {
                int[] c = coords.get(randomOrder[base + i]);
                sink += AnvilReader.toView(LinearRegionReader.INSTANCE.readChunk(benchLinear, c[0], c[1]).root).sections().size();
            }
            batchNs[b] = System.nanoTime() - start;
        }
        Arrays.sort(batchNs);
        double linearWarmReadUs = (batchNs[batches / 2] / 1000.0) / batchRandomIters;

        for (int b = 0; b < batches; b++) {
            long start = System.nanoTime();
            int base = b * batchRandomIters;
            for (int i = 0; i < batchRandomIters; i++) {
                int[] c = coords.get(randomOrder[base + i]);
                sink += LcaRegionReader.readChunkView(benchLcaA, c[0], c[1]).sections().size();
            }
            batchNs[b] = System.nanoTime() - start;
        }
        Arrays.sort(batchNs);
        double lcaModeAFullReadUs = (batchNs[batches / 2] / 1000.0) / batchRandomIters;

        for (int b = 0; b < batches; b++) {
            long start = System.nanoTime();
            int base = b * batchRandomIters;
            for (int i = 0; i < batchRandomIters; i++) {
                int[] c = coords.get(randomOrder[base + i]);
                sink += LcaRegionReader.readChunkView(benchLcaB, c[0], c[1]).sections().size();
            }
            batchNs[b] = System.nanoTime() - start;
        }
        Arrays.sort(batchNs);
        double lcaModeBFullReadUs = (batchNs[batches / 2] / 1000.0) / batchRandomIters;

        // =========================================================================
        // 2. SEQUENTIAL WHOLE-REGION SWEEP BENCHMARK (Reading ALL N Chunks 0..N-1)
        //    Tests the claim: "Linear is great if it reads a whole region file"
        // =========================================================================
        System.gc();
        int seqSweepsPerBatch = 4;
        long[] sweepBatchNs = new long[batches];
        long[] sweepLcaAColdBatchNs = new long[batches];

        // 2a. Cold Whole-Region Sequential Sweep -> AnvilChunkView (all 64 chunks)
        for (int b = 0; b < batches; b++) {
            long start = System.nanoTime();
            for (int s = 0; s < seqSweepsPerBatch; s++) {
                for (int i = 0; i < numChunks; i++) {
                    int[] c = coords.get(i);
                    sink += AnvilReader.readChunkView(benchMca, c[0], c[1]).sections().size();
                }
            }
            sweepBatchNs[b] = System.nanoTime() - start;
        }
        Arrays.sort(sweepBatchNs);
        double seqMcaRegionMs = (sweepBatchNs[batches / 2] / 1_000_000.0) / seqSweepsPerBatch;
        double seqMcaPerChunkUs = (seqMcaRegionMs * 1000.0) / numChunks;

        for (int b = 0; b < batches; b++) {
            long startLca = System.nanoTime();
            for (int s = 0; s < seqSweepsPerBatch; s++) {
                LcaRegionReader.INSTANCE.invalidateFastDictCache();
                for (int i = 0; i < numChunks; i++) {
                    int[] c = coords.get(i);
                    sink += LcaRegionReader.readChunkView(benchLcaA, c[0], c[1]).sections().size();
                }
            }
            sweepLcaAColdBatchNs[b] = System.nanoTime() - startLca;

            long startLin = System.nanoTime();
            for (int s = 0; s < seqSweepsPerBatch; s++) {
                coldLinearBb.putLong(9, 1800000000L + b * 100L + s);
                for (int i = 0; i < numChunks; i++) {
                    int[] c = coords.get(i);
                    sink += AnvilReader.toView(LinearRegionReader.INSTANCE.readChunk(coldLinearBuf, c[0], c[1]).root).sections().size();
                }
            }
            sweepBatchNs[b] = System.nanoTime() - startLin;
        }
        Arrays.sort(sweepBatchNs);
        Arrays.sort(sweepLcaAColdBatchNs);
        double seqLinearColdRegionMs = (sweepBatchNs[batches / 2] / 1_000_000.0) / seqSweepsPerBatch;
        double seqLinearColdPerChunkUs = (seqLinearColdRegionMs * 1000.0) / numChunks;
        double seqLcaAColdRegionMs = (sweepLcaAColdBatchNs[batches / 2] / 1_000_000.0) / seqSweepsPerBatch;
        double seqLcaAColdPerChunkUs = (seqLcaAColdRegionMs * 1000.0) / numChunks;

        for (int b = 0; b < batches; b++) {
            long start = System.nanoTime();
            for (int s = 0; s < seqSweepsPerBatch; s++) {
                for (int i = 0; i < numChunks; i++) {
                    int[] c = coords.get(i);
                    sink += AnvilReader.toView(LinearRegionReader.INSTANCE.readChunk(benchLinear, c[0], c[1]).root).sections().size();
                }
            }
            sweepBatchNs[b] = System.nanoTime() - start;
        }
        Arrays.sort(sweepBatchNs);
        double seqLinearWarmRegionMs = (sweepBatchNs[batches / 2] / 1_000_000.0) / seqSweepsPerBatch;
        double seqLinearWarmPerChunkUs = (seqLinearWarmRegionMs * 1000.0) / numChunks;

        for (int b = 0; b < batches; b++) {
            long start = System.nanoTime();
            for (int s = 0; s < seqSweepsPerBatch; s++) {
                for (int i = 0; i < numChunks; i++) {
                    int[] c = coords.get(i);
                    sink += LcaRegionReader.readChunkView(benchLcaA, c[0], c[1]).sections().size();
                }
            }
            sweepBatchNs[b] = System.nanoTime() - start;
        }
        Arrays.sort(sweepBatchNs);
        double seqLcaAWarmRegionMs = (sweepBatchNs[batches / 2] / 1_000_000.0) / seqSweepsPerBatch;
        double seqLcaAWarmPerChunkUs = (seqLcaAWarmRegionMs * 1000.0) / numChunks;

        for (int b = 0; b < batches; b++) {
            long start = System.nanoTime();
            for (int s = 0; s < seqSweepsPerBatch; s++) {
                LcaRegionReader.INSTANCE.invalidateFastDictCache();
                for (int i = 0; i < numChunks; i++) {
                    int[] c = coords.get(i);
                    sink += LcaRegionReader.readChunkView(benchLcaB, c[0], c[1]).sections().size();
                }
            }
            sweepBatchNs[b] = System.nanoTime() - start;
        }
        Arrays.sort(sweepBatchNs);
        double seqLcaBColdRegionMs = (sweepBatchNs[batches / 2] / 1_000_000.0) / seqSweepsPerBatch;
        double seqLcaBColdPerChunkUs = (seqLcaBColdRegionMs * 1000.0) / numChunks;

        // 2b. Cold Whole-Region Sequential Terrain Probe Sweep (Y=[48..112], all 64 chunks)
        for (int b = 0; b < batches; b++) {
            long start = System.nanoTime();
            for (int s = 0; s < seqSweepsPerBatch; s++) {
                for (int i = 0; i < numChunks; i++) {
                    int[] c = coords.get(i);
                    sink += AnvilReader.readTerrainProbe(benchMca, benchMca.length, c[0], c[1], 48, 112).sections().size();
                }
            }
            sweepBatchNs[b] = System.nanoTime() - start;
        }
        Arrays.sort(sweepBatchNs);
        double seqProbeMcaRegionMs = (sweepBatchNs[batches / 2] / 1_000_000.0) / seqSweepsPerBatch;

        for (int b = 0; b < batches; b++) {
            long start = System.nanoTime();
            for (int s = 0; s < seqSweepsPerBatch; s++) {
                coldLinearBb.putLong(9, 1900000000L + b * 100L + s);
                for (int i = 0; i < numChunks; i++) {
                    int[] c = coords.get(i);
                    sink += AnvilReader.toView(LinearRegionReader.INSTANCE.readChunk(coldLinearBuf, c[0], c[1]).root).sections().size();
                }
            }
            sweepBatchNs[b] = System.nanoTime() - start;
        }
        Arrays.sort(sweepBatchNs);
        double seqProbeLinearRegionMs = (sweepBatchNs[batches / 2] / 1_000_000.0) / seqSweepsPerBatch;

        for (int b = 0; b < batches; b++) {
            long start = System.nanoTime();
            for (int s = 0; s < seqSweepsPerBatch; s++) {
                LcaRegionReader.INSTANCE.invalidateFastDictCache();
                for (int i = 0; i < numChunks; i++) {
                    int[] c = coords.get(i);
                    sink += LcaRegionReader.readTerrainProbe(benchLcaA, benchLcaA.length, c[0], c[1], 48, 112).sections().size();
                }
            }
            sweepBatchNs[b] = System.nanoTime() - start;
        }
        Arrays.sort(sweepBatchNs);
        double seqProbeLcaARegionMs = (sweepBatchNs[batches / 2] / 1_000_000.0) / seqSweepsPerBatch;

        // =========================================================================
        // 3. RTP Terrain Window Probe ([48..112]) & Single-Section Partial Read
        // =========================================================================
        for (int b = 0; b < batches; b++) {
            long start = System.nanoTime();
            int base = b * batchRandomIters;
            for (int i = 0; i < batchRandomIters; i++) {
                int[] c = coords.get(randomOrder[base + i]);
                sink += AnvilReader.readTerrainProbe(benchMca, benchMca.length, c[0], c[1], 48, 112).sections().size();
            }
            batchNs[b] = System.nanoTime() - start;
        }
        Arrays.sort(batchNs);
        double mcaTerrainProbeUs = (batchNs[batches / 2] / 1000.0) / batchRandomIters;

        for (int b = 0; b < batches; b++) {
            long start = System.nanoTime();
            int base = b * batchRandomIters;
            for (int i = 0; i < batchRandomIters; i++) {
                int[] c = coords.get(randomOrder[base + i]);
                sink += LcaRegionReader.readTerrainProbe(benchLcaA, benchLcaA.length, c[0], c[1], 48, 112).sections().size();
            }
            batchNs[b] = System.nanoTime() - start;
        }
        Arrays.sort(batchNs);
        double lcaTerrainProbeUs = (batchNs[batches / 2] / 1000.0) / batchRandomIters;

        long tLcaSecNonUniform = System.nanoTime();
        for (int i = 0; i < measureIters; i++) {
            int[] c = coords.get(randomOrder[i]);
            PaletteSection s = LcaRegionReader.readSection(benchLcaA, benchLcaA.length, c[0], c[1], 4);
            if (s != null) sink += s.palette().size();
        }
        double lcaSingleSecNonUniformUs = (System.nanoTime() - tLcaSecNonUniform) / 1000.0 / measureIters;

        long tLcaSecUniform = System.nanoTime();
        for (int i = 0; i < measureIters * 4; i++) {
            int[] c = coords.get(i % coords.size());
            PaletteSection s = LcaRegionReader.readSection(benchLcaA, benchLcaA.length, c[0], c[1], 18);
            if (s != null) sink += s.palette().size();
        }
        double lcaSingleSecUniformUs = (System.nanoTime() - tLcaSecUniform) / 1000.0 / (measureIters * 4);

        // 4. Single Dirty Chunk Save Latency (PaperMC-style autosave: .mca Zlib vs .lca CoW Mode A)
        int savePerBatch = 32;
        long[] mcaSaveBatchNs = new long[batches];
        long[] lcaSaveBatchNs = new long[batches];
        for (int b = 0; b < batches; b++) {
            long startMca = System.nanoTime();
            for (int i = 0; i < savePerBatch; i++) {
                LinkedHashMap<String, Object> root = chunkRoots.get((b * savePerBatch + i) % chunkRoots.size());
                byte[] nbt = Nbt.writeNamedRoot("", root);
                ByteArrayOutputStream comp = new ByteArrayOutputStream(4096);
                Deflater def = new Deflater(Deflater.DEFAULT_COMPRESSION);
                try (DeflaterOutputStream dos = new DeflaterOutputStream(comp, def)) {
                    dos.write(nbt);
                } finally {
                    def.end();
                }
                sink += comp.size();
            }
            mcaSaveBatchNs[b] = System.nanoTime() - startMca;

            long startLca = System.nanoTime();
            for (int i = 0; i < savePerBatch; i++) {
                int idx = (b * savePerBatch + i) % coords.size();
                int[] c = coords.get(idx);
                sink += lcaModeAArchive.saveChunkCoW(c[0], c[1], chunkRoots.get(idx));
            }
            lcaSaveBatchNs[b] = System.nanoTime() - startLca;
        }
        Arrays.sort(mcaSaveBatchNs);
        Arrays.sort(lcaSaveBatchNs);
        double mcaSingleSaveUs = (mcaSaveBatchNs[batches / 2] / 1000.0) / savePerBatch;
        double lcaSingleSaveUs = (lcaSaveBatchNs[batches / 2] / 1000.0) / savePerBatch;

        System.out.printf(Locale.ROOT,
                "%n====================================================================================%n"
                        + " EMPIRICAL BENCHMARK: %s (%d chunks, sink=%d)%n"
                        + "====================================================================================%n"
                        + "1. Region Storage Size (%d chunks):%n"
                        + "   - Vanilla .mca (4 KiB Zlib sectors) : %,10d B (100.0%%)%n"
                        + "   - .linear (whole-region Zstd-1)     : %,10d B (%5.1f%% of .mca)%n"
                        + "   - .lca Mode A (Pure 0-LZ)           : %,10d B (%5.1f%% of .mca)%n"
                        + "   - .lca Mode B (+ Zstd-1)            : %,10d B (%5.1f%% of .mca)%n"
                        + "2. RANDOM 1-Chunk Read Latency (Store -> Runtime AnvilChunkView):%n"
                        + "   - Cold Random (.linear Zstd region) : %8.2f us%n"
                        + "   - Cold/Warm Random (.mca Zlib)      : %8.2f us%n"
                        + "   - Cold Random (.lca Mode A 0-LZ)    : %8.2f us (%.2fx < .mca, %.1fx < cold .linear)%n"
                        + "   - Cold Random (.lca Mode B Zstd-1)  : %8.2f us (%.2fx < .mca, %.1fx < cold .linear)%n"
                        + "   - Warm Random (.linear cached NBT)  : %8.2f us%n"
                        + "   - Warm Random (.lca Mode A 0-LZ)    : %8.2f us (%.2fx < warm .linear, %.2fx < .mca)%n"
                        + "   - Warm Random (.lca Mode B Zstd-1)  : %8.2f us (%.2fx < warm .linear, %.2fx < .mca)%n"
                        + "3. SEQUENTIAL WHOLE-REGION SWEEP (All %d Chunks -> Runtime AnvilChunkView):%n"
                        + "   - Cold Whole Region (.mca Zlib)     : %8.2f ms total (%7.2f us/chunk)%n"
                        + "   - Cold Whole Region (.linear Zstd)  : %8.2f ms total (%7.2f us/chunk) [%.2fx < .mca]%n"
                        + "   - Warm Whole Region (.linear cached): %8.2f ms total (%7.2f us/chunk)%n"
                        + "   - Cold Whole Region (.lca Mode A)   : %8.2f ms total (%7.2f us/chunk) [%.2fx < cold .linear, %.2fx < .mca]%n"
                        + "   - Warm Whole Region (.lca Mode A)   : %8.2f ms total (%7.2f us/chunk) [%.2fx < warm .linear, %.2fx < .mca]%n"
                        + "   - Cold Whole Region (.lca Mode B)   : %8.2f ms total (%7.2f us/chunk) [%.2fx < cold .linear, %.2fx < .mca]%n"
                        + "4. SEQUENTIAL WHOLE-REGION TERRAIN PROBE SWEEP (All %d Chunks, Y=[48..112]):%n"
                        + "   - Cold Region Terrain Sweep (.mca)  : %8.2f ms total (%7.2f us/chunk)%n"
                        + "   - Cold Region Terrain Sweep (.linear):%8.2f ms total (%7.2f us/chunk)%n"
                        + "   - Cold Region Terrain Sweep (.lca A): %8.2f ms total (%7.2f us/chunk) [%.2fx < .linear, %.2fx < .mca]%n"
                        + "5. Sub-Chunk Random Probes & Incremental Dirty Chunk Save:%n"
                        + "   - RTP Terrain Window Probe (.mca)   : %8.2f us%n"
                        + "   - RTP Terrain Window Probe (.lca A) : %8.2f us (%.2fx faster than .mca)%n"
                        + "   - 1-Section Partial Read (.lca H_3) : %8.2f us (%.2fx faster than .mca)%n"
                        + "   - 1-Section Inline Uniform (.lca)   : %8.2f us (%.1fx faster than .mca)%n"
                        + "   - Dirty Save .mca (NBT + Zlib)      : %8.2f us%n"
                        + "   - Dirty Save .lca Mode A (CoW 256B) : %8.2f us (%.2fx faster than .mca)%n"
                        + "====================================================================================%n",
                datasetLabel, coords.size(), sink, coords.size(),
                benchMca.length,
                benchLinear.length, 100.0 * benchLinear.length / benchMca.length,
                benchLcaA.length, 100.0 * benchLcaA.length / benchMca.length,
                benchLcaB.length, 100.0 * benchLcaB.length / benchMca.length,
                linearColdReadUs,
                mcaFullReadUs,
                lcaModeAColdRandomUs, mcaFullReadUs / Math.max(0.01, lcaModeAColdRandomUs),
                linearColdReadUs / Math.max(0.01, lcaModeAColdRandomUs),
                lcaModeBColdRandomUs, mcaFullReadUs / Math.max(0.01, lcaModeBColdRandomUs),
                linearColdReadUs / Math.max(0.01, lcaModeBColdRandomUs),
                linearWarmReadUs,
                lcaModeAFullReadUs, linearWarmReadUs / Math.max(0.01, lcaModeAFullReadUs),
                mcaFullReadUs / Math.max(0.01, lcaModeAFullReadUs),
                lcaModeBFullReadUs, linearWarmReadUs / Math.max(0.01, lcaModeBFullReadUs),
                mcaFullReadUs / Math.max(0.01, lcaModeBFullReadUs),
                numChunks,
                seqMcaRegionMs, seqMcaPerChunkUs,
                seqLinearColdRegionMs, seqLinearColdPerChunkUs, seqMcaRegionMs / Math.max(0.001, seqLinearColdRegionMs),
                seqLinearWarmRegionMs, seqLinearWarmPerChunkUs,
                seqLcaAColdRegionMs, seqLcaAColdPerChunkUs,
                seqLinearColdRegionMs / Math.max(0.001, seqLcaAColdRegionMs),
                seqMcaRegionMs / Math.max(0.001, seqLcaAColdRegionMs),
                seqLcaAWarmRegionMs, seqLcaAWarmPerChunkUs,
                seqLinearWarmRegionMs / Math.max(0.001, seqLcaAWarmRegionMs),
                seqMcaRegionMs / Math.max(0.001, seqLcaAWarmRegionMs),
                seqLcaBColdRegionMs, seqLcaBColdPerChunkUs,
                seqLinearColdRegionMs / Math.max(0.001, seqLcaBColdRegionMs),
                seqMcaRegionMs / Math.max(0.001, seqLcaBColdRegionMs),
                numChunks,
                seqProbeMcaRegionMs, (seqProbeMcaRegionMs * 1000.0) / numChunks,
                seqProbeLinearRegionMs, (seqProbeLinearRegionMs * 1000.0) / numChunks,
                seqProbeLcaARegionMs, (seqProbeLcaARegionMs * 1000.0) / numChunks,
                seqProbeLinearRegionMs / Math.max(0.001, seqProbeLcaARegionMs),
                seqProbeMcaRegionMs / Math.max(0.001, seqProbeLcaARegionMs),
                mcaTerrainProbeUs,
                lcaTerrainProbeUs, mcaTerrainProbeUs / Math.max(0.01, lcaTerrainProbeUs),
                lcaSingleSecNonUniformUs, mcaTerrainProbeUs / Math.max(0.01, lcaSingleSecNonUniformUs),
                lcaSingleSecUniformUs, mcaTerrainProbeUs / Math.max(0.001, lcaSingleSecUniformUs),
                mcaSingleSaveUs,
                lcaSingleSaveUs, mcaSingleSaveUs / Math.max(0.01, lcaSingleSaveUs));

        assertTrue(lcaModeAFullReadUs < mcaFullReadUs,
                ".lca Mode A full chunk read (" + lcaModeAFullReadUs + " us) must be faster than .mca (" + mcaFullReadUs + " us)");
        assertTrue(seqLcaAColdRegionMs < seqLinearColdRegionMs,
                ".lca Mode A cold whole-region sweep (" + seqLcaAColdRegionMs + " ms) must beat cold .linear (" + seqLinearColdRegionMs + " ms)");
        assertTrue(lcaTerrainProbeUs < mcaTerrainProbeUs,
                ".lca terrain probe (" + lcaTerrainProbeUs + " us) must be faster than .mca (" + mcaTerrainProbeUs + " us)");
        assertTrue(lcaSingleSaveUs < mcaSingleSaveUs,
                ".lca Mode A save (" + lcaSingleSaveUs + " us) must be faster than .mca Zlib save (" + mcaSingleSaveUs + " us)");
        assertTrue(benchLcaA.length < benchLinear.length,
                ".lca Mode A (0-LZ) size (" + benchLcaA.length + ") must be smaller than .linear Zstd-1 (" + benchLinear.length + ")");
        assertTrue(benchLcaB.length < benchMca.length,
                ".lca Mode B size (" + benchLcaB.length + ") must be smaller than .mca (" + benchMca.length + ")");
    }

    private static final String[] FALLBACK_TERRAIN_STATES = {
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

    private static void expandSingleChunkFixtureTo64Chunks(
            List<int[]> coords, List<LinkedHashMap<String, Object>> chunkRoots) {
        LinkedHashMap<String, Object> template = chunkRoots.get(0);
        coords.clear();
        chunkRoots.clear();

        for (int idx = 0; idx < 64; idx++) {
            int rx = idx & 7;
            int rz = (idx >>> 3) & 7;
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>(template);
            copy.put("xPos", rx);
            copy.put("zPos", rz);
            copy.put("LastUpdate", 1700000000L + idx * 37L);
            copy.put("InhabitedTime", 120L + idx * 19L);

            Object hmObj = template.get("Heightmaps");
            if (hmObj instanceof Map<?, ?> hmMap) {
                LinkedHashMap<String, Object> newHm = new LinkedHashMap<>();
                int keyIdx = 0;
                for (Map.Entry<?, ?> entry : hmMap.entrySet()) {
                    String k = String.valueOf(entry.getKey());
                    Object v = entry.getValue();
                    if (v instanceof long[] arr) {
                        long[] modArr = new long[arr.length];
                        for (int w = 0; w < arr.length; w++) {
                            long noise = mix64((idx + 1L) * 0x9E3779B97F4A7C15L ^ (keyIdx * 64L + w) * 0xBF58476D1CE4E5B9L);
                            modArr[w] = arr[w] ^ (noise & 0x001F_0F87_C3E1_F078L);
                        }
                        newHm.put(k, modArr);
                    } else {
                        newHm.put(k, v);
                    }
                    keyIdx++;
                }
                copy.put("Heightmaps", newHm);
            }

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
                        secCopy.put("block_states", buildSynthesizedTerrainBlockStates(idx, secY));
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

    private static LinkedHashMap<String, Object> buildSynthesizedTerrainBlockStates(int chunkIdx, int secY) {
        int palSize = FALLBACK_TERRAIN_STATES.length; // 17 -> 5 bits per entry (entriesPerLong = 12, long[342])
        int[] order = new int[palSize];
        for (int i = 0; i < palSize; i++) {
            order[i] = i;
        }
        long seed = mix64((chunkIdx + 1L) * 0x9E3779B97F4A7C15L ^ (secY + 16L) * 0xBF58476D1CE4E5B9L);
        for (int i = palSize - 1; i > 0; i--) {
            seed = mix64(seed + i);
            int j = (int) (Long.remainderUnsigned(seed, i + 1));
            int tmp = order[i];
            order[i] = order[j];
            order[j] = tmp;
        }
        String dominantName = secY < 0 ? "minecraft:deepslate" : "minecraft:stone";
        int domLocalIdx = 0;
        for (int i = 0; i < palSize; i++) {
            if (FALLBACK_TERRAIN_STATES[order[i]].equals(dominantName)) {
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
            entry.put("Name", FALLBACK_TERRAIN_STATES[order[i]]);
            paletteList.add(entry);
        }

        int[] voxels = new int[4096];
        Arrays.fill(voxels, domLocalIdx);
        // Place 2 contiguous YZX strata/vein runs per minority state at deterministic per-(chunk, section) offsets
        int cursor = (int) (Long.remainderUnsigned(seed, 97)) + 16;
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

        // Pack 4,096 5-bit indices into long[342] (12 entries per 64-bit long, Mojang post-1.16 non-spanning layout)
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

    private static byte[] buildMcaFromRoots(List<int[]> coords, List<LinkedHashMap<String, Object>> roots) throws IOException {
        ByteArrayOutputStream sectorsOut = new ByteArrayOutputStream(coords.size() * 4096);
        byte[] header = new byte[8192];
        int currentSector = 2;

        for (int i = 0; i < coords.size(); i++) {
            int rx = coords.get(i)[0] & 31;
            int rz = coords.get(i)[1] & 31;
            byte[] nbt = Nbt.writeNamedRoot("", roots.get(i));
            ByteArrayOutputStream zOut = new ByteArrayOutputStream(4096);
            Deflater def = new Deflater(Deflater.DEFAULT_COMPRESSION);
            try (DeflaterOutputStream dos = new DeflaterOutputStream(zOut, def)) {
                dos.write(nbt);
            } finally {
                def.end();
            }
            byte[] compressed = zOut.toByteArray();
            int declaredLen = compressed.length + 1;
            int totalBytes = 4 + declaredLen;
            int sectorCount = (totalBytes + 4095) >>> 12;
            int paddedBytes = sectorCount << 12;

            byte[] sectorBuf = new byte[paddedBytes];
            ByteBuffer bb = ByteBuffer.wrap(sectorBuf);
            bb.putInt(0, declaredLen);
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

    private static byte[] buildLinearFromRoots(List<int[]> coords, List<LinkedHashMap<String, Object>> roots) throws IOException {
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

    public interface MockRegionFileBridge {
        boolean doesChunkExist(int rx, int rz);

        DataInputStream getChunkDataInputStream(int rx, int rz) throws IOException;

        DataOutputStream getChunkDataOutputStream(int rx, int rz);

        boolean clear(int rx, int rz);

        void flush() throws IOException;

        void close() throws IOException;
    }

    @Test
    @DisplayName("REQ-RTP-S-004 / REQ-RTP-S-005: Disk-backed FileChannel incremental writes, DataInputStream/DataOutputStream bridge, and compaction")
    void diskBackedFileChannelPersistenceAndStreamBridge(@TempDir Path tempDir) throws IOException {
        byte[] mcaBytes = loadFixture("/anvil/real/1_21_R1/r.0.0.mca");
        AnvilReader.ChunkEntry orig00 = AnvilReader.readChunkEntry(mcaBytes, 0, 0);
        assertNotNull(orig00);

        Path lcaFile = tempDir.resolve("r.0.0.lca");
        try (LcaRegionWriter.LcaLiveArchive archive =
                     LcaRegionWriter.createEmptyLiveArchive(lcaFile, 0, 0, -4, 24, 4671, false)) {
            assertTrue(archive.isChannelOpen());
            assertEquals(lcaFile, archive.backingFile());
            assertEquals(LcaRegionReader.STORAGE_KIND_REGION_VOXEL, archive.storageKind());

            // Save chunk (0, 0) through the RegionFile DataOutputStream bridge
            try (DataOutputStream out = archive.getChunkDataOutputStream(0, 0)) {
                out.write(Nbt.writeNamedRoot("", orig00.root));
            }
            assertTrue(archive.hasChunk(0, 0));
            assertTrue(archive.doesChunkExist(0, 0));
            assertFalse(archive.hasChunk(1, 0));
            assertTrue(archive.diskChannelWriteCount() > 1);

            // On-disk bytes must already be valid and readable by an independent reader before close()
            byte[] onDiskMidRun = Files.readAllBytes(lcaFile);
            assertTrue(LcaRegionReader.INSTANCE.isChunkGenerated(onDiskMidRun, 0, 0));
            AnvilChunkView midView = LcaRegionReader.readChunkView(onDiskMidRun, 0, 0);
            AnvilChunkView expectedView = AnvilReader.readChunkView(mcaBytes, 0, 0);
            assertNotNull(midView);
            assertNotNull(expectedView);
            assertEquals(expectedView.blockIdAt(8, 64, 8), midView.blockIdAt(8, 64, 8));

            // Read back through RegionFile DataInputStream bridge
            try (DataInputStream in = archive.getChunkDataInputStream(0, 0)) {
                assertNotNull(in);
                LinkedHashMap<String, Object> streamDecoded = Nbt.readRootCompound(in.readAllBytes());
                assertEquals(4671, AnvilReader.getDataVersion(streamDecoded));
            }

            // Perform 12 incremental CoW saves to create free-slab holes and test compaction
            for (int iter = 1; iter <= 12; iter++) {
                LinkedHashMap<String, Object> copy = new LinkedHashMap<>(orig00.root);
                copy.put("LastUpdate", (long) iter);
                archive.saveChunkCoW(0, 0, copy);
            }
            assertTrue(archive.freeSlabCount() > 0);
            int preCompactLen = archive.activeByteLength();
            assertTrue(archive.compactIfFragmented(0.05));
            assertEquals(0, archive.freeSlabCount());
            assertEquals(0.0, archive.fragmentationRatio(), 1e-9);
            assertTrue(archive.activeByteLength() < preCompactLen);
        }

        // Re-open from disk via openLiveArchive(Path) and verify persistence + removeChunk
        try (LcaRegionWriter.LcaLiveArchive reopened = LcaRegionWriter.openLiveArchive(lcaFile)) {
            assertTrue(reopened.hasChunk(0, 0));
            AnvilReader.ChunkEntry entry = reopened.readChunkEntry(0, 0);
            assertNotNull(entry);
            assertEquals(12L, ((Number) entry.root.get("LastUpdate")).longValue());

            // Test dynamic interface proxy for IRegionFile
            MockRegionFileBridge proxy =
                    LcaRegionFileStorage.createRegionFileInterfaceProxy(MockRegionFileBridge.class, reopened);
            assertTrue(proxy.doesChunkExist(0, 0));
            try (DataInputStream in = proxy.getChunkDataInputStream(0, 0)) {
                assertNotNull(in);
                assertTrue(in.readAllBytes().length > 100);
            }
            assertTrue(proxy.clear(0, 0));
            assertFalse(proxy.doesChunkExist(0, 0));
            proxy.flush();
        }
    }

    @Test
    @DisplayName("REQ-RTP-S-004: Non-voxel entities/ (storageKind=1) and poi/ (storageKind=2) round-trip and .lca -> .mca reverse transcoder")
    void entitiesAndPoiNonVoxelStorageAndReverseMcaTranscoder(@TempDir Path tempDir) throws IOException {
        try (LcaRegionFileStorage.LcaWorldStorageSession session =
                     new LcaRegionFileStorage.LcaWorldStorageSession(tempDir, -4, 24, 4671, false, true)) {
            assertEquals(LcaRegionReader.STORAGE_KIND_REGION_VOXEL, session.voxelStorage().storageKind());
            assertEquals(LcaRegionReader.STORAGE_KIND_ENTITIES, session.entityStorage().storageKind());
            assertEquals(LcaRegionReader.STORAGE_KIND_POI, session.poiStorage().storageKind());

            // 1. Save an MC 1.17+ entities/ chunk compound (DataVersion, Position, Entities list)
            LinkedHashMap<String, Object> entityChunk = new LinkedHashMap<>();
            entityChunk.put("DataVersion", 4671);
            entityChunk.put("Position", new int[] {3, -2});
            LinkedHashMap<String, Object> villager = new LinkedHashMap<>();
            villager.put("id", "minecraft:villager");
            villager.put("UUID", new int[] {11, 22, 33, 44});
            entityChunk.put("Entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(villager)));

            try (DataOutputStream out = session.entityStorage().getChunkDataOutputStream(3, -2)) {
                out.write(Nbt.writeNamedRoot("", entityChunk));
            }
            assertTrue(session.entityStorage().doesChunkExist(3, -2));
            LinkedHashMap<String, Object> readEntities = session.entityStorage().readChunkCompound(3, -2);
            assertNotNull(readEntities);
            assertEquals(4671, ((Number) readEntities.get("DataVersion")).intValue());
            assertFalse(readEntities.containsKey("sections"), "Entity storage chunk must not have synthetic voxel 'sections'");
            assertFalse(readEntities.containsKey("xPos"), "Entity storage chunk must not have synthetic 'xPos'");
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    new int[] {3, -2}, (int[]) readEntities.get("Position"));
            Nbt.NbtList entList = (Nbt.NbtList) readEntities.get("Entities");
            assertEquals(1, entList.items.size());
            assertEquals("minecraft:villager", ((Map<?, ?>) entList.items.get(0)).get("id"));

            // 2. Save a POI chunk compound (where "Sections" is a TAG_Compound map, not a TAG_List!)
            LinkedHashMap<String, Object> poiChunk = new LinkedHashMap<>();
            poiChunk.put("DataVersion", 4671);
            LinkedHashMap<String, Object> poiSections = new LinkedHashMap<>();
            LinkedHashMap<String, Object> sec4 = new LinkedHashMap<>();
            sec4.put("Valid", (byte) 1);
            LinkedHashMap<String, Object> record = new LinkedHashMap<>();
            record.put("type", "minecraft:home");
            record.put("pos", new int[] {48, 65, -30});
            sec4.put("Records", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(record)));
            poiSections.put("4", sec4);
            poiChunk.put("Sections", poiSections);

            session.poiStorage().writeChunkCompound(3, -2, poiChunk);
            assertTrue(session.poiStorage().doesChunkExist(3, -2));
            LinkedHashMap<String, Object> readPoi = session.poiStorage().readChunkCompound(3, -2);
            assertNotNull(readPoi);
            assertFalse(readPoi.containsKey("sections"));
            assertTrue(readPoi.get("Sections") instanceof Map<?, ?>);
            Map<?, ?> readSec4 = (Map<?, ?>) ((Map<?, ?>) readPoi.get("Sections")).get("4");
            assertNotNull(readSec4);
            assertEquals((byte) 1, readSec4.get("Valid"));

            session.flush();
        }

        // 3. Verify .mca -> .lca -> .mca reverse transcoder full voxel & biome parity
        byte[] origMca = loadFixture("/anvil/real/1_21_R1/r.0.0.mca");
        byte[] lcaBytes = LcaRegionWriter.transcodeFromMca(origMca, 0, 0, false);
        byte[] roundTripMca = LcaRegionWriter.transcodeToMca(lcaBytes);
        assertTrue(AnvilReader.INSTANCE.isChunkGenerated(roundTripMca, 0, 0));
        AnvilChunkView viewOrig = AnvilReader.readChunkView(origMca, 0, 0);
        AnvilChunkView viewRoundTrip = AnvilReader.readChunkView(roundTripMca, 0, 0);
        assertNotNull(viewOrig);
        assertNotNull(viewRoundTrip);
        for (int y = -64; y < 320; y += 7) {
            for (int z = 0; z < 16; z += 5) {
                for (int x = 0; x < 16; x += 5) {
                    assertEquals(viewOrig.blockIdAt(x, y, z), viewRoundTrip.blockIdAt(x, y, z));
                }
            }
        }
    }

    @Test
    @DisplayName("REQ-RTP-S-005: LcaRegionFileStorage lazy on-open .mca -> .lca migration and LRU region cache eviction")
    void lazyLegacyMcaMigrationAndLruEviction(@TempDir Path tempDir) throws IOException {
        Path regionDir = tempDir.resolve("region");
        Files.createDirectories(regionDir);
        byte[] mcaBytes = loadFixture("/anvil/real/1_21_R1/r.0.0.mca");
        Path legacyMcaPath = regionDir.resolve("r.0.0.mca");
        Files.write(legacyMcaPath, mcaBytes);

        // Configure maxOpenRegions = 2 to exercise LRU eviction across regions (0,0), (1,0), (2,0)
        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(
                regionDir,
                LcaRegionReader.STORAGE_KIND_REGION_VOXEL,
                -4,
                24,
                4671,
                false,
                true,
                true,
                2,
                0.30)) {
            // Accessing chunk (0, 0) must lazily transcode r.0.0.mca -> r.0.0.lca and move .mca to .mca.bak
            assertTrue(storage.doesChunkExist(0, 0));
            assertEquals(1L, storage.migratedRegionCount());
            assertTrue(Files.exists(regionDir.resolve("r.0.0.lca")));
            assertTrue(Files.exists(regionDir.resolve("r.0.0.mca.bak")));
            assertFalse(Files.exists(legacyMcaPath));

            LinkedHashMap<String, Object> chunk00 = storage.readChunkCompound(0, 0);
            assertNotNull(chunk00);

            // Write chunks in region (1, 0) [chunkX=32] and region (2, 0) [chunkX=64] to trigger LRU eviction of (0, 0)
            LinkedHashMap<String, Object> chunk32 = new LinkedHashMap<>(chunk00);
            chunk32.put("xPos", 32);
            storage.writeChunkCompound(32, 0, chunk32);
            assertEquals(2, storage.openRegionCount());

            LinkedHashMap<String, Object> chunk64 = new LinkedHashMap<>(chunk00);
            chunk64.put("xPos", 64);
            storage.writeChunkCompound(64, 0, chunk64);
            assertEquals(2, storage.openRegionCount(), "LRU cache must cap open regions at maxOpenRegions=2");

            // Re-accessing region (0, 0) must seamlessly re-open r.0.0.lca from disk
            assertTrue(storage.doesChunkExist(0, 0));
            assertTrue(storage.doesChunkExist(32, 0));
            assertTrue(storage.doesChunkExist(64, 0));
        }
    }

    @Test
    @DisplayName("REQ-RTP-S-005: Block state Properties, container block_entities Items, and persistent mob Entities survive repeated CoW edits and disk reopen")
    void blockPropertiesContainersAndEntitiesSurviveRepeatedCoWAndReopen(@TempDir Path tempDir) throws IOException {
        byte[] mcaBytes = loadFixture("/anvil/real/1_21_R1/r.0.0.mca");
        AnvilReader.ChunkEntry baseEntry = AnvilReader.readChunkEntry(mcaBytes, 0, 0);
        assertNotNull(baseEntry);

        try (LcaRegionFileStorage.LcaWorldStorageSession session =
                new LcaRegionFileStorage.LcaWorldStorageSession(tempDir, -4, 24, 4671, false, true)) {
            LinkedHashMap<String, Object> chunk = new LinkedHashMap<>(baseEntry.root);
            chunk.put("xPos", 0);
            chunk.put("zPos", 0);

            // Build a mixed voxel section at Y=4 containing multi-property block states
            LinkedHashMap<String, Object> airEntry = new LinkedHashMap<>();
            airEntry.put("Name", "minecraft:air");

            LinkedHashMap<String, Object> stairsEntry = new LinkedHashMap<>();
            stairsEntry.put("Name", "minecraft:oak_stairs");
            LinkedHashMap<String, Object> stairsProps = new LinkedHashMap<>();
            stairsProps.put("facing", "east");
            stairsProps.put("half", "top");
            stairsProps.put("shape", "outer_left");
            stairsProps.put("waterlogged", "true");
            stairsEntry.put("Properties", stairsProps);

            LinkedHashMap<String, Object> chestBlockEntry = new LinkedHashMap<>();
            chestBlockEntry.put("Name", "minecraft:chest");
            LinkedHashMap<String, Object> chestProps = new LinkedHashMap<>();
            chestProps.put("facing", "south");
            chestProps.put("type", "single");
            chestProps.put("waterlogged", "false");
            chestBlockEntry.put("Properties", chestProps);

            LinkedHashMap<String, Object> repeaterEntry = new LinkedHashMap<>();
            repeaterEntry.put("Name", "minecraft:repeater");
            LinkedHashMap<String, Object> repeaterProps = new LinkedHashMap<>();
            repeaterProps.put("delay", "4");
            repeaterProps.put("facing", "west");
            repeaterProps.put("locked", "true");
            repeaterProps.put("powered", "true");
            repeaterEntry.put("Properties", repeaterProps);

            // 4-entry palette uses 4 bits per entry (16 entries per 64-bit long, 256 longs for 4096 voxels)
            long[] packed = new long[256];
            // Voxel (x=2, y=1, z=3) -> yzx index = (1 << 8) | (3 << 4) | 2 = 306 -> word 19, shift 8
            packed[306 / 16] |= (1L << ((306 % 16) * 4)); // palette index 1 = oak_stairs
            // Voxel (x=4, y=1, z=7) -> yzx index = (1 << 8) | (7 << 4) | 4 = 372 -> word 23, shift 16
            packed[372 / 16] |= (2L << ((372 % 16) * 4)); // palette index 2 = chest
            // Voxel (x=9, y=2, z=5) -> yzx index = (2 << 8) | (5 << 4) | 9 = 601 -> word 37, shift 36
            packed[601 / 16] |= (3L << ((601 % 16) * 4)); // palette index 3 = repeater

            LinkedHashMap<String, Object> secY4 = new LinkedHashMap<>();
            secY4.put("Y", (byte) 4);
            LinkedHashMap<String, Object> bs = new LinkedHashMap<>();
            bs.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND,
                    List.of(airEntry, stairsEntry, chestBlockEntry, repeaterEntry)));
            bs.put("data", packed);
            secY4.put("block_states", bs);

            Nbt.NbtList origSecs = AnvilReader.getSections(chunk);
            List<Object> replacedSecs = new ArrayList<>();
            for (Object o : origSecs.items) {
                Map<?, ?> m = (Map<?, ?>) o;
                if (((Number) m.get("Y")).intValue() == 4) {
                    replacedSecs.add(secY4);
                } else {
                    replacedSecs.add(o);
                }
            }
            chunk.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, replacedSecs));

            // Add container block_entities (chest with enchanted sword + golden apples)
            LinkedHashMap<String, Object> swordItem = new LinkedHashMap<>();
            swordItem.put("Slot", (byte) 0);
            swordItem.put("id", "minecraft:diamond_sword");
            swordItem.put("count", 1);
            LinkedHashMap<String, Object> swordComponents = new LinkedHashMap<>();
            swordComponents.put("minecraft:damage", 42);
            swordComponents.put("minecraft:custom_name", "\"Excalibur\"");
            swordItem.put("components", swordComponents);

            LinkedHashMap<String, Object> appleItem = new LinkedHashMap<>();
            appleItem.put("Slot", (byte) 13);
            appleItem.put("id", "minecraft:golden_apple");
            appleItem.put("count", 16);

            LinkedHashMap<String, Object> chestBe = new LinkedHashMap<>();
            chestBe.put("id", "minecraft:chest");
            chestBe.put("x", 4);
            chestBe.put("y", 65);
            chestBe.put("z", 7);
            chestBe.put("Items", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(swordItem, appleItem)));
            chunk.put("block_entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(chestBe)));

            // Initial write to region/r.0.0.lca
            session.voxelStorage().writeChunkCompound(0, 0, chunk);

            // Second CoW mutation: flip oak_stairs facing=north, waterlogged=false & add emerald_block in slot 26
            LinkedHashMap<String, Object> stairsMutated = new LinkedHashMap<>();
            stairsMutated.put("Name", "minecraft:oak_stairs");
            LinkedHashMap<String, Object> stairsPropsMut = new LinkedHashMap<>();
            stairsPropsMut.put("facing", "north");
            stairsPropsMut.put("half", "bottom");
            stairsPropsMut.put("shape", "straight");
            stairsPropsMut.put("waterlogged", "false");
            stairsMutated.put("Properties", stairsPropsMut);

            bs.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND,
                    List.of(airEntry, stairsMutated, chestBlockEntry, repeaterEntry)));

            LinkedHashMap<String, Object> emeraldItem = new LinkedHashMap<>();
            emeraldItem.put("Slot", (byte) 26);
            emeraldItem.put("id", "minecraft:emerald_block");
            emeraldItem.put("count", 64);
            chestBe.put("Items", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(swordItem, appleItem, emeraldItem)));

            session.voxelStorage().writeChunkCompound(0, 0, chunk);

            // Write persistent mob entities to entities/r.0.0.lca and mutate across CoW
            LinkedHashMap<String, Object> villager = new LinkedHashMap<>();
            villager.put("id", "minecraft:villager");
            villager.put("UUID", new int[] {101, 202, 303, 404});
            villager.put("PersistenceRequired", (byte) 1);
            villager.put("CustomName", "\"LeafArchivist\"");
            villager.put("Health", 20.0f);

            LinkedHashMap<String, Object> sheep = new LinkedHashMap<>();
            sheep.put("id", "minecraft:sheep");
            sheep.put("UUID", new int[] {505, 606, 707, 808});
            sheep.put("PersistenceRequired", (byte) 1);
            sheep.put("Color", (byte) 11);

            LinkedHashMap<String, Object> entityChunk = new LinkedHashMap<>();
            entityChunk.put("DataVersion", 4671);
            entityChunk.put("Position", new int[] {0, 0});
            entityChunk.put("Entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(villager)));
            session.entityStorage().writeChunkCompound(0, 0, entityChunk);

            // CoW update on entity chunk: add sheep and update villager health
            villager.put("Health", 18.5f);
            entityChunk.put("Entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(villager, sheep)));
            session.entityStorage().writeChunkCompound(0, 0, entityChunk);

            session.flush();
        }

        // Reopen session cold from disk and verify all block Properties, container Items, and mob Entities
        try (LcaRegionFileStorage.LcaWorldStorageSession reopened =
                new LcaRegionFileStorage.LcaWorldStorageSession(tempDir, -4, 24, 4671, false, true)) {
            LinkedHashMap<String, Object> readChunk = reopened.voxelStorage().readChunkCompound(0, 0);
            assertNotNull(readChunk);

            // 1. Verify decoded section Y=4 palette contains exact Properties maps for stairs, chest, repeater
            Nbt.NbtList secs = AnvilReader.getSections(readChunk);
            Map<?, ?> readSecY4 = null;
            for (Object o : secs.items) {
                Map<?, ?> m = (Map<?, ?>) o;
                if (((Number) m.get("Y")).intValue() == 4) {
                    readSecY4 = m;
                    break;
                }
            }
            assertNotNull(readSecY4, "Section Y=4 must exist after reopen");
            Map<?, ?> readBs = (Map<?, ?>) readSecY4.get("block_states");
            Nbt.NbtList readPal = (Nbt.NbtList) readBs.get("palette");
            long[] readData = (long[]) readBs.get("data");
            assertNotNull(readData);

            int stairsIdx = PackedPaletteDecoder.decode(readData, readPal.items.size(), 306);
            Map<?, ?> decodedStairs = (Map<?, ?>) readPal.items.get(stairsIdx);
            assertEquals("minecraft:oak_stairs", decodedStairs.get("Name"));
            Map<?, ?> decStairsProps = (Map<?, ?>) decodedStairs.get("Properties");
            assertNotNull(decStairsProps);
            assertEquals("north", decStairsProps.get("facing"));
            assertEquals("bottom", decStairsProps.get("half"));
            assertEquals("straight", decStairsProps.get("shape"));
            assertEquals("false", decStairsProps.get("waterlogged"));

            int chestIdx = PackedPaletteDecoder.decode(readData, readPal.items.size(), 372);
            Map<?, ?> decodedChestBlock = (Map<?, ?>) readPal.items.get(chestIdx);
            assertEquals("minecraft:chest", decodedChestBlock.get("Name"));
            Map<?, ?> decChestProps = (Map<?, ?>) decodedChestBlock.get("Properties");
            assertNotNull(decChestProps);
            assertEquals("south", decChestProps.get("facing"));
            assertEquals("single", decChestProps.get("type"));
            assertEquals("false", decChestProps.get("waterlogged"));

            int repIdx = PackedPaletteDecoder.decode(readData, readPal.items.size(), 601);
            Map<?, ?> decodedRepeater = (Map<?, ?>) readPal.items.get(repIdx);
            assertEquals("minecraft:repeater", decodedRepeater.get("Name"));
            Map<?, ?> decRepProps = (Map<?, ?>) decodedRepeater.get("Properties");
            assertNotNull(decRepProps);
            assertEquals("4", decRepProps.get("delay"));
            assertEquals("west", decRepProps.get("facing"));
            assertEquals("true", decRepProps.get("locked"));
            assertEquals("true", decRepProps.get("powered"));

            // 2. Verify container block_entities Items and components survived CoW
            Nbt.NbtList blockEntities = (Nbt.NbtList) readChunk.get("block_entities");
            assertNotNull(blockEntities);
            assertEquals(1, blockEntities.items.size());
            Map<?, ?> readChestBe = (Map<?, ?>) blockEntities.items.get(0);
            assertEquals("minecraft:chest", readChestBe.get("id"));
            assertEquals(4, ((Number) readChestBe.get("x")).intValue());
            assertEquals(65, ((Number) readChestBe.get("y")).intValue());
            assertEquals(7, ((Number) readChestBe.get("z")).intValue());
            Nbt.NbtList items = (Nbt.NbtList) readChestBe.get("Items");
            assertNotNull(items);
            assertEquals(3, items.items.size());
            Map<?, ?> item0 = (Map<?, ?>) items.items.get(0);
            assertEquals("minecraft:diamond_sword", item0.get("id"));
            Map<?, ?> comps0 = (Map<?, ?>) item0.get("components");
            assertEquals(42, ((Number) comps0.get("minecraft:damage")).intValue());
            assertEquals("\"Excalibur\"", comps0.get("minecraft:custom_name"));
            Map<?, ?> item2 = (Map<?, ?>) items.items.get(2);
            assertEquals((byte) 26, item2.get("Slot"));
            assertEquals("minecraft:emerald_block", item2.get("id"));
            assertEquals(64, ((Number) item2.get("count")).intValue());

            // 3. Verify persistent mobs in entities/r.0.0.lca survived CoW
            LinkedHashMap<String, Object> readEntChunk = reopened.entityStorage().readChunkCompound(0, 0);
            assertNotNull(readEntChunk);
            Nbt.NbtList entities = (Nbt.NbtList) readEntChunk.get("Entities");
            assertNotNull(entities);
            assertEquals(2, entities.items.size());
            Map<?, ?> readVillager = (Map<?, ?>) entities.items.get(0);
            assertEquals("minecraft:villager", readVillager.get("id"));
            assertEquals((byte) 1, readVillager.get("PersistenceRequired"));
            assertEquals("\"LeafArchivist\"", readVillager.get("CustomName"));
            assertEquals(18.5f, ((Number) readVillager.get("Health")).floatValue(), 0.001f);
            Map<?, ?> readSheep = (Map<?, ?>) entities.items.get(1);
            assertEquals("minecraft:sheep", readSheep.get("id"));
            assertEquals((byte) 11, readSheep.get("Color"));
        }
    }

    @Test
    @DisplayName("REQ-RTP-S-004 / REQ-RTP-S-005: Mode B uncompressed ChunkFrame header SEC-DED bit-rot healing and header-only LcaLiveArchive")
    void modeBUncompressedHeaderSecDedHealingAndHeaderOnlyLiveArchive(@TempDir Path tempDir) throws IOException {
        byte[] mcaBytes = loadFixture("/anvil/real/1_21_R1/r.0.0.mca");
        AnvilReader.ChunkEntry orig00 = AnvilReader.readChunkEntry(mcaBytes, 0, 0);
        assertNotNull(orig00);

        // 1. Encode chunk (0, 0) in Mode B (Zstd-1 with uncompressed 72B ChunkFrame header + 9B SEC-DED parity)
        LcaRegionWriter.LcaLiveArchive modeBArchive =
                LcaRegionWriter.createEmptyLiveArchive(0, 0, -4, 24, 4671, true);
        modeBArchive.saveChunkCoW(0, 0, orig00.root);
        byte[] modeBBytes = modeBArchive.toByteArray();

        long ptr = LcaRegionReader.getVolatileLongBE(modeBBytes, LcaRegionReader.POINTER_DIRECTORY_OFFSET);
        int slabStart = (int) (ptr >>> 29);
        int codec1 = (int) ((ptr >>> 28) & 1L);
        assertEquals(1, codec1);
        int slabByteOff = slabStart << LcaRegionReader.SLAB_SHIFT;

        // Verify the first 4 bytes of the Mode B slab are the uncompressed self-describing ChunkFrame header
        int dvWord = LcaSectionCodec.readIntBE(modeBBytes, slabByteOff);
        assertEquals(LcaRegionReader.CHUNK_FRAME_SELF_TAG_MAGIC, dvWord & LcaRegionReader.CHUNK_FRAME_SELF_TAG_MASK);

        // Inject 1-bit flips into multiple 64-bit words of the uncompressed 72B ChunkFrame header:
        // - Word 0 (selfTagAndDataVersion + lastUpdate high 32 bits)
        // - Word 2 (statusOrdinal + chunkFlags + residualNbtRelOffset + sectionOffsets[0..1])
        // - Word 5 (sectionOffsets[8..11])
        byte[] corruptedModeB = Arrays.copyOf(modeBBytes, modeBBytes.length);
        corruptedModeB[slabByteOff + 1] ^= 0x04;  // Flip a bit in chunkIdx inside word 0
        corruptedModeB[slabByteOff + 21] ^= 0x02; // Flip hasSlabLocalPalette bit in chunkFlags inside word 2
        corruptedModeB[slabByteOff + 42] ^= 0x10; // Flip a bit in sectionOffsets[] inside word 5

        LcaRegionReader.RecoveryReport report = new LcaRegionReader.RecoveryReport();
        AnvilChunkView healedView = LcaRegionReader.readChunkViewWithRecovery(corruptedModeB, 0, 0, report);
        AnvilChunkView expectedView = AnvilReader.readChunkView(mcaBytes, 0, 0);
        assertNotNull(healedView);
        assertNotNull(expectedView);
        assertEquals(3, report.chunkHeaderSecDedHealedWords(), "All 3 corrupted 64-bit header words must be healed by SEC-DED");
        assertEquals(expectedView.blockIdAt(8, 64, 8), healedView.blockIdAt(8, 64, 8));

        // Verify healRegionBitRot also repairs Mode B ChunkFrame header bit flips in-place
        byte[] corruptedForRegionHeal = Arrays.copyOf(modeBBytes, modeBBytes.length);
        corruptedForRegionHeal[slabByteOff + 2] ^= 0x08;
        LcaRegionReader.RecoveryReport regionReport = LcaRegionReader.healRegionBitRot(corruptedForRegionHeal);
        assertEquals(1, regionReport.chunkHeaderSecDedHealedWords());
        assertEquals(dvWord, LcaSectionCodec.readIntBE(corruptedForRegionHeal, slabByteOff));

        // Verify a 2-bit flip inside a single 64-bit header word is caught as unrecoverable CBIT T3c DED
        byte[] doubleBitCorrupt = Arrays.copyOf(modeBBytes, modeBBytes.length);
        doubleBitCorrupt[slabByteOff + 20] ^= 0x03; // 2-bit flip in word 2
        assertThrows(CorruptRegionEntryException.class, () -> LcaRegionReader.readChunkView(doubleBitCorrupt, 0, 0));

        // 2. Verify disk-backed LcaLiveArchive stays header-only (~25.6 KB) and updates FILE_HEADER_CACHE in-place
        Path lcaFile = tempDir.resolve("r.0.0.lca");
        Files.write(lcaFile, modeBBytes);
        assertTrue(LcaRegionReader.isChunkGeneratedInFile(lcaFile, 0, 0));
        assertFalse(LcaRegionReader.isChunkGeneratedInFile(lcaFile, 1, 0));

        try (LcaRegionWriter.LcaLiveArchive diskArchive = LcaRegionWriter.openLiveArchive(lcaFile)) {
            assertTrue(diskArchive.hasChunk(0, 0));
            assertFalse(diskArchive.hasChunk(1, 0));
            // Save chunk (1, 0) via header-only FileChannel CoW and verify FILE_HEADER_CACHE sees (1, 0) immediately
            diskArchive.saveChunkCoW(1, 0, orig00.root);
            assertTrue(diskArchive.hasChunk(1, 0));
            assertTrue(LcaRegionReader.isChunkGeneratedInFile(lcaFile, 1, 0));

            AnvilReader.ChunkEntry readBack10 = diskArchive.readChunkEntry(1, 0);
            assertNotNull(readBack10);
            assertEquals(expectedView.dataVersion(), AnvilReader.getDataVersion(readBack10.root));
        }
    }

    @Test
    @DisplayName("REQ-RTP-S-005 / anvil-api-ADR-003: Direct ChunkFrame->binary NBT streaming & lock-free multi-threaded FileChannel reads")
    void directChunkNbtStreamingAndLockFreeConcurrentReads(@TempDir Path tempDir) throws Exception {
        byte[] mcaBytes = loadFixture("/anvil/real/1_21_R1/r.0.0.mca");
        byte[] lcaBytes = LcaRegionWriter.transcodeFromMca(mcaBytes, 0, 0, false);

        // 1. Verify direct binary NBT streaming produces 100% identical decoded NBT compound to readChunk()
        byte[] directNbt = LcaRegionReader.INSTANCE.readChunkNbtBytes(lcaBytes, 0, 0);
        assertNotNull(directNbt);
        LinkedHashMap<String, Object> fromDirectNbt = Nbt.readRootCompound(directNbt);
        LinkedHashMap<String, Object> fromMapDecode = LcaRegionReader.INSTANCE.readChunk(lcaBytes, 0, 0).root;
        assertEquals(fromMapDecode.keySet(), fromDirectNbt.keySet());
        AnvilChunkView viewDirect = AnvilReader.toView(fromDirectNbt);
        AnvilChunkView viewMap = AnvilReader.toView(fromMapDecode);
        for (int y = -64; y < 256; y += 8) {
            assertEquals(viewMap.blockIdAt(8, y, 8), viewDirect.blockIdAt(8, y, 8));
        }
        Nbt.NbtList directSecs = (Nbt.NbtList) fromDirectNbt.get("sections");
        Nbt.NbtList mapSecs = (Nbt.NbtList) fromMapDecode.get("sections");
        assertEquals(mapSecs.items.size(), directSecs.items.size());
        for (int s = 0; s < mapSecs.items.size(); s++) {
            Map<?, ?> mSec = (Map<?, ?>) mapSecs.items.get(s);
            Map<?, ?> dSec = (Map<?, ?>) directSecs.items.get(s);
            assertEquals(mSec.get("Y"), dSec.get("Y"));
            byte[] mSky = (byte[]) mSec.get("SkyLight");
            byte[] dSky = (byte[]) dSec.get("SkyLight");
            org.junit.jupiter.api.Assertions.assertArrayEquals(mSky, dSky, "SkyLight parity at section index " + s);
            byte[] mBlk = (byte[]) mSec.get("BlockLight");
            byte[] dBlk = (byte[]) dSec.get("BlockLight");
            org.junit.jupiter.api.Assertions.assertArrayEquals(mBlk, dBlk, "BlockLight parity at section index " + s);
        }

        // 2. Benchmark direct binary NBT streaming vs legacy (readChunk -> Nbt.writeNamedRoot) vs .mca zlib->NBT
        int warmup = 1000;
        int iters = 2000;
        int sink = 0;
        for (int i = 0; i < warmup; i++) {
            sink += LcaRegionReader.INSTANCE.readChunkNbtBytes(lcaBytes, 0, 0).length;
            sink += Nbt.writeNamedRoot("", LcaRegionReader.INSTANCE.readChunk(lcaBytes, 0, 0).root).length;
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            sink += LcaRegionReader.INSTANCE.readChunkNbtBytes(lcaBytes, 0, 0).length;
        }
        double directUs = (System.nanoTime() - t0) / 1000.0 / iters;

        long t1 = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            sink += Nbt.writeNamedRoot("", LcaRegionReader.INSTANCE.readChunk(lcaBytes, 0, 0).root).length;
        }
        double boxedMapUs = (System.nanoTime() - t1) / 1000.0 / iters;

        System.out.printf(Locale.ROOT,
                "%n[LCA-Direct-NBT] directStream=%.2f us vs boxedMapRoundTrip=%.2f us (%.2fx faster, sink=%d)%n",
                directUs, boxedMapUs, boxedMapUs / Math.max(0.01, directUs), sink);
        assertTrue(directUs < boxedMapUs,
                "Direct binary NBT stream (" + directUs + " us) must be faster than boxed Map round-trip (" + boxedMapUs + " us)");

        // 3. Verify lock-free 16-thread concurrent getChunkDataInputStream reads on LcaRegionFileStorage
        Path regionDir = tempDir.resolve("region");
        Files.createDirectories(regionDir);
        Files.write(regionDir.resolve("r.0.0.lca"), lcaBytes);

        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(regionDir)) {
            int threads = 16;
            int readsPerThread = 128;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch startLatch = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>(threads);
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    startLatch.await();
                    int totalBytes = 0;
                    for (int r = 0; r < readsPerThread; r++) {
                        try (DataInputStream dis = storage.getChunkDataInputStream(0, 0)) {
                            assertNotNull(dis);
                            byte[] all = dis.readAllBytes();
                            assertEquals(directNbt.length, all.length);
                            totalBytes += all.length;
                        }
                    }
                    return totalBytes;
                }));
            }
            startLatch.countDown();
            for (Future<Integer> f : futures) {
                assertEquals(readsPerThread * directNbt.length, f.get().intValue());
            }
            pool.shutdownNow();
        }

        // 4. Verify multi-segment chained dictionary (>60 custom block states with non-static property suffixes)
        //    through both directChunkNbtStreaming and LcaRegionWriter.convertModeAToModeB (Mode A -> Mode B)
        LcaRegionWriter.LcaLiveArchive multiSegArchive = LcaRegionWriter.createEmptyLiveArchive(0, 0, -4, 24, 3953, false);
        LinkedHashMap<String, Object> multiStateRoot = Nbt.readRootCompound(Nbt.writeNamedRoot("", fromMapDecode));
        Nbt.NbtList sectionsList = (Nbt.NbtList) multiStateRoot.get("sections");
        @SuppressWarnings("unchecked")
        LinkedHashMap<String, Object> sec0 = (LinkedHashMap<String, Object>) sectionsList.items.get(0);
        @SuppressWarnings("unchecked")
        LinkedHashMap<String, Object> bs0 = (LinkedHashMap<String, Object>) sec0.get("block_states");
        List<Object> customPaletteItems = new ArrayList<>();
        for (int p = 0; p < 64; p++) {
            LinkedHashMap<String, Object> palEntry = new LinkedHashMap<>();
            palEntry.put("Name", "minecraft:custom_block_" + p);
            LinkedHashMap<String, Object> props = new LinkedHashMap<>();
            props.put("facing", (p % 2 == 0) ? "north" : "south");
            props.put("power", Integer.toString(p % 16));
            props.put("variant_key_" + p, "val_" + p);
            palEntry.put("Properties", props);
            customPaletteItems.add(palEntry);
        }
        bs0.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, customPaletteItems));
        long[] customData = new long[384]; // 6 bits per block -> 10 blocks per long -> 4096 blocks = 410 longs
        customData = new long[410];
        for (int idx = 0; idx < 4096; idx++) {
            int pIdx = idx & 63;
            int longIdx = idx / 10;
            int bitOff = (idx % 10) * 6;
            customData[longIdx] |= ((long) pIdx) << bitOff;
        }
        bs0.put("data", customData);

        multiSegArchive.saveChunkCoW(0, 0, multiStateRoot);
        multiSegArchive.saveChunkCoW(5, 7, fromMapDecode);
        assertTrue(multiSegArchive.totalDictionaryStates() >= 64,
                "Expected >= 64 dictionary states to trigger continuation dictionary segment");

        byte[] modeAMultiBytes = multiSegArchive.toByteArray();
        byte[] modeANbt00 = LcaRegionReader.INSTANCE.readChunkNbtBytes(modeAMultiBytes, 0, 0);
        assertNotNull(modeANbt00);
        AnvilChunkView modeAView00 = AnvilReader.toView(Nbt.readRootCompound(modeANbt00));
        assertEquals("minecraft:custom_block_0", modeAView00.blockIdAt(0, -64, 0));

        {
            LcaRegionWriter.LcaLiveArchive multiSegModeBArchive =
                    LcaRegionWriter.createEmptyLiveArchive(0, 0, -4, 24, 3953, true);
            multiSegModeBArchive.saveChunkCoW(0, 0, multiStateRoot);
            multiSegModeBArchive.saveChunkCoW(5, 7, fromMapDecode);
            byte[] modeBMultiBytes = multiSegModeBArchive.toByteArray();
            // Verify superblock magic, Mode B flag (0x19), and footer canary
            assertEquals(LcaRegionReader.LCA_SUPERBLOCK_MAGIC,
                    (long) LcaRegionReader.LONG_BE_VIEW.get(modeBMultiBytes, 0));
            assertEquals(0x19, modeBMultiBytes[0x0B] & 0xFF);
            assertEquals(LcaRegionReader.LCA_FOOTER_CANARY_MAGIC,
                    (long) LcaRegionReader.LONG_BE_VIEW.get(modeBMultiBytes, modeBMultiBytes.length - 8));

            // Verify direct binary NBT streaming and FileChannel reads on Mode B multi-segment region
            byte[] modeBNbt00 = LcaRegionReader.INSTANCE.readChunkNbtBytes(modeBMultiBytes, 0, 0);
            assertNotNull(modeBNbt00);
            org.junit.jupiter.api.Assertions.assertArrayEquals(modeANbt00, modeBNbt00,
                    "Mode A and Mode B must emit byte-identical binary NBT for multi-segment dictionary chunk");

            Path modeBFile = tempDir.resolve("r.0.0.modeb.lca");
            Files.write(modeBFile, modeBMultiBytes);
            byte[] fromChannel00 = LcaRegionReader.readChunkNbtBytesFromFile(modeBFile, 0, 0);
            org.junit.jupiter.api.Assertions.assertArrayEquals(modeANbt00, fromChannel00);
            byte[] modeANbt57 = LcaRegionReader.INSTANCE.readChunkNbtBytes(modeAMultiBytes, 5, 7);
            byte[] fromChannel57 = LcaRegionReader.readChunkNbtBytesFromFile(modeBFile, 5, 7);
            org.junit.jupiter.api.Assertions.assertArrayEquals(modeANbt57, fromChannel57);
        }
    }

    @Test
    @DisplayName("anvil-api-ADR-003 & LCA-ICD-1.0: Order-independent sectionOffsets[] (before & after Residual Tail) and HAS_CHUNK_PATCH_TAIL (0x4000) parity + CBIT T5/T6b/Tier-2 recovery")
    void orderIndependentSectionOffsetsAndChunkPatchTailParity() throws Exception {
        byte[] mcaBytes = loadFixture("/anvil/real/1_21_R1/r.0.0.mca");
        AnvilReader.ChunkEntry origEntry = AnvilReader.readChunkEntry(mcaBytes, 0, 0);
        assertNotNull(origEntry);

        for (boolean modeB : new boolean[] {false, true}) {

            // 1. Out-of-order appended SectionFrame tests (both before residualRelStart and after Residual Tail,
            //    including sectionIdx=0 to exercise min_k(relStart_k) initial micro-palette bounding)
            for (boolean afterResidual : new boolean[] {false, true}) {
                for (boolean viaPatchTailOffset : new boolean[] {false, true}) {
                    byte[] baseLca = LcaRegionWriter.transcodeFromMca(mcaBytes, 0, 0, modeB);
                    LcaRegionWriter.LcaLiveArchive archive = LcaRegionWriter.openLiveArchive(baseLca);

                    // Build a modified section for Y = -64 (secIdx = 0) and Y = 64 (secIdx = 8)
                    LinkedHashMap<String, Object> clonedRoot =
                            Nbt.readRootCompound(Nbt.writeNamedRoot("", origEntry.root));
                    // Also inject a non-empty block_entities entry so bit 10 (residualNbtSlice) is active!
                    LinkedHashMap<String, Object> fakeBe = new LinkedHashMap<>();
                    fakeBe.put("id", "minecraft:chest");
                    fakeBe.put("x", 2);
                    fakeBe.put("y", 65);
                    fakeBe.put("z", 3);
                    clonedRoot.put("block_entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(fakeBe)));
                    archive.saveChunkCoW(0, 0, clonedRoot);

                    Nbt.NbtList secs = (Nbt.NbtList) clonedRoot.get("sections");
                    LinkedHashMap<String, Object> targetSec0 = null;
                    LinkedHashMap<String, Object> targetSec4 = null;
                    for (Object o : secs.items) {
                        @SuppressWarnings("unchecked")
                        LinkedHashMap<String, Object> sm = (LinkedHashMap<String, Object>) o;
                        int sy = ((Number) sm.get("Y")).intValue();
                        if (sy == -4) targetSec0 = sm;
                        if (sy == 4) targetSec4 = sm;
                    }
                    assertNotNull(targetSec0);
                    assertNotNull(targetSec4);

                    // Introduce a brand-new block state with properties into section Y = -4 (secIdx = 0)
                    @SuppressWarnings("unchecked")
                    LinkedHashMap<String, Object> bs0 = (LinkedHashMap<String, Object>) targetSec0.get("block_states");
                    @SuppressWarnings("unchecked")
                    List<Object> pal0 = new ArrayList<>(((Nbt.NbtList) bs0.get("palette")).items);
                    LinkedHashMap<String, Object> newBlock = new LinkedHashMap<>();
                    newBlock.put("Name", "minecraft:oak_stairs");
                    LinkedHashMap<String, Object> newProps = new LinkedHashMap<>();
                    newProps.put("facing", "north");
                    newProps.put("half", "bottom");
                    newProps.put("waterlogged", "false");
                    newBlock.put("Properties", newProps);
                    int newPalIdx = pal0.size();
                    pal0.add(newBlock);
                    bs0.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, pal0));
                    int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(pal0.size() - 1));
                    int entriesPerLong = 64 / bits;
                    int numLongs = (4096 + entriesPerLong - 1) / entriesPerLong;
                    long[] packed0 = new long[numLongs];
                    // Place the new block at (x=3, y=2, z=5) -> yzx = (2 << 8) | (5 << 4) | 3 = 595
                    int targetYzx = (2 << 8) | (5 << 4) | 3;
                    int wIdx = targetYzx / entriesPerLong;
                    int bOff = (targetYzx % entriesPerLong) * bits;
                    packed0[wIdx] |= ((long) newPalIdx) << bOff;
                    bs0.put("data", packed0);

                    archive.spliceOutOfOrderSectionCoW(0, 0, -4, targetSec0, afterResidual, viaPatchTailOffset);
                    byte[] splicedLca = archive.toByteArray();

                    AnvilChunkView view = LcaRegionReader.readChunkView(splicedLca, 0, 0);
                    assertNotNull(view);
                    assertEquals("minecraft:oak_stairs", view.blockIdAt(3, -64 + 2, 5),
                            "Out-of-order section (afterResidual=" + afterResidual
                                    + ", viaPatchTailOffset=" + viaPatchTailOffset + ", modeB=" + modeB + ") failed in readChunkView");

                    ColumnProbe colProbe = LcaRegionReader.readColumnProbe(splicedLca, 0, 0, -64, 0);
                    assertNotNull(colProbe);
                    assertEquals("minecraft:oak_stairs", colProbe.blockAt(3, 5, -64 + 2));

                    ColumnProbe terProbe = LcaRegionReader.readTerrainProbe(
                            splicedLca, splicedLca.length, 0, 0, -64, 0);
                    assertNotNull(terProbe);
                    assertEquals("minecraft:oak_stairs", terProbe.blockAt(3, 5, -64 + 2));

                    PaletteSection singleSec = LcaRegionReader.readSection(splicedLca, splicedLca.length, 0, 0, -4);
                    assertNotNull(singleSec);
                    assertEquals("minecraft:oak_stairs", singleSec.blockIdAt(3, 2, 5));

                    AnvilReader.ChunkEntry decodedEntry = LcaRegionReader.INSTANCE.readChunk(splicedLca, 0, 0);
                    assertNotNull(decodedEntry);
                    // Verify residual NBT (block_entities chest) was NOT truncated or corrupted by post-residual section!
                    Nbt.NbtList decodedBe = (Nbt.NbtList) decodedEntry.root.get("block_entities");
                    assertNotNull(decodedBe);
                    assertEquals(1, decodedBe.items.size());

                    byte[] nbtBytes = LcaRegionReader.INSTANCE.readChunkNbtBytes(splicedLca, 0, 0);
                    assertNotNull(nbtBytes);
                    AnvilChunkView fromNbtBytes = AnvilReader.toView(Nbt.readRootCompound(nbtBytes));
                    assertEquals("minecraft:oak_stairs", fromNbtBytes.blockIdAt(3, -64 + 2, 5));

                    // In Mode A, test Tier-2 healRegionBitRot (estimateSelfDescribingChunkLengthStatic)
                    if (!modeB) {
                        byte[] corruptedPtrLca = Arrays.copyOf(splicedLca, splicedLca.length);
                        // Wipe pointer 0 and its SEC-DED byte to force Tier-2 self-describing slab reverse-scan
                        LcaRegionReader.setVolatileLongBE(corruptedPtrLca, LcaRegionReader.POINTER_DIRECTORY_OFFSET, 0L);
                        int secDedOff = (int) ((long) LcaRegionReader.LONG_BE_VIEW.get(corruptedPtrLca, 0x28) & 0xFFFF_FFFFL);
                        corruptedPtrLca[secDedOff] = (byte) 0xFF;

                        LcaRegionReader.RecoveryReport rep = LcaRegionReader.healRegionBitRot(corruptedPtrLca);
                        assertEquals(1, rep.slabScanReconstructedPointers());
                        AnvilChunkView healedView = LcaRegionReader.readChunkView(corruptedPtrLca, 0, 0);
                        assertNotNull(healedView);
                        assertEquals("minecraft:oak_stairs", healedView.blockIdAt(3, -64 + 2, 5));
                    }
                }
            }

            // 2. Test HAS_CHUNK_PATCH_TAIL (0x4000) voxel deltas, light patches, and appended micro-palette entries
            //    (including inline-uniform section referencing an appended micro-palette state!)
            byte[] baseLca = LcaRegionWriter.transcodeFromMca(mcaBytes, 0, 0, modeB);
            LcaRegionWriter.LcaLiveArchive patchArchive = LcaRegionWriter.openLiveArchive(baseLca);

            // Inspect base local palette count so we know the localDictId of our appended states
            byte[] rawUncomp = patchArchive.readUncompressedChunkFrame(0, 0);
            int secDirEnd = 24 + (24 << 1);
            int resRelStart = secDirEnd + LcaSectionCodec.readUint16BE(rawUncomp, 22);
            int[] relStarts = new int[24];
            for (int i = 0; i < 24; i++) {
                int e16 = LcaSectionCodec.readUint16BE(rawUncomp, 24 + (i << 1));
                relStarts[i] = (e16 != 0 && (e16 & 0x8000) == 0) ? (secDirEnd + e16 - 1) : -1;
            }
            int palLim = LcaSectionCodec.findFirstSectionRelStart(relStarts, 24, resRelStart);
            LcaSectionCodec.SlabLocalPalette basePal = LcaSectionCodec.decodeSlabLocalPalette(
                    rawUncomp, 0, secDirEnd, palLim, false, false, List.of());
            int baseK = basePal.baseBlockNames.size();

            // Append two new states:
            //   K + 0: "minecraft:lantern[hanging=true,waterlogged=false]" (static core property suffix)
            //   K + 1: "minecraft:custom_mod_lamp[custom_power=11]" (Tier-3 region dictionary escape)
            List<String> appendedStates = List.of(
                    "minecraft:lantern[hanging=true,waterlogged=false]",
                    "minecraft:custom_mod_lamp[custom_power=11]");

            // Patch section index 6 (Y = -4 + 6 = 2, world Y = 32..47) with 2 voxel deltas + custom SkyLight & BlockLight
            int yzxA = (1 << 8) | (2 << 4) | 3; // (3, 33, 2)
            int yzxB = (7 << 8) | (8 << 4) | 9; // (9, 39, 8)
            byte[] customSky = new byte[2048];
            Arrays.fill(customSky, (byte) 0xCC); // const nibble 12
            byte[] customBlk = new byte[2048];
            customBlk[0] = (byte) 0x5E; // non-uniform Gray light bitplanes

            LcaSectionCodec.SectionPatch sec6Patch = new LcaSectionCodec.SectionPatch(
                    6,
                    LcaSectionCodec.PATCH_FLAG_VOXEL_DELTAS
                            | LcaSectionCodec.PATCH_FLAG_SKY_LIGHT
                            | LcaSectionCodec.PATCH_FLAG_BLOCK_LIGHT,
                    0,
                    new int[] {yzxA, yzxB},
                    new int[] {baseK, baseK + 1},
                    customSky,
                    customBlk);

            // Also patch top sky section index 23 (Y = 19, world Y = 304..319) via PATCH_FLAG_REPLACEMENT_OFFSET
            // to an inline-uniform entry (0x8000) referencing appended state K + 0!
            int inlineAppendedEntry16 = 0x8000 | (0b01 << 13) | (3 << 11) | (baseK & 0x07FF);
            LcaSectionCodec.SectionPatch sec23InlinePatch = new LcaSectionCodec.SectionPatch(
                    23,
                    LcaSectionCodec.PATCH_FLAG_REPLACEMENT_OFFSET,
                    inlineAppendedEntry16,
                    null,
                    null,
                    null,
                    null);

            // Also patch inline-uniform section index 22 (Y = 18, world Y = 288..303) with a voxel delta
            // so createUniformDecodedSection + applySectionPatch on a uniform base is exercised!
            int yzxUniformDelta = (0 << 8) | (4 << 4) | 5; // (5, 288, 4)
            LcaSectionCodec.SectionPatch sec22UniformVoxelPatch = new LcaSectionCodec.SectionPatch(
                    22,
                    LcaSectionCodec.PATCH_FLAG_VOXEL_DELTAS,
                    0,
                    new int[] {yzxUniformDelta},
                    new int[] {baseK},
                    null,
                    null);

            patchArchive.applyChunkPatchCoW(0, 0, appendedStates, List.of(sec6Patch, sec22UniformVoxelPatch, sec23InlinePatch));
            // Splice an out-of-order section AFTER the Residual Tail on the already-patched chunk, then re-apply
            // a patch tail so findMaxPostResidualSectionStart and post-residual offset adjustment are exercised!
            byte[] patchedLca = patchArchive.toByteArray();

            // Verify across readChunkView, readColumnProbe, readTerrainProbe, readSection, readChunk, and readChunkNbtBytes
            AnvilChunkView patchedView = LcaRegionReader.readChunkView(patchedLca, 0, 0);
            assertNotNull(patchedView);
            assertEquals("minecraft:lantern", patchedView.blockIdAt(3, 33, 2));
            assertEquals("minecraft:custom_mod_lamp", patchedView.blockIdAt(9, 39, 8));
            assertEquals("minecraft:lantern", patchedView.blockIdAt(5, 288, 4),
                    "Voxel delta on inline-uniform section 22 must resolve via createUniformDecodedSection");
            assertEquals("minecraft:lantern", patchedView.blockIdAt(0, 304, 0),
                    "Inline-uniform section must resolve appended micro-palette state K");

            ColumnProbe patchedCol = LcaRegionReader.readColumnProbe(patchedLca, 0, 0, 32, 319);
            assertNotNull(patchedCol);
            assertEquals("minecraft:lantern", patchedCol.blockAt(3, 2, 33));
            assertEquals("minecraft:custom_mod_lamp", patchedCol.blockAt(9, 8, 39));
            assertEquals("minecraft:lantern", patchedCol.blockAt(5, 4, 288));
            assertEquals("minecraft:lantern", patchedCol.blockAt(5, 5, 310));

            ColumnProbe patchedTer = LcaRegionReader.readTerrainProbe(patchedLca, patchedLca.length, 0, 0, 32, 319);
            assertNotNull(patchedTer);
            assertEquals("minecraft:lantern", patchedTer.blockAt(3, 2, 33));
            assertEquals("minecraft:custom_mod_lamp", patchedTer.blockAt(9, 8, 39));
            assertEquals("minecraft:lantern", patchedTer.blockAt(5, 4, 288));

            AnvilReader.ChunkEntry patchedChunkEntry = LcaRegionReader.INSTANCE.readChunk(patchedLca, 0, 0);
            assertNotNull(patchedChunkEntry);
            byte[] patchedNbtBytes = LcaRegionReader.INSTANCE.readChunkNbtBytes(patchedLca, 0, 0);
            assertNotNull(patchedNbtBytes);
            LinkedHashMap<String, Object> reparsedPatchedRoot = Nbt.readRootCompound(patchedNbtBytes);
            AnvilChunkView fromPatchedNbt = AnvilReader.toView(reparsedPatchedRoot);
            assertEquals("minecraft:lantern", fromPatchedNbt.blockIdAt(3, 33, 2));
            assertEquals("minecraft:custom_mod_lamp", fromPatchedNbt.blockIdAt(9, 39, 8));
            assertEquals("minecraft:lantern", fromPatchedNbt.blockIdAt(5, 288, 4));
            assertEquals("minecraft:lantern", fromPatchedNbt.blockIdAt(0, 304, 0));

            // Verify patched SkyLight & BlockLight in section Y=2 (secIdx=6)
            Nbt.NbtList patchedSecs = (Nbt.NbtList) patchedChunkEntry.root.get("sections");
            LinkedHashMap<String, Object> decodedSecY2 = null;
            for (Object o : patchedSecs.items) {
                @SuppressWarnings("unchecked")
                LinkedHashMap<String, Object> sm = (LinkedHashMap<String, Object>) o;
                if (((Number) sm.get("Y")).intValue() == 2) {
                    decodedSecY2 = sm;
                    break;
                }
            }
            assertNotNull(decodedSecY2);
            org.junit.jupiter.api.Assertions.assertArrayEquals(customSky, (byte[]) decodedSecY2.get("SkyLight"));
            org.junit.jupiter.api.Assertions.assertArrayEquals(customBlk, (byte[]) decodedSecY2.get("BlockLight"));

            // Exercise spliceOutOfOrderSectionCoW(afterResidual=true) on the already-patched chunk, followed by
            // another applyChunkPatchCoW replacement (covers findMaxPostResidualSectionStart + post-residual shift)
            patchArchive.spliceOutOfOrderSectionCoW(0, 0, 2, decodedSecY2, true, true);
            patchArchive.applyChunkPatchCoW(
                    0, 0, appendedStates, List.of(sec6Patch, sec22UniformVoxelPatch, sec23InlinePatch));
            if (!modeB) {
                byte[] postResPatched = patchArchive.toByteArray();
                long p0 = LcaRegionReader.getVolatileLongBE(postResPatched, LcaRegionReader.POINTER_DIRECTORY_OFFSET);
                int fs0 = (int) ((p0 >>> 29) << LcaRegionReader.SLAB_SHIFT);
                int estLen = LcaRegionReader.estimateSelfDescribingChunkLengthStatic(
                        postResPatched, fs0, 24, false, postResPatched.length - 8);
                assertTrue(estLen > 72);
            }

            // 3. CBIT T6b tripwire: corrupting the 1-byte patchParity8 at the end of ChunkPatchTail must fail closed
            if (!modeB) {
                byte[] badParityLca = Arrays.copyOf(patchedLca, patchedLca.length);
                long ptr = LcaRegionReader.getVolatileLongBE(badParityLca, LcaRegionReader.POINTER_DIRECTORY_OFFSET);
                int fStart = (int) ((ptr >>> 29) << LcaRegionReader.SLAB_SHIFT);
                int fLen = (int) ((ptr >>> 8) & 0xFFFFFL);
                // Locate the exact byte offset of patchParity8 inside the Residual Tail (immediately before bit 10 residualNbtSlice)
                byte[] expectedPatchBytes = LcaSectionCodec.encodeChunkPatchTailBytes(
                        baseK, appendedStates, new int[] {-1, 0}, List.of(sec6Patch, sec22UniformVoxelPatch, sec23InlinePatch));
                int parityByteSubIdx = -1;
                for (int off = fStart + resRelStart; off <= fStart + fLen - 4; off++) {
                    if (badParityLca[off] == expectedPatchBytes[0]
                            && badParityLca[off + 1] == expectedPatchBytes[1]
                            && badParityLca[off + 2] == expectedPatchBytes[2]) {
                        parityByteSubIdx = off + expectedPatchBytes.length - 1;
                        break;
                    }
                }
                assertTrue(parityByteSubIdx > fStart + resRelStart);
                badParityLca[parityByteSubIdx] ^= 0x01; // Flip 1 bit in patchParity8
                assertThrows(CorruptRegionEntryException.class,
                        () -> LcaRegionReader.readChunkView(badParityLca, 0, 0),
                        "Corrupted patchParity8 must trigger CBIT T6b");
            }
        }

        // 4. CBIT T5 tripwire: duplicate non-uniform sectionOffsets[] must fail closed
        byte[] modeALca = LcaRegionWriter.transcodeFromMca(mcaBytes, 0, 0, false);
        long ptr = LcaRegionReader.getVolatileLongBE(modeALca, LcaRegionReader.POINTER_DIRECTORY_OFFSET);
        int fStart = (int) ((ptr >>> 29) << LcaRegionReader.SLAB_SHIFT);
        int firstNonUniformIdx = -1;
        int secondNonUniformIdx = -1;
        for (int i = 0; i < 24; i++) {
            int e16 = LcaSectionCodec.readUint16BE(modeALca, fStart + 24 + (i << 1));
            if (e16 != 0 && (e16 & 0x8000) == 0) {
                if (firstNonUniformIdx < 0) firstNonUniformIdx = i;
                else {
                    secondNonUniformIdx = i;
                    break;
                }
            }
        }
        assertTrue(firstNonUniformIdx >= 0 && secondNonUniformIdx >= 0);
        byte[] dupOffsetLca = Arrays.copyOf(modeALca, modeALca.length);
        dupOffsetLca[fStart + 24 + (secondNonUniformIdx << 1)] = dupOffsetLca[fStart + 24 + (firstNonUniformIdx << 1)];
        dupOffsetLca[fStart + 24 + (secondNonUniformIdx << 1) + 1] = dupOffsetLca[fStart + 24 + (firstNonUniformIdx << 1) + 1];
        assertThrows(CorruptRegionEntryException.class,
                () -> LcaRegionReader.readChunkView(dupOffsetLca, 0, 0),
                "Duplicate non-uniform sectionOffsets[] must trigger CBIT T5");
    }

    @Test
    @DisplayName("REQ-RTP-S-005: skipSectionFrame across all Roaring/Flat/Biome/Light modes, FileSession, AnvilPrefilter, and LcaRegionFileStorage")
    void skipSectionFrameAndFileSessionStorageCoverage(@TempDir Path tempDir) throws Exception {
        // 1. Exercise skipSectionFrame across TAG_SINGLETON, TAG_DELTA_ARRAY (with gap >= 240), TAG_BITMAP,
        //    6:2 / 4:4 run containers (with adjGap==63/15 and lenM1==3/15), BLOCK_MODE_FLAT_BITPACK,
        //    multi-biome palettes (bLen <= 8 and bLen > 8), and LIGHT_MODE_CONST / LIGHT_MODE_RAW
        int[] pal4 = new int[] {0, 1, 2, 3};
        long[] dataDeltaArrayAndRuns = new long[256]; // 4 bits per entry, 16 entries per long
        // State 1: 4 scattered voxels with gap >= 250 -> TAG_DELTA_ARRAY with Bounded240 2-byte escape
        for (int idx : new int[] {5, 265, 530, 800}) {
            dataDeltaArrayAndRuns[idx / 16] |= (1L << ((idx % 16) * 4));
        }
        // State 2: long run (>= 20 voxels) after a large gap (>= 300) -> run container with escaped gap & length
        for (int idx = 1200; idx < 1225; idx++) {
            dataDeltaArrayAndRuns[idx / 16] |= (2L << ((idx % 16) * 4));
        }
        // State 3: dense (> 600 scattered entries) -> TAG_BITMAP (512B)
        for (int idx = 2000; idx < 3400; idx += 2) {
            dataDeltaArrayAndRuns[idx / 16] |= (3L << ((idx % 16) * 4));
        }
        int[] biomes10 = new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        long[] biomeData10 = new long[4]; // 4 bits per entry for 64 biome cells
        for (int i = 0; i < 64; i++) {
            int b = i % 10;
            biomeData10[i / 16] |= ((long) b) << ((i % 16) * 4);
        }
        byte[] constSky = new byte[2048];
        Arrays.fill(constSky, (byte) 0xFF);
        byte[] noisyLight = new byte[2048];
        Random rng = new Random(12345L);
        rng.nextBytes(noisyLight);

        LcaSectionCodec.EncodedSection encRoaring = LcaSectionCodec.encodeSection(
                pal4, dataDeltaArrayAndRuns, biomes10, biomeData10, constSky, noisyLight, -2, -1);
        assertNotNull(encRoaring.frameBytes);
        assertEquals(encRoaring.frameBytes.length,
                LcaSectionCodec.skipSectionFrame(encRoaring.frameBytes, 0, encRoaring.frameBytes.length));

        // Also test skipSectionFrame on a 4-biome palette (bLen <= 8) and flat bitpack (16-state high-entropy section)
        int[] pal16 = new int[16];
        for (int i = 0; i < 16; i++) pal16[i] = i;
        long[] noisyBlocks = new long[256];
        for (int i = 0; i < 4096; i++) {
            long p = (i * 7 + (i >>> 4) * 13 + (i >>> 8) * 11) & 15;
            noisyBlocks[i / 16] |= (p << ((i % 16) * 4));
        }
        int[] biomes4 = new int[] {0, 1, 2, 3};
        long[] biomeData4 = new long[] {0x0123456789ABCDEFL, 0xFEDCBA9876543210L};
        LcaSectionCodec.EncodedSection encFlat = LcaSectionCodec.encodeSection(
                pal16, noisyBlocks, biomes4, biomeData4, null, constSky, -2, -1);
        assertNotNull(encFlat.frameBytes);
        assertEquals(encFlat.frameBytes.length,
                LcaSectionCodec.skipSectionFrame(encFlat.frameBytes, 0, encFlat.frameBytes.length));

        // Raw light patch channel round-trip inside ChunkPatchTail + injectChunkPatchTail when hasResidualNbt == false
        byte[] mcaBytes = loadFixture("/anvil/real/1_21_R1/r.0.0.mca");
        byte[] lcaBytes = LcaRegionWriter.transcodeFromMca(mcaBytes, 0, 0, false);
        LcaRegionWriter.LcaLiveArchive rawLightArchive = LcaRegionWriter.openLiveArchive(lcaBytes);
        LcaSectionCodec.SectionPatch rawLightPatch = new LcaSectionCodec.SectionPatch(
                4,
                LcaSectionCodec.PATCH_FLAG_SKY_LIGHT | LcaSectionCodec.PATCH_FLAG_BLOCK_LIGHT,
                0,
                null,
                null,
                noisyLight,
                constSky);
        rawLightArchive.applyChunkPatchCoW(0, 0, List.of(), List.of(rawLightPatch));
        AnvilReader.ChunkEntry rawLightEntry = LcaRegionReader.INSTANCE.readChunk(rawLightArchive.toByteArray(), 0, 0);
        assertNotNull(rawLightEntry);

        byte[] uncompFrame = rawLightArchive.readUncompressedChunkFrame(0, 0);
        byte[] noResFrame = Arrays.copyOf(uncompFrame, uncompFrame.length);
        noResFrame[21] &= ~1; // clear hasResidualNbt bit
        byte[] smallPatch = LcaSectionCodec.encodeChunkPatchTailBytes(0, List.of(), new int[0], List.of(rawLightPatch));
        byte[] injectedNoRes = LcaSectionCodec.injectChunkPatchTailIntoUncompressedFrame(noResFrame, 24, smallPatch);
        assertTrue((injectedNoRes[21] & 1) != 0);

        // 2. Exercise LcaRegionReader.FileSession, AnvilPrefilter (.lca), AnvilRegionSampler (.lca), and ColumnProbeCoalescer (.lca)
        LcaRegionReader.register();
        Path worldDir = tempDir.resolve("world");
        Path regionDir = worldDir.resolve("region");
        Files.createDirectories(regionDir);
        Path lcaFile = regionDir.resolve("r.0.0.lca");
        Files.write(lcaFile, lcaBytes);

        try (LcaRegionReader.FileSession fs = LcaRegionReader.openFileSession(lcaFile)) {
            assertTrue(fs.isChunkGenerated(0, 0));
            assertFalse(fs.isChunkGenerated(1, 0));
            assertNotNull(fs.readChunkView(0, 0));
            assertNull(fs.readChunkView(1, 0));
            assertNotNull(fs.readColumnProbe(0, 0, -64, 319));
            assertNull(fs.readColumnProbe(1, 0, -64, 319));
            assertNotNull(fs.readTerrainProbe(0, 0, 20, 120));
            assertNull(fs.readTerrainProbe(1, 0, 20, 120));
            assertNotNull(fs.readChunkEntry(0, 0));
            assertNull(fs.readChunkEntry(1, 0));
        }
        assertNotNull(LcaRegionReader.readChunkFromFile(lcaFile, 0, 0));

        // Probe .lca via AnvilPrefilter both cold (FileSession) and warm (AnvilRegionByteCache resident lease)
        AnvilRegionByteCache.invalidateAll();
        try (AnvilPrefilter.RegionProbeSession probeSession =
                AnvilPrefilter.openSession(worldDir, "", java.util.Set.of("minecraft:lava"), null)) {
            assertNotNull(probeSession.probe(0, 0));
            assertEquals(Verdict.UNKNOWN, probeSession.probe(1, 0).verdict());
        }
        try (AnvilRegionByteCache.Lease warmLease = AnvilRegionByteCache.acquire(lcaFile)) {
            assertNotNull(warmLease);
            try (AnvilPrefilter.RegionProbeSession warmProbeSession =
                    AnvilPrefilter.openSession(worldDir, "", java.util.Set.of("minecraft:lava"), null)) {
                assertNotNull(warmProbeSession.probe(0, 0));
                assertEquals(Verdict.UNKNOWN, warmProbeSession.probe(1, 0).verdict());
            }
        }

        // AnvilRegionSampler cold (.lca FileSession) and warm (readAllBiomes)
        AnvilRegionByteCache.invalidateAll();
        Map<Long, String> sampledCold = AnvilRegionSampler.sampleBiomes(
                lcaFile, 0, 0, 64, new int[] {0, 1}, java.util.function.UnaryOperator.identity());
        assertFalse(sampledCold.isEmpty());
        Map<Long, String> sampledAll = AnvilRegionSampler.readAllBiomes(
                lcaFile, 0, 0, 64, java.util.function.UnaryOperator.identity());
        assertFalse(sampledAll.isEmpty());

        // 3. Exercise LcaRegionFileStorage cold read channels, NBT byte / pre-encoded slab APIs, and LcaWorldStorageSession
        Path storageWorldDir = tempDir.resolve("storage_world");
        try (LcaRegionFileStorage.LcaWorldStorageSession ws =
                new LcaRegionFileStorage.LcaWorldStorageSession(storageWorldDir, -4, 24, 4671, 0, true)) {
            assertEquals(storageWorldDir, ws.dimensionDirectory());
            assertEquals(ws.voxelStorage(), ws.storageForFolder(storageWorldDir.resolve("region")));
            assertEquals(ws.entityStorage(), ws.storageForFolder(storageWorldDir.resolve("entities")));
            assertEquals(ws.poiStorage(), ws.storageForFolder(storageWorldDir.resolve("poi")));

            byte[] rawChunkNbt = LcaRegionReader.INSTANCE.readChunkNbtBytes(lcaBytes, 0, 0);
            assertNotNull(rawChunkNbt);
            assertTrue(ws.voxelStorage().writeChunkNbtBytes(0, 0, rawChunkNbt) > 0);
            assertNotNull(ws.voxelStorage().readChunkNbtBytes(0, 0));
            byte[] slabPayload = ws.voxelStorage().encodeChunkNbtForRegionSlab(0, 0, rawChunkNbt);
            assertTrue(ws.totalOpenRegionCount() >= 1);
            assertEquals(0L, ws.totalMigratedRegionCount());
            ws.compactOpenRegions(0.0);
            ws.flush();
        }
    }

    @Test
    @DisplayName("DataVersion 5006+ (V5006 / 26.3) emits id/properties in block_states.palette while <5006 emits Name/Properties")
    @SuppressWarnings("unchecked")
    void dataVersion5023PaletteUsesIdAndPropertiesKeys() throws Exception {
        for (int dv : new int[] {4903, 5006, 5023}) {
            boolean expectModern = dv >= 5006;
            LinkedHashMap<String, Object> root = new LinkedHashMap<>();
            root.put("DataVersion", dv);
            root.put("xPos", 0);
            root.put("zPos", 0);
            root.put("yPos", -4);
            root.put("Status", "minecraft:full");

            // Section Y=-4: uniform bare-string palette (Codec.either bare string form)
            LinkedHashMap<String, Object> secUniform = new LinkedHashMap<>();
            secUniform.put("Y", (byte) -4);
            LinkedHashMap<String, Object> bsUniform = new LinkedHashMap<>();
            bsUniform.put("palette", new Nbt.NbtList(Nbt.TAG_STRING, List.of("minecraft:deepslate")));
            secUniform.put("block_states", bsUniform);

            // Section Y=-3: mixed palette with {"": id}, static-core id/properties, and custom id/properties
            LinkedHashMap<String, Object> secMixed = new LinkedHashMap<>();
            secMixed.put("Y", (byte) -3);
            LinkedHashMap<String, Object> bsMixed = new LinkedHashMap<>();

            LinkedHashMap<String, Object> wrappedAir = new LinkedHashMap<>();
            wrappedAir.put("", "minecraft:air");

            LinkedHashMap<String, Object> stairsEntry = new LinkedHashMap<>();
            stairsEntry.put("id", "minecraft:oak_stairs");
            LinkedHashMap<String, Object> stairsProps = new LinkedHashMap<>();
            stairsProps.put("facing", "north");
            stairsProps.put("type", "single");
            stairsProps.put("waterlogged", "false");
            stairsEntry.put("properties", stairsProps);

            LinkedHashMap<String, Object> barrelEntry = new LinkedHashMap<>();
            barrelEntry.put("id", "minecraft:barrel");
            LinkedHashMap<String, Object> barrelProps = new LinkedHashMap<>();
            barrelProps.put("facing", "up");
            barrelProps.put("open", "true");
            barrelEntry.put("properties", barrelProps);

            bsMixed.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(wrappedAir, stairsEntry, barrelEntry)));
            int[] yzx = new int[4096];
            yzx[1] = 1;
            yzx[2] = 2;
            bsMixed.put("data", LcaSectionCodec.packBlockLongs(yzx, 3));
            secMixed.put("block_states", bsMixed);

            root.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, new ArrayList<>(List.of(secUniform, secMixed))));

            try (LcaRegionWriter.LcaLiveArchive archive =
                    LcaRegionWriter.createEmptyLiveArchive(0, 0, -4, 24, dv, false)) {
                archive.saveChunkCoW(0, 0, root);
                byte[] lcaBytes = archive.toByteArray();

                // 1. Direct fast-path NBT byte emission (readChunkNbtBytes -> encodeChunkFrameToNbtBytes)
                byte[] rawNbt = LcaRegionReader.INSTANCE.readChunkNbtBytes(lcaBytes, 0, 0);
                assertNotNull(rawNbt);
                Map<String, Object> directRoot = Nbt.readRootCompound(rawNbt);
                assertPaletteKeysForVersion(directRoot, expectModern);

                // 2. Map decode path (readChunk -> decodeChunkFromFrame)
                AnvilReader.ChunkEntry mapEntry = LcaRegionReader.INSTANCE.readChunk(lcaBytes, 0, 0);
                assertNotNull(mapEntry);
                assertPaletteKeysForVersion(mapEntry.root, expectModern);

                // 3. RegionFileReader.applyPatchesToChunkRoot
                boolean modified = RegionFileReader.applyPatchesToChunkRoot(
                        mapEntry.root,
                        List.of(
                                new RegionFileReader.BlockPatch(0, -48, 0, "minecraft:hopper[enabled=true,facing=down]"),
                                new RegionFileReader.BlockPatch(0, 16, 0, "minecraft:stone")),
                        null);
                assertTrue(modified);
                assertPaletteKeysForVersion(mapEntry.root, expectModern);
                AnvilChunkView patchedView = AnvilReader.toView(mapEntry.root);
                assertEquals("minecraft:hopper", patchedView.blockIdAt(0, -48, 0));
                assertEquals("minecraft:oak_stairs", patchedView.blockIdAt(1, -48, 0));
                assertEquals("minecraft:barrel", patchedView.blockIdAt(2, -48, 0));
                assertEquals("minecraft:stone", patchedView.blockIdAt(0, 16, 0));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertPaletteKeysForVersion(Map<String, Object> chunkRoot, boolean expectModern) {
        String expectedNameKey = expectModern ? "id" : "Name";
        String unexpectedNameKey = expectModern ? "Name" : "id";
        String unexpectedPropsKey = expectModern ? "Properties" : "properties";
        Nbt.NbtList sections = (Nbt.NbtList) chunkRoot.get("sections");
        assertNotNull(sections);
        for (Object secObj : sections.items) {
            Map<String, Object> sec = (Map<String, Object>) secObj;
            Map<String, Object> bs = (Map<String, Object>) sec.get("block_states");
            if (bs == null) continue;
            Nbt.NbtList pal = (Nbt.NbtList) bs.get("palette");
            assertNotNull(pal);
            for (Object entryObj : pal.items) {
                Map<String, Object> entry = (Map<String, Object>) entryObj;
                assertTrue(entry.containsKey(expectedNameKey),
                        "Expected '" + expectedNameKey + "' key in palette entry: " + entry);
                assertFalse(entry.containsKey(unexpectedNameKey),
                        "Unexpected '" + unexpectedNameKey + "' key in palette entry: " + entry);
                assertFalse(entry.containsKey(unexpectedPropsKey),
                        "Unexpected '" + unexpectedPropsKey + "' key in palette entry: " + entry);
            }
        }
    }

    @Test
    @DisplayName("anvil-api-ADR-003 Section 3.1 & 7.2: Continuous atomic Layer 2 dictionary (LCD2) training and (72, 64) SEC-DED dictionary healing")
    void continuousAtomicLayer2DictionaryTrainingAndSecDedHealing() throws IOException {
        byte[] mcaBytes = loadFixture("/anvil/real/1_21_R1/r.0.0.mca");
        AnvilReader.ChunkEntry orig = AnvilReader.readChunkEntry(mcaBytes, 0, 0);
        assertNotNull(orig);

        // 1. Incrementally write 4 chunks into a Mode B live archive, verifying continuous atomic dictionary growth
        LcaRegionWriter.LcaLiveArchive archive =
                LcaRegionWriter.createEmptyLiveArchive(0, 0, -4, 24, 4671, true, 1024);
        for (int i = 0; i < 4; i++) {
            LinkedHashMap<String, Object> root = new LinkedHashMap<>(orig.root);
            root.put("xPos", i);
            root.put("zPos", 0);
            root.put("LastUpdate", 900L + i);
            archive.saveChunkCoW(i, 0, root);
        }
        byte[] lcaBytes = archive.toByteArray();

        // Verify the root dictionary segment trailer has bit 1 (0x02, DICT_SEG_FLAG_HAS_PATTERN_SECDED) set
        long rootTrailer = LcaRegionReader.getVolatileLongBE(lcaBytes, LcaRegionReader.DICT_ROOT_OFFSET + 1024 - 8);
        assertEquals(2L, rootTrailer & 2L, "Layer 2 dictionary segment trailer must have SEC-DED + pattern flag (0x02) set");

        // Verify transcodeFromMca Mode B produces valid LCD2 frames smaller than Mode A with SEC-DED dictionary parity
        byte[] modeABytes = LcaRegionWriter.transcodeFromMca(mcaBytes, 0, 0, false);
        byte[] convertedModeB = LcaRegionWriter.transcodeFromMca(mcaBytes, 0, 0, true);
        assertTrue(convertedModeB.length < modeABytes.length,
                "LCD2 Mode B (" + convertedModeB.length + " B) must be smaller than Mode A (" + modeABytes.length + " B)");
        AnvilChunkView convView = LcaRegionReader.readChunkView(convertedModeB, 0, 0);
        AnvilChunkView baselineView = LcaRegionReader.readChunkView(lcaBytes, 0, 0);
        assertNotNull(convView);
        assertNotNull(baselineView);
        assertEquals(baselineView.blockIdAt(8, 64, 8), convView.blockIdAt(8, 64, 8));

        // 2. Inject 1-bit flips into multiple 64-bit words of the Layer 2 dictionary segment (Word 0 header + Huffman/pattern words)
        byte[] corruptedDict = Arrays.copyOf(lcaBytes, lcaBytes.length);
        corruptedDict[LcaRegionReader.DICT_ROOT_OFFSET + 2] ^= 0x01;   // Flip a bit in Word 0 (segStateCount)
        corruptedDict[LcaRegionReader.DICT_ROOT_OFFSET + 64] ^= 0x20;  // Flip a bit in the Canonical Huffman nibble table
        corruptedDict[LcaRegionReader.DICT_ROOT_OFFSET + 420] ^= 0x08; // Flip a bit in the cross-chunk pattern arena

        LcaRegionReader.RecoveryReport report = LcaRegionReader.healRegionBitRot(corruptedDict);
        assertEquals(3, report.dictSecDedHealedWords(),
                "Layer 2 dictionary (72, 64) SEC-DED must heal all 3 single-bit flips across header, Huffman tables, and pattern arena");
        for (int i = 0; i < 4; i++) {
            AnvilReader.ChunkEntry healedEntry = LcaRegionReader.INSTANCE.readChunk(corruptedDict, i, 0);
            assertNotNull(healedEntry);
            assertEquals(900L + i, ((Number) healedEntry.root.get("LastUpdate")).longValue());
            AnvilChunkView healedView = LcaRegionReader.readChunkView(corruptedDict, i, 0);
            assertEquals(baselineView.blockIdAt(8, 64, 8), healedView.blockIdAt(8, 64, 8));
        }

        // 3. Inject an unrecoverable 2-bit flip (DED) in the same 64-bit word of the Layer 2 dictionary -> must fail closed (S-004)
        byte[] dedDict = Arrays.copyOf(lcaBytes, lcaBytes.length);
        dedDict[LcaRegionReader.DICT_ROOT_OFFSET + 64] ^= 0x03; // 2-bit flip in the same word
        assertThrows(CorruptRegionEntryException.class, () -> LcaRegionReader.INSTANCE.readChunk(dedDict, 0, 0));
    }

    @Test
    @DisplayName("anvil-api-ADR-003 Section 2.3 & 4.1: Disk-backed LcaLiveArchive moves LCA1_END footer canary first on EOF growth and retains cached dictionary across single-chunk reads")
    void diskLiveArchiveMovesFooterFirstAndCachesDictionaryForSingleChunkReads(
            @org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        byte[] mcaBytes = loadFixture("/anvil/real/1_21_R1/r.0.0.mca");
        AnvilReader.ChunkEntry orig = AnvilReader.readChunkEntry(mcaBytes, 0, 0);
        assertNotNull(orig);

        Path lcaFile = tempDir.resolve("r.1.0.live.lca");
        try (LcaRegionWriter.LcaLiveArchive archive = LcaRegionWriter.createEmptyLiveArchive(
                lcaFile, 1, 0, -4, 24, 4671, 1, LcaRegionReader.STORAGE_KIND_REGION_VOXEL, 256)) {
            for (int i = 0; i < 6; i++) {
                LinkedHashMap<String, Object> root =
                        Nbt.readRootCompound(Nbt.writeNamedRoot("", orig.root));
                root.put("xPos", 32 + i);
                root.put("zPos", 0);
                // Force dictionary continuation segment allocation on disk while chunks are being appended
                for (int k = 0; k < 12; k++) {
                    archive.registerOrGetBlockStateId("minecraft:test_footer_state_" + i + "_" + k);
                    byte[] diskBytes = Files.readAllBytes(lcaFile);
                    assertEquals(
                            LcaRegionReader.LCA_FOOTER_CANARY_MAGIC,
                            (long) LcaRegionReader.LONG_BE_VIEW.get(diskBytes, diskBytes.length - 8),
                            "Disk file must maintain LCA1_END footer canary at EOF even immediately after dictionary segment allocation");
                }
                archive.saveChunkCoW(i, 0, root);
                AnvilChunkView view = LcaRegionReader.readChunkViewFromFile(lcaFile, i, 0);
                assertNotNull(view, "Single-chunk reader must decode newly appended chunk using cached header/dictionary");
            }
            LcaRegionReader.CachedFileHeader cached = LcaRegionReader.peekCachedFileHeader(lcaFile);
            assertNotNull(cached, "CachedFileHeader must remain hot in FILE_HEADER_CACHE across live chunk saves");
            assertNotNull(cached.parsedDict, "Cached dictionary must remain populated when subsequent saves do not mutate dictionary");
        }
    }
}
