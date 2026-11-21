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

            if (readerError.get() != null) {
                throw new AssertionError("Concurrent reader failed during compaction", readerError.get());
            }
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
}
