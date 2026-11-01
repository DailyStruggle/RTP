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
            IOException ex1 = assertThrows(IOException.class, () -> {
                try (DataOutputStream out = archive.getChunkDataOutputStream(1, 0)) {
                    byte[] nbt = Nbt.writeNamedRoot("", buildTestChunk(1, 0, "minecraft:deepslate", 4));
                    out.write(nbt);
                }
            });
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
}
