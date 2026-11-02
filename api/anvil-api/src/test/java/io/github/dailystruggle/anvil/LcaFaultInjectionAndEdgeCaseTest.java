package io.github.dailystruggle.anvil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic fault-injection, crash-consistency, `ENOSPC` transactional rollback, inline
 * hot-read `SEC-DED` self-healing, and LRU eviction compaction verification suite for `.lca`
 * archives (`anvil-api-ADR-003`, `leafanvil-ADR-001`).
 *
 * @governedBy anvil-api-ADR-002
 * @governedBy anvil-api-ADR-003
 * @governedBy leafanvil-ADR-001
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
class LcaFaultInjectionAndEdgeCaseTest {

    private static LinkedHashMap<String, Object> buildTestChunk(int chunkX, int chunkZ, String blockName, int variantCount) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", 4671);
        root.put("xPos", chunkX);
        root.put("zPos", chunkZ);
        root.put("Status", "minecraft:full");
        root.put("LastUpdate", 100L + variantCount);
        root.put("InhabitedTime", 50L + variantCount);

        LinkedHashMap<String, Object> sec = new LinkedHashMap<>();
        sec.put("Y", (byte) 0);
        LinkedHashMap<String, Object> bs = new LinkedHashMap<>();
        List<Object> palette = new java.util.ArrayList<>();
        LinkedHashMap<String, Object> baseEntry = new LinkedHashMap<>();
        baseEntry.put("Name", blockName);
        palette.add(baseEntry);
        for (int i = 1; i < Math.max(2, variantCount); i++) {
            LinkedHashMap<String, Object> p = new LinkedHashMap<>();
            p.put("Name", "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false,variant=" + i + "]");
            palette.add(p);
        }
        bs.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, palette));
        int[] yzx = new int[4096];
        for (int i = 0; i < 4096; i++) {
            yzx[i] = i % palette.size();
        }
        bs.put("data", LcaSectionCodec.packBlockLongs(yzx, palette.size()));
        sec.put("block_states", bs);

        LinkedHashMap<String, Object> biomes = new LinkedHashMap<>();
        biomes.put("palette", new Nbt.NbtList(Nbt.TAG_STRING, List.of("minecraft:plains")));
        sec.put("biomes", biomes);

        root.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(sec)));
        return root;
    }

    /**
     * Delegating {@link FileChannel} that enforces a configurable byte quota and simulates
     * mid-write `ENOSPC` (partial write followed by `IOException("No space left on device")`).
     */
    private static final class QuotaFaultInjectingFileChannel extends FileChannel {
        private final FileChannel delegate;
        private final AtomicLong maxQuotaBytes;

        QuotaFaultInjectingFileChannel(FileChannel delegate, long initialQuotaBytes) {
            this.delegate = delegate;
            this.maxQuotaBytes = new AtomicLong(initialQuotaBytes);
        }

        void setMaxQuotaBytes(long quotaBytes) {
            this.maxQuotaBytes.set(quotaBytes);
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            return delegate.read(dst);
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
            return delegate.read(dsts, offset, length);
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            long pos = delegate.position();
            return write(src, pos);
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public FileChannel position(long newPosition) throws IOException {
            delegate.position(newPosition);
            return this;
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public FileChannel truncate(long size) throws IOException {
            delegate.truncate(size);
            return this;
        }

        @Override
        public void force(boolean metaData) throws IOException {
            delegate.force(metaData);
        }

        @Override
        public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
            return delegate.transferTo(position, count, target);
        }

        @Override
        public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
            return delegate.transferFrom(src, position, count);
        }

        @Override
        public int read(ByteBuffer dst, long position) throws IOException {
            return delegate.read(dst, position);
        }

        @Override
        public int write(ByteBuffer src, long position) throws IOException {
            long quota = maxQuotaBytes.get();
            int rem = src.remaining();
            if (position + rem > quota) {
                // Simulate a partial write of up to 64 bytes before ENOSPC if space allows
                long allowed = quota - position;
                if (allowed > 0 && allowed < rem) {
                    ByteBuffer slice = src.slice();
                    slice.limit((int) allowed);
                    int n = delegate.write(slice, position);
                    src.position(src.position() + n);
                }
                throw new IOException("No space left on device (simulated ENOSPC at offset " + (position + rem) + " > quota " + quota + ")");
            }
            return delegate.write(src, position);
        }

        @Override
        public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
            return delegate.map(mode, position, size);
        }

        @Override
        public FileLock lock(long position, long size, boolean shared) throws IOException {
            return delegate.lock(position, size, shared);
        }

        @Override
        public FileLock tryLock(long position, long size, boolean shared) throws IOException {
            return delegate.tryLock(position, size, shared);
        }

        @Override
        protected void implCloseChannel() throws IOException {
            delegate.close();
        }
    }

    @Test
    @DisplayName("Pillar 1 (ENOSPC Rollback): Mid-write disk full during chunk slab & dictionary continuation rolls back cleanly")
    void enospcMidWriteRollsBackCleanlyAndRecoversWhenSpaceFreed(@TempDir Path tempDir) throws IOException {
        Path lcaFile = tempDir.resolve("r.0.0.lca");
        boolean modeB = DynamicZstdBinding.isAvailable();

        try (LcaRegionWriter.LcaLiveArchive init = LcaRegionWriter.createEmptyLiveArchive(
                lcaFile, 0, 0, -4, 24, 4671, modeB, LcaRegionReader.STORAGE_KIND_REGION_VOXEL, 1024)) {
            init.saveChunkCoW(0, 0, buildTestChunk(0, 0, "minecraft:stone", 2));
            assertTrue(init.toByteArray().length > 0);
            assertTrue(init.liveBuffer().length > 0);
            init.flush();
        }

        long baselineFileSize = Files.size(lcaFile);
        FileChannel rawCh = FileChannel.open(lcaFile, StandardOpenOption.READ, StandardOpenOption.WRITE);
        QuotaFaultInjectingFileChannel quotaCh = new QuotaFaultInjectingFileChannel(rawCh, Long.MAX_VALUE);

        try (LcaRegionWriter.LcaLiveArchive archive = new LcaRegionWriter.LcaLiveArchive(
                lcaFile, quotaCh, (int) baselineFileSize)) {
            int endSlabBefore = archive.activeByteLength();
            long savesBefore = archive.totalSaves();
            int dictSegsBefore = archive.dictionarySegmentCount();
            int dictStatesBefore = archive.totalDictionaryStates();

            // Cap quota just 128 bytes above baselineFileSize so any EOF slab append or dict continuation triggers partial-write ENOSPC
            quotaCh.setMaxQuotaBytes(baselineFileSize + 128);

            // 1. Attempt a chunk save via DataOutputStream#close -> must throw checked IOException (not UncheckedIOException)
            byte[] nbt = Nbt.writeNamedRoot("", buildTestChunk(1, 0, "minecraft:deepslate", 4));
            DataOutputStream out = archive.getChunkDataOutputStream(1, 0);
            out.write(nbt);
            IOException ex1 = assertThrows(IOException.class, out::close);
            assertTrue(ex1.getMessage().contains("ENOSPC"), ex1.getMessage());

            // Verify rollback of activeByteLength, telemetry, and on-disk file size + footer canary
            assertEquals(endSlabBefore, archive.activeByteLength(), "activeByteLength must roll back after ENOSPC");
            assertEquals(savesBefore, archive.totalSaves(), "totalSaves must roll back after ENOSPC");
            assertEquals(baselineFileSize, quotaCh.size(), "On-disk file must be truncated back to pre-ENOSPC size");
            assertFalse(archive.hasChunk(1, 0), "Aborted chunk (1,0) must not be marked present");

            // 2. Attempt a save with many new block states that forces a dictionary continuation segment allocation -> ENOSPC
            IOException ex2 = assertThrows(IOException.class, () ->
                    archive.saveChunkCoW(2, 0, buildTestChunk(2, 0, "minecraft:granite", 40)));
            assertTrue(ex2.getMessage().contains("ENOSPC"), ex2.getMessage());
            assertEquals(dictSegsBefore, archive.dictionarySegmentCount(),
                    "Dictionary continuation segment list must roll back on ENOSPC");
            assertEquals(dictStatesBefore, archive.totalDictionaryStates(),
                    "Dictionary state count must roll back on ENOSPC");

            // Existing chunk (0,0) must remain 100% readable
            AnvilReader.ChunkEntry c00 = archive.readChunkEntry(0, 0);
            assertNotNull(c00);
            assertEquals(0, ((Number) c00.root.get("xPos")).intValue());

            // 3. Free disk space and verify subsequent saves + dictionary continuation succeed cleanly
            quotaCh.setMaxQuotaBytes(Long.MAX_VALUE);
            archive.saveChunkCoW(1, 0, buildTestChunk(1, 0, "minecraft:deepslate", 4));
            archive.saveChunkCoW(2, 0, buildTestChunk(2, 0, "minecraft:granite", 40));
            assertTrue(archive.hasChunk(1, 0));
            assertTrue(archive.hasChunk(2, 0));
            assertTrue(archive.dictionarySegmentCount() >= 2);
        }

        // Verify on-disk archive passes full bit-rot audit with 0 corruptions
        byte[] finalBytes = Files.readAllBytes(lcaFile);
        LcaRegionReader.RecoveryReport report = LcaRegionReader.healRegionBitRot(finalBytes);
        assertEquals(0, report.secDedHealedPointers() + report.slabScanReconstructedPointers());
        LcaRegionReader.clearThreadLocalScratch();
    }

    @Test
    @DisplayName("Pillar 2 (Crash Consistency): Unclean shutdown missing LCA1_END footer, torn syndrome byte, and Mode B Slab Reverse-Scan")
    void uncleanShutdownMissingFooterAndTornSyndromeRecoversOnOpen(@TempDir Path tempDir) throws IOException {
        Path lcaFile = tempDir.resolve("r.0.0.lca");
        boolean modeB = DynamicZstdBinding.isAvailable();

        try (LcaRegionWriter.LcaLiveArchive archive = LcaRegionWriter.createEmptyLiveArchive(
                lcaFile, 0, 0, -4, 24, 4671, modeB, LcaRegionReader.STORAGE_KIND_REGION_VOXEL, 4096)) {
            archive.saveChunkCoW(0, 0, buildTestChunk(0, 0, "minecraft:stone", 3));
            archive.saveChunkCoW(1, 0, buildTestChunk(1, 0, "minecraft:dirt", 5));
            archive.flush();
        }

        // Simulate crash scenario:
        // 1. Corrupt chunk (0,0)'s SEC-DED syndrome byte on disk (simulating kill -9 between secDed write and pointer write)
        // 2. Wipe chunk (1,0)'s pointer in the pointer directory (simulating lost pointer directory update while slab is on disk)
        // 3. Append 500 bytes of uncommitted garbage at EOF (overwriting and destroying the trailing "LCA1_END" footer canary)
        byte[] raw = Files.readAllBytes(lcaFile);
        int secDedOff = (int) (ByteBuffer.wrap(raw).getLong(0x28) & 0xFFFF_FFFFL);
        raw[secDedOff] ^= (byte) 0x55; // Torn syndrome byte for chunkIdx=0
        ByteBuffer.wrap(raw).putLong(LcaRegionReader.POINTER_DIRECTORY_OFFSET + 8, 0xDEAD_BEEF_CAFE_BABEL); // Corrupt chunkIdx=1 pointer

        byte[] crashedWithTail = Arrays.copyOf(raw, raw.length + 500);
        Arrays.fill(crashedWithTail, raw.length - 8, crashedWithTail.length, (byte) 0x33);
        Files.write(lcaFile, crashedWithTail);

        // Reopen via openLiveArchive(Path) -> must self-heal the missing footer canary, reconcile chunk (0,0)'s syndrome,
        // and reconstruct chunk (1,0)'s Mode B/A pointer via Tier-2 Slab Reverse-Scan!
        try (LcaRegionWriter.LcaLiveArchive reopened = LcaRegionWriter.openLiveArchive(lcaFile)) {
            assertEquals(1, reopened.crashFooterRepairs(), "Must detect and repair missing LCA1_END footer canary");
            assertEquals(1, reopened.liveSlabScanReconstructions(), "Must reconstruct chunk (1,0) pointer via Slab Reverse-Scan");
            assertFalse(reopened.selfHealingDiagnostics().isEmpty());

            AnvilReader.ChunkEntry c00 = reopened.readChunkEntry(0, 0);
            AnvilReader.ChunkEntry c10 = reopened.readChunkEntry(1, 0);
            assertNotNull(c00, "Chunk (0,0) must be readable after torn syndrome reconciliation");
            assertNotNull(c10, "Chunk (1,0) must be readable after Tier-2 Mode B/A slab reverse-scan");
            assertEquals(103L, ((Number) c00.root.get("LastUpdate")).longValue());
            assertEquals(105L, ((Number) c10.root.get("LastUpdate")).longValue());
        }

        // Verify the repaired file on disk now has a valid LCA1_END footer canary
        byte[] healedDisk = Files.readAllBytes(lcaFile);
        assertEquals(LcaRegionReader.LCA_FOOTER_CANARY_MAGIC,
                (long) LcaRegionReader.LONG_BE_VIEW.get(healedDisk, healedDisk.length - 8));
    }

    @Test
    @DisplayName("Pillar 3 (Hot-Read SEC-DED & Tier-4 CoW Fallback): Live bit-flip self-healing with durable FileChannel write-back")
    void hotReadSecDedSelfHealingAndCoWRollback(@TempDir Path tempDir) throws IOException {
        Path lcaFile = tempDir.resolve("r.0.0.lca");
        boolean modeB = DynamicZstdBinding.isAvailable();

        try (LcaRegionWriter.LcaLiveArchive archive = LcaRegionWriter.createEmptyLiveArchive(
                lcaFile, 0, 0, -4, 24, 4671, modeB, LcaRegionReader.STORAGE_KIND_REGION_VOXEL, 4096)) {
            // Save two CoW generations of chunk (0,0) and one of chunk (1,0)
            archive.saveChunkCoW(0, 0, buildTestChunk(0, 0, "minecraft:stone", 2));
            archive.saveChunkCoW(0, 0, buildTestChunk(0, 0, "minecraft:diamond_block", 3));
            archive.saveChunkCoW(1, 0, buildTestChunk(1, 0, "minecraft:gold_block", 4));

            // 1. Inject a 1-bit flip into chunk (1,0)'s pointer in the on-disk pointer directory while the region is hot
            int ptr10Off = LcaRegionReader.POINTER_DIRECTORY_OFFSET + 8;
            try (FileChannel extCh = FileChannel.open(lcaFile, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                ByteBuffer bb = ByteBuffer.allocate(8);
                extCh.read(bb, ptr10Off);
                bb.flip();
                long origPtr10 = bb.getLong(0);
                // Flip bit 30 (shifts slabOffset35 by +2 slabs, triggering out-of-bounds or wrong slab on hot read)
                ByteBuffer corruptBb = ByteBuffer.allocate(8).putLong(0, origPtr10 ^ (1L << 30));
                extCh.write(corruptBb, ptr10Off);

                if (modeB) {
                    // Also inject a 1-bit flip into chunk (1,0)'s uncompressed Mode B ChunkFrame header on disk
                    long slabByteOff = (origPtr10 >>> 29) << LcaRegionReader.SLAB_SHIFT;
                    ByteBuffer hdrByte = ByteBuffer.allocate(1);
                    extCh.read(hdrByte, slabByteOff + 5);
                    hdrByte.put(0, (byte) (hdrByte.get(0) ^ 0x04));
                    hdrByte.flip();
                    extCh.write(hdrByte, slabByteOff + 5);
                }
            }

            // Also flip bit 30 in the cached file header / live pointer so hot read exercises inline Tier-1 SEC-DED
            byte[] nbt10 = archive.readChunkNbtBytes(1, 0);
            assertNotNull(nbt10);
            LinkedHashMap<String, Object> decoded10 = Nbt.readRootCompound(nbt10);
            assertEquals(104L, ((Number) decoded10.get("LastUpdate")).longValue());
            if (modeB) {
                assertTrue(archive.liveChunkHeaderHealedWords() >= 1,
                        "Hot read must heal 1-bit flip in Mode B ChunkFrame header and write back to channel");
            }

            // 2. Corrupt the latest CoW slab of chunk (0,0) on disk beyond SEC-DED repair (wipe its header + Zstd body)
            try (FileChannel extCh = FileChannel.open(lcaFile, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                ByteBuffer bb = ByteBuffer.allocate(8);
                extCh.read(bb, LcaRegionReader.POINTER_DIRECTORY_OFFSET);
                long latestPtr00 = bb.getLong(0);
                long latestSlabOff = (latestPtr00 >>> 29) << LcaRegionReader.SLAB_SHIFT;
                byte[] zeroes = new byte[128];
                extCh.write(ByteBuffer.wrap(zeroes), latestSlabOff);
            }

            // Reading chunk (0,0) must automatically trigger Tier-4 CoW rollback to generation 1!
            AnvilReader.ChunkEntry recovered00 = archive.readChunkEntry(0, 0);
            assertNotNull(recovered00, "Tier-4 CoW rollback must recover chunk (0,0) from previous generation slab");
            assertEquals(102L, ((Number) recovered00.root.get("LastUpdate")).longValue(),
                    "Recovered chunk must match generation 1 LastUpdate");
            assertEquals(1, archive.liveCoWRollbacks());
        }
    }

    @Test
    @DisplayName("Pillar 4 (LRU Eviction Compaction): Fragmented .lca archive is automatically compacted on LRU cache eviction")
    void fragmentedArchiveCompactsAutomaticallyOnLruEviction(@TempDir Path tempDir) throws IOException {
        // Configure storage with maxOpenRegions=1 and compactFragmentationThreshold=0.20
        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(
                tempDir,
                LcaRegionReader.STORAGE_KIND_REGION_VOXEL,
                -4,
                24,
                4671,
                false,
                false,
                false,
                1,
                0.20)) {
            LcaRegionWriter.LcaLiveArchive r00 = storage.getOrOpenRegion(0, 0, true);
            r00.reserveCoWHeadroomSlabs(200); // Inject 200 free 256B slabs (51.2 KiB headroom)
            r00.saveChunkCoW(0, 0, buildTestChunk(0, 0, "minecraft:stone", 2));
            int uncompactedSize = r00.activeByteLength();
            assertTrue(r00.fragmentationRatio() > 0.50);

            // Opening region (1, 0) evicts region (0, 0) from the 1-slot LRU cache -> triggers auto-compaction
            LcaRegionWriter.LcaLiveArchive r10 = storage.getOrOpenRegion(1, 0, true);
            r10.saveChunkCoW(0, 0, buildTestChunk(32, 0, "minecraft:dirt", 2));

            long compactedDiskSize = Files.size(tempDir.resolve("r.0.0.lca"));
            assertTrue(compactedDiskSize < uncompactedSize - (150 * LcaRegionReader.SLAB_BYTES),
                    "Evicted fragmented region r.0.0.lca must be compacted on disk ("
                            + compactedDiskSize + " < " + uncompactedSize + ")");
        }
    }

    @Test
    @DisplayName("Additional Edge Branches: In-memory hot-read SEC-DED, FileSession pointer healing, healRegionBitRot footer, and biome/saveChunkInitial rollback")
    void inMemoryAndFileSessionHealingAndBiomeRollback(@TempDir Path tempDir) throws IOException {
        // 1. In-memory LcaLiveArchive hot-read pointer SEC-DED healing & healRegionBitRot footer canary repair
        LcaRegionWriter.LcaLiveArchive memArchive =
                LcaRegionWriter.createEmptyLiveArchive(0, 0, -4, 24, 4671, false, 1024);
        memArchive.saveChunkInitial(0, 0, buildTestChunk(0, 0, "minecraft:stone", 2));
        memArchive.saveChunkCoW(0, 0, buildTestChunk(0, 0, "minecraft:emerald_block", 3));
        byte[] rawMem = memArchive.liveBuffer();

        // Flip 1 bit in chunk (0,0) pointer in-memory and verify hasChunk/readChunkNbtBytes heal it inline
        long origPtr = (long) LcaRegionReader.LONG_BE_VIEW.get(rawMem, LcaRegionReader.POINTER_DIRECTORY_OFFSET);
        LcaRegionReader.LONG_BE_VIEW.set(rawMem, LcaRegionReader.POINTER_DIRECTORY_OFFSET, origPtr ^ (1L << 31));
        assertTrue(memArchive.hasChunk(0, 0));
        assertNotNull(memArchive.readChunkNbtBytes(0, 0));
        assertEquals(1, memArchive.liveSecDedHealedPointers());

        // Double-bit flip in in-memory pointer -> recovered via in-memory Tier-4/Tier-2 fallback
        LcaRegionReader.LONG_BE_VIEW.set(rawMem, LcaRegionReader.POINTER_DIRECTORY_OFFSET, origPtr ^ 0b11L);
        assertNotNull(memArchive.readChunkEntry(0, 0));

        // Corrupt trailing LCA1_END footer canary in byte array and verify healRegionBitRot repairs it
        byte[] serialized = memArchive.toByteArray();
        serialized[serialized.length - 1] ^= (byte) 0xFF;
        LcaRegionReader.RecoveryReport footerRep = LcaRegionReader.healRegionBitRot(serialized);
        assertTrue(footerRep.secDedHealedPointers() >= 1);
        assertEquals(LcaRegionReader.LCA_FOOTER_CANARY_MAGIC,
                (long) LcaRegionReader.LONG_BE_VIEW.get(serialized, serialized.length - 8));

        // 2. FileSession / CachedFileHeader inline 1-bit pointer SEC-DED healing on disk
        Path lcaFile = tempDir.resolve("r.0.0.lca");
        Files.write(lcaFile, serialized);
        LcaRegionReader.invalidateFileCache(lcaFile);
        try (FileChannel ch = FileChannel.open(lcaFile, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer bb = ByteBuffer.allocate(8);
            ch.read(bb, LcaRegionReader.POINTER_DIRECTORY_OFFSET);
            long diskPtr = bb.getLong(0);
            ch.write(ByteBuffer.allocate(8).putLong(0, diskPtr ^ (1L << 30)).flip(), LcaRegionReader.POINTER_DIRECTORY_OFFSET);
        }
        assertTrue(LcaRegionReader.isChunkGeneratedInFile(lcaFile, 0, 0));
        assertNotNull(LcaRegionReader.readChunkFromFile(lcaFile, 0, 0));

        // 3. saveChunkInitial & biome dictionary ENOSPC rollback
        long fileSize = Files.size(lcaFile);
        FileChannel rawCh = FileChannel.open(lcaFile, StandardOpenOption.READ, StandardOpenOption.WRITE);
        QuotaFaultInjectingFileChannel quotaCh = new QuotaFaultInjectingFileChannel(rawCh, Long.MAX_VALUE);
        try (LcaRegionWriter.LcaLiveArchive diskArc =
                new LcaRegionWriter.LcaLiveArchive(lcaFile, quotaCh, (int) fileSize)) {
            quotaCh.setMaxQuotaBytes(0L);
            LinkedHashMap<String, Object> biomeChunk = buildTestChunk(3, 0, "minecraft:stone", 2);
            assertThrows(IOException.class, () -> diskArc.saveChunkInitial(3, 0, biomeChunk));
            assertFalse(diskArc.hasChunk(3, 0));
            assertThrows(RuntimeException.class, () -> diskArc.registerOrGetBiomeId("minecraft:cherry_grove"));
        }

        // 4. Streaming Zstd frame without content-size header (expectedSize == 0) + maxDecompressedBytes guard
        if (DynamicZstdBinding.isAvailable()) {
            java.io.ByteArrayOutputStream zosBytes = new java.io.ByteArrayOutputStream();
            byte[] sample = new byte[2048];
            Arrays.fill(sample, (byte) 'A');
            try (com.github.luben.zstd.ZstdOutputStream zos = new com.github.luben.zstd.ZstdOutputStream(zosBytes)) {
                zos.write(sample);
            }
            byte[] streamFrame = zosBytes.toByteArray();
            assertArrayEquals(sample, DynamicZstdBinding.decompress(streamFrame, 0, streamFrame.length, 4096));
            assertThrows(CorruptRegionEntryException.class,
                    () -> DynamicZstdBinding.decompress(streamFrame, 0, streamFrame.length, 512));

            byte[] validFrame = com.github.luben.zstd.Zstd.compress(sample);
            validFrame[validFrame.length - 1] ^= (byte) 0xFF;
            assertThrows(CorruptRegionEntryException.class,
                    () -> DynamicZstdBinding.decompress(validFrame, 0, validFrame.length, 4096));
        }
        assertFalse(AnvilRegionOccupancyCache.isOccupied(null, 0, 0));
        assertFalse(AnvilRegionOccupancyCache.isCached(null));
        assertNull(AnvilSectorReader.readChunkEntry(null, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> AnvilSectorReader.readColumnProbe(null, 0, 0, 10, 5));
    }

    @Test
    @DisplayName("Mode A On-Disk Preservation & Zstd Level 0..22 Write Preference Separation")
    void modeAFileOpenPreservesOnDiskSemanticsAndSupportsZstdLevelVariation(@TempDir Path tempDir) throws IOException {
        Path modeAFile = tempDir.resolve("r.0.0.lca");
        try (LcaRegionWriter.LcaLiveArchive archive = LcaRegionWriter.createEmptyLiveArchive(
                modeAFile, 0, 0, -4, 24, 4671, 0, LcaRegionReader.STORAGE_KIND_REGION_VOXEL, 4096)) {
            archive.saveChunkCoW(0, 0, buildTestChunk(0, 0, "minecraft:stone", 8));
            archive.saveChunkCoW(1, 0, buildTestChunk(1, 0, "minecraft:oak_planks", 10));
            archive.flush();
        }

        // 1. Default 1-arg openLiveArchive(Path) on Mode A file inherits Mode A (zstdLevel = 0)
        try (LcaRegionWriter.LcaLiveArchive reopenedDefault = LcaRegionWriter.openLiveArchive(modeAFile)) {
            reopenedDefault.saveChunkCoW(3, 0, buildTestChunk(3, 0, "minecraft:dirt", 4));
            AnvilReader.ChunkEntry c30 = reopenedDefault.readChunkEntry(3, 0);
            assertNotNull(c30);
            assertEquals(3, c30.compressionType, "1-arg openLiveArchive on Mode A file must write Mode A chunks");
            reopenedDefault.removeChunk(3, 0);
            reopenedDefault.flush();
        }

        // 2. Simulate crash on Mode A file and reopen via LcaRegionFileStorage (default zstdLevel = 1)
        byte[] rawModeA = Files.readAllBytes(modeAFile);
        assertEquals(0, rawModeA[0x0B] & 8, "Mode A superblock must not have DEFAULT_ZSTD1 bit set");
        ByteBuffer.wrap(rawModeA).putLong(LcaRegionReader.POINTER_DIRECTORY_OFFSET + 8, 0xCAFE_BABE_DEAD_BEEFL);
        byte[] crashedModeA = Arrays.copyOf(rawModeA, rawModeA.length + 256);
        Arrays.fill(crashedModeA, rawModeA.length - 8, crashedModeA.length, (byte) 0x5A);
        Files.write(modeAFile, crashedModeA);

        try (LcaRegionFileStorage defaultStorage = new LcaRegionFileStorage(tempDir)) {
            LcaRegionWriter.LcaLiveArchive reopened = defaultStorage.getOrOpenRegion(0, 0, false);
            assertNotNull(reopened);
            assertEquals(0, reopened.liveChunkHeaderHealedWords(),
                    "Mode A slab scan must never run Mode B SEC-DED header healing on Mode A slabs");
            assertEquals(1, reopened.liveSlabScanReconstructions(),
                    "Mode A chunk (1,0) pointer must be reconstructed via Slab Reverse-Scan");

            AnvilReader.ChunkEntry c00 = reopened.readChunkEntry(0, 0);
            AnvilReader.ChunkEntry c10 = reopened.readChunkEntry(1, 0);
            assertNotNull(c00);
            assertNotNull(c10);
            assertEquals(3, c00.compressionType, "Existing Mode A chunk (0,0) must remain Mode A (compressionType=3)");
            assertEquals(3, c10.compressionType, "Reconstructed Mode A chunk (1,0) must have codec1=0 (compressionType=3)");
            assertEquals(108L, ((Number) c00.root.get("LastUpdate")).longValue());
            assertEquals(110L, ((Number) c10.root.get("LastUpdate")).longValue());
        }

        byte[] afterRecovery = Files.readAllBytes(modeAFile);
        assertEquals(0, afterRecovery[0x0B] & 8,
                "Superblock DEFAULT_ZSTD1 flag must remain 0 on Mode A file after recovery and close");

        // 3. Vary zstdLevel across 0..22 (e.g. 6 and 22 on Mode A file, and 0 on Mode B file)
        if (DynamicZstdBinding.isAvailable()) {
            try (LcaRegionWriter.LcaLiveArchive modeAWithLevel6 = LcaRegionWriter.openLiveArchive(modeAFile, 6)) {
                modeAWithLevel6.saveChunkCoW(2, 0, buildTestChunk(2, 0, "minecraft:deepslate", 6));
                modeAWithLevel6.flush();

                AnvilReader.ChunkEntry c00 = modeAWithLevel6.readChunkEntry(0, 0);
                AnvilReader.ChunkEntry c20 = modeAWithLevel6.readChunkEntry(2, 0);
                assertNotNull(c00);
                assertNotNull(c20);
                assertEquals(3, c00.compressionType, "Existing chunk (0,0) remains Mode A");
                assertEquals(5, c20.compressionType, "Newly written chunk (2,0) uses Mode B (Zstd-6)");
            }
            assertEquals(0, Files.readAllBytes(modeAFile)[0x0B] & 8,
                    "On-disk superblock flag must remain Mode A even when new chunks are written with zstdLevel=6");

            // 3b. Crash-recover a Mode B chunk (2,0) AND a Mode A chunk (1,0) inside the Mode A file
            //     (also inject a 1-bit flip into the Mode B chunk's uncompressed header word 0 to verify per-slab SEC-DED healing)
            byte[] mixedModeABytes = Files.readAllBytes(modeAFile);
            long ptr20BeforeCrash = ByteBuffer.wrap(mixedModeABytes).getLong(LcaRegionReader.POINTER_DIRECTORY_OFFSET + 16);
            int slab20ByteOff = (int) ((ptr20BeforeCrash >>> 29) << LcaRegionReader.SLAB_SHIFT);
            mixedModeABytes[slab20ByteOff] ^= 0x40; // 1-bit flip in Mode B self-tag magic inside Mode A file
            ByteBuffer.wrap(mixedModeABytes).putLong(LcaRegionReader.POINTER_DIRECTORY_OFFSET + 8, 0xCAFE_BABE_DEAD_BEEFL);
            ByteBuffer.wrap(mixedModeABytes).putLong(LcaRegionReader.POINTER_DIRECTORY_OFFSET + 16, 0xDEAD_BEEF_CAFE_BABEL);

            // Verify healRegionBitRot reconstructs mixed Mode A (codec1=0) and Mode B (codec1=1) pointers per slab
            byte[] mixedForBitRotHeal = Arrays.copyOf(mixedModeABytes, mixedModeABytes.length);
            LcaRegionReader.RecoveryReport mixedReport = LcaRegionReader.healRegionBitRot(mixedForBitRotHeal);
            assertEquals(2, mixedReport.slabScanReconstructedPointers(),
                    "healRegionBitRot must reconstruct both Mode A chunk (1,0) and Mode B chunk (2,0)");
            assertEquals(1, mixedReport.chunkHeaderSecDedHealedWords(),
                    "healRegionBitRot must heal the 1-bit flip in Mode B chunk (2,0) header inside Mode A file");
            AnvilReader.ChunkEntry healed10 = LcaRegionReader.INSTANCE.readChunk(mixedForBitRotHeal, 1, 0);
            AnvilReader.ChunkEntry healed20 = LcaRegionReader.INSTANCE.readChunk(mixedForBitRotHeal, 2, 0);
            assertNotNull(healed10);
            assertNotNull(healed20);
            assertEquals(3, healed10.compressionType, "healRegionBitRot must preserve codec1=0 on Mode A chunk (1,0)");
            assertEquals(5, healed20.compressionType, "healRegionBitRot must preserve codec1=1 on Mode B chunk (2,0)");
            assertEquals(106L, ((Number) healed20.root.get("LastUpdate")).longValue());

            // Verify open-time crash recovery reconstructs the Mode B chunk inside the Mode A file
            byte[] crashedMixedModeA = Arrays.copyOf(mixedModeABytes, mixedModeABytes.length + 256);
            Arrays.fill(crashedMixedModeA, mixedModeABytes.length - 8, crashedMixedModeA.length, (byte) 0x5A);
            Files.write(modeAFile, crashedMixedModeA);
            LcaRegionReader.invalidateFileCache(modeAFile);

            try (LcaRegionFileStorage defaultStorage = new LcaRegionFileStorage(tempDir)) {
                LcaRegionWriter.LcaLiveArchive recoveredMixed = defaultStorage.getOrOpenRegion(0, 0, false);
                assertNotNull(recoveredMixed);
                assertEquals(1, recoveredMixed.liveChunkHeaderHealedWords(),
                        "Open-time slab scan must heal 1-bit flip on Mode B chunk (2,0) header without touching Mode A slabs");
                assertEquals(2, recoveredMixed.liveSlabScanReconstructions(),
                        "Both Mode A chunk (1,0) and Mode B chunk (2,0) pointers must be reconstructed on open");

                AnvilReader.ChunkEntry rec00 = recoveredMixed.readChunkEntry(0, 0);
                AnvilReader.ChunkEntry rec10 = recoveredMixed.readChunkEntry(1, 0);
                AnvilReader.ChunkEntry rec20 = recoveredMixed.readChunkEntry(2, 0);
                assertNotNull(rec00);
                assertNotNull(rec10);
                assertNotNull(rec20);
                assertEquals(3, rec00.compressionType, "Chunk (0,0) remains Mode A");
                assertEquals(3, rec10.compressionType, "Reconstructed Mode A chunk (1,0) must have codec1=0");
                assertEquals(5, rec20.compressionType, "Reconstructed Mode B chunk (2,0) in Mode A file must have codec1=1");
                assertEquals(110L, ((Number) rec10.root.get("LastUpdate")).longValue());
                assertEquals(106L, ((Number) rec20.root.get("LastUpdate")).longValue());
            }

            // Now open a Mode B file with preferredZstdLevel = 0 (switch new writes to Mode A 0-LZ) and 22
            Path modeBFile = tempDir.resolve("r.1.0.lca");
            try (LcaRegionWriter.LcaLiveArchive modeB22 = LcaRegionWriter.createEmptyLiveArchive(
                    modeBFile, 1, 0, -4, 24, 4671, 22, LcaRegionReader.STORAGE_KIND_REGION_VOXEL, 4096)) {
                modeB22.saveChunkCoW(0, 0, buildTestChunk(32, 0, "minecraft:stone", 5));
                modeB22.flush();
            }
            assertEquals(8, Files.readAllBytes(modeBFile)[0x0B] & 8,
                    "Mode B file created with zstdLevel=22 must have DEFAULT_ZSTD1 set in superblock");

            try (LcaRegionWriter.LcaLiveArchive modeBWithLevel0 = LcaRegionWriter.openLiveArchive(modeBFile, 0)) {
                modeBWithLevel0.saveChunkCoW(1, 0, buildTestChunk(33, 0, "minecraft:granite", 7));
                modeBWithLevel0.flush();

                AnvilReader.ChunkEntry c00 = modeBWithLevel0.readChunkEntry(0, 0);
                AnvilReader.ChunkEntry c10 = modeBWithLevel0.readChunkEntry(1, 0);
                assertNotNull(c00);
                assertNotNull(c10);
                assertEquals(5, c00.compressionType, "Existing chunk (0,0) remains Mode B (Zstd-22)");
                assertEquals(3, c10.compressionType, "Newly written chunk (1,0) uses Mode A (0-LZ)");
            }
            assertEquals(8, Files.readAllBytes(modeBFile)[0x0B] & 8,
                    "On-disk superblock flag must remain Mode B even when new chunks are written with zstdLevel=0");

            // 3c. Crash-recover a Mode A chunk (1,0) and Mode B chunk (0,0) inside the Mode B file
            byte[] mixedModeBBytes = Files.readAllBytes(modeBFile);
            ByteBuffer.wrap(mixedModeBBytes).putLong(LcaRegionReader.POINTER_DIRECTORY_OFFSET, 0xCAFE_BABE_DEAD_BEEFL);
            ByteBuffer.wrap(mixedModeBBytes).putLong(LcaRegionReader.POINTER_DIRECTORY_OFFSET + 8, 0xDEAD_BEEF_CAFE_BABEL);
            byte[] crashedMixedModeB = Arrays.copyOf(mixedModeBBytes, mixedModeBBytes.length + 256);
            Arrays.fill(crashedMixedModeB, mixedModeBBytes.length - 8, crashedMixedModeB.length, (byte) 0x5A);
            Files.write(modeBFile, crashedMixedModeB);
            LcaRegionReader.invalidateFileCache(modeBFile);

            try (LcaRegionWriter.LcaLiveArchive recoveredModeB = LcaRegionWriter.openLiveArchive(modeBFile)) {
                assertEquals(0, recoveredModeB.liveChunkHeaderHealedWords(),
                        "Mode B file slab scan must not false-heal Mode A interior slabs");
                assertEquals(2, recoveredModeB.liveSlabScanReconstructions());
                AnvilReader.ChunkEntry c00 = recoveredModeB.readChunkEntry(0, 0);
                AnvilReader.ChunkEntry c10 = recoveredModeB.readChunkEntry(1, 0);
                assertNotNull(c00);
                assertNotNull(c10);
                assertEquals(5, c00.compressionType, "Reconstructed Mode B chunk (0,0) in Mode B file must have codec1=1");
                assertEquals(3, c10.compressionType, "Reconstructed Mode A chunk (1,0) in Mode B file must have codec1=0");
                assertEquals(105L, ((Number) c00.root.get("LastUpdate")).longValue());
                assertEquals(107L, ((Number) c10.root.get("LastUpdate")).longValue());
            }
        }
    }
}
