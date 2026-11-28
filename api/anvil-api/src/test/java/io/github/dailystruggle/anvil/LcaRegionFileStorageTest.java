package io.github.dailystruggle.anvil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit and concurrency verification suite for {@link LcaRegionFileStorage} and
 * {@link LcaRegionWriter} durability invariants (`anvil-api-ADR-002`, `anvil-api-ADR-003`).
 *
 * @governedBy anvil-api-ADR-002
 * @governedBy anvil-api-ADR-003
 * @governedBy leafanvil-ADR-005
 * @governedBy leafanvil-ADR-006
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
class LcaRegionFileStorageTest {

    private static LinkedHashMap<String, Object> buildChunkNbt(int chunkX, int chunkZ, String blockName) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", 4671);
        root.put("xPos", chunkX);
        root.put("yPos", -4);
        root.put("zPos", chunkZ);
        root.put("Status", "minecraft:full");
        root.put("LastUpdate", 42L);
        root.put("InhabitedTime", 7L);

        LinkedHashMap<String, Object> sec = new LinkedHashMap<>();
        sec.put("Y", (byte) 0);
        LinkedHashMap<String, Object> blockStates = new LinkedHashMap<>();
        List<Object> palette = new ArrayList<>();
        LinkedHashMap<String, Object> p0 = new LinkedHashMap<>();
        p0.put("Name", blockName);
        palette.add(p0);
        blockStates.put("palette", palette);
        sec.put("block_states", blockStates);

        LinkedHashMap<String, Object> biomes = new LinkedHashMap<>();
        biomes.put("palette", List.of("minecraft:plains"));
        sec.put("biomes", biomes);

        root.put("sections", List.of(sec));
        return root;
    }

    @Test
    @DisplayName("Concurrent createIfAbsent callers joining a null read-open share one archive without truncation")
    void concurrentCreateAfterNullReadOpenPreservesSingleArchiveAndAllChunks(@TempDir Path tempDir) throws Exception {
        Path regionDir = tempDir.resolve("region");
        Files.createDirectories(regionDir);

        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(regionDir)) {
            Field openingField = LcaRegionFileStorage.class.getDeclaredField("openingRegions");
            openingField.setAccessible(true);
            @SuppressWarnings("unchecked")
            ConcurrentHashMap<Long, CompletableFuture<LcaRegionWriter.LcaLiveArchive>> openingRegions =
                    (ConcurrentHashMap<Long, CompletableFuture<LcaRegionWriter.LcaLiveArchive>>) openingField.get(storage);

            long key = LcaRegionFileStorage.packRegionKey(0, 0);
            CompletableFuture<LcaRegionWriter.LcaLiveArchive> inflightReadOpen = new CompletableFuture<>();
            openingRegions.put(key, inflightReadOpen);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch entered = new CountDownLatch(2);
            try {
                Future<LcaRegionWriter.LcaLiveArchive> w1 = pool.submit(() -> {
                    entered.countDown();
                    LcaRegionWriter.LcaLiveArchive a = storage.getOrOpenRegion(0, 0, true);
                    a.saveChunkCoW(1, 1, buildChunkNbt(1, 1, "minecraft:diamond_block"));
                    return a;
                });
                Future<LcaRegionWriter.LcaLiveArchive> w2 = pool.submit(() -> {
                    entered.countDown();
                    LcaRegionWriter.LcaLiveArchive a = storage.getOrOpenRegion(0, 0, true);
                    a.saveChunkCoW(2, 2, buildChunkNbt(2, 2, "minecraft:emerald_block"));
                    return a;
                });

                assertTrue(entered.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (inflightReadOpen.getNumberOfDependents() < 2 && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }

                openingRegions.remove(key, inflightReadOpen);
                inflightReadOpen.complete(null);

                LcaRegionWriter.LcaLiveArchive a1 = w1.get(10, TimeUnit.SECONDS);
                LcaRegionWriter.LcaLiveArchive a2 = w2.get(10, TimeUnit.SECONDS);
                assertSame(a1, a2, "Concurrent writers retrying after null read-open must converge on one LcaLiveArchive");
            } finally {
                pool.shutdownNow();
            }
        }

        byte[] persisted = Files.readAllBytes(regionDir.resolve("r.0.0.lca"));
        AnvilReader.ChunkEntry c1 = LcaRegionReader.INSTANCE.readChunk(persisted, 1, 1);
        AnvilReader.ChunkEntry c2 = LcaRegionReader.INSTANCE.readChunk(persisted, 2, 2);
        assertNotNull(c1, "Chunk (1, 1) must survive concurrent region creation");
        assertNotNull(c2, "Chunk (2, 2) must survive concurrent region creation");
        assertEquals(1, ((Number) c1.root.get("xPos")).intValue());
        assertEquals(2, ((Number) c2.root.get("xPos")).intValue());
    }

    @Test
    @DisplayName("invalidateRegionCacheState evicts LcaRegionReader CachedFileHeader after external file update")
    void invalidateRegionCacheStateEvictsFreshHeaderCache(@TempDir Path tempDir) throws Exception {
        Path regionDir = tempDir.resolve("region");
        Files.createDirectories(regionDir);

        try (LcaRegionFileStorage writerStorage = new LcaRegionFileStorage(regionDir)) {
            writerStorage.writeChunkCompound(0, 0, buildChunkNbt(0, 0, "minecraft:stone"));
        }

        try (LcaRegionFileStorage readerStorage = new LcaRegionFileStorage(
                regionDir,
                LcaRegionReader.STORAGE_KIND_REGION_VOXEL,
                -4,
                24,
                4671,
                1,
                false,
                true,
                16,
                0.35)) {
            assertTrue(readerStorage.doesChunkExist(0, 0));
            assertFalse(readerStorage.doesChunkExist(5, 5));

            LcaRegionWriter.LcaLiveArchive external =
                    LcaRegionWriter.createEmptyLiveArchive(0, 0, -4, 24, 4671, false);
            external.saveChunkCoW(0, 0, buildChunkNbt(0, 0, "minecraft:stone"));
            external.saveChunkCoW(5, 5, buildChunkNbt(5, 5, "minecraft:gold_block"));
            Files.write(regionDir.resolve("r.0.0.lca"), external.toByteArray());

            readerStorage.invalidateRegionCacheState(0, 0);
            assertTrue(readerStorage.doesChunkExist(5, 5),
                    "invalidateRegionCacheState must clear stale CachedFileHeader so newly written chunks are visible");
            Map<String, Object> loaded = readerStorage.readChunkCompound(5, 5);
            assertNotNull(loaded);
            assertEquals(5, ((Number) loaded.get("xPos")).intValue());
        }
    }

    @Test
    @DisplayName("close and closeRegion force dirty channels while LRU eviction closes without force")
    void closeAndCloseRegionForceChannelsWhileLruEvictionDoesNot(@TempDir Path tempDir) throws Exception {
        Path regionDir = tempDir.resolve("region");
        Files.createDirectories(regionDir);

        LcaRegionWriter.LcaLiveArchive evictedRegion0;
        LcaRegionWriter.LcaLiveArchive explicitCloseRegion1;
        LcaRegionWriter.LcaLiveArchive storageClosedRegion2;
        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(
                regionDir,
                LcaRegionReader.STORAGE_KIND_REGION_VOXEL,
                -4,
                24,
                4671,
                1,
                false,
                true,
                1,
                0.35)) {
            evictedRegion0 = storage.getOrOpenRegion(0, 0, true);
            evictedRegion0.saveChunkCoW(0, 0, buildChunkNbt(0, 0, "minecraft:stone"));
            assertEquals(0L, evictedRegion0.diskChannelForceCount());

            // Opening region (1, 0) then (2, 0) with maxOpenRegions=1 evicts region (0, 0) via closeWithoutForce()
            explicitCloseRegion1 = storage.getOrOpenRegion(1, 0, true);
            explicitCloseRegion1.saveChunkCoW(0, 0, buildChunkNbt(32, 0, "minecraft:dirt"));
            storageClosedRegion2 = storage.getOrOpenRegion(2, 0, true);
            storageClosedRegion2.saveChunkCoW(0, 0, buildChunkNbt(64, 0, "minecraft:cobblestone"));

            assertFalse(evictedRegion0.isChannelOpen(), "Evicted region handle must close its channel");
            assertEquals(0L, evictedRegion0.diskChannelForceCount(),
                    "LRU cache eviction must not stall workers with synchronous fsync");

            // Reopen region (1, 0), call unforced flush(), then explicit closeRegion(1, 0) -> must still force
            explicitCloseRegion1 = storage.getOrOpenRegion(1, 0, false);
            assertNotNull(explicitCloseRegion1);
            explicitCloseRegion1.saveChunkCoW(1, 1, buildChunkNbt(33, 1, "minecraft:oak_planks"));
            explicitCloseRegion1.flush();
            assertEquals(0L, explicitCloseRegion1.diskChannelForceCount(),
                    "Default runtime flush() leaves leafanvil.fsync off unless explicitly requested");
            storage.closeRegion(1, 0);
            assertEquals(1L, explicitCloseRegion1.diskChannelForceCount(),
                    "Explicit closeRegion must force prior unforced channel writes to disk");

            storageClosedRegion2 = storage.getOrOpenRegion(2, 0, true);
            storageClosedRegion2.saveChunkCoW(2, 2, buildChunkNbt(66, 2, "minecraft:sandstone"));
        }

        assertEquals(1L, storageClosedRegion2.diskChannelForceCount(),
                "Explicit storage.close() must force open dirty/written archives by default");
    }

    @Test
    @DisplayName("writeBytesAtomicallyDurable replaces target durably and lazy .mca migration preserves chunk NBT")
    void writeBytesAtomicallyDurableAndLazyMigrationPreserveData(@TempDir Path tempDir) throws Exception {
        Path regionDir = tempDir.resolve("region");
        Files.createDirectories(regionDir);

        LcaRegionWriter.LcaLiveArchive srcLca =
                LcaRegionWriter.createEmptyLiveArchive(0, 0, -4, 24, 4671, false);
        LinkedHashMap<String, Object> expected = buildChunkNbt(4, 9, "minecraft:obsidian");
        srcLca.saveChunkCoW(4, 9, expected);
        byte[] mcaBytes = LcaRegionWriter.transcodeToMca(srcLca.toByteArray());

        Path mcaFile = regionDir.resolve("r.0.0.mca");
        Path mcaTmp = regionDir.resolve("r.0.0.mca.tmp");
        LcaRegionWriter.writeBytesAtomicallyDurable(mcaFile, mcaTmp, mcaBytes);
        assertTrue(Files.isRegularFile(mcaFile));
        assertFalse(Files.exists(mcaTmp), "Temporary staging file must not remain after atomic move");

        byte[] expectedNbtBytes;
        AnvilReader.ChunkEntry mcaEntry = AnvilReader.INSTANCE.readChunk(Files.readAllBytes(mcaFile), 4, 9);
        assertNotNull(mcaEntry);
        expectedNbtBytes = Nbt.writeNamedRoot("", mcaEntry.root);

        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(regionDir)) {
            assertTrue(storage.doesChunkExist(4, 9));
            assertEquals(1L, storage.migratedRegionCount());
            byte[] migratedNbtBytes = storage.readChunkNbtBytes(4, 9);
            assertNotNull(migratedNbtBytes);
            AnvilReader.ChunkEntry roundTripped = LcaRegionReader.INSTANCE.readChunk(
                    Files.readAllBytes(regionDir.resolve("r.0.0.lca")), 4, 9);
            assertNotNull(roundTripped);
            assertEquals(expected.get("xPos"), roundTripped.root.get("xPos"));
            assertEquals(expected.get("zPos"), roundTripped.root.get("zPos"));
            assertEquals(expectedNbtBytes.length > 0, migratedNbtBytes.length > 0);
        }
        assertTrue(Files.isRegularFile(regionDir.resolve("r.0.0.mca.bak")),
                "Migrated .mca source must be preserved as .bak after durable .lca write");
    }

    @Test
    @DisplayName("getChunkDataInputStream and getChunkDataOutputStream buffer byte-level NBT I/O and round-trip concurrently")
    void chunkDataStreamsBufferByteLevelIoAndRoundTripConcurrently(@TempDir Path tempDir) throws Exception {
        Path regionDir = tempDir.resolve("region");
        Files.createDirectories(regionDir);

        try (LcaRegionFileStorage writerStorage = new LcaRegionFileStorage(regionDir)) {
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Future<?>> writes = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    final int cx = i;
                    final int cz = i + 1;
                    writes.add(pool.submit(() -> {
                        byte[] rawNbt = Nbt.writeNamedRoot("", buildChunkNbt(cx, cz, "minecraft:deepslate_diamond_ore"));
                        try (java.io.DataOutputStream out = writerStorage.getChunkDataOutputStream(cx, cz)) {
                            // Exercise single-byte DataOutputStream writes matching NbtIo.write tag headers
                            for (byte b : rawNbt) {
                                out.writeByte(b & 0xFF);
                            }
                        }
                        return null;
                    }));
                }
                for (Future<?> f : writes) {
                    f.get(10, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            // Live cached archive read path must wrap LazyChunkNbtInputStream in BufferedInputStream (markSupported == true)
            try (java.io.DataInputStream liveIn = writerStorage.getChunkDataInputStream(3, 4)) {
                assertNotNull(liveIn);
                assertTrue(
                        liveIn.markSupported(),
                        "Live archive getChunkDataInputStream must buffer LazyChunkNbtInputStream for NbtIo#read");
                Map<String, Object> parsed = Nbt.readRootCompound(liveIn.readAllBytes());
                assertEquals(3, ((Number) parsed.get("xPos")).intValue());
                assertEquals(4, ((Number) parsed.get("zPos")).intValue());
            }
        }

        // Cold read-channel header path must also wrap LazyChunkNbtInputStream in BufferedInputStream (markSupported == true)
        try (LcaRegionFileStorage coldReaderStorage = new LcaRegionFileStorage(regionDir)) {
            try (java.io.DataInputStream coldIn = coldReaderStorage.getChunkDataInputStream(5, 6)) {
                assertNotNull(coldIn);
                assertTrue(
                        coldIn.markSupported(),
                        "Cold channel getChunkDataInputStream must buffer LazyChunkNbtInputStream for NbtIo#read");
                Map<String, Object> parsed = Nbt.readRootCompound(coldIn.readAllBytes());
                assertEquals(5, ((Number) parsed.get("xPos")).intValue());
                assertEquals(6, ((Number) parsed.get("zPos")).intValue());
            }
        }

        // Verify Nbt slice overloads decode embedded slices without copying and fail closed on invalid bounds
        byte[] validBytes = Nbt.writeNamedRoot("", buildChunkNbt(9, 11, "minecraft:emerald_block"));
        byte[] padded = new byte[validBytes.length + 24];
        System.arraycopy(validBytes, 0, padded, 11, validBytes.length);
        Map<String, Object> sliceParsed = Nbt.readRootCompound(padded, 11, validBytes.length);
        assertEquals(9, ((Number) sliceParsed.get("xPos")).intValue());
        assertEquals(11, ((Number) sliceParsed.get("zPos")).intValue());
        org.junit.jupiter.api.Assertions.assertThrows(
                java.io.IOException.class, () -> Nbt.readRootCompound(padded, -1, 4));
        org.junit.jupiter.api.Assertions.assertThrows(
                java.io.IOException.class, () -> Nbt.readRootCompound(padded, 11, padded.length));
    }

    private static LinkedHashMap<String, Object> build24SectionNonUniformChunkNbt(int chunkX, int chunkZ) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", 4671);
        root.put("xPos", chunkX);
        root.put("yPos", -4);
        root.put("zPos", chunkZ);
        root.put("Status", "minecraft:full");
        root.put("LastUpdate", 100L);
        root.put("InhabitedTime", 25L);

        List<Object> sections = new ArrayList<>(24);
        String[] blocks = {
                "minecraft:stone", "minecraft:granite", "minecraft:diorite", "minecraft:andesite",
                "minecraft:coal_ore", "minecraft:iron_ore", "minecraft:copper_ore", "minecraft:gold_ore"
        };
        for (int s = 0; s < 24; s++) {
            int sy = -4 + s;
            LinkedHashMap<String, Object> sec = new LinkedHashMap<>();
            sec.put("Y", (byte) sy);

            LinkedHashMap<String, Object> bs = new LinkedHashMap<>();
            List<Object> pal = new ArrayList<>(3);
            LinkedHashMap<String, Object> p0 = new LinkedHashMap<>();
            p0.put("Name", blocks[s % blocks.length]);
            pal.add(p0);
            LinkedHashMap<String, Object> p1 = new LinkedHashMap<>();
            p1.put("Name", blocks[(s + 1) % blocks.length]);
            pal.add(p1);
            LinkedHashMap<String, Object> p2 = new LinkedHashMap<>();
            p2.put("Name", "minecraft:deepslate");
            pal.add(p2);
            bs.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, pal));

            // 3 states -> 4 bits per entry -> 16 entries per long -> 256 longs
            long[] words = new long[256];
            words[0] = (1L) | (2L << 4) | (1L << 12);
            words[1 + s * 7] = (2L << 8) | (1L << 20);
            words[255] = (1L << 60);
            bs.put("data", words);
            sec.put("block_states", bs);

            LinkedHashMap<String, Object> biomes = new LinkedHashMap<>();
            biomes.put("palette", new Nbt.NbtList(Nbt.TAG_STRING, List.of("minecraft:plains")));
            sec.put("biomes", biomes);

            byte[] sky = new byte[2048];
            java.util.Arrays.fill(sky, (byte) ((s * 17 + 3) & 0xFF));
            sec.put("SkyLight", sky);
            sections.add(sec);
        }
        root.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, sections));
        return root;
    }

    @Test
    @DisplayName("Mode A (0-LZ) and Mode B (LCD2 0x4C44) readRawChunkFrame + commitSplicedChunkFrameCoW preserve 23 untouched SectionFrames bit-for-bit")
    void rawChunkFrameAndSplicedCoWFlushPreserve23UntouchedSectionsBitForBit(@TempDir Path tempDir) throws Exception {
        Map<String, Integer> runtimeRegistry = new LinkedHashMap<>();
        String[] allStates = {
                "minecraft:air", "minecraft:stone", "minecraft:granite", "minecraft:diorite",
                "minecraft:andesite", "minecraft:coal_ore", "minecraft:iron_ore", "minecraft:copper_ore",
                "minecraft:gold_ore", "minecraft:deepslate", "minecraft:diamond_block", "minecraft:emerald_block"
        };
        for (int i = 0; i < allStates.length; i++) {
            runtimeRegistry.put(allStates[i], 100 + i);
        }

        for (int lcd2Level : new int[] {0, 1}) {
            Path regionDir = tempDir.resolve("region_mode_" + lcd2Level);
            Files.createDirectories(regionDir);

            try (LcaRegionFileStorage storage = new LcaRegionFileStorage(
                    regionDir,
                    LcaRegionReader.STORAGE_KIND_REGION_VOXEL,
                    -4,
                    24,
                    4671,
                    lcd2Level,
                    true,
                    true,
                    16,
                    0.35)) {
                assertEquals(lcd2Level, storage.lcd2Level());
                assertEquals(lcd2Level > 0, storage.modeBLcd2());

                storage.writeChunkCompound(3, 5, build24SectionNonUniformChunkNbt(3, 5));

                // Test both cached live-archive readRawChunkFrame and cold channel readRawChunkFrame
                RawChunkFrameSnapshot liveSnap = storage.readRawChunkFrame(3, 5, runtimeRegistry::get);
                assertNotNull(liveSnap);
                assertEquals(4671, liveSnap.dataVersion());
                assertEquals(-4, liveSnap.minSectionY());
                assertEquals(24, liveSnap.sectionCount());
                assertNotNull(liveSnap.dictIdToRuntimeId());

                storage.closeRegion(0, 0);
                RawChunkFrameSnapshot coldSnap = storage.readRawChunkFrame(3, 5, runtimeRegistry::get);
                assertNotNull(coldSnap);
                org.junit.jupiter.api.Assertions.assertArrayEquals(
                        liveSnap.chunkFrame(),
                        coldSnap.chunkFrame(),
                        "Cold FileChannel pread ChunkFrame must match live archive ChunkFrame in Mode " + lcd2Level);

                byte[][] beforeSlices = new byte[24][];
                for (int s = 0; s < 24; s++) {
                    beforeSlices[s] = coldSnap.sliceSectionFrame(s);
                    assertTrue(beforeSlices[s].length > 0, "Fixture section " + s + " must have non-uniform SectionFrame bytes");
                }

                // Mutate 2 blocks in section y = 4 (sectionIdx = 8, since minSectionY = -4)
                int editedSectionIdx = 8; // y = 4 -> worldY in [64..79]
                int diamondDictId = storage.resolveRuntimeIdToDictId(0, 0, 110, id -> allStates[id - 100]);
                int emeraldDictId = storage.resolveRuntimeIdToDictId(0, 0, 111, id -> allStates[id - 100]);
                int[] sparseModifiers = new int[] {
                        LcaSectionBacking.packEntry32(LcaSectionBacking.pos12(2, 3, 4), diamondDictId),
                        LcaSectionBacking.packEntry32(LcaSectionBacking.pos12(14, 15, 9), emeraldDictId)
                };

                storage.commitSplicedChunkFrameCoW(
                        3,
                        5,
                        coldSnap.chunkFrame(),
                        1 << editedSectionIdx,
                        secIdx -> secIdx == editedSectionIdx ? sparseModifiers : null,
                        null
                );

                RawChunkFrameSnapshot afterSnap = storage.readRawChunkFrame(3, 5, runtimeRegistry::get);
                assertNotNull(afterSnap);
                for (int s = 0; s < 24; s++) {
                    if (s != editedSectionIdx) {
                        org.junit.jupiter.api.Assertions.assertArrayEquals(
                                beforeSlices[s],
                                afterSnap.sliceSectionFrame(s),
                                "Untouched section " + s + " SectionFrame bytes must remain bit-for-bit identical in Mode " + lcd2Level);
                    }
                }

                storage.flush();
                AnvilChunkView view = LcaRegionReader.readChunkViewFromFile(regionDir.resolve("r.0.0.lca"), 3, 5);
                assertNotNull(view);
                assertEquals("minecraft:diamond_block", view.blockIdAt(2, 64 + 3, 4));
                assertEquals("minecraft:emerald_block", view.blockIdAt(14, 64 + 15, 9));
                // Untouched section y = 0 (worldY = 0) voxel 0 was palette[1] = blocks[5] = minecraft:iron_ore
                assertEquals("minecraft:iron_ore", view.blockIdAt(0, 0, 0));
            }
        }
    }

    @Test
    @DisplayName("Mode A lazy AtomicDictState, shared per-region dictIdToRuntimeId array, and bounded default region cache")
    void modeALazyAtomicDictStateSharedRuntimeDictArrayAndBoundedCacheDefaults(@TempDir Path tempDir) throws Exception {
        assertEquals(
                128,
                LcaRegionFileStorage.DEFAULT_MAX_OPEN_REGIONS,
                "Default max open regions per folder must be bounded at 128");

        Map<String, Integer> runtimeRegistry = new LinkedHashMap<>();
        String[] allStates = {
                "minecraft:air", "minecraft:stone", "minecraft:granite", "minecraft:diorite",
                "minecraft:andesite", "minecraft:coal_ore", "minecraft:iron_ore", "minecraft:copper_ore",
                "minecraft:gold_ore", "minecraft:deepslate"
        };
        for (int i = 0; i < allStates.length; i++) {
            runtimeRegistry.put(allStates[i], 200 + i);
        }
        java.util.function.Function<String, Integer> lookup = runtimeRegistry::get;

        Path modeADir = tempDir.resolve("region_lazy_mode_a");
        Files.createDirectories(modeADir);
        try (LcaRegionFileStorage modeAStorage = new LcaRegionFileStorage(modeADir, false)) {
            modeAStorage.writeChunkCompound(1, 2, build24SectionNonUniformChunkNbt(1, 2));
            modeAStorage.writeChunkCompound(2, 2, build24SectionNonUniformChunkNbt(2, 2));
            modeAStorage.flush();
        }

        Path modeAFile = modeADir.resolve("r.0.0.lca");
        LcaRegionReader.invalidateFileCache(modeAFile);
        RawChunkFrameSnapshot snapA = LcaRegionReader.readRawChunkFrameFromFile(modeAFile, 1, 2, lookup);
        RawChunkFrameSnapshot snapB = LcaRegionReader.readRawChunkFrameFromFile(modeAFile, 2, 2, lookup);
        assertNotNull(snapA);
        assertNotNull(snapB);
        assertNotNull(snapA.dictIdToRuntimeId());
        org.junit.jupiter.api.Assertions.assertSame(
                snapA.dictIdToRuntimeId(),
                snapB.dictIdToRuntimeId(),
                "CachedFileHeader must share a single dictIdToRuntimeId int[] across chunk reads in the same region");

        java.lang.reflect.Field atomicField =
                LcaRegionWriter.LcaLiveArchive.class.getDeclaredField("atomicDictState");
        atomicField.setAccessible(true);

        try (LcaRegionWriter.LcaLiveArchive modeALive = LcaRegionWriter.openLiveArchive(modeAFile)) {
            org.junit.jupiter.api.Assertions.assertNull(
                    atomicField.get(modeALive),
                    "Mode A LcaLiveArchive must not allocate 502 KB AtomicDictState when Mode B is inactive");
            modeALive.saveChunkCoW(3, 2, build24SectionNonUniformChunkNbt(3, 2));
            org.junit.jupiter.api.Assertions.assertNull(
                    atomicField.get(modeALive),
                    "Saving Mode A chunks must keep AtomicDictState unallocated (null)");
        }

        Path modeBDir = tempDir.resolve("region_lazy_mode_b");
        Files.createDirectories(modeBDir);
        try (LcaRegionFileStorage modeBStorage = new LcaRegionFileStorage(modeBDir, true)) {
            modeBStorage.writeChunkCompound(1, 2, build24SectionNonUniformChunkNbt(1, 2));
            modeBStorage.flush();
        }
        try (LcaRegionWriter.LcaLiveArchive modeBLive = LcaRegionWriter.openLiveArchive(modeBDir.resolve("r.0.0.lca"))) {
            modeBLive.saveChunkCoW(2, 2, build24SectionNonUniformChunkNbt(2, 2));
            assertNotNull(
                    atomicField.get(modeBLive),
                    "Mode B LcaLiveArchive must lazily initialize AtomicDictState on Mode B encode");
        }
    }

    @Test
    @DisplayName("anvil-api-ADR-006: Atomic 0x0002 -> 0x0003 upgrade on writable open and RawChunkFrameSnapshot O(1) pure-ID + secExtFlags (0x01/0x02) header rewriting")
    void atomicV2ToV3UpgradeAndRawSnapshotSecExtHeaderRewriting(@TempDir Path tempDir) throws Exception {
        // 1. Write a legacy 0x0002 archive to disk and verify read-only access does not mutate it
        Path upgradeDir = tempDir.resolve("region_v2_upgrade");
        Files.createDirectories(upgradeDir);
        Path v2File = upgradeDir.resolve("r.0.0.lca");
        LcaRegionWriter.LcaLiveArchive v2Arch = LcaRegionWriter.createEmptyLiveArchiveWithVersion(
                0, 0, -4, 24, 4671, 0, LcaRegionReader.STORAGE_KIND_REGION_VOXEL,
                LcaRegionWriter.DEFAULT_ROOT_DICT_CAPACITY_BYTES, LcaRegionReader.LCA_FORMAT_VERSION_V2);
        v2Arch.saveChunkCoW(2, 3, build24SectionNonUniformChunkNbt(2, 3));
        byte[] v2Bytes = v2Arch.toByteArray();
        Files.write(v2File, v2Bytes);
        assertEquals(LcaRegionReader.LCA_FORMAT_VERSION_V2, LcaRegionWriter.peekFormatVersion(v2File));

        AnvilChunkView roView = LcaRegionReader.readChunkViewFromFile(v2File, 2, 3);
        assertNotNull(roView);
        assertEquals("minecraft:iron_ore", roView.blockIdAt(0, 0, 0));
        assertEquals(LcaRegionReader.LCA_FORMAT_VERSION_V2, LcaRegionWriter.peekFormatVersion(v2File),
                "Read-only LcaRegionReader must leave 0x0002 archive untouched on disk");

        // Opening via LcaRegionFileStorage for write atomically upgrades 0x0002 -> 0x0003
        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(upgradeDir, false)) {
            storage.writeChunkCompound(4, 5, build24SectionNonUniformChunkNbt(4, 5));
            storage.flush();
        }
        assertEquals(LcaRegionReader.LCA_FORMAT_VERSION_V3, LcaRegionWriter.peekFormatVersion(v2File),
                "Writable open via LcaRegionFileStorage must atomically upgrade 0x0002 -> 0x0003");
        try (var stream = Files.list(upgradeDir)) {
            assertTrue(stream.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "No leftover .tmp files may remain after atomic upgrade");
        }
        AnvilChunkView afterUpgradeOrig = LcaRegionReader.readChunkViewFromFile(v2File, 2, 3);
        AnvilChunkView afterUpgradeNew = LcaRegionReader.readChunkViewFromFile(v2File, 4, 5);
        assertNotNull(afterUpgradeOrig);
        assertNotNull(afterUpgradeNew);
        assertEquals("minecraft:iron_ore", afterUpgradeOrig.blockIdAt(0, 0, 0));
        assertEquals("minecraft:iron_ore", afterUpgradeNew.blockIdAt(0, 0, 0));

        // 2. RawChunkFrameSnapshot O(1) pure-ID micro-palette runtime resolution + secExtFlags (0x01 & 0x02) header rewriting
        LinkedHashMap<String, Object> v3Chunk = new LinkedHashMap<>();
        v3Chunk.put("DataVersion", 4671);
        v3Chunk.put("xPos", 0);
        v3Chunk.put("zPos", 0);
        v3Chunk.put("yPos", -4);
        v3Chunk.put("Status", "minecraft:full");

        List<Object> pal = new ArrayList<>();
        Map<String, Integer> runtimeMap = new LinkedHashMap<>();
        runtimeMap.put("minecraft:air", 1000);
        for (int i = 0; i < 16; i++) {
            String name = "snapmod:band_" + i;
            LinkedHashMap<String, Object> e = new LinkedHashMap<>();
            e.put("Name", name);
            pal.add(e);
            runtimeMap.put(name, 2000 + i);
        }
        long[] packed = new long[256];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int yzx = (y << 8) | (z << 4) | x;
                    int s = (y < 4) ? 0 : (((y - 4) + ((x + z) >>> 3)) % 16);
                    packed[yzx / 16] |= ((long) s) << ((yzx % 16) * 4);
                }
            }
        }
        LinkedHashMap<String, Object> sec = new LinkedHashMap<>();
        sec.put("Y", (byte) 0);
        LinkedHashMap<String, Object> bs = new LinkedHashMap<>();
        bs.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, pal));
        bs.put("data", packed);
        sec.put("block_states", bs);
        LinkedHashMap<String, Object> biomes = new LinkedHashMap<>();
        biomes.put("palette", new Nbt.NbtList(Nbt.TAG_STRING, List.of("minecraft:plains")));
        sec.put("biomes", biomes);
        v3Chunk.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(sec)));

        LcaV3Codec.setThreadFeatureMask(LcaV3Codec.DEFAULT_V3_FEATURES | LcaV3Codec.FEATURE_GRAY_BITPLANES);
        try {
            LcaRegionWriter.LcaLiveArchive arch =
                    LcaRegionWriter.createEmptyLiveArchive(0, 0, -4, 24, 4671, false, 1024);
            arch.saveChunkCoW(0, 0, v3Chunk);
            RawChunkFrameSnapshot snap = arch.readRawChunkFrame(0, 0, runtimeMap::get);
            assertNotNull(snap);
            assertEquals(LcaRegionReader.LCA_FORMAT_VERSION_V3, snap.formatVersion());

            // Pass null fallbackRuntimeLookup to prove 0x0003 pure-ID micro-palette resolves in O(1) with ZERO string lookups
            byte[] graySlice = snap.sliceSectionFrameWithRuntimePalette(4, null); // Y = 0 -> sectionIdx 4
            assertTrue(RawChunkFrameSnapshot.isV3ExtendedSectionFrame(graySlice),
                    "Rewritten runtime slice must preserve SEC_EXT_GRAY_BITPLANES (0x02) header");

            int[] decOrds = new int[LcaSectionCodec.SECTION_VOXELS];
            int[] runtimePal = LcaSectionCodec.decodeSectionFrameBlockOrdinals(
                    graySlice, graySlice.length, decOrds, null);
            assertEquals(15, runtimePal.length);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int yzx = (y << 8) | (z << 4) | x;
                        int expectedStateIdx = (y < 4) ? 0 : (((y - 4) + ((x + z) >>> 3)) % 16);
                        assertEquals(2000 + expectedStateIdx, runtimePal[decOrds[yzx]],
                                "Runtime ID mismatch after zero-copy secExtFlags=0x02 header rewrite at yzx=" + yzx);
                    }
                }
            }

            // Downgrade v3 Gray slice to v2-compatible SectionFrame and verify identical voxels & isV3Extended == false
            byte[] downgraded = RawChunkFrameSnapshot.downgradeV3BlockSectionFrameToV2(graySlice);
            assertFalse(RawChunkFrameSnapshot.isV3ExtendedSectionFrame(downgraded));
            int[] dgOrds = new int[LcaSectionCodec.SECTION_VOXELS];
            int[] dgPal = LcaSectionCodec.decodeSectionFrameBlockOrdinals(downgraded, downgraded.length, dgOrds, null);
            for (int yzx = 0; yzx < LcaSectionCodec.SECTION_VOXELS; yzx++) {
                assertEquals(runtimePal[decOrds[yzx]], dgPal[dgOrds[yzx]]);
            }
        } finally {
            LcaV3Codec.resetThreadFeatureMask();
        }
    }

    @Test
    @DisplayName("Concurrent compaction and lock-free ENTITY_DATA reads must never throw CBIT T3b offset variation")
    void concurrentCompactionAndEntityDataReadsNeverThrowCbitT3b(@TempDir Path tempDir) throws Exception {
        Path entitiesDir = tempDir.resolve("entities");
        Files.createDirectories(entitiesDir);
        Path lcaFile = entitiesDir.resolve("r.1.1.lca");

        // Create an entities archive in headerOnlyChannelMode with 32 chunks (including idx=4 and idx=25)
        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(entitiesDir, false)) {
            for (int rx = 0; rx < 32; rx++) {
                LinkedHashMap<String, Object> root = new LinkedHashMap<>();
                root.put("DataVersion", 5023);
                root.put("Position", new int[] {32 + rx, 32});
                List<Object> entities = new ArrayList<>();
                LinkedHashMap<String, Object> ent = new LinkedHashMap<>();
                ent.put("id", "minecraft:cow");
                ent.put("CustomName", "\"cow-" + rx + "\"");
                entities.add(ent);
                root.put("Entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, entities));
                storage.writeChunkCompound(32 + rx, 32, root);
            }
            storage.flush();
        }

        try (LcaRegionWriter.LcaLiveArchive archive = LcaRegionWriter.openLiveArchive(lcaFile)) {
            // Re-save several chunks to create fragmentation so compact() relocates slabs
            for (int round = 0; round < 6; round++) {
                for (int rx = 0; rx < 32; rx += 2) {
                    LinkedHashMap<String, Object> root = new LinkedHashMap<>();
                    root.put("DataVersion", 5023);
                    root.put("Position", new int[] {32 + rx, 32});
                    List<Object> entities = new ArrayList<>();
                    LinkedHashMap<String, Object> ent = new LinkedHashMap<>();
                    ent.put("id", "minecraft:sheep");
                    ent.put("CustomName", "\"sheep-" + rx + "-r" + round + "\"");
                    entities.add(ent);
                    root.put("Entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, entities));
                    archive.saveChunkCoW(rx, 0, root);
                }
            }

            java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
            java.util.concurrent.atomic.AtomicReference<Throwable> readerError = new java.util.concurrent.atomic.AtomicReference<>();
            Thread[] readers = new Thread[4];
            for (int t = 0; t < readers.length; t++) {
                final int tid = t;
                readers[t] = new Thread(() -> {
                    int iter = 0;
                    while (running.get() && readerError.get() == null) {
                        int rx = (iter + tid * 7) & 31;
                        try {
                            byte[] nbt = archive.readChunkNbtBytes(rx, 0);
                            if (nbt == null) {
                                readerError.compareAndSet(null, new AssertionError("Missing chunk (" + rx + ",0)"));
                                break;
                            }
                        } catch (Throwable ex) {
                            readerError.compareAndSet(null, ex);
                            break;
                        }
                        iter++;
                    }
                }, "lca-reader-" + t);
                readers[t].start();
            }

            try {
                for (int c = 0; c < 40 && readerError.get() == null; c++) {
                    // Dirty one chunk to shift slabs, then compact
                    int rx = c & 31;
                    LinkedHashMap<String, Object> root = new LinkedHashMap<>();
                    root.put("DataVersion", 5023);
                    root.put("Position", new int[] {32 + rx, 32});
                    List<Object> entities = new ArrayList<>();
                    LinkedHashMap<String, Object> ent = new LinkedHashMap<>();
                    ent.put("id", "minecraft:pig");
                    ent.put("CustomName", "\"pig-" + rx + "-c" + c + "\"");
                    entities.add(ent);
                    root.put("Entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, entities));
                    archive.saveChunkCoW(rx, 0, root);
                    archive.compact();
                }
            } finally {
                running.set(false);
                for (Thread r : readers) {
                    r.join(5000);
                }
            }

            assertNull(
                    readerError.get(),
                    () -> "Concurrent reader failed during compaction: " + readerError.get());
        }
    }

    @Test
    @DisplayName("Chest block_entities and ENTITY_DATA roundtrip via stream API in Mode A and Mode B across CoW updates and reopen")
    void chestBlockEntitiesAndEntityDataRoundtripAcrossModeAAndModeB(@TempDir Path tempDir) throws Exception {
        for (boolean modeB : new boolean[] {false, true}) {
            Path regionDir = tempDir.resolve("region_modeB_" + modeB);
            Path entitiesDir = tempDir.resolve("entities_modeB_" + modeB);
            Files.createDirectories(regionDir);
            Files.createDirectories(entitiesDir);

            // 1. Write a chunk with a chest containing items via getChunkDataOutputStream
            LinkedHashMap<String, Object> chunkWithChest = build24SectionNonUniformChunkNbt(4, 0);
            LinkedHashMap<String, Object> chest = new LinkedHashMap<>();
            chest.put("id", "minecraft:chest");
            chest.put("x", 4 * 16 + 3);
            chest.put("y", 64);
            chest.put("z", 5);
            chest.put("keepPacked", (byte) 0);
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("Slot", (byte) 0);
            item.put("id", "minecraft:diamond");
            item.put("count", 16);
            chest.put("Items", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(item)));
            chunkWithChest.put("block_entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(chest)));

            byte[] chestChunkNbt = Nbt.writeNamedRoot("", chunkWithChest);

            try (LcaRegionFileStorage regionStorage = new LcaRegionFileStorage(regionDir, modeB);
                 LcaRegionFileStorage entityStorage = new LcaRegionFileStorage(entitiesDir, modeB)) {
                try (java.io.DataOutputStream dos = regionStorage.getChunkDataOutputStream(4, 0)) {
                    dos.write(chestChunkNbt);
                }

                // Write 32 entity chunks, update them, remove some, and re-write
                for (int rx = 0; rx < 32; rx++) {
                    LinkedHashMap<String, Object> entRoot = new LinkedHashMap<>();
                    entRoot.put("DataVersion", 5023);
                    entRoot.put("Position", new int[] {32 + rx, 32});
                    LinkedHashMap<String, Object> ent = new LinkedHashMap<>();
                    ent.put("id", "minecraft:villager");
                    ent.put("CustomName", "\"villager-" + rx + "\"");
                    entRoot.put("Entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(ent)));
                    try (java.io.DataOutputStream dos = entityStorage.getChunkDataOutputStream(32 + rx, 32)) {
                        dos.write(Nbt.writeNamedRoot("", entRoot));
                    }
                }
                entityStorage.removeChunk(32 + 4, 32);
                for (int rx = 0; rx < 32; rx++) {
                    LinkedHashMap<String, Object> entRoot = new LinkedHashMap<>();
                    entRoot.put("DataVersion", 5023);
                    entRoot.put("Position", new int[] {32 + rx, 32});
                    LinkedHashMap<String, Object> ent = new LinkedHashMap<>();
                    ent.put("id", "minecraft:cow");
                    ent.put("CustomName", "\"cow-v2-" + rx + "\"");
                    entRoot.put("Entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(ent)));
                    try (java.io.DataOutputStream dos = entityStorage.getChunkDataOutputStream(32 + rx, 32)) {
                        dos.write(Nbt.writeNamedRoot("", entRoot));
                    }
                }
                regionStorage.flush();
                entityStorage.flush();
            }

            // Reopen and verify both chest block_entities and all 32 entity chunks
            try (LcaRegionFileStorage regionStorage = new LcaRegionFileStorage(regionDir, modeB);
                 LcaRegionFileStorage entityStorage = new LcaRegionFileStorage(entitiesDir, modeB)) {
                try (java.io.DataInputStream dis = regionStorage.getChunkDataInputStream(4, 0)) {
                    assertNotNull(dis);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> decoded = (Map<String, Object>) Nbt.readNamedRoot(dis.readAllBytes()).value;
                    Nbt.NbtList besList = (Nbt.NbtList) decoded.get("block_entities");
                    assertNotNull(besList, "block_entities missing in modeB=" + modeB);
                    assertEquals(1, besList.items.size());
                    @SuppressWarnings("unchecked")
                    Map<String, Object> decChest = (Map<String, Object>) besList.items.get(0);
                    assertEquals("minecraft:chest", decChest.get("id"));
                    Nbt.NbtList itemsList = (Nbt.NbtList) decChest.get("Items");
                    assertNotNull(itemsList, "Chest Items missing in modeB=" + modeB);
                    assertEquals(1, itemsList.items.size());
                    @SuppressWarnings("unchecked")
                    Map<String, Object> decItem = (Map<String, Object>) itemsList.items.get(0);
                    assertEquals("minecraft:diamond", decItem.get("id"));
                    assertEquals(16, ((Number) decItem.get("count")).intValue());
                }

                for (int rx = 0; rx < 32; rx++) {
                    try (java.io.DataInputStream dis = entityStorage.getChunkDataInputStream(32 + rx, 32)) {
                        assertNotNull(dis, "Missing entity chunk (" + (32 + rx) + ",32) in modeB=" + modeB);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> decoded = (Map<String, Object>) Nbt.readNamedRoot(dis.readAllBytes()).value;
                        Nbt.NbtList entsList = (Nbt.NbtList) decoded.get("Entities");
                        assertNotNull(entsList);
                        assertEquals(1, entsList.items.size());
                        @SuppressWarnings("unchecked")
                        Map<String, Object> decEnt = (Map<String, Object>) entsList.items.get(0);
                        assertEquals("minecraft:cow", decEnt.get("id"));
                        assertEquals("\"cow-v2-" + rx + "\"", decEnt.get("CustomName"));
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Pending getChunkDataOutputStream closed after storage.close() must not silently drop chest data")
    void pendingOutputStreamClosedAfterStorageCloseMustNotSilentlyDropChestData(@TempDir Path tempDir) throws Exception {
        Path regionDir = tempDir.resolve("region_close_drop");
        Files.createDirectories(regionDir);

        LinkedHashMap<String, Object> chunkWithChest = build24SectionNonUniformChunkNbt(4, 0);
        LinkedHashMap<String, Object> chest = new LinkedHashMap<>();
        chest.put("id", "minecraft:chest");
        chest.put("x", 4 * 16 + 3);
        chest.put("y", 64);
        chest.put("z", 5);
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        item.put("Slot", (byte) 0);
        item.put("id", "minecraft:diamond");
        item.put("count", 32);
        chest.put("Items", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(item)));
        chunkWithChest.put("block_entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(chest)));
        byte[] chestChunkNbt = Nbt.writeNamedRoot("", chunkWithChest);

        LcaRegionFileStorage storage = new LcaRegionFileStorage(regionDir, true);
        // Prime archive with an initial chunk
        storage.writeChunkCompound(0, 0, build24SectionNonUniformChunkNbt(0, 0));
        // Paper opens output stream for chest chunk, then LeafAnvil.onDisable() calls storage.close() before stream closes
        java.io.DataOutputStream dos = storage.getChunkDataOutputStream(4, 0);
        storage.close();
        dos.write(chestChunkNbt);
        dos.close();
        storage.close();

        // Reopen and verify chest chunk was NOT silently dropped
        try (LcaRegionFileStorage reopened = new LcaRegionFileStorage(regionDir, true)) {
            try (java.io.DataInputStream dis = reopened.getChunkDataInputStream(4, 0)) {
                assertNotNull(dis, "Chest chunk (4,0) was silently dropped when output stream closed after storage.close()");
                @SuppressWarnings("unchecked")
                Map<String, Object> decoded = (Map<String, Object>) Nbt.readNamedRoot(dis.readAllBytes()).value;
                Nbt.NbtList besList = (Nbt.NbtList) decoded.get("block_entities");
                assertNotNull(besList, "block_entities missing after reopen");
                assertEquals(1, besList.items.size());
            }
        }
    }

    @Test
    @DisplayName("Concurrent storage.close() and ENTITY_DATA writes must never produce overlapping slabs (CBIT T3b)")
    void concurrentStorageCloseAndEntityWritesNeverProduceOverlappingSlabs(@TempDir Path tempDir) throws Exception {
        Path entitiesDir = tempDir.resolve("entities_close_race");
        Files.createDirectories(entitiesDir);

        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(entitiesDir, true)) {
            for (int rx = 0; rx < 32; rx++) {
                LinkedHashMap<String, Object> root = new LinkedHashMap<>();
                root.put("DataVersion", 5023);
                root.put("Position", new int[] {32 + rx, 32});
                LinkedHashMap<String, Object> ent = new LinkedHashMap<>();
                ent.put("id", "minecraft:cow");
                ent.put("CustomName", "\"cow-init-" + rx + "\"");
                root.put("Entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(ent)));
                storage.writeChunkCompound(32 + rx, 32, root);
            }
            storage.flush();

            java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
            java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
            Thread closer = new Thread(() -> {
                while (running.get() && error.get() == null) {
                    try {
                        storage.close();
                        Thread.yield();
                    } catch (Throwable t) {
                        error.compareAndSet(null, t);
                    }
                }
            }, "lca-closer");
            closer.start();

            Thread[] writers = new Thread[4];
            for (int t = 0; t < writers.length; t++) {
                final int tid = t;
                writers[t] = new Thread(() -> {
                    for (int round = 0; round < 20 && error.get() == null; round++) {
                        for (int rx = tid; rx < 32 && error.get() == null; rx += writers.length) {
                            try {
                                LinkedHashMap<String, Object> root = new LinkedHashMap<>();
                                root.put("DataVersion", 5023);
                                root.put("Position", new int[] {32 + rx, 32});
                                LinkedHashMap<String, Object> ent = new LinkedHashMap<>();
                                ent.put("id", "minecraft:sheep");
                                ent.put("CustomName", "\"sheep-r" + round + "-" + rx + "\"");
                                root.put("Entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(ent)));
                                byte[] nbt = Nbt.writeNamedRoot("", root);
                                try (java.io.DataOutputStream dos = storage.getChunkDataOutputStream(32 + rx, 32)) {
                                    dos.write(nbt);
                                }
                            } catch (Throwable ex) {
                                error.compareAndSet(null, ex);
                                break;
                            }
                        }
                    }
                }, "lca-writer-" + t);
                writers[t].start();
            }

            try {
                for (Thread w : writers) {
                    w.join(15000);
                }
            } finally {
                running.set(false);
                closer.join(5000);
            }
            if (error.get() != null) {
                throw new AssertionError("Concurrent close/write threw error", error.get());
            }
        }

        // Reopen and read all 32 chunks — any overlapping slab allocation throws CBIT T3b
        try (LcaRegionFileStorage verify = new LcaRegionFileStorage(entitiesDir, true)) {
            for (int rx = 0; rx < 32; rx++) {
                try (java.io.DataInputStream dis = verify.getChunkDataInputStream(32 + rx, 32)) {
                    assertNotNull(dis, "Entity chunk (" + (32 + rx) + ",32) missing");
                    byte[] bytes = dis.readAllBytes();
                    assertTrue(bytes.length > 0);
                }
            }
        }
    }

    @Test
    @DisplayName("Overlapping pointer alias on open is scrubbed and never double-frees slabs into live allocations")
    void overlappingPointerAliasOnOpenIsScrubbedAndNeverDoubleFrees(@TempDir Path tempDir) throws Exception {
        Path entitiesDir = tempDir.resolve("entities_overlap_scrub");
        Files.createDirectories(entitiesDir);
        Path lcaFile = entitiesDir.resolve("r.1.0.lca");

        // 1. Create a valid entity archive with chunkIdx=707 (rx=3, rz=22) and chunkIdx=900 (rx=4, rz=28)
        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(entitiesDir, true)) {
            for (int i = 0; i < 4; i++) {
                int rx = (i == 0) ? 3 : (i == 1) ? 4 : 10 + i;
                int rz = (i == 0) ? 22 : (i == 1) ? 28 : 5;
                LinkedHashMap<String, Object> root = new LinkedHashMap<>();
                root.put("DataVersion", 5023);
                root.put("Position", new int[] {32 + rx, rz});
                LinkedHashMap<String, Object> ent = new LinkedHashMap<>();
                ent.put("id", "minecraft:villager");
                ent.put("CustomName", "\"entity-" + rx + "-" + rz + "\"");
                root.put("Entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(ent)));
                storage.writeChunkCompound(32 + rx, rz, root);
            }
            storage.flush();
        }

        // 2. Inject both overlap variants with valid SEC-DED bytes:
        //    - idx=900 (4,28) aliases idx=707 (3,22), while its original intact slab still exists on disk -> must recover
        //    - idx=901 (5,28) aliases idx=707 (3,22) with no backing slab on disk -> must be cleared on open
        byte[] raw = Files.readAllBytes(lcaFile);
        int idxOwner = (3 & 31) | ((22 & 31) << 5); // 707
        int idxRecoverableAlias = (4 & 31) | ((28 & 31) << 5); // 900
        int idxDanglingAlias = (5 & 31) | ((28 & 31) << 5); // 901
        long ownerPtr = LcaRegionReader.getVolatileLongBE(
                raw, LcaRegionReader.POINTER_DIRECTORY_OFFSET + (idxOwner << 3));
        assertTrue(ownerPtr != 0L, "Owner pointer 707 must be present");
        int secDedTableOffset = LcaRegionReader.SUPERBLOCK_SIZE + LcaRegionReader.POINTER_DIRECTORY_SIZE;
        byte ownerSyn = (byte) LcaSectionCodec.computeHammingSecDed8(ownerPtr);
        LcaRegionReader.setVolatileLongBE(
                raw, LcaRegionReader.POINTER_DIRECTORY_OFFSET + (idxRecoverableAlias << 3), ownerPtr);
        raw[secDedTableOffset + idxRecoverableAlias] = ownerSyn;
        LcaRegionReader.setVolatileLongBE(
                raw, LcaRegionReader.POINTER_DIRECTORY_OFFSET + (idxDanglingAlias << 3), ownerPtr);
        raw[secDedTableOffset + idxDanglingAlias] = ownerSyn;
        Files.write(lcaFile, raw);
        LcaRegionReader.invalidateFileCache(lcaFile);

        // 3. Reopen via LcaLiveArchive: overlapping aliases must be detected and healed/scrubbed on open
        try (LcaRegionWriter.LcaLiveArchive live = LcaRegionWriter.openLiveArchive(lcaFile)) {
            assertTrue(live.hasChunk(3, 22), "True slab owner (idx=707) must be preserved on open");
            assertTrue(live.hasChunk(4, 28), "Recoverable overlapping alias (idx=900) must be recovered to its own slab");
            assertNotNull(live.readChunkNbtBytes(4, 28), "Recovered chunk (4,28) must decode cleanly without CBIT T3b");
            assertFalse(live.hasChunk(5, 28), "Dangling overlapping alias (idx=901) with no backing slab must be cleared on open");

            // 4. Exercise returnFreeSlabs double-free guard directly on a live slab and an already-free slab
            java.lang.reflect.Method returnFree = LcaRegionWriter.LcaLiveArchive.class
                    .getDeclaredMethod("returnFreeSlabs", int.class, int.class);
            returnFree.setAccessible(true);
            int ownerSlabStart = (int) (ownerPtr >>> 29);
            int ownerSlabs = (((int) ((ownerPtr >>> 8) & 0xFFFFFL)) + 255) >>> LcaRegionReader.SLAB_SHIFT;
            // Attempt to double-free the live owner's slab — must be rejected
            returnFree.invoke(live, ownerSlabStart, ownerSlabs);

            // 5. Overwrite owner + write 40 more chunks to rotate the CoW grace ring and force slab reuse
            for (int k = 0; k < 40; k++) {
                int rx = (k == 0) ? 3 : (k & 31);
                int rz = (k == 0) ? 22 : (1 + (k >>> 5));
                LinkedHashMap<String, Object> root = new LinkedHashMap<>();
                root.put("DataVersion", 5023);
                root.put("Position", new int[] {32 + rx, rz});
                LinkedHashMap<String, Object> ent = new LinkedHashMap<>();
                ent.put("id", "minecraft:sheep");
                ent.put("CustomName", "\"after-scrub-" + k + "\"");
                root.put("Entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(ent)));
                live.saveChunkCoW(rx, rz, root);
            }
            live.flush();
        }

        // 6. Verify every live chunk in the file decodes cleanly via read-only LcaRegionReader (zero CBIT T3b)
        LcaRegionReader.invalidateFileCache(lcaFile);
        try (LcaRegionFileStorage verify = new LcaRegionFileStorage(entitiesDir, true)) {
            try (java.io.DataInputStream dis = verify.getChunkDataInputStream(32 + 3, 22)) {
                assertNotNull(dis, "Owner chunk (35,22) must remain readable");
                assertTrue(dis.readAllBytes().length > 0);
            }
            for (int k = 1; k < 40; k++) {
                int rx = k & 31;
                int rz = 1 + (k >>> 5);
                try (java.io.DataInputStream dis = verify.getChunkDataInputStream(32 + rx, rz)) {
                    assertNotNull(dis, "Chunk (" + (32 + rx) + "," + rz + ") missing");
                    assertTrue(dis.readAllBytes().length > 0);
                }
            }
        }
    }

    @Test
    @DisplayName("Concurrent lazy ENTITY_DATA stream reads survive writes, eviction, and compaction without CBIT T3b")
    void concurrentLazyEntityStreamReadsSurviveWritesEvictionAndCompaction(@TempDir Path tempDir) throws Exception {
        Path entitiesDir = tempDir.resolve("entities_concurrent_lazy_compact");
        Files.createDirectories(entitiesDir);

        // Seed r.1.-2.lca with 128 chunks including (35,-62) [rx=3, rz=2, idx=67] and (39,-60) [rx=7, rz=4, idx=135]
        try (LcaRegionFileStorage seed = new LcaRegionFileStorage(entitiesDir, true)) {
            for (int i = 0; i < 128; i++) {
                int cx = 32 + (i & 15);
                int cz = -64 + (i >>> 4);
                LinkedHashMap<String, Object> root = new LinkedHashMap<>();
                root.put("DataVersion", 5023);
                root.put("Position", new int[] {cx, cz});
                LinkedHashMap<String, Object> ent = new LinkedHashMap<>();
                ent.put("id", "minecraft:villager");
                ent.put("CustomName", "\"seed-" + i + "-" + "x".repeat(256 + ((i * 73) & 1023)) + "\"");
                root.put("Entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(ent)));
                seed.writeChunkCompound(cx, cz, root);
            }
            seed.flush();
        }

        Path lcaFile = entitiesDir.resolve("r.1.-2.lca");

        // Set maxOpenRegions=1 and compactFragmentationThreshold=0.01 to trigger frequent eviction + compaction
        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(
                entitiesDir,
                LcaRegionReader.STORAGE_KIND_ENTITIES,
                -4, 24, 5023, 1,
                false, false, 1, 0.01)) {

            java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
            java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicInteger completedReads = new java.util.concurrent.atomic.AtomicInteger(0);

            // Reader threads open LazyChunkNbtInputStream (and periodically invalidate header cache to exercise cachedHeader=false)
            Thread[] readers = new Thread[6];
            for (int r = 0; r < readers.length; r++) {
                final int tid = r;
                readers[r] = new Thread(() -> {
                    int iter = 0;
                    while (running.get() && failure.get() == null) {
                        int idx = (iter + tid * 7) & 127;
                        int cx = 32 + (idx & 15);
                        int cz = -64 + (idx >>> 4);
                        if ((iter & 3) == 0) {
                            LcaRegionReader.invalidateFileCache(lcaFile);
                        }
                        try (java.io.DataInputStream dis = storage.getChunkDataInputStream(cx, cz)) {
                            if (dis != null) {
                                byte[] bytes = dis.readAllBytes();
                                if (bytes.length == 0) {
                                    failure.compareAndSet(null, new AssertionError("Empty entity stream for (" + cx + "," + cz + ")"));
                                    return;
                                }
                                completedReads.incrementAndGet();
                            }
                        } catch (Throwable t) {
                            failure.compareAndSet(null, t);
                            return;
                        }
                        iter++;
                    }
                }, "entity-lazy-reader-" + r);
                readers[r].start();
            }

            // Writer thread rotates slabs rapidly across r.1.-2.lca and triggers eviction + compaction via r.2.-2.lca
            Thread writer = new Thread(() -> {
                try {
                    for (int round = 0; round < 40 && failure.get() == null; round++) {
                        for (int k = 0; k < 16; k++) {
                            int idx = (round * 11 + k) & 127;
                            int cx = 32 + (idx & 15);
                            int cz = -64 + (idx >>> 4);
                            LinkedHashMap<String, Object> root = new LinkedHashMap<>();
                            root.put("DataVersion", 5023);
                            root.put("Position", new int[] {cx, cz});
                            LinkedHashMap<String, Object> ent = new LinkedHashMap<>();
                            ent.put("id", "minecraft:villager");
                            ent.put("CustomName", "\"round-" + round + "-" + k + "-" + "y".repeat(64 + ((round * 97 + k * 131) & 2047)) + "\"");
                            root.put("Entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(ent)));
                            storage.writeChunkCompound(cx, cz, root);
                        }
                        // Write to a second region (r.2.-2.lca) to evict r.1.-2.lca and trigger compactIfFragmented on close
                        // while concurrent readers see regionCache.get == null and read via FileChannel!
                        LinkedHashMap<String, Object> other = new LinkedHashMap<>();
                        other.put("DataVersion", 5023);
                        other.put("Position", new int[] {64, -64});
                        other.put("Entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of()));
                        storage.writeChunkCompound(64, -64, other);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }, "entity-writer-compactor");
            writer.start();

            try {
                writer.join(20_000);
                assertFalse(writer.isAlive(), "Writer thread timed out");
            } finally {
                running.set(false);
                for (Thread reader : readers) {
                    reader.join(5_000);
                }
            }

            if (failure.get() != null) {
                throw new AssertionError("Concurrent lazy entity stream read failed", failure.get());
            }
            assertTrue(completedReads.get() > 0, "Expected at least one completed lazy entity read");
        }
    }

    @Test
    @DisplayName("Genuine on-disk slab self-tag corruption still fails closed with CorruptRegionEntryException")
    void genuineOnDiskSlabTagCorruptionStillFailsClosedOnLazyStreamRead(@TempDir Path tempDir) throws Exception {
        Path entitiesDir = tempDir.resolve("entities_genuine_corruption");
        Files.createDirectories(entitiesDir);
        Path lcaFile = entitiesDir.resolve("r.1.-2.lca");

        try (LcaRegionFileStorage seed = new LcaRegionFileStorage(entitiesDir, true)) {
            for (int[] coord : new int[][] {{35, -62}, {39, -60}}) {
                LinkedHashMap<String, Object> root = new LinkedHashMap<>();
                root.put("DataVersion", 5023);
                root.put("Position", coord);
                LinkedHashMap<String, Object> ent = new LinkedHashMap<>();
                ent.put("id", "minecraft:villager");
                ent.put("CustomName", "\"chunk-" + coord[0] + "-" + coord[1] + "\"");
                root.put("Entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(ent)));
                seed.writeChunkCompound(coord[0], coord[1], root);
            }
            seed.flush();
        }

        // Point idx=67 (35,-62) to idx=135 (39,-60)'s slab on disk with valid SEC-DED
        byte[] raw = Files.readAllBytes(lcaFile);
        int idx67 = (3 & 31) | ((2 & 31) << 5);
        int idx135 = (7 & 31) | ((4 & 31) << 5);
        long ptr135 = LcaRegionReader.getVolatileLongBE(
                raw, LcaRegionReader.POINTER_DIRECTORY_OFFSET + (idx135 << 3));
        assertTrue(ptr135 != 0L);
        int secDedTableOffset = LcaRegionReader.SUPERBLOCK_SIZE + LcaRegionReader.POINTER_DIRECTORY_SIZE;
        LcaRegionReader.setVolatileLongBE(
                raw, LcaRegionReader.POINTER_DIRECTORY_OFFSET + (idx67 << 3), ptr135);
        raw[secDedTableOffset + idx67] = (byte) LcaSectionCodec.computeHammingSecDed8(ptr135);
        Files.write(lcaFile, raw);
        LcaRegionReader.invalidateFileCache(lcaFile);

        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(entitiesDir, true)) {
            org.junit.jupiter.api.Assertions.assertThrows(CorruptRegionEntryException.class, () -> {
                try (java.io.DataInputStream dis = storage.getChunkDataInputStream(35, -62)) {
                    assertNotNull(dis);
                    dis.readAllBytes();
                }
            });
        }
    }

    @Test
    @DisplayName("Removing chunks before first allocation does not leak grace-ring slabs into free pool")
    void removeChunkBeforeFirstAllocationDoesNotLeakGraceRingSlabsIntoFreePool(@TempDir Path tempDir) throws Exception {
        Path entitiesDir = tempDir.resolve("entities_remove_before_alloc");
        Files.createDirectories(entitiesDir);
        Path lcaFile = entitiesDir.resolve("r.0.0.lca");

        // Seed 8 multi-slab entity chunks so removed slabs have 3-slab holes
        try (LcaRegionFileStorage seed = new LcaRegionFileStorage(entitiesDir, true)) {
            for (int i = 0; i < 8; i++) {
                LinkedHashMap<String, Object> root = new LinkedHashMap<>();
                root.put("DataVersion", 5023);
                root.put("Position", new int[] {i, 0});
                LinkedHashMap<String, Object> ent = new LinkedHashMap<>();
                ent.put("id", "minecraft:villager");
                ent.put("CustomName", "\"seed-" + i + "-" + "a".repeat(600) + "\"");
                root.put("Entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(ent)));
                seed.writeChunkCompound(i, 0, root);
            }
            seed.flush();
        }

        List<String> refusedWarnings = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.logging.Logger writerLogger = java.util.logging.Logger.getLogger(LcaRegionWriter.class.getName());
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                String msg = record.getMessage();
                if (msg != null && (msg.contains("Refused to free slab range")
                        || msg.contains("Refused duplicate free"))) {
                    refusedWarnings.add(msg);
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        writerLogger.addHandler(capture);
        try {
            // Reopen freshly (freeSlabPoolInitialized == false), remove chunks BEFORE any allocation,
            // then save 6 chunks to trigger lazy free-pool init + 4-epoch grace-ring eviction.
            try (LcaRegionWriter.LcaLiveArchive live = LcaRegionWriter.openLiveArchive(lcaFile)) {
                assertTrue(live.removeChunk(0, 0));
                assertTrue(live.removeChunk(1, 0));

                for (int k = 2; k < 8; k++) {
                    LinkedHashMap<String, Object> root = new LinkedHashMap<>();
                    root.put("DataVersion", 5023);
                    root.put("Position", new int[] {k, 0});
                    LinkedHashMap<String, Object> ent = new LinkedHashMap<>();
                    ent.put("id", "minecraft:villager");
                    ent.put("CustomName", "\"updated-" + k + "-" + "b".repeat((k % 2 == 0) ? 350 : 600) + "\"");
                    root.put("Entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(ent)));
                    live.saveChunkCoW(k, 0, root);
                }
                live.flush();
            }
        } finally {
            writerLogger.removeHandler(capture);
        }

        assertTrue(
                refusedWarnings.isEmpty(),
                "Expected zero Refused-to-free warnings after removeChunk before first allocation, got: "
                        + refusedWarnings);

        // Verify all remaining live chunks decode cleanly
        LcaRegionReader.invalidateFileCache(lcaFile);
        try (LcaRegionFileStorage verify = new LcaRegionFileStorage(entitiesDir, true)) {
            for (int k = 2; k < 8; k++) {
                try (java.io.DataInputStream dis = verify.getChunkDataInputStream(k, 0)) {
                    assertNotNull(dis, "Chunk (" + k + ",0) missing after remove+write sequence");
                    assertTrue(dis.readAllBytes().length > 0);
                }
            }
        }
    }

    @Test
    @DisplayName("Buffered write-back coalesces overwrites, serves read-your-own-writes, and batches disk syscalls")
    void bufferedWriteBackCoalescesOverwritesAndBatchesDiskWrites(@TempDir Path tempDir) throws Exception {
        Path regionDir = tempDir.resolve("region_buffered_writeback");
        Files.createDirectories(regionDir);

        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(regionDir, true)) {
            assertTrue(storage.isBufferedIoEnabled(), "Buffered I/O should be enabled by default in LcaRegionFileStorage");
            LcaRegionWriter.LcaLiveArchive archive = storage.getOrOpenRegion(0, 0, true);
            assertTrue(archive.isBufferedWriteBackEnabled());
            long writesBefore = archive.diskChannelWriteCount();

            // Overwrite the same chunk 10 times before flush: must coalesce into 1 staged entry with 0 disk writes
            for (int rev = 0; rev < 10; rev++) {
                String block = (rev == 9) ? "minecraft:diamond_block" : "minecraft:stone";
                archive.saveChunkCoW(3, 4, buildChunkNbt(3, 4, block));
            }
            assertEquals(1, archive.pendingWriteCount(), "Repeated saves to the same chunk must coalesce");
            assertEquals(9L, archive.coalescedWriteCount(), "9 repeated saves must be counted as coalesced");
            assertEquals(writesBefore, archive.diskChannelWriteCount(),
                    "Staged write-back must not issue positional disk writes before threshold or flush");

            // Read-your-own-writes consistency BEFORE flush()
            assertTrue(archive.hasChunk(3, 4));
            byte[] nbtBeforeFlush = archive.readChunkNbtBytes(3, 4);
            assertNotNull(nbtBeforeFlush);
            assertNotNull(archive.readChunkEntry(3, 4));
            assertNotNull(archive.readRawChunkFrame(3, 4, null));

            // Stage 15 more distinct chunks, remove one before flush, then flush once
            for (int rx = 0; rx < 15; rx++) {
                archive.saveChunkCoW(rx, 0, buildChunkNbt(rx, 0, "minecraft:oak_planks"));
            }
            assertEquals(16, archive.pendingWriteCount());
            assertTrue(archive.removeChunk(14, 0));
            assertFalse(archive.hasChunk(14, 0), "Removed staged chunk must not appear present");
            assertEquals(15, archive.pendingWriteCount());

            archive.flush();
            assertEquals(0, archive.pendingWriteCount());
            long writesAfterFlush = archive.diskChannelWriteCount() - writesBefore;
            assertTrue(writesAfterFlush <= 6L,
                    "Flushing 15 batched chunks must coalesce into <= 6 channel writes (got " + writesAfterFlush + ")");
        }

        // Reopen cleanly from disk and verify adaptive sequential read-ahead promotes on cold reads
        Path lcaFile = regionDir.resolve("r.0.0.lca");
        LcaRegionReader.invalidateFileCache(lcaFile);
        try (LcaRegionFileStorage readerStorage = new LcaRegionFileStorage(regionDir, true)) {
            for (int rx = 0; rx < 14; rx++) {
                byte[] nbt = readerStorage.readChunkNbtBytes(rx, 0);
                assertNotNull(nbt, "Chunk (" + rx + ",0) must persist across buffered flush & reopen");
            }
            assertTrue(readerStorage.readOnlySequentialReadAheadCount() >= 1L,
                    "Read-only channel path must promote to sequential read-ahead after trigger misses");
            assertNotNull(readerStorage.readChunkNbtBytes(3, 4));
            assertFalse(readerStorage.doesChunkExist(14, 0), "Removed staged chunk must remain absent on disk");
        }
    }

    @Test
    @DisplayName("Adaptive sequential read-ahead triggers on LcaLiveArchive and invalidates cleanly on write")
    void adaptiveSequentialReadAheadOnLiveArchiveAndOptOut(@TempDir Path tempDir) throws Exception {
        Path regionDir = tempDir.resolve("region_readahead");
        Files.createDirectories(regionDir);

        try (LcaRegionFileStorage seed = new LcaRegionFileStorage(regionDir, true)) {
            for (int rx = 0; rx < 12; rx++) {
                seed.writeChunkCompound(rx, 0, buildChunkNbt(rx, 0, "minecraft:deepslate"));
            }
            seed.flush();
        }

        // Verify live-archive sequential read-ahead triggers after default 4 cold reads
        LcaRegionReader.invalidateFileCache(regionDir.resolve("r.0.0.lca"));
        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(regionDir, true)) {
            LcaRegionWriter.LcaLiveArchive live = storage.getOrOpenRegion(0, 0, false);
            assertNotNull(live);
            assertTrue(live.isSequentialReadAheadEnabled());
            for (int rx = 0; rx < 12; rx++) {
                assertNotNull(live.readChunkNbtBytes(rx, 0));
            }
            assertTrue(live.sequentialReadAheadCount() >= 1L,
                    "Live archive must trigger sequential slab-pool read-ahead after cold reads");

            // Opt-out disables both buffered write-back and read-ahead immediately
            storage.setBufferedIoEnabled(false);
            assertFalse(storage.isBufferedIoEnabled());
            assertFalse(live.isBufferedWriteBackEnabled());
            assertFalse(live.isSequentialReadAheadEnabled());
        }
    }

    @Test
    @DisplayName("readRawChunkFrameWithMetadata returns frame + lightweight metadata NBT omitting block_states")
    void readRawChunkFrameWithMetadataOmitsBlockStatesAndPreservesMetadata(@TempDir Path tempDir) throws Exception {
        Path regionDir = tempDir.resolve("region_meta_only");
        Files.createDirectories(regionDir);

        try (LcaRegionFileStorage writer = new LcaRegionFileStorage(regionDir, true)) {
            byte[] chunkNbt = Nbt.writeNamedRoot("", buildChunkNbt(2, 3, "minecraft:stone"));
            writer.writeChunkNbtBytes(2, 3, chunkNbt);
            writer.flush();
        }

        Map<String, Integer> runtimeLookup = Map.of(
                "minecraft:air", 0,
                "minecraft:stone", 1
        );
        LcaRegionReader.invalidateFileCache(regionDir.resolve("r.0.0.lca"));
        try (LcaRegionFileStorage reader = new LcaRegionFileStorage(regionDir, true)) {
            LcaRegionReader.RawChunkFrameWithMetadata paired =
                    reader.readRawChunkFrameWithMetadata(2, 3, s -> runtimeLookup.getOrDefault(s, -1));
            assertNotNull(paired);
            assertNotNull(paired.frame());
            assertNotNull(paired.metadataNbtBytes());

            byte[] fullBytes = reader.readChunkNbtBytes(2, 3);
            @SuppressWarnings("unchecked")
            Map<String, Object> fullRoot = (Map<String, Object>) Nbt.readNamedRoot(fullBytes).value;
            @SuppressWarnings("unchecked")
            Map<String, Object> metaRoot = (Map<String, Object>) Nbt.readNamedRoot(paired.metadataNbtBytes()).value;
            assertEquals(fullRoot.get("DataVersion"), metaRoot.get("DataVersion"));
            assertEquals(fullRoot.get("Status"), metaRoot.get("Status"));
            assertEquals(fullRoot.get("xPos"), metaRoot.get("xPos"));
            assertEquals(fullRoot.get("zPos"), metaRoot.get("zPos"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> metaSections =
                    (List<Map<String, Object>>) (List<?>) ((Nbt.NbtList) metaRoot.get("sections")).items;
            assertFalse(metaSections.isEmpty());
            for (Map<String, Object> sec : metaSections) {
                assertNotNull(sec.get("Y"));
                assertFalse(sec.containsKey("block_states"),
                        "Metadata-only NBT must omit block_states to skip voxel NBT decode");
            }
            assertTrue(paired.metadataNbtBytes().length < fullBytes.length,
                    "Metadata-only NBT should be strictly smaller than full voxel NBT");
        }
    }

    @Test
    @DisplayName("readRawChunkFrameWithMetadata promotes to sequential read-ahead and skips block payloads losslessly")
    void readRawChunkFrameWithMetadataUsesReadAheadAndSkipsBlockPayloadLosslessly(@TempDir Path tempDir) throws Exception {
        Path regionDir = tempDir.resolve("region_meta_readahead");
        Files.createDirectories(regionDir);

        try (LcaRegionFileStorage writer = new LcaRegionFileStorage(regionDir, true)) {
            for (int rx = 0; rx < 10; rx++) {
                byte[] chunkNbt = Nbt.writeNamedRoot("", buildChunkNbt(rx, 1, "minecraft:stone"));
                writer.writeChunkNbtBytes(rx, 1, chunkNbt);
            }
            writer.flush();
        }

        Map<String, Integer> runtimeLookup = Map.of(
                "minecraft:air", 0,
                "minecraft:stone", 1,
                "minecraft:dirt", 2,
                "minecraft:grass_block", 3
        );
        LcaRegionReader.invalidateFileCache(regionDir.resolve("r.0.0.lca"));
        try (LcaRegionFileStorage reader = new LcaRegionFileStorage(regionDir, true)) {
            for (int rx = 0; rx < 10; rx++) {
                LcaRegionReader.RawChunkFrameWithMetadata paired =
                        reader.readRawChunkFrameWithMetadata(rx, 1, s -> runtimeLookup.getOrDefault(s, 99));
                assertNotNull(paired, "Paired metadata read must succeed for (" + rx + ",1)");
                assertNotNull(paired.frame());
                assertNotNull(paired.metadataNbtBytes());

                byte[] fullBytes = reader.readChunkNbtBytes(rx, 1);
                @SuppressWarnings("unchecked")
                Map<String, Object> fullRoot = (Map<String, Object>) Nbt.readNamedRoot(fullBytes).value;
                @SuppressWarnings("unchecked")
                Map<String, Object> metaRoot = (Map<String, Object>) Nbt.readNamedRoot(paired.metadataNbtBytes()).value;
                assertEquals(fullRoot.get("DataVersion"), metaRoot.get("DataVersion"));
                assertEquals(fullRoot.get("Status"), metaRoot.get("Status"));

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> fullSections =
                        (List<Map<String, Object>>) (List<?>) ((Nbt.NbtList) fullRoot.get("sections")).items;
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> metaSections =
                        (List<Map<String, Object>>) (List<?>) ((Nbt.NbtList) metaRoot.get("sections")).items;
                assertEquals(fullSections.size(), metaSections.size());
                for (int i = 0; i < fullSections.size(); i++) {
                    assertEquals(fullSections.get(i).get("Y"), metaSections.get(i).get("Y"));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> fullBiomes = (Map<String, Object>) fullSections.get(i).get("biomes");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> metaBiomes = (Map<String, Object>) metaSections.get(i).get("biomes");
                    assertNotNull(fullBiomes);
                    assertNotNull(metaBiomes);
                    assertEquals(
                            ((Nbt.NbtList) fullBiomes.get("palette")).items,
                            ((Nbt.NbtList) metaBiomes.get("palette")).items,
                            "Biome palette after skipped block payload must match full decode exactly");
                    org.junit.jupiter.api.Assertions.assertArrayEquals(
                            (long[]) fullBiomes.get("data"),
                            (long[]) metaBiomes.get("data"),
                            "Biome data array after skipped block payload must match full decode exactly");
                    assertFalse(metaSections.get(i).containsKey("block_states"));
                }
            }
            assertTrue(reader.readOnlySequentialReadAheadCount() >= 1L,
                    "readRawChunkFrameWithMetadata must promote sequential read-ahead on bulk warm reads");
        }
    }

    @Test
    @DisplayName("Starlight uniform zero light (state=1) round-trips without emitting 2048-byte zero arrays")
    void starlightUniformZeroLightOmits2048ByteArrayOnDecode(@TempDir Path tempDir) throws Exception {
        Path regionDir = tempDir.resolve("region_starlight_zero_light");
        Files.createDirectories(regionDir);

        LinkedHashMap<String, Object> root = build24SectionNonUniformChunkNbt(4, 5);
        root.put("starlight.light_version", 10);
        root.put("isLightOn", (byte) 0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections =
                (List<Map<String, Object>>) (List<?>) ((Nbt.NbtList) root.get("sections")).items;
        // Section 0 (underground dark): Starlight state=1 (all-zero, no byte array) for both sky & block light
        Map<String, Object> darkSec = sections.get(0);
        darkSec.remove("SkyLight");
        darkSec.remove("BlockLight");
        darkSec.put("starlight.skylight_state", 1);
        darkSec.put("starlight.blocklight_state", 1);

        // Section 1 (full sunlight, zero block light): SkyLight all-0xFF (state=2), BlockLight state=1 (no array)
        Map<String, Object> litSec = sections.get(1);
        byte[] fullSky = new byte[2048];
        java.util.Arrays.fill(fullSky, (byte) 0xFF);
        litSec.put("SkyLight", fullSky);
        litSec.put("starlight.skylight_state", 2);
        litSec.remove("BlockLight");
        litSec.put("starlight.blocklight_state", 1);

        try (LcaRegionFileStorage writer = new LcaRegionFileStorage(regionDir, true)) {
            writer.writeChunkNbtBytes(4, 5, Nbt.writeNamedRoot("", root));
            writer.flush();
        }

        LcaRegionReader.invalidateFileCache(regionDir.resolve("r.0.0.lca"));
        try (LcaRegionFileStorage reader = new LcaRegionFileStorage(regionDir, true)) {
            LcaRegionReader.RawChunkFrameWithMetadata paired =
                    reader.readRawChunkFrameWithMetadata(4, 5, s -> 0);
            assertNotNull(paired);
            byte[] decodedBytes = paired.metadataNbtBytes();
            assertNotNull(decodedBytes);
            @SuppressWarnings("unchecked")
            Map<String, Object> decodedRoot = (Map<String, Object>) Nbt.readNamedRoot(decodedBytes).value;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> decSections =
                    (List<Map<String, Object>>) (List<?>) ((Nbt.NbtList) decodedRoot.get("sections")).items;

            Map<String, Object> decDark = decSections.stream()
                    .filter(s -> ((Number) s.get("Y")).intValue() == -4)
                    .findFirst().orElseThrow();
            assertFalse(decDark.containsKey("SkyLight"),
                    "Starlight all-zero SkyLight must omit 2048-byte array on metadata-only load");
            assertFalse(decDark.containsKey("BlockLight"),
                    "Starlight all-zero BlockLight must omit 2048-byte array on metadata-only load");
            assertEquals(1, ((Number) decDark.get("starlight.skylight_state")).intValue());
            assertEquals(1, ((Number) decDark.get("starlight.blocklight_state")).intValue());

            Map<String, Object> decLit = decSections.stream()
                    .filter(s -> ((Number) s.get("Y")).intValue() == -3)
                    .findFirst().orElseThrow();
            assertNotNull(decLit.get("SkyLight"), "Full 0xFF SkyLight must still be emitted");
            assertEquals(2, ((Number) decLit.get("starlight.skylight_state")).intValue());
            assertFalse(decLit.containsKey("BlockLight"), "All-zero BlockLight in lit section must omit 2048-byte array");
            assertEquals(1, ((Number) decLit.get("starlight.blocklight_state")).intValue());
        }
    }

    @Test
    @DisplayName("Block-only SectionFrame slice strips trailing biomes and light while preserving 4096-voxel parity and fail-closed checks")
    void blockOnlySectionFrameStripsBiomesAndLightWithExactParityAndFailClosed(@TempDir Path tempDir) throws Exception {
        Path regionDir = tempDir.resolve("region_block_only_slice");
        Files.createDirectories(regionDir);

        LinkedHashMap<String, Object> root = build24SectionNonUniformChunkNbt(6, 7);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections =
                (List<Map<String, Object>>) (List<?>) ((Nbt.NbtList) root.get("sections")).items;
        // Attach non-uniform SkyLight + BlockLight to section 0 so trailing light bytes are substantial
        byte[] gradSky = new byte[2048];
        byte[] gradBlk = new byte[2048];
        for (int i = 0; i < 2048; i++) {
            gradSky[i] = (byte) ((i * 31) & 0xFF);
            gradBlk[i] = (byte) ((i * 17 + 5) & 0xFF);
        }
        sections.get(0).put("SkyLight", gradSky);
        sections.get(0).put("BlockLight", gradBlk);

        try (LcaRegionFileStorage writer = new LcaRegionFileStorage(regionDir, true)) {
            writer.writeChunkNbtBytes(6, 7, Nbt.writeNamedRoot("", root));
            writer.flush();
        }

        Map<String, Integer> runtimeLookup = Map.of(
                "minecraft:air", 0,
                "minecraft:stone", 1,
                "minecraft:dirt", 2,
                "minecraft:grass_block", 3
        );
        LcaRegionReader.invalidateFileCache(regionDir.resolve("r.0.0.lca"));
        try (LcaRegionFileStorage reader = new LcaRegionFileStorage(regionDir, true)) {
            LcaRegionReader.RawChunkFrameWithMetadata paired =
                    reader.readRawChunkFrameWithMetadata(6, 7, s -> runtimeLookup.getOrDefault(s, 99));
            assertNotNull(paired);
            RawChunkFrameSnapshot frame = paired.frame();
            byte[] fullSlice = frame.sliceSectionFrameWithRuntimePalette(0, s -> runtimeLookup.getOrDefault(s, 99));
            byte[] blockOnly = frame.sliceBlockOnlySectionFrameWithRuntimePalette(0, s -> runtimeLookup.getOrDefault(s, 99));
            assertNotNull(blockOnly);
            assertTrue(blockOnly.length > 0 && blockOnly.length < fullSlice.length,
                    "Block-only slice (" + blockOnly.length + " B) must be strictly smaller than full slice (" + fullSlice.length + " B)");
            assertEquals(0, blockOnly[0] & 0x0F, "Block-only header must clear skyMode and blkMode to LIGHT_MODE_ABSENT");

            LcaSectionBacking fullBacking = LcaSectionBacking.fromColdSectionFrameWithBaseAndIndex(
                    fullSlice, 0, id -> id != 0, id -> false, id -> false);
            LcaSectionBacking blockOnlyBacking = LcaSectionBacking.fromColdSectionFrameWithBaseAndIndex(
                    blockOnly, 0, id -> id != 0, id -> false, id -> false);
            assertTrue(blockOnlyBacking.evictCompactBaseIndexToColdBaseFrame());
            assertFalse(blockOnlyBacking.hasHydratedCompactBaseIndex());
            assertTrue(blockOnlyBacking.hydrateCompactBaseIndexIfNeeded());

            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        assertEquals(fullBacking.getStateId(x, y, z), blockOnlyBacking.getStateId(x, y, z),
                                "Mismatch at (" + x + "," + y + "," + z + ")");
                    }
                }
            }

            // Fail-closed: truncated block-only frame must fail cleanly
            byte[] truncated = java.util.Arrays.copyOf(blockOnly, Math.max(1, blockOnly.length - 3));
            assertEquals(0, LcaSectionCodec.decodeSectionFrameBlockOrdinals(
                    truncated, truncated.length, new int[4096], new byte[1]).length);

            // Fail-closed: unmapped (-1) runtime ID must return null
            int[] badMap = new int[] { -1, -1, -1, -1 };
            org.junit.jupiter.api.Assertions.assertNull(
                    RawChunkFrameSnapshot.rewriteBlockOnlySectionFramePaletteHeaderBytes(fullSlice, badMap));
        }
    }

    @Test
    @DisplayName("Direct zero-NBT chunk metadata decode matches full NBT across biomes (1..20), heightmaps, light, and residual tags")
    void readRawChunkFrameWithDirectMetaMatchesFullNbtParity(@TempDir Path tempDir) throws Exception {
        Path regionDir = tempDir.resolve("region_direct_meta");
        Files.createDirectories(regionDir);

        LinkedHashMap<String, Object> root = build24SectionNonUniformChunkNbt(8, 9);
        root.put("isLightOn", (byte) 1);
        root.put("starlight.light_version", 9);
        root.put("LastUpdate", 12345L);
        root.put("InhabitedTime", 6789L);

        // Add heightmaps
        LinkedHashMap<String, Object> hm = new LinkedHashMap<>();
        long[] hmData = new long[37];
        for (int i = 0; i < hmData.length; i++) {
            hmData[i] = 0x0102030405060708L ^ (i * 1315423911L);
        }
        hm.put("MOTION_BLOCKING", hmData);
        hm.put("MOTION_BLOCKING_NO_LEAVES", hmData.clone());
        hm.put("WORLD_SURFACE", hmData.clone());
        root.put("Heightmaps", hm);

        // Configure diverse biome palette sizes (1, 2, 4, 9, 20) and light modes across sections
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections =
                (List<Map<String, Object>>) (List<?>) ((Nbt.NbtList) root.get("sections")).items;

        int[] biomeCounts = { 1, 2, 4, 9, 20 };
        for (int sIdx = 0; sIdx < biomeCounts.length; sIdx++) {
            int bCount = biomeCounts[sIdx];
            LinkedHashMap<String, Object> biomesTag = new LinkedHashMap<>();
            List<Object> pal = new ArrayList<>(bCount);
            for (int b = 0; b < bCount; b++) {
                pal.add("minecraft:test_biome_" + b);
            }
            biomesTag.put("palette", new Nbt.NbtList(Nbt.TAG_STRING, pal));
            if (bCount > 1) {
                int bits = BiomePaletteSection.biomeBitsPerEntry(bCount);
                int perLong = 64 / bits;
                int numLongs = (64 + perLong - 1) / perLong;
                long[] packed = new long[numLongs];
                for (int cell = 0; cell < 64; cell++) {
                    int val = cell % bCount;
                    packed[cell / perLong] |= ((long) val) << ((cell % perLong) * bits);
                }
                biomesTag.put("data", packed);
            }
            sections.get(sIdx).put("biomes", biomesTag);
        }

        // Section 0: dark underground (all-zero -> UNINIT_ZERO)
        sections.get(0).put("SkyLight", new byte[2048]);
        sections.get(0).put("starlight.skylight_state", 2);
        sections.get(0).put("BlockLight", new byte[2048]);
        sections.get(0).put("starlight.blocklight_state", 2);

        // Section 1: full 0xFF SkyLight (FULL_15), gradient BlockLight (ARRAY)
        byte[] fullSky = new byte[2048];
        java.util.Arrays.fill(fullSky, (byte) 0xFF);
        byte[] gradBlk = new byte[2048];
        for (int i = 0; i < 2048; i++) {
            gradBlk[i] = (byte) ((i * 23 + 7) & 0xFF);
        }
        sections.get(1).put("SkyLight", fullSky);
        sections.get(1).put("starlight.skylight_state", 2);
        sections.get(1).put("BlockLight", gradBlk);
        sections.get(1).put("starlight.blocklight_state", 2);

        try (LcaRegionFileStorage writer = new LcaRegionFileStorage(regionDir, true)) {
            writer.writeChunkNbtBytes(8, 9, Nbt.writeNamedRoot("", root));
            writer.flush();
        }

        LcaRegionReader.invalidateFileCache(regionDir.resolve("r.0.0.lca"));
        try (LcaRegionFileStorage reader = new LcaRegionFileStorage(regionDir, true)) {
            LcaRegionReader.RawChunkFrameWithDirectMeta paired =
                    reader.readRawChunkFrameWithDirectMeta(8, 9, s -> 0);
            assertNotNull(paired);
            assertNotNull(paired.frame());
            LcaDecodedChunkMeta direct = paired.directMeta();
            assertNotNull(direct, "Direct zero-NBT metadata must succeed for v3 voxel chunk");
            assertEquals(8, direct.chunkX());
            assertEquals(9, direct.chunkZ());
            assertEquals("minecraft:full", direct.statusName());
            assertEquals(12345L, direct.lastUpdateTick());
            assertEquals(6789L, direct.inhabitedTimeTicks());
            assertTrue(direct.hasIsLightOn());
            assertTrue(direct.hasStarlightVersion());
            assertEquals(9, direct.starlightLightVersion());
            assertTrue(direct.heightmapCodes().length >= 3, "Heightmaps must be decoded directly");

            // Compare each section's biomes and light against full NBT decode
            byte[] fullNbtBytes = reader.readChunkNbtBytes(8, 9);
            assertNotNull(fullNbtBytes);
            assertNotNull(Nbt.readNamedRoot(fullNbtBytes).value);

            for (int sIdx = 0; sIdx < biomeCounts.length; sIdx++) {
                int targetY = -4 + sIdx;
                LcaDecodedChunkMeta.DecodedSectionMeta decSec = java.util.Arrays.stream(direct.sections())
                        .filter(s -> s.sectionY() == targetY)
                        .findFirst().orElseThrow();
                assertNotNull(decSec.biomeGlobalDictIds());
                assertEquals(biomeCounts[sIdx], decSec.biomeGlobalDictIds().length,
                        "Biome palette count mismatch for section Y=" + targetY);
                for (int b = 0; b < biomeCounts[sIdx]; b++) {
                    assertEquals("minecraft:test_biome_" + b, direct.biomeKeys().get(decSec.biomeGlobalDictIds()[b]));
                }
                if (biomeCounts[sIdx] == 1) {
                    org.junit.jupiter.api.Assertions.assertNull(decSec.biomePackedLongs());
                } else {
                    assertNotNull(decSec.biomePackedLongs());
                }
            }

            LcaDecodedChunkMeta.DecodedSectionMeta sec0 = java.util.Arrays.stream(direct.sections())
                    .filter(s -> s.sectionY() == -4).findFirst().orElseThrow();
            assertEquals(LcaDecodedChunkMeta.LIGHT_UNINIT_ZERO, sec0.skyLightKind());
            assertEquals(1, sec0.skyLightStarlightState());
            org.junit.jupiter.api.Assertions.assertNull(sec0.skyLightBytes());

            LcaDecodedChunkMeta.DecodedSectionMeta sec1 = java.util.Arrays.stream(direct.sections())
                    .filter(s -> s.sectionY() == -3).findFirst().orElseThrow();
            assertEquals(LcaDecodedChunkMeta.LIGHT_FULL_15, sec1.skyLightKind());
            assertEquals(2, sec1.skyLightStarlightState());
            org.junit.jupiter.api.Assertions.assertNull(sec1.skyLightBytes());
            assertEquals(LcaDecodedChunkMeta.LIGHT_ARRAY, sec1.blockLightKind());
            org.junit.jupiter.api.Assertions.assertArrayEquals(gradBlk, sec1.blockLightBytes());
            assertEquals(2, sec1.blockLightStarlightState());
        }
    }

    @Test
    @DisplayName("DeferredChunkSlab decodes identically to synchronous direct-meta across Mode B, Mode A, and parallel worker threads")
    void readDeferredChunkSlabMatchesDirectMetaConcurrently(@TempDir Path tempDir) throws Exception {
        for (boolean modeB : new boolean[] { true, false }) {
            Path regionDir = tempDir.resolve(modeB ? "deferred_mode_b" : "deferred_mode_a");
            Files.createDirectories(regionDir);

            try (LcaRegionFileStorage writer = new LcaRegionFileStorage(regionDir, modeB)) {
                for (int i = 0; i < 16; i++) {
                    LinkedHashMap<String, Object> root = build24SectionNonUniformChunkNbt(i, 3);
                    root.put("isLightOn", (byte) 1);
                    root.put("starlight.light_version", 9);
                    writer.writeChunkNbtBytes(i, 3, Nbt.writeNamedRoot("", root));
                }
                writer.flush();
            }

            LcaRegionReader.invalidateFileCache(regionDir.resolve("r.0.0.lca"));
            try (LcaRegionFileStorage reader = new LcaRegionFileStorage(regionDir, true)) {
                List<LcaRegionReader.DeferredChunkSlab> slabs = new ArrayList<>(16);
                for (int i = 0; i < 16; i++) {
                    LcaRegionReader.DeferredChunkSlab slab = reader.readDeferredChunkSlab(i, 3, s -> 0);
                    assertNotNull(slab, "Deferred slab must exist for chunk (" + i + ",3)");
                    slabs.add(slab);
                }

                java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
                try {
                    List<java.util.concurrent.Future<LcaRegionReader.RawChunkFrameWithDirectMeta>> futures = new ArrayList<>(16);
                    for (LcaRegionReader.DeferredChunkSlab slab : slabs) {
                        futures.add(pool.submit(slab::decodeToDirectMeta));
                    }
                    for (int i = 0; i < 16; i++) {
                        LcaRegionReader.RawChunkFrameWithDirectMeta decoded = futures.get(i).get();
                        LcaRegionReader.RawChunkFrameWithDirectMeta sync =
                                reader.readRawChunkFrameWithDirectMeta(i, 3, s -> 0);
                        assertNotNull(decoded);
                        assertNotNull(sync);
                        org.junit.jupiter.api.Assertions.assertArrayEquals(
                                sync.frame().chunkFrame(), decoded.frame().chunkFrame());
                        assertNotNull(decoded.directMeta());
                        assertEquals(sync.directMeta().chunkX(), decoded.directMeta().chunkX());
                        assertEquals(sync.directMeta().chunkZ(), decoded.directMeta().chunkZ());
                        assertEquals(sync.directMeta().statusName(), decoded.directMeta().statusName());
                        assertEquals(sync.directMeta().sections().length, decoded.directMeta().sections().length);
                    }
                } finally {
                    pool.shutdownNow();
                }
            }
        }
    }

    @Test
    @DisplayName("DeferredChunkSlab reads staged buffered write-back payloads before flush in Mode B and Mode A")
    void readDeferredChunkSlabReadsUnflushedPendingPayloads(@TempDir Path tempDir) throws Exception {
        for (boolean modeB : new boolean[] { true, false }) {
            Path regionDir = tempDir.resolve(modeB ? "deferred_pending_mode_b" : "deferred_pending_mode_a");
            Files.createDirectories(regionDir);

            try (LcaRegionFileStorage storage = new LcaRegionFileStorage(regionDir, modeB)) {
                storage.configureBufferedIo(true, 64, 4 * 1024 * 1024, false, 4);

                for (int i = 0; i < 4; i++) {
                    LinkedHashMap<String, Object> root = build24SectionNonUniformChunkNbt(i, 5);
                    root.put("LastUpdate", 1000L + i);
                    root.put("isLightOn", (byte) 1);
                    root.put("starlight.light_version", 9);
                    storage.writeChunkNbtBytes(i, 5, Nbt.writeNamedRoot("", root));
                }

                // Read staged payloads BEFORE flush()
                for (int i = 0; i < 4; i++) {
                    LcaRegionReader.DeferredChunkSlab slab = storage.readDeferredChunkSlab(i, 5, s -> 0);
                    assertNotNull(slab, "Deferred slab must read staged pending payload for chunk (" + i + ",5)");
                    LcaRegionReader.RawChunkFrameWithDirectMeta decoded = slab.decodeToDirectMeta();
                    LcaRegionReader.RawChunkFrameWithDirectMeta sync =
                            storage.readRawChunkFrameWithDirectMeta(i, 5, s -> 0);
                    assertNotNull(decoded);
                    assertNotNull(sync);
                    org.junit.jupiter.api.Assertions.assertArrayEquals(
                            sync.frame().chunkFrame(), decoded.frame().chunkFrame());
                    assertNotNull(decoded.directMeta());
                    assertEquals(1000L + i, decoded.directMeta().lastUpdateTick());
                }

                // Flush to disk, then overwrite chunk (0,5) with a newer staged payload and verify RYOW
                storage.flush();
                LinkedHashMap<String, Object> updated = build24SectionNonUniformChunkNbt(0, 5);
                updated.put("LastUpdate", 9999L);
                updated.put("isLightOn", (byte) 1);
                updated.put("starlight.light_version", 9);
                storage.writeChunkNbtBytes(0, 5, Nbt.writeNamedRoot("", updated));

                LcaRegionReader.DeferredChunkSlab overwrittenSlab = storage.readDeferredChunkSlab(0, 5, s -> 0);
                assertNotNull(overwrittenSlab, "Deferred slab must return updated staged payload");
                LcaRegionReader.RawChunkFrameWithDirectMeta overwrittenDecoded = overwrittenSlab.decodeToDirectMeta();
                assertNotNull(overwrittenDecoded.directMeta());
                assertEquals(9999L, overwrittenDecoded.directMeta().lastUpdateTick());
            }
        }
    }

    @Test
    @DisplayName("Direct FlatBitPack mode writes mode-10 YZX sections and extracts zero-decode across 1..5-bit palettes")
    void directFlatBitPackModeRoundTripsLosslesslyAndExtractsZeroDecode(@TempDir Path tempDir) throws Exception {
        Path regionDir = tempDir.resolve("direct_flat_bitpack");
        Files.createDirectories(regionDir);

        int[] paletteSizes = {2, 4, 7, 12, 20};
        int[] expectedBits = {1, 2, 3, 4, 5};
        int[][] expectedVoxelsBySec = new int[paletteSizes.length][4096];

        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", 4325);
        root.put("xPos", 2);
        root.put("zPos", 5);
        root.put("yPos", -4);
        root.put("Status", "minecraft:full");
        root.put("isLightOn", (byte) 1);
        root.put("starlight.light_version", 9);

        List<Object> sections = new ArrayList<>();
        for (int s = 0; s < paletteSizes.length; s++) {
            int pSize = paletteSizes[s];
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(pSize - 1));
            int entriesPerLong = 64 / bits;
            int numLongs = (4096 + entriesPerLong - 1) / entriesPerLong;
            long[] packed = new long[numLongs];
            List<Object> palette = new ArrayList<>(pSize);
            for (int p = 0; p < pSize; p++) {
                LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
                entry.put("Name", "minecraft:test_flat_block_" + p);
                palette.add(entry);
            }
            for (int i = 0; i < 4096; i++) {
                // Make state 0 the majority, distribute remaining states across all 4096 positions
                int ord = (i % 5 == 0) ? (1 + ((i / 5) % (pSize - 1))) : 0;
                expectedVoxelsBySec[s][i] = ord;
                int w = i / entriesPerLong;
                int shift = (i % entriesPerLong) * bits;
                packed[w] |= ((long) ord) << shift;
            }
            LinkedHashMap<String, Object> blockStates = new LinkedHashMap<>();
            blockStates.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, palette));
            blockStates.put("data", packed);

            LinkedHashMap<String, Object> biomes = new LinkedHashMap<>();
            biomes.put("palette", new Nbt.NbtList(Nbt.TAG_STRING, List.of("minecraft:plains")));

            LinkedHashMap<String, Object> sec = new LinkedHashMap<>();
            sec.put("Y", (byte) (-4 + s));
            sec.put("block_states", blockStates);
            sec.put("biomes", biomes);
            sections.add(sec);
        }
        root.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, sections));

        try (LcaRegionFileStorage writer = new LcaRegionFileStorage(regionDir, false)) {
            writer.setDirectFlatBitPack(true);
            writer.writeChunkNbtBytes(2, 5, Nbt.writeNamedRoot("", root));
            writer.flush();
        }

        java.util.function.Function<String, Integer> lookup = name -> {
            if (name != null && name.startsWith("minecraft:test_flat_block_")) {
                return 100 + Integer.parseInt(name.substring("minecraft:test_flat_block_".length()));
            }
            return 0;
        };

        LcaRegionReader.invalidateFileCache(regionDir.resolve("r.0.0.lca"));
        try (LcaRegionFileStorage reader = new LcaRegionFileStorage(regionDir, false)) {
            LcaRegionReader.DeferredChunkSlab slab = reader.readDeferredChunkSlab(2, 5, lookup);
            assertNotNull(slab);
            LcaRegionReader.RawChunkFrameWithDirectMeta decoded = slab.decodeToDirectMeta();
            assertNotNull(decoded);
            RawChunkFrameSnapshot frame = decoded.frame();

            for (int s = 0; s < paletteSizes.length; s++) {
                RawChunkFrameSnapshot.FlatBitPackSection flat = frame.tryExtractFlatBitPackSection(s, lookup);
                assertNotNull(flat, "Section " + s + " must extract directly as FlatBitPack");
                assertEquals(expectedBits[s], flat.bitsPerVoxel());
                assertEquals((4096 * expectedBits[s] + 7) >>> 3, flat.flatBytes().length);
                assertEquals(paletteSizes[s], flat.paletteRuntimeIds().length);

                int bits = flat.bitsPerVoxel();
                byte[] bytes = flat.flatBytes();
                int[] palIds = flat.paletteRuntimeIds();
                int mask = (1 << bits) - 1;
                for (int i = 0; i < 4096; i++) {
                    int bitPos = i * bits;
                    int byteIdx = bitPos >>> 3;
                    int bitOff = bitPos & 7;
                    int raw = (bytes[byteIdx] & 0xFF)
                            | ((byteIdx + 1 < bytes.length ? (bytes[byteIdx + 1] & 0xFF) : 0) << 8)
                            | ((byteIdx + 2 < bytes.length ? (bytes[byteIdx + 2] & 0xFF) : 0) << 16);
                    int localOrd = (raw >>> bitOff) & mask;
                    int actualRuntimeId = palIds[localOrd];
                    int expectedRuntimeId = 100 + expectedVoxelsBySec[s][i];
                    assertEquals(expectedRuntimeId, actualRuntimeId,
                            "Voxel mismatch at section " + s + " idx=" + i);
                }
            }
        }
    }

    @Test
    @DisplayName("Direct FlatBitPack CoW splice emits mode-10 YZX sections without falling back to Delta-Roaring")
    void directFlatBitPackCoWSpliceEmitsMode10Section(@TempDir Path tempDir) throws Exception {
        Path regionDir = tempDir.resolve("direct_flat_cow_splice");
        Files.createDirectories(regionDir);

        LinkedHashMap<String, Object> root = build24SectionNonUniformChunkNbt(1, 2);
        java.util.function.Function<String, Integer> lookup = name -> {
            if ("minecraft:air".equals(name)) {
                return 0;
            }
            if ("minecraft:stone".equals(name)) {
                return 1;
            }
            if ("minecraft:dirt".equals(name)) {
                return 2;
            }
            if ("minecraft:oak_planks".equals(name)) {
                return 3;
            }
            return 10;
        };

        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(regionDir, false)) {
            storage.setDirectFlatBitPack(true);
            storage.writeChunkNbtBytes(1, 2, Nbt.writeNamedRoot("", root));

            RawChunkFrameSnapshot base = storage.readRawChunkFrame(1, 2, lookup);
            assertNotNull(base);
            int stoneDict = storage.registerBlockStateInRegion(0, 0, "minecraft:stone");
            int dirtDict = storage.registerBlockStateInRegion(0, 0, "minecraft:dirt");
            int planksDict = storage.registerBlockStateInRegion(0, 0, "minecraft:oak_planks");
            int[] dirtyVoxels = new int[4096];
            java.util.Arrays.fill(dirtyVoxels, stoneDict);
            // Sparse minority edits: Delta-Roaring would normally win unless directFlatBitPack=true is wired.
            for (int i = 0; i < 8; i++) {
                dirtyVoxels[i * 17] = dirtDict;
                dirtyVoxels[i * 17 + 1] = planksDict;
            }

            byte[] committed = storage.commitAndGetSplicedChunkFrameCoW(
                    1, 2, base.chunkFrame(), 1 << 0, secIdx -> secIdx == 0 ? dirtyVoxels : null, null, null, null);
            assertNotNull(committed);

            RawChunkFrameSnapshot after = storage.readRawChunkFrame(1, 2, lookup);
            assertNotNull(after);
            RawChunkFrameSnapshot.FlatBitPackSection flat = after.tryExtractFlatBitPackSection(0, lookup);
            assertNotNull(flat, "CoW-spliced section must remain mode-10 FlatBitPack when directFlatBitPack=true");
            assertEquals(2, flat.bitsPerVoxel());
            assertEquals(1024, flat.flatBytes().length);
        }
    }

    @Test
    @DisplayName("Closing a flushed archive still compacts dead CoW tail slabs when above fragmentation threshold")
    void closeCompactsFragmentedArchiveEvenAfterFlushClearsDirty(@TempDir Path tempDir) throws Exception {
        Path regionDir = tempDir.resolve("flush_then_close_compact");
        Files.createDirectories(regionDir);
        Path lcaFile = regionDir.resolve("r.0.0.lca");

        long sizeBeforeClose;
        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(
                regionDir, 0, -4, 24, 4325, false, false, false, 16, 0.25)) {
            storage.configureBufferedIo(false, 1, 4096, false, 4);
            // Write 16 chunks, then remove 8 of them so free-slab fragmentation exceeds 25%
            for (int c = 0; c < 16; c++) {
                LinkedHashMap<String, Object> first = build24SectionNonUniformChunkNbt(c, 0);
                storage.writeChunkNbtBytes(c, 0, Nbt.writeNamedRoot("", first));
            }
            for (int c = 0; c < 8; c++) {
                storage.removeChunk(c, 0);
            }
            // flush() clears archive.isDirty(); close() must still compact the dead CoW tail
            storage.flush();
            sizeBeforeClose = Files.size(lcaFile);
        }
        long sizeAfterClose = Files.size(lcaFile);
        assertTrue(sizeAfterClose < sizeBeforeClose,
                "Expected close() after flush() to compact fragmented CoW slabs (before="
                        + sizeBeforeClose + ", after=" + sizeAfterClose + ")");
    }

    @Test
    @DisplayName("Clean CoW splice with unchanged residual is a zero-write no-op and metadata-only splice preserves sections")
    void cleanCoWSpliceWithUnchangedResidualIsZeroWriteNoOpAndMetadataOnlySplicePreservesSections(
            @TempDir Path tempDir
    ) throws Exception {
        Path regionDir = tempDir.resolve("clean_cow_noop");
        Files.createDirectories(regionDir);
        Path lcaFile = regionDir.resolve("r.0.0.lca");
        java.util.function.Function<String, Integer> lookup = name -> "minecraft:air".equals(name) ? 0 : 1;

        LinkedHashMap<String, Object> root = build24SectionNonUniformChunkNbt(3, 4);
        byte[] customResidual1 = new byte[] {0x04, 0x00, 0x0A, 0x00, 0x00, 0x00}; // bpFlags=1024 + empty TAG_Compound
        byte[] customResidual2 = new byte[] {
                0x04, 0x40, 0x0A, // bpFlags=1024|64, starlightVersion=10
                0x0A, 0x00, 0x00, 0x00 // empty TAG_Compound
        };

        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(regionDir, false)) {
            assertTrue(storage.isForceSyncOnClose(), "Standalone default must force-sync on close");
            storage.setForceSyncOnClose(false);
            assertFalse(storage.isForceSyncOnClose());
            storage.configureBufferedIo(false, 1, 4096, false, 4);

            storage.writeChunkNbtBytes(3, 4, Nbt.writeNamedRoot("", root));
            RawChunkFrameSnapshot base = storage.readRawChunkFrame(3, 4, lookup);
            assertNotNull(base);

            // 1. Metadata-only CoW splice (dirtySectionMask == 0, different residual) -> fast path skips palette decode
            byte[] afterRes1 = storage.commitAndGetSplicedChunkFrameCoW(
                    3, 4, base.chunkFrame(), 0, sec -> null, null, null, customResidual1);
            assertNotNull(afterRes1);
            long sizeAfterRes1 = Files.size(lcaFile);

            // 2. Identical residual with dirtySectionMask == 0 -> must be a zero-write no-op (no slab growth)
            byte[] noopResult = storage.commitAndGetSplicedChunkFrameCoW(
                    3, 4, afterRes1, 0, sec -> null, null, null, customResidual1);
            assertSame(afterRes1, noopResult, "Unchanged clean CoW splice must return base frame without allocating");
            assertEquals(sizeAfterRes1, Files.size(lcaFile), "Zero-write no-op must not grow region file");

            // 3. Second metadata-only splice updates residual tail while preserving section directory entries
            byte[] afterRes2 = storage.commitAndGetSplicedChunkFrameCoW(
                    3, 4, afterRes1, 0, sec -> null, null, null, customResidual2);
            assertNotNull(afterRes2);
            assertTrue(afterRes1 != afterRes2);

            RawChunkFrameSnapshot verify = storage.readRawChunkFrame(3, 4, lookup);
            assertNotNull(verify);
            assertEquals(base.sectionCount(), verify.sectionCount());
            for (int s = 0; s < base.sectionCount(); s++) {
                assertEquals(base.sectionDirectoryEntry16(s), verify.sectionDirectoryEntry16(s),
                        "Section directory entry " + s + " must remain unchanged across metadata-only splice");
            }
        }

        // Reopen after forceSyncOnClose=false close and verify chunk persisted cleanly
        LcaRegionReader.invalidateFileCache(lcaFile);
        try (LcaRegionFileStorage reopened = new LcaRegionFileStorage(regionDir, false)) {
            LcaRegionReader.RawChunkFrameWithDirectMeta pair =
                    reopened.readRawChunkFrameWithDirectMeta(3, 4, lookup);
            assertNotNull(pair);
            assertNotNull(pair.directMeta());
            assertTrue(pair.directMeta().hasStarlightVersion());
            assertEquals(10, pair.directMeta().starlightLightVersion());
        }
    }

    @Test
    @DisplayName("writeFreshChunkDirect round-trips uniform, multi-state, biome, light, and residual metadata with zero NBT section encode")
    void writeFreshChunkDirectRoundTripParity(@TempDir Path tempDir) throws Exception {
        Path regionDir = tempDir.resolve("fresh_direct_roundtrip");
        Files.createDirectories(regionDir);

        int sectionCount = 24;
        String[][] blockPalettes = new String[sectionCount][];
        long[][] blockData = new long[sectionCount][];
        String[][] biomePalettes = new String[sectionCount][];
        long[][] biomeData = new long[sectionCount][];
        byte[][] skyLight = new byte[sectionCount][];
        byte[][] blockLight = new byte[sectionCount][];

        // Section 0 (y=-4): uniform bedrock
        blockPalettes[0] = new String[] {"minecraft:bedrock"};
        biomePalettes[0] = new String[] {"minecraft:plains"};
        skyLight[0] = LcaRegionFileStorage.allZeroLight2048();

        // Section 1 (y=-3): 4-bit multi-state stone + deepslate + diamond_ore with non-uniform light
        blockPalettes[1] = new String[] {"minecraft:stone", "minecraft:deepslate", "minecraft:diamond_ore"};
        long[] s1Longs = new long[256]; // 4096 voxels * 4 bits = 256 longs (16 voxels per long)
        for (int i = 0; i < s1Longs.length; i++) {
            s1Longs[i] = 0x0120_1021_0210_1201L;
        }
        blockData[1] = s1Longs;
        biomePalettes[1] = new String[] {"minecraft:plains", "minecraft:forest"};
        biomeData[1] = new long[] {0xAAAA_5555_AAAA_5555L}; // 64 cells * 1 bit = 1 long
        byte[] s1Sky = new byte[2048];
        java.util.Arrays.fill(s1Sky, (byte) 0xF0);
        skyLight[1] = s1Sky;
        byte[] s1Blk = new byte[2048];
        java.util.Arrays.fill(s1Blk, (byte) 0x3A);
        blockLight[1] = s1Blk;

        // Remaining sections: uniform air with full sky light
        byte[] fullSky = new byte[2048];
        java.util.Arrays.fill(fullSky, (byte) 0xFF);
        for (int s = 2; s < sectionCount; s++) {
            blockPalettes[s] = new String[] {"minecraft:air"};
            biomePalettes[s] = new String[] {"minecraft:plains"};
            skyLight[s] = fullSky;
        }

        byte[] residualTail = new byte[] {
                0x04, 0x40, 0x0A, // bpFlags = 1024 | 64, starlightVersion = 10
                0x0A, 0x00, 0x00, 0x00 // empty TAG_Compound
        };

        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(regionDir, false)) {
            int written = storage.writeFreshChunkDirect(
                    5,
                    -3,
                    4671,
                    12345L,
                    678L,
                    "minecraft:full",
                    blockPalettes,
                    blockData,
                    biomePalettes,
                    biomeData,
                    skyLight,
                    blockLight,
                    residualTail
            );
            assertTrue(written > 0, "Expected non-zero .lca bytes written");
            storage.flush();
        }

        try (LcaRegionFileStorage reopened = new LcaRegionFileStorage(regionDir, false)) {
            byte[] nbtBytes = reopened.readChunkNbtBytes(5, -3);
            assertNotNull(nbtBytes, "Fresh direct chunk must decode cleanly via readChunkNbtBytes");
            Map<String, Object> root = Nbt.readRootCompound(nbtBytes);
            assertEquals(4671, ((Number) root.get("DataVersion")).intValue());
            assertEquals(12345L, ((Number) root.get("LastUpdate")).longValue());
            assertEquals(678L, ((Number) root.get("InhabitedTime")).longValue());
            assertEquals("minecraft:full", root.get("Status"));
            assertEquals(10, ((Number) root.get("starlight.light_version")).intValue());

            Nbt.NbtList sections = (Nbt.NbtList) root.get("sections");
            assertNotNull(sections);
            Map<Integer, Map<String, Object>> byY = new java.util.HashMap<>();
            for (Object item : sections.items) {
                @SuppressWarnings("unchecked")
                Map<String, Object> sec = (Map<String, Object>) item;
                byY.put(((Number) sec.get("Y")).intValue(), sec);
            }

            // Section y=-4: uniform bedrock
            Map<String, Object> sec0 = byY.get(-4);
            assertNotNull(sec0);
            @SuppressWarnings("unchecked")
            Map<String, Object> bs0 = (Map<String, Object>) sec0.get("block_states");
            Nbt.NbtList pal0 = (Nbt.NbtList) bs0.get("palette");
            assertEquals(1, pal0.items.size());
            assertEquals("minecraft:bedrock", ((Map<?, ?>) pal0.items.get(0)).get("Name"));

            // Section y=-3: multi-state stone/deepslate/diamond_ore + biomes + lights
            Map<String, Object> sec1 = byY.get(-3);
            assertNotNull(sec1);
            @SuppressWarnings("unchecked")
            Map<String, Object> bs1 = (Map<String, Object>) sec1.get("block_states");
            Nbt.NbtList pal1 = (Nbt.NbtList) bs1.get("palette");
            assertEquals(3, pal1.items.size());
            org.junit.jupiter.api.Assertions.assertArrayEquals(s1Sky, (byte[]) sec1.get("SkyLight"));
            org.junit.jupiter.api.Assertions.assertArrayEquals(s1Blk, (byte[]) sec1.get("BlockLight"));
        }
    }

    @Test
    @DisplayName("writeFreshChunkDirectModeA splices Mode A, GPU FlatBitPack, and hot SimpleBitStorage zero-reencode with full round-trip parity")
    void writeFreshChunkDirectModeAPassthroughRoundTripParity(@TempDir Path tempDir) throws Exception {
        Path regionDir = tempDir.resolve("fresh_modea_passthrough");
        Files.createDirectories(regionDir);

        int sectionCount = 24;
        String[][] blockPalettes = new String[sectionCount][];
        LcaModeABlockSection[] preModeA = new LcaModeABlockSection[sectionCount];
        byte[][] preFlatBytes = new byte[sectionCount][];
        int[] preFlatBits = new int[sectionCount];
        long[][] blockData = new long[sectionCount][];
        String[][] biomePalettes = new String[sectionCount][];
        long[][] biomeData = new long[sectionCount][];
        byte[][] skyLight = new byte[sectionCount][];
        byte[][] blockLight = new byte[sectionCount][];

        // Section 0 (y=-4): uniform bedrock with explicit Starlight zero sky light
        blockPalettes[0] = new String[] {"minecraft:bedrock"};
        biomePalettes[0] = new String[] {"minecraft:plains"};
        skyLight[0] = LcaRegionFileStorage.allZeroLight2048();

        // Section 1 (y=-3): pre-encoded LcaModeABlockSection (Delta-Roaring Mode A passthrough)
        int[] s1Voxels = new int[4096];
        for (int i = 0; i < 4096; i++) {
            s1Voxels[i] = (i % 17 == 0) ? 2 : 1;
        }
        LcaModeABlockSection s1ModeA = LcaModeABlockSection.encodeFromRuntimeVoxels(s1Voxels);
        assertNotNull(s1ModeA);
        String[] s1Pal = new String[s1ModeA.paletteLength()];
        for (int p = 0; p < s1Pal.length; p++) {
            s1Pal[p] = s1ModeA.paletteRuntimeId(p) == 1 ? "minecraft:deepslate" : "minecraft:diamond_ore";
        }
        blockPalettes[1] = s1Pal;
        preModeA[1] = s1ModeA;
        biomePalettes[1] = new String[] {"minecraft:plains"};

        // Section 2 (y=-2): pre-encoded GPU 4-bit FlatBitPack (mode 10) with 10-state palette (exact 1:1 copy)
        String[] s2Pal = new String[] {
                "minecraft:stone", "minecraft:granite", "minecraft:diorite", "minecraft:andesite",
                "minecraft:coal_ore", "minecraft:iron_ore", "minecraft:copper_ore", "minecraft:gold_ore",
                "minecraft:redstone_ore", "minecraft:lapis_ore"
        };
        byte[] s2Flat = new byte[2048];
        for (int i = 0; i < 2048; i++) {
            s2Flat[i] = (byte) ((i % 10) | (((i + 3) % 10) << 4));
        }
        blockPalettes[2] = s2Pal;
        preFlatBytes[2] = s2Flat;
        preFlatBits[2] = 4;
        biomePalettes[2] = new String[] {"minecraft:plains", "minecraft:forest"};
        biomeData[2] = new long[] {0xAAAA_5555_AAAA_5555L};
        byte[] s2Sky = new byte[2048];
        java.util.Arrays.fill(s2Sky, (byte) 0xF0);
        skyLight[2] = s2Sky;
        byte[] s2Blk = new byte[2048];
        java.util.Arrays.fill(s2Blk, (byte) 0x3A);
        blockLight[2] = s2Blk;

        // Section 3 (y=-1): pre-encoded GPU 4-bit FlatBitPack with 3-state palette (4-bit -> 2-bit repack)
        blockPalettes[3] = new String[] {"minecraft:stone", "minecraft:dirt", "minecraft:grass_block"};
        byte[] s3Flat4Bit = new byte[2048];
        for (int i = 0; i < 2048; i++) {
            s3Flat4Bit[i] = (byte) ((i % 3) | (((i + 1) % 3) << 4));
        }
        preFlatBytes[3] = s3Flat4Bit;
        preFlatBits[3] = 4;
        biomePalettes[3] = new String[] {"minecraft:plains"};

        // Section 4 (y=0): hot SimpleBitStorage 4-bit longs (9 states -> exact 4-bit 1:1 copy)
        blockPalettes[4] = new String[] {
                "minecraft:air", "minecraft:stone", "minecraft:dirt", "minecraft:grass_block",
                "minecraft:oak_log", "minecraft:oak_leaves", "minecraft:water", "minecraft:sand", "minecraft:gravel"
        };
        long[] s4Longs = new long[256];
        for (int w = 0; w < 256; w++) {
            s4Longs[w] = 0x8765_4321_0123_4567L;
        }
        blockData[4] = s4Longs;
        biomePalettes[4] = new String[] {"minecraft:plains"};

        // Remaining sections: uniform air with full sky light
        byte[] fullSky = new byte[2048];
        java.util.Arrays.fill(fullSky, (byte) 0xFF);
        for (int s = 5; s < sectionCount; s++) {
            blockPalettes[s] = new String[] {"minecraft:air"};
            biomePalettes[s] = new String[] {"minecraft:plains"};
            skyLight[s] = fullSky;
        }

        byte[] residualTail = new byte[] {
                0x04, 0x40, 0x0A,
                0x0A, 0x00, 0x00, 0x00
        };

        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(regionDir, false)) {
            int[] dictIds = storage.preRegisterBlockStates(7, -9, new String[] {"minecraft:air", "minecraft:stone", "minecraft:deepslate"});
            assertEquals(3, dictIds.length);
            assertTrue(dictIds[0] >= 0 && dictIds[1] > dictIds[0] && dictIds[2] > dictIds[1]);

            int written = storage.writeFreshChunkDirectModeA(
                    7,
                    -9,
                    4671,
                    99999L,
                    1234L,
                    "minecraft:full",
                    blockPalettes,
                    preModeA,
                    preFlatBytes,
                    preFlatBits,
                    blockData,
                    biomePalettes,
                    biomeData,
                    skyLight,
                    blockLight,
                    residualTail
            );
            assertTrue(written > 0, "Expected non-zero .lca bytes written");
            storage.flush();
        }

        try (LcaRegionFileStorage reopened = new LcaRegionFileStorage(regionDir, false)) {
            byte[] nbtBytes = reopened.readChunkNbtBytes(7, -9);
            assertNotNull(nbtBytes, "Mode A passthrough chunk must decode cleanly via readChunkNbtBytes");
            Map<String, Object> root = Nbt.readRootCompound(nbtBytes);
            assertEquals(4671, ((Number) root.get("DataVersion")).intValue());
            assertEquals(99999L, ((Number) root.get("LastUpdate")).longValue());
            assertEquals(1234L, ((Number) root.get("InhabitedTime")).longValue());
            assertEquals("minecraft:full", root.get("Status"));
            assertEquals(10, ((Number) root.get("starlight.light_version")).intValue());

            Nbt.NbtList sections = (Nbt.NbtList) root.get("sections");
            assertNotNull(sections);
            Map<Integer, Map<String, Object>> byY = new java.util.HashMap<>();
            for (Object item : sections.items) {
                @SuppressWarnings("unchecked")
                Map<String, Object> sec = (Map<String, Object>) item;
                byY.put(((Number) sec.get("Y")).intValue(), sec);
            }

            // Section y=-3 (pre-encoded Delta-Roaring Mode A passthrough)
            @SuppressWarnings("unchecked")
            Map<String, Object> bs1 = (Map<String, Object>) byY.get(-3).get("block_states");
            assertEquals(2, ((Nbt.NbtList) bs1.get("palette")).items.size());

            // Section y=-2 (GPU 4-bit FlatBitPack + mixed light)
            Map<String, Object> sec2 = byY.get(-2);
            @SuppressWarnings("unchecked")
            Map<String, Object> bs2 = (Map<String, Object>) sec2.get("block_states");
            assertEquals(10, ((Nbt.NbtList) bs2.get("palette")).items.size());
            org.junit.jupiter.api.Assertions.assertArrayEquals(s2Sky, (byte[]) sec2.get("SkyLight"));
            org.junit.jupiter.api.Assertions.assertArrayEquals(s2Blk, (byte[]) sec2.get("BlockLight"));

            // Section y=-1 (4-bit -> 2-bit repack)
            @SuppressWarnings("unchecked")
            Map<String, Object> bs3 = (Map<String, Object>) byY.get(-1).get("block_states");
            assertEquals(3, ((Nbt.NbtList) bs3.get("palette")).items.size());

            // Section y=0 (hot SimpleBitStorage 9-state 4-bit: verify exact 4,096-voxel state parity after Mode A frequency sort)
            @SuppressWarnings("unchecked")
            Map<String, Object> bs4 = (Map<String, Object>) byY.get(0).get("block_states");
            Nbt.NbtList decodedPal4 = (Nbt.NbtList) bs4.get("palette");
            assertEquals(9, decodedPal4.items.size());
            long[] decodedData4 = (long[]) bs4.get("data");
            assertEquals(256, decodedData4.length);
            for (int w = 0; w < 256; w++) {
                long expWord = s4Longs[w];
                long actWord = decodedData4[w];
                for (int s = 0; s < 16; s++) {
                    int expIdx = (int) ((expWord >>> (s << 2)) & 0xFL);
                    int actIdx = (int) ((actWord >>> (s << 2)) & 0xFL);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> actEntry = (Map<String, Object>) decodedPal4.items.get(actIdx);
                    assertEquals(blockPalettes[4][expIdx], actEntry.get("Name"),
                            "Mismatch at voxel " + ((w << 4) | s));
                }
            }
        }
    }

    @Test
    void writeFreshChunkDirectModeAWithDmaWorldgenDictionaryAndLegacyFallback(@TempDir Path tempDir) throws Exception {
        Path freshDir = tempDir.resolve("region-dma-dict-fresh");
        Files.createDirectories(freshDir);

        String[] wgDict = new String[] {
                "minecraft:air",
                "minecraft:stone",
                "minecraft:deepslate",
                "minecraft:dirt",
                "minecraft:grass_block",
                "minecraft:water",
                "minecraft:sand",
                "minecraft:gravel",
                "minecraft:bedrock"
        };

        int sectionCount = 24;
        String[][] blockPalettes = new String[sectionCount][];
        LcaModeABlockSection[] preModeA = new LcaModeABlockSection[sectionCount];
        byte[][] preFlatBytes = new byte[sectionCount][];
        int[] preFlatBits = new int[sectionCount];
        int[][] preResolvedDictIds = new int[sectionCount][];
        long[] preOctreeMasks = new long[sectionCount];
        boolean[] hasPreOctreeMask = new boolean[sectionCount];
        long[][] blockData = new long[sectionCount][];
        String[][] biomePalettes = new String[sectionCount][];
        long[][] biomeData = new long[sectionCount][];
        byte[][] skyLight = new byte[sectionCount][];
        byte[][] blockLight = new byte[sectionCount][];

        // Section 0 (y=-4): GPU 1-bit compacted (air, bedrock) with pre-resolved dict IDs [0, 8] + negative octreeMask64
        blockPalettes[0] = new String[] {"minecraft:air", "minecraft:bedrock"};
        byte[] s0Flat1Bit = new byte[512];
        java.util.Arrays.fill(s0Flat1Bit, (byte) 0xAA);
        preFlatBytes[0] = s0Flat1Bit;
        preFlatBits[0] = 1;
        preResolvedDictIds[0] = new int[] {0, 8};
        preOctreeMasks[0] = -1L; // all 64 octants set (tests bit-63 signed long handling)
        hasPreOctreeMask[0] = true;
        biomePalettes[0] = new String[] {"minecraft:plains"};

        // Section 1 (y=-3): GPU 2-bit compacted (air, stone, deepslate) with pre-resolved dict IDs [0, 1, 2]
        blockPalettes[1] = new String[] {"minecraft:air", "minecraft:stone", "minecraft:deepslate"};
        byte[] s1Flat2Bit = new byte[1024];
        java.util.Arrays.fill(s1Flat2Bit, (byte) 0x64); // 0, 1, 2, 1 repeating
        preFlatBytes[1] = s1Flat2Bit;
        preFlatBits[1] = 2;
        preResolvedDictIds[1] = new int[] {0, 1, 2};
        preOctreeMasks[1] = -1L;
        hasPreOctreeMask[1] = true;
        biomePalettes[1] = new String[] {"minecraft:plains"};

        // Section 2 (y=-2): GPU 3-bit compacted (5 states: air, stone, dirt, grass_block, sand)
        blockPalettes[2] = new String[] {
                "minecraft:air", "minecraft:stone", "minecraft:dirt", "minecraft:grass_block", "minecraft:sand"
        };
        byte[] s2Flat3Bit = new byte[1536];
        for (int row = 0; row < 256; row++) {
            long word = 0L;
            for (int px = 0; px < 16; px++) {
                word |= ((long) (px % 5)) << (px * 3);
            }
            int off = row * 6;
            for (int b = 0; b < 6; b++) {
                s2Flat3Bit[off + b] = (byte) (word >>> (b * 8));
            }
        }
        preFlatBytes[2] = s2Flat3Bit;
        preFlatBits[2] = 3;
        preResolvedDictIds[2] = new int[] {0, 1, 3, 4, 6};
        preOctreeMasks[2] = -1L;
        hasPreOctreeMask[2] = true;
        biomePalettes[2] = new String[] {"minecraft:plains"};

        // Section 3 (y=-1): CPU-edited section (no preResolvedDictIds) sharing states with GPU sections + new oak_log
        blockPalettes[3] = new String[] {"minecraft:air", "minecraft:stone", "minecraft:oak_log"};
        long[] s3Longs = new long[256];
        java.util.Arrays.fill(s3Longs, 0x2102_1021_0210_2101L);
        blockData[3] = s3Longs;
        biomePalettes[3] = new String[] {"minecraft:plains"};

        for (int s = 4; s < sectionCount; s++) {
            blockPalettes[s] = new String[] {"minecraft:air"};
            biomePalettes[s] = new String[] {"minecraft:plains"};
        }

        // 1) Fresh region: worldgen dictionary auto-binds with 1:1 direct match
        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(freshDir, false)) {
            storage.setWorldgenBlockDictionary(wgDict);
            int written = storage.writeFreshChunkDirectModeA(
                    2, 3, 4671, 100L, 10L, "minecraft:full",
                    blockPalettes, preModeA, preFlatBytes, preFlatBits,
                    preResolvedDictIds, preOctreeMasks, hasPreOctreeMask,
                    blockData, biomePalettes, biomeData, skyLight, blockLight, new byte[0]);
            assertTrue(written > 0);
            storage.flush();
        }
        try (LcaRegionFileStorage reopened = new LcaRegionFileStorage(freshDir, false)) {
            byte[] nbtBytes = reopened.readChunkNbtBytes(2, 3);
            assertNotNull(nbtBytes);
            Map<String, Object> root = Nbt.readRootCompound(nbtBytes);
            Nbt.NbtList sections = (Nbt.NbtList) root.get("sections");
            Map<Integer, Map<String, Object>> byY = new java.util.HashMap<>();
            for (Object item : sections.items) {
                @SuppressWarnings("unchecked")
                Map<String, Object> sec = (Map<String, Object>) item;
                byY.put(((Number) sec.get("Y")).intValue(), sec);
            }
            @SuppressWarnings("unchecked")
            Nbt.NbtList p0 = (Nbt.NbtList) ((Map<String, Object>) byY.get(-4).get("block_states")).get("palette");
            assertEquals(2, p0.items.size());
            assertEquals("minecraft:bedrock", ((Map<?, ?>) p0.items.get(1)).get("Name"));
            @SuppressWarnings("unchecked")
            Nbt.NbtList p2 = (Nbt.NbtList) ((Map<String, Object>) byY.get(-2).get("block_states")).get("palette");
            assertEquals(5, p2.items.size());
            assertEquals("minecraft:sand", ((Map<?, ?>) p2.items.get(4)).get("Name"));
            @SuppressWarnings("unchecked")
            Nbt.NbtList p3 = (Nbt.NbtList) ((Map<String, Object>) byY.get(-1).get("block_states")).get("palette");
            assertEquals(3, p3.items.size());
            assertEquals("minecraft:oak_log", ((Map<?, ?>) p3.items.get(2)).get("Name"));
        }

        // 2) Legacy mismatched region: obsidian pre-registered at ID 1 before wgDict is set -> fail-closed fallback
        Path legacyDir = tempDir.resolve("region-dma-dict-legacy");
        Files.createDirectories(legacyDir);
        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(legacyDir, false)) {
            int[] legacyIds = storage.preRegisterBlockStates(0, 0, new String[] {"minecraft:air", "minecraft:obsidian"});
            assertEquals(1, legacyIds[1]);
            storage.setWorldgenBlockDictionary(wgDict);
            int written = storage.writeFreshChunkDirectModeA(
                    0, 0, 4671, 100L, 10L, "minecraft:full",
                    blockPalettes, preModeA, preFlatBytes, preFlatBits,
                    preResolvedDictIds, preOctreeMasks, hasPreOctreeMask,
                    blockData, biomePalettes, biomeData, skyLight, blockLight, new byte[0]);
            assertTrue(written > 0);
            storage.flush();
        }
        try (LcaRegionFileStorage reopened = new LcaRegionFileStorage(legacyDir, false)) {
            byte[] nbtBytes = reopened.readChunkNbtBytes(0, 0);
            assertNotNull(nbtBytes);
            Map<String, Object> root = Nbt.readRootCompound(nbtBytes);
            Nbt.NbtList sections = (Nbt.NbtList) root.get("sections");
            Map<Integer, Map<String, Object>> byY = new java.util.HashMap<>();
            for (Object item : sections.items) {
                @SuppressWarnings("unchecked")
                Map<String, Object> sec = (Map<String, Object>) item;
                byY.put(((Number) sec.get("Y")).intValue(), sec);
            }
            @SuppressWarnings("unchecked")
            Nbt.NbtList p0 = (Nbt.NbtList) ((Map<String, Object>) byY.get(-4).get("block_states")).get("palette");
            assertEquals("minecraft:bedrock", ((Map<?, ?>) p0.items.get(1)).get("Name"));
        }
    }

    @Test
    @DisplayName("Region locks remain fixed-bounded via lock striping across many regions and eviction churn")
    void regionLocksRemainBoundedAndStripeSafelyUnderChurn(@TempDir Path tempDir) throws Exception {
        Path regionDir = tempDir.resolve("region-lock-stripes");
        Files.createDirectories(regionDir);

        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(
                regionDir,
                LcaRegionReader.STORAGE_KIND_REGION_VOXEL,
                -4,
                24,
                4671,
                0,
                false,
                false,
                4,
                LcaRegionFileStorage.DEFAULT_COMPACT_FRAGMENTATION_THRESHOLD)) {
            java.lang.reflect.Method regionLockMethod =
                    LcaRegionFileStorage.class.getDeclaredMethod("regionLock", long.class);
            regionLockMethod.setAccessible(true);

            java.util.Set<Object> distinctLocks =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (int rx = -16; rx < 16; rx++) {
                for (int rz = -16; rz < 16; rz++) {
                    long key = LcaRegionFileStorage.packRegionKey(rx, rz);
                    Object lockA = regionLockMethod.invoke(storage, key);
                    Object lockB = regionLockMethod.invoke(storage, key);
                    assertNotNull(lockA);
                    assertSame(lockA, lockB, "regionLock must deterministically map a key to the same monitor");
                    distinctLocks.add(lockA);
                }
            }
            assertTrue(
                    distinctLocks.size() <= 256,
                    "Region lock monitors must remain bounded at <= 256 stripes, found: " + distinctLocks.size());
            assertTrue(
                    distinctLocks.size() >= 128,
                    "Region lock stripes should distribute 1024 region keys across >= 128 stripes, found: "
                            + distinctLocks.size());

            // Exercise concurrent writes and eviction across 24 regions with maxOpenRegions=4
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < 24; i++) {
                    final int regionIdx = i;
                    futures.add(pool.submit(() -> {
                        int chunkX = regionIdx * 32 + 1;
                        int chunkZ = (regionIdx % 5) * 32 + 2;
                        storage.writeChunkCompound(
                                chunkX,
                                chunkZ,
                                buildChunkNbt(chunkX, chunkZ, "minecraft:stone"));
                        return null;
                    }));
                }
                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }
            assertTrue(storage.openRegionCount() <= 4, "Open regions must stay bounded by maxOpenRegions");
            storage.flush();
        }

        try (LcaRegionFileStorage reopened = new LcaRegionFileStorage(regionDir, false)) {
            for (int i = 0; i < 24; i++) {
                int chunkX = i * 32 + 1;
                int chunkZ = (i % 5) * 32 + 2;
                assertTrue(
                        reopened.doesChunkExist(chunkX, chunkZ),
                        "Chunk must persist across lock-striped concurrent writes and eviction: ("
                                + chunkX + "," + chunkZ + ")");
            }
        }
    }
}
