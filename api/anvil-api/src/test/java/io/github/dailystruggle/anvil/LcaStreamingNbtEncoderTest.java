package io.github.dailystruggle.anvil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bit-exact parity and fail-closed safety suite for {@link LcaStreamingNbtEncoder} against the
 * reference {@link Nbt#readRootCompound(byte[], int, int)} + {@link LcaRegionWriter.LcaLiveArchive}
 * chunk slab encoder across legacy ({@code DataVersion < 5006}) and modern ({@code DataVersion >= 5006})
 * Mojang NBT schemas.
 *
 * @governedBy leafanvil-ADR-005
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
class LcaStreamingNbtEncoderTest {

    @TempDir
    Path tempDir;

    private static LinkedHashMap<String, Object> buildChunkRoot(
            int dataVersion,
            boolean modernPaletteKeys,
            int chunkX,
            int chunkZ,
            boolean includeResidualPdcAndBlockEntities
    ) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", dataVersion);
        root.put("xPos", chunkX);
        root.put("yPos", -4);
        root.put("zPos", chunkZ);
        root.put("Status", "minecraft:full");
        root.put("LastUpdate", 123456L);
        root.put("InhabitedTime", 7890L);
        root.put("isLightOn", (byte) 1);
        root.put("starlight.light_version", 9);

        String nameKey = modernPaletteKeys ? "id" : "Name";
        String propsKey = modernPaletteKeys ? "properties" : "Properties";

        List<Object> sections = new ArrayList<>();

        // Low boundary light-only section (Y = -5, uniform sky=0, blk=0 -> compact bpFlags |= 128)
        LinkedHashMap<String, Object> lowLightSec = new LinkedHashMap<>();
        lowLightSec.put("Y", (byte) -5);
        byte[] zeroLight = new byte[2048];
        lowLightSec.put("SkyLight", zeroLight.clone());
        lowLightSec.put("BlockLight", zeroLight.clone());
        sections.add(lowLightSec);

        // Section Y = -4: uniform bedrock
        LinkedHashMap<String, Object> uniformSec = new LinkedHashMap<>();
        uniformSec.put("Y", (byte) -4);
        LinkedHashMap<String, Object> uniformBs = new LinkedHashMap<>();
        LinkedHashMap<String, Object> bedrockEntry = new LinkedHashMap<>();
        bedrockEntry.put(nameKey, "minecraft:bedrock");
        uniformBs.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(bedrockEntry)));
        uniformSec.put("block_states", uniformBs);
        LinkedHashMap<String, Object> uniformBiomes = new LinkedHashMap<>();
        uniformBiomes.put("palette", new Nbt.NbtList(Nbt.TAG_STRING, List.of("minecraft:plains")));
        uniformSec.put("biomes", uniformBiomes);
        byte[] fullSky = new byte[2048];
        Arrays.fill(fullSky, (byte) 0xFF);
        uniformSec.put("SkyLight", fullSky);
        uniformSec.put("BlockLight", zeroLight.clone());
        sections.add(uniformSec);

        // Section Y = 0: non-uniform multi-state section with properties + 2 biomes + varied lighting
        LinkedHashMap<String, Object> mixedSec = new LinkedHashMap<>();
        mixedSec.put("Y", (byte) 0);
        LinkedHashMap<String, Object> mixedBs = new LinkedHashMap<>();

        LinkedHashMap<String, Object> airEntry = new LinkedHashMap<>();
        airEntry.put(nameKey, "minecraft:air");

        LinkedHashMap<String, Object> stoneEntry = new LinkedHashMap<>();
        stoneEntry.put(nameKey, "minecraft:stone");

        LinkedHashMap<String, Object> stairsEntry = new LinkedHashMap<>();
        stairsEntry.put(nameKey, "minecraft:oak_stairs");
        LinkedHashMap<String, Object> stairsProps = new LinkedHashMap<>();
        // Deliberately non-alphabetical insertion order ("waterlogged" before "facing") to verify stream-order preservation
        stairsProps.put("waterlogged", "false");
        stairsProps.put("facing", "east");
        stairsProps.put("half", "bottom");
        stairsEntry.put(propsKey, stairsProps);

        LinkedHashMap<String, Object> lampEntry = new LinkedHashMap<>();
        lampEntry.put(nameKey, "minecraft:redstone_lamp");
        LinkedHashMap<String, Object> lampProps = new LinkedHashMap<>();
        lampProps.put("lit", "true");
        lampEntry.put(propsKey, lampProps);

        mixedBs.put(
                "palette",
                new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(airEntry, stoneEntry, stairsEntry, lampEntry)));

        // 4-bit palette indices -> 16 entries per 64-bit word -> 256 longs for 4096 voxels
        long[] packedBlocks = new long[256];
        for (int i = 0; i < packedBlocks.length; i++) {
            long word = 0L;
            for (int sub = 0; sub < 16; sub++) {
                int voxel = (i << 4) | sub;
                int palIdx = (voxel * 7 + i) & 3;
                word |= ((long) palIdx) << (sub * 4);
            }
            packedBlocks[i] = word;
        }
        mixedBs.put("data", packedBlocks);
        mixedSec.put("block_states", mixedBs);

        LinkedHashMap<String, Object> mixedBiomes = new LinkedHashMap<>();
        mixedBiomes.put(
                "palette",
                new Nbt.NbtList(Nbt.TAG_STRING, List.of("minecraft:plains", "minecraft:forest")));
        mixedBiomes.put("data", new long[] {0x5555555555555555L});
        mixedSec.put("biomes", mixedBiomes);

        byte[] patternedSky = new byte[2048];
        byte[] patternedBlk = new byte[2048];
        for (int i = 0; i < 2048; i++) {
            patternedSky[i] = (byte) ((i * 17) & 0xFF);
            patternedBlk[i] = (byte) ((i * 31) & 0xFF);
        }
        mixedSec.put("SkyLight", patternedSky);
        mixedSec.put("BlockLight", patternedBlk);
        sections.add(mixedSec);

        root.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, sections));

        // Heightmaps (including duplicate + cross-delta candidates)
        LinkedHashMap<String, Object> heightmaps = new LinkedHashMap<>();
        long[] mbnl = new long[37];
        long[] ws = new long[37];
        for (int i = 0; i < 37; i++) {
            mbnl[i] = 0x0402010080402010L ^ i;
            ws[i] = mbnl[i] ^ ((i % 5 == 0) ? (1L << 9) : 0L);
        }
        heightmaps.put("MOTION_BLOCKING_NO_LEAVES", mbnl);
        heightmaps.put("MOTION_BLOCKING", mbnl.clone());
        heightmaps.put("WORLD_SURFACE", ws);
        root.put("Heightmaps", heightmaps);

        // Empty tick lists + empty PostProcessing + structures
        root.put("block_ticks", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of()));
        root.put("fluid_ticks", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of()));

        List<Object> ppSubLists = new ArrayList<>(24);
        for (int i = 0; i < 24; i++) {
            ppSubLists.add(new Nbt.NbtList(Nbt.TAG_SHORT, List.of()));
        }
        root.put("PostProcessing", new Nbt.NbtList(Nbt.TAG_LIST, ppSubLists));

        LinkedHashMap<String, Object> structures = new LinkedHashMap<>();
        structures.put("References", new LinkedHashMap<>());
        structures.put("Starts", new LinkedHashMap<>());
        root.put("structures", structures);

        if (includeResidualPdcAndBlockEntities) {
            LinkedHashMap<String, Object> chestEntity = new LinkedHashMap<>();
            chestEntity.put("id", "minecraft:chest");
            chestEntity.put("x", (chunkX << 4) + 3);
            chestEntity.put("y", 64);
            chestEntity.put("z", (chunkZ << 4) + 9);
            chestEntity.put("CustomName", "{\"text\":\"Loot\"}");
            root.put("block_entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(chestEntity)));

            LinkedHashMap<String, Object> bukkitValues = new LinkedHashMap<>();
            bukkitValues.put("leafrtp:visited_epoch", 42L);
            bukkitValues.put("leafanvil:tag", "custom-pdc-payload-".repeat(16));
            bukkitValues.put("leafanvil:ints", new int[] {10, 20, 30, 40});
            root.put("BukkitValues", bukkitValues);
        } else {
            root.put("block_entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of()));
        }

        return root;
    }

    @Test
    void bitExactParityAcrossLegacyAndModernDataVersionsAndZeroCopySlice() throws IOException {
        int[][] cases = {
            // {dataVersion, modernPalette(0/1), chunkX, chunkZ, includeResiduals(0/1), modeBLcd2(0/1)}
            {3953, 0, 5, 11, 0, 0},
            {4189, 0, -12, 7, 1, 0},
            {5006, 1, 3, -9, 1, 0},
            {5120, 1, 34, 65, 1, 1},
            {3465, 0, 34, 65, 1, 1}
        };

        for (int[] tc : cases) {
            int dv = tc[0];
            boolean modern = tc[1] == 1;
            int cx = tc[2];
            int cz = tc[3];
            boolean residuals = tc[4] == 1;
            boolean modeB = tc[5] == 1;
            int regionX = cx >> 5;
            int regionZ = cz >> 5;

            LinkedHashMap<String, Object> chunkRoot = buildChunkRoot(dv, modern, cx, cz, residuals);
            byte[] rawNbt = Nbt.writeNamedRoot("", chunkRoot);

            // Wrap inside an offset byte[] + direct ByteBuffer slice to prove zero-copy offset fidelity.
            byte[] padded = new byte[rawNbt.length + 37];
            Arrays.fill(padded, (byte) 0x7E);
            int sliceOffset = 19;
            System.arraycopy(rawNbt, 0, padded, sliceOffset, rawNbt.length);

            ByteBuffer directBuf = ByteBuffer.allocateDirect(padded.length);
            directBuf.put(padded);
            directBuf.position(sliceOffset).limit(sliceOffset + rawNbt.length);
            ByteBuffer directSlice = directBuf.slice();

            LcaRegionWriter.LcaLiveArchive refArchive =
                    LcaRegionWriter.createEmptyLiveArchive(regionX, regionZ, -4, 24, dv, modeB);
            LcaRegionWriter.LcaLiveArchive streamArchive =
                    LcaRegionWriter.createEmptyLiveArchive(regionX, regionZ, -4, 24, dv, modeB);

            LinkedHashMap<String, Object> parsedRef = Nbt.readRootCompound(padded, sliceOffset, rawNbt.length);
            byte[] expectedSlab = refArchive.encodeChunkPayloadForSlab(cx & 31, cz & 31, parsedRef);
            byte[] actualSlabFromSlice =
                    LcaStreamingNbtEncoder.encodeChunkSlab(streamArchive, regionX, regionZ, directSlice);

            assertArrayEquals(
                    expectedSlab,
                    actualSlabFromSlice,
                    "Streaming slab must be bit-identical for dv=" + dv + ", modern=" + modern + ", modeB=" + modeB);

            // Also verify end-to-end LcaRegionFileStorage write + read round-trip parity.
            Path storageDir = tempDir.resolve("storage-" + dv + "-" + modern + "-" + modeB);
            try (LcaRegionFileStorage storage = new LcaRegionFileStorage(storageDir, false)) {
                ByteBuffer sliceForStorage = ByteBuffer.wrap(padded, sliceOffset, rawNbt.length).slice();
                byte[] preEncoded = storage.encodeChunkNbtForRegionSlab(regionX, regionZ, sliceForStorage);
                assertTrue(preEncoded.length > 24);
                storage.commitPreEncodedChunkSlab(cx, cz, ByteBuffer.wrap(preEncoded));

                byte[] roundTripNbt = storage.readChunkNbtBytes(cx, cz);
                LinkedHashMap<String, Object> decodedRoot = Nbt.readRootCompound(roundTripNbt);
                assertEquals(dv, ((Number) decodedRoot.get("DataVersion")).intValue());
                if (residuals) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> bv = (Map<String, Object>) decodedRoot.get("BukkitValues");
                    assertEquals(42L, ((Number) bv.get("leafrtp:visited_epoch")).longValue());
                    assertArrayEquals(new int[] {10, 20, 30, 40}, (int[]) bv.get("leafanvil:ints"));
                }
            }
        }
    }

    @Test
    void malformedOrTruncatedBinaryNbtFailsClosedWithIoException() {
        LcaRegionWriter.LcaLiveArchive archive =
                LcaRegionWriter.createEmptyLiveArchive(0, 0, -4, 24, 5006, false);
        byte[] truncated = new byte[] {Nbt.TAG_COMPOUND, 0, 0, Nbt.TAG_INT, 0, 4, 'x', 'P', 'o', 's', 0};
        assertThrows(
                IOException.class,
                () -> LcaStreamingNbtEncoder.encodeChunkSlab(archive, 0, 0, ByteBuffer.wrap(truncated)));
    }

    @Test
    void paperStarlightOmittedZeroSkyLightSectionsRoundTripAsZeroLightNotNullExtrusion() throws IOException {
        int dv = 5006;
        int cx = 2;
        int cz = -3;
        int regionX = cx >> 5;
        int regionZ = cz >> 5;

        LinkedHashMap<String, Object> root = buildChunkRoot(dv, true, cx, cz, false);
        List<Object> sections = ((Nbt.NbtList) root.get("sections")).items;
        sections.clear();

        // 1. Subterranean uniform deepslate section (Y = -2): Paper omits "SkyLight" and writes starlight.skylight_state = 1
        LinkedHashMap<String, Object> deepUniformSec = new LinkedHashMap<>();
        deepUniformSec.put("Y", (byte) -2);
        LinkedHashMap<String, Object> deepBs = new LinkedHashMap<>();
        LinkedHashMap<String, Object> deepslateEntry = new LinkedHashMap<>();
        deepslateEntry.put("id", "minecraft:deepslate");
        deepBs.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(deepslateEntry)));
        deepUniformSec.put("block_states", deepBs);
        LinkedHashMap<String, Object> plainsBiomes = new LinkedHashMap<>();
        plainsBiomes.put("palette", new Nbt.NbtList(Nbt.TAG_STRING, List.of("minecraft:plains")));
        deepUniformSec.put("biomes", plainsBiomes);
        deepUniformSec.put("starlight.skylight_state", 1);
        deepUniformSec.put("starlight.blocklight_state", 1);
        sections.add(deepUniformSec);

        // 2. Enclosed mixed stone + air cave section (Y = 1): Paper omits "SkyLight" and writes starlight.skylight_state = 1
        LinkedHashMap<String, Object> caveSec = new LinkedHashMap<>();
        caveSec.put("Y", (byte) 1);
        LinkedHashMap<String, Object> caveBs = new LinkedHashMap<>();
        LinkedHashMap<String, Object> airEntry = new LinkedHashMap<>();
        airEntry.put("id", "minecraft:air");
        LinkedHashMap<String, Object> stoneEntry = new LinkedHashMap<>();
        stoneEntry.put("id", "minecraft:stone");
        caveBs.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(airEntry, stoneEntry)));
        long[] caveData = new long[256];
        Arrays.fill(caveData, 0xFFFFFFFFFFFFFFFFL);
        for (int i = 0; i < 64; i++) {
            caveData[i] = 0L; // enclosed cave air pocket
        }
        caveBs.put("data", caveData);
        caveSec.put("block_states", caveBs);
        caveSec.put("biomes", plainsBiomes);
        caveSec.put("starlight.skylight_state", 1);
        caveSec.put("starlight.blocklight_state", 1);
        sections.add(caveSec);

        // 3. Open-sky uniform air section above terrain (Y = 12): Paper omits both "SkyLight" and "starlight.skylight_state"
        LinkedHashMap<String, Object> openSkySec = new LinkedHashMap<>();
        openSkySec.put("Y", (byte) 12);
        LinkedHashMap<String, Object> skyBs = new LinkedHashMap<>();
        skyBs.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(airEntry)));
        openSkySec.put("block_states", skyBs);
        openSkySec.put("biomes", plainsBiomes);
        sections.add(openSkySec);

        byte[] rawNbt = Nbt.writeNamedRoot("", root);
        LcaRegionWriter.LcaLiveArchive refArchive =
                LcaRegionWriter.createEmptyLiveArchive(regionX, regionZ, -4, 24, dv, false);
        LcaRegionWriter.LcaLiveArchive streamArchive =
                LcaRegionWriter.createEmptyLiveArchive(regionX, regionZ, -4, 24, dv, false);

        LinkedHashMap<String, Object> parsedRef = Nbt.readRootCompound(rawNbt);
        byte[] expectedSlab = refArchive.encodeChunkPayloadForSlab(cx & 31, cz & 31, parsedRef);
        byte[] actualSlab = LcaStreamingNbtEncoder.encodeChunkSlab(
                streamArchive, regionX, regionZ, ByteBuffer.wrap(rawNbt));
        assertArrayEquals(expectedSlab, actualSlab,
                "LcaStreamingNbtEncoder and LcaRegionWriter must encode identical slabs for Starlight state=1 cave sections");

        Path storageDir = tempDir.resolve("starlight-cave-sky");
        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(storageDir, false)) {
            storage.commitPreEncodedChunkSlab(cx, cz, ByteBuffer.wrap(actualSlab));
            byte[] roundTrip = storage.readChunkNbtBytes(cx, cz);
            LinkedHashMap<String, Object> decoded = Nbt.readRootCompound(roundTrip);
            Nbt.NbtList decodedSecs = (Nbt.NbtList) decoded.get("sections");

            Map<Integer, Map<String, Object>> byY = new LinkedHashMap<>();
            for (Object item : decodedSecs.items) {
                @SuppressWarnings("unchecked")
                Map<String, Object> secMap = (Map<String, Object>) item;
                byY.put(((Number) secMap.get("Y")).intValue(), secMap);
            }

            Map<String, Object> decDeep = byY.get(-2);
            org.junit.jupiter.api.Assertions.assertNotNull(decDeep);
            boolean deepZeroSky = (decDeep.get("SkyLight") instanceof byte[] ds && Arrays.equals(new byte[2048], ds))
                    || (decDeep.get("starlight.skylight_state") instanceof Number n && n.intValue() == 1);
            assertTrue(deepZeroSky,
                    "Subterranean uniform section with starlight.skylight_state=1 must preserve zero sky light on .lca reload");

            Map<String, Object> decCave = byY.get(1);
            org.junit.jupiter.api.Assertions.assertNotNull(decCave);
            assertTrue(decCave.get("SkyLight") instanceof byte[],
                    "Mixed cave section with starlight.skylight_state=1 must restore explicit zero SkyLight[2048] instead of null extrusion");
            assertArrayEquals(new byte[2048], (byte[]) decCave.get("SkyLight"));
            assertEquals(
                    2,
                    ((Number) decCave.getOrDefault("starlight.skylight_state", 0)).intValue(),
                    "Whenever SkyLight[2048] is emitted on a Starlight chunk, starlight.skylight_state must be 2 (INIT_STATE_INIT), never omitted (0 = INIT_STATE_NULL -> 15 sky extrusion)");
            assertEquals(
                    2,
                    ((Number) decCave.getOrDefault("starlight.blocklight_state", 0)).intValue(),
                    "Whenever BlockLight[2048] is emitted on a Starlight chunk, starlight.blocklight_state must be 2 (INIT_STATE_INIT)");

            Map<String, Object> decSky = byY.get(12);
            org.junit.jupiter.api.Assertions.assertNotNull(decSky);
            org.junit.jupiter.api.Assertions.assertNull(decSky.get("SkyLight"),
                    "Open-sky uniform air section without starlight.skylight_state must remain unallocated for sky extrusion");
            org.junit.jupiter.api.Assertions.assertFalse(decSky.containsKey("starlight.skylight_state"),
                    "Open-sky uniform air section without starlight.skylight_state must not be forced to zero sky light");
        }
    }

    @Test
    void starlightNonUniformAndInlineLitSectionsEmitInitStateTwoAcrossCodecAndReaderWithoutTouchingVanilla() throws Exception {
        int dv = 5006;
        int cx = 7;
        int cz = 9;
        int regionX = cx >> 5;
        int regionZ = cz >> 5;

        LinkedHashMap<String, Object> root = buildChunkRoot(dv, true, cx, cz, false);
        root.put("Status", "minecraft:full");
        root.put("isLightOn", (byte) 0);
        root.put("starlight.light_version", 10);
        List<Object> sections = ((Nbt.NbtList) root.get("sections")).items;
        sections.clear();

        LinkedHashMap<String, Object> plainsBiomes = new LinkedHashMap<>();
        plainsBiomes.put("palette", new Nbt.NbtList(Nbt.TAG_STRING, List.of("minecraft:plains")));

        // 1. Mixed underground section (Y = -1) with mixed SkyLight/BlockLight bytes + INIT_STATE_INIT (2)
        LinkedHashMap<String, Object> litCaveSec = new LinkedHashMap<>();
        litCaveSec.put("Y", (byte) -1);
        LinkedHashMap<String, Object> litBs = new LinkedHashMap<>();
        LinkedHashMap<String, Object> stoneEntry = new LinkedHashMap<>();
        stoneEntry.put("id", "minecraft:deepslate");
        LinkedHashMap<String, Object> airEntry = new LinkedHashMap<>();
        airEntry.put("id", "minecraft:air");
        litBs.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(stoneEntry, airEntry)));
        long[] packed = new long[256];
        packed[0] = 0x0010_0001_0010_0001L;
        litBs.put("data", packed);
        litCaveSec.put("block_states", litBs);
        litCaveSec.put("biomes", plainsBiomes);
        byte[] skyBytes = new byte[2048];
        skyBytes[0] = 0x34;
        byte[] blkBytes = new byte[2048];
        blkBytes[0] = 0x78;
        litCaveSec.put("SkyLight", skyBytes);
        litCaveSec.put("BlockLight", blkBytes);
        litCaveSec.put("starlight.skylight_state", 2);
        litCaveSec.put("starlight.blocklight_state", 2);
        sections.add(litCaveSec);

        // 2. Inline uniform full-sky section (Y = 8, SkyLight = all 0xFF)
        LinkedHashMap<String, Object> fullSkyAirSec = new LinkedHashMap<>();
        fullSkyAirSec.put("Y", (byte) 8);
        LinkedHashMap<String, Object> airBs = new LinkedHashMap<>();
        airBs.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(airEntry)));
        fullSkyAirSec.put("block_states", airBs);
        fullSkyAirSec.put("biomes", plainsBiomes);
        byte[] all15 = new byte[2048];
        Arrays.fill(all15, (byte) 0xFF);
        fullSkyAirSec.put("SkyLight", all15);
        fullSkyAirSec.put("starlight.skylight_state", 2);
        sections.add(fullSkyAirSec);

        byte[] rawNbt = Nbt.writeNamedRoot("", root);
        Path storageDir = tempDir.resolve("starlight-state-two");
        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(storageDir, false)) {
            byte[] slab = storage.encodeChunkNbtForRegionSlab(regionX, regionZ, ByteBuffer.wrap(rawNbt));
            storage.commitPreEncodedChunkSlab(cx, cz, ByteBuffer.wrap(slab));
            storage.flush();

            // Fast byte-codec path (LcaSectionCodec#encodeChunkFrameToNbtBytes)
            LinkedHashMap<String, Object> fastDecoded = Nbt.readRootCompound(storage.readChunkNbtBytes(cx, cz));
            Map<Integer, Map<String, Object>> fastByY = new LinkedHashMap<>();
            for (Object item : ((Nbt.NbtList) fastDecoded.get("sections")).items) {
                @SuppressWarnings("unchecked")
                Map<String, Object> sm = (Map<String, Object>) item;
                fastByY.put(((Number) sm.get("Y")).intValue(), sm);
            }
            assertEquals(2, ((Number) fastByY.get(-1).getOrDefault("starlight.skylight_state", 0)).intValue());
            assertEquals(2, ((Number) fastByY.get(-1).getOrDefault("starlight.blocklight_state", 0)).intValue());
            assertEquals(2, ((Number) fastByY.get(8).getOrDefault("starlight.skylight_state", 0)).intValue());

            // Structured reader path (LcaRegionReader#decodeChunkFromFrame)
            byte[] regFileBytes = java.nio.file.Files.readAllBytes(storageDir.resolve("r.0.0.lca"));
            AnvilReader.ChunkEntry structured = LcaRegionReader.INSTANCE.readChunk(regFileBytes, cx & 31, cz & 31);
            Map<Integer, Map<String, Object>> structByY = new LinkedHashMap<>();
            for (Object item : ((Nbt.NbtList) structured.root.get("sections")).items) {
                @SuppressWarnings("unchecked")
                Map<String, Object> sm = (Map<String, Object>) item;
                structByY.put(((Number) sm.get("Y")).intValue(), sm);
            }
            assertEquals(2, ((Number) structByY.get(-1).getOrDefault("starlight.skylight_state", 0)).intValue());
            assertEquals(2, ((Number) structByY.get(-1).getOrDefault("starlight.blocklight_state", 0)).intValue());
            assertEquals(2, ((Number) structByY.get(8).getOrDefault("starlight.skylight_state", 0)).intValue());
        }

        // Verify non-Starlight Vanilla chunks emit zero starlight.* tags
        LinkedHashMap<String, Object> vanillaRoot = buildChunkRoot(dv, true, cx, cz, false);
        vanillaRoot.remove("starlight.light_version");
        byte[] vanRaw = Nbt.writeNamedRoot("", vanillaRoot);
        Path vanDir = tempDir.resolve("vanilla-no-starlight");
        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(vanDir, false)) {
            byte[] vanSlab = storage.encodeChunkNbtForRegionSlab(regionX, regionZ, ByteBuffer.wrap(vanRaw));
            storage.commitPreEncodedChunkSlab(cx, cz, ByteBuffer.wrap(vanSlab));
            LinkedHashMap<String, Object> vanDec = Nbt.readRootCompound(storage.readChunkNbtBytes(cx, cz));
            org.junit.jupiter.api.Assertions.assertFalse(vanDec.containsKey("starlight.light_version"));
            for (Object item : ((Nbt.NbtList) vanDec.get("sections")).items) {
                @SuppressWarnings("unchecked")
                Map<String, Object> sm = (Map<String, Object>) item;
                org.junit.jupiter.api.Assertions.assertFalse(sm.containsKey("starlight.skylight_state"));
                org.junit.jupiter.api.Assertions.assertFalse(sm.containsKey("starlight.blocklight_state"));
            }
        }
    }

    @Test
    void starlightFullChunkInlineNonAirUniformSectionsEmitZeroStateWithoutAffectingOpenSkyAir() throws Exception {
        int dv = 5006;
        int cx = 5;
        int cz = 6;
        int regionX = cx >> 5;
        int regionZ = cz >> 5;

        LinkedHashMap<String, Object> root = buildChunkRoot(dv, true, cx, cz, false);
        root.put("Status", "minecraft:full");
        root.put("isLightOn", (byte) 0);
        root.put("starlight.light_version", 10);
        List<Object> sections = ((Nbt.NbtList) root.get("sections")).items;
        sections.clear();

        LinkedHashMap<String, Object> plainsBiomes = new LinkedHashMap<>();
        plainsBiomes.put("palette", new Nbt.NbtList(Nbt.TAG_STRING, List.of("minecraft:plains")));

        // Inline uniform non-air section (Y = -3, no explicit SkyLight array): must decode with starlight.skylight_state = 1
        LinkedHashMap<String, Object> inlineStoneSec = new LinkedHashMap<>();
        inlineStoneSec.put("Y", (byte) -3);
        LinkedHashMap<String, Object> stoneBs = new LinkedHashMap<>();
        LinkedHashMap<String, Object> deepslateEntry = new LinkedHashMap<>();
        deepslateEntry.put("id", "minecraft:deepslate");
        stoneBs.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(deepslateEntry)));
        inlineStoneSec.put("block_states", stoneBs);
        inlineStoneSec.put("biomes", plainsBiomes);
        sections.add(inlineStoneSec);

        // Inline uniform open-sky air section (Y = 14, no explicit SkyLight): must remain open for sky extrusion
        LinkedHashMap<String, Object> inlineAirSec = new LinkedHashMap<>();
        inlineAirSec.put("Y", (byte) 14);
        LinkedHashMap<String, Object> airBs = new LinkedHashMap<>();
        LinkedHashMap<String, Object> airEntry = new LinkedHashMap<>();
        airEntry.put("id", "minecraft:air");
        airBs.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(airEntry)));
        inlineAirSec.put("block_states", airBs);
        inlineAirSec.put("biomes", plainsBiomes);
        sections.add(inlineAirSec);

        byte[] rawNbt = Nbt.writeNamedRoot("", root);
        LcaRegionWriter.LcaLiveArchive archive =
                LcaRegionWriter.createEmptyLiveArchive(regionX, regionZ, -4, 24, dv, false);
        byte[] slab = LcaStreamingNbtEncoder.encodeChunkSlab(
                archive, regionX, regionZ, ByteBuffer.wrap(rawNbt));

        Path storageDir = tempDir.resolve("starlight-inline-full");
        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(storageDir, false)) {
            storage.commitPreEncodedChunkSlab(cx, cz, ByteBuffer.wrap(slab));
            byte[] nbtFast = storage.readChunkNbtBytes(cx, cz);
            LinkedHashMap<String, Object> fastDecoded = Nbt.readRootCompound(nbtFast);
            Map<Integer, Map<String, Object>> fastByY = new LinkedHashMap<>();
            for (Object item : ((Nbt.NbtList) fastDecoded.get("sections")).items) {
                @SuppressWarnings("unchecked")
                Map<String, Object> sm = (Map<String, Object>) item;
                fastByY.put(((Number) sm.get("Y")).intValue(), sm);
            }
            Map<String, Object> fastStone = fastByY.get(-3);
            org.junit.jupiter.api.Assertions.assertNotNull(fastStone);
            org.junit.jupiter.api.Assertions.assertEquals(
                    1,
                    ((Number) fastStone.getOrDefault("starlight.skylight_state", -1)).intValue(),
                    "Inline non-air uniform section in Starlight full chunk must emit starlight.skylight_state=1");
            Map<String, Object> fastAir = fastByY.get(14);
            org.junit.jupiter.api.Assertions.assertNotNull(fastAir);
            org.junit.jupiter.api.Assertions.assertFalse(
                    fastAir.containsKey("starlight.skylight_state"),
                    "Open-sky inline uniform air section must not emit starlight.skylight_state=1");
        }
    }
}
