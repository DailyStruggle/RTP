package io.github.dailystruggle.anvil;

import com.github.luben.zstd.Zstd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Empirical magnetic-HDD benchmark comparing Vanilla `.mca`, Luminol/Leaf BufferedLinear
 * (`.b_linear` v3), current `.lca` v3 positional I/O, and sequential/write-back coalesced `.lca`
 * intake/write procedures on real Minecraft Overworld region data.
 *
 * <p>When {@code -Dhdd.bench.dir=/mnt/storage/.leafanvil-hdd-bench} is supplied, runs full
 * cold-cache ({@code posix_fadvise(POSIX_FADV_DONTNEED)}) and durable ({@code fdatasync})
 * benchmarks directly on the target magnetic hard drive and cleans up its scratch directory.
 * Without the property, runs a fast correctness smoke pass in {@code @TempDir}.
 *
 * @governedBy anvil-api-ADR-002
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
@DisplayName("HDD Empirical Benchmark: BufferedLinear (.b_linear) vs Vanilla (.mca) vs LCA (.lca)")
class HddBufferedLinearVsMcaBenchmarkTest {

    private static final int SECTOR_BYTES = 4096;

    private record ChunkSample(int rx, int rz, byte[] rawNbt) {}

    private static Path resolveOverworldFixture() {
        Path[] candidates = new Path[] {
            Path.of("resource/LeafRTP/rtp-core/src/test/resources/fixtures/regions/overworld_mountain_r.0.0.mca"),
            Path.of("../../resource/LeafRTP/rtp-core/src/test/resources/fixtures/regions/overworld_mountain_r.0.0.mca"),
            Path.of("src/test/resources/anvil/real/26_3_R1/r.0.0.mca"),
        };
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                return p.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("Could not locate real .mca benchmark fixture from " + Path.of("").toAbsolutePath());
    }

    private static List<ChunkSample> extractChunksFromMca(byte[] mcaBytes, int limit) throws IOException {
        List<ChunkSample> out = new ArrayList<>();
        for (int rz = 0; rz < 32 && out.size() < limit; rz++) {
            for (int rx = 0; rx < 32 && out.size() < limit; rx++) {
                if (!AnvilReader.INSTANCE.isChunkGenerated(mcaBytes, rx, rz)) {
                    continue;
                }
                int idx = (rx & 31) + ((rz & 31) << 5);
                int loc = ((mcaBytes[idx * 4] & 0xFF) << 24)
                        | ((mcaBytes[idx * 4 + 1] & 0xFF) << 16)
                        | ((mcaBytes[idx * 4 + 2] & 0xFF) << 8)
                        | (mcaBytes[idx * 4 + 3] & 0xFF);
                int sectorOff = (loc >>> 8) * SECTOR_BYTES;
                if (sectorOff < 8192 || sectorOff + 5 > mcaBytes.length) {
                    continue;
                }
                int len = ((mcaBytes[sectorOff] & 0xFF) << 24)
                        | ((mcaBytes[sectorOff + 1] & 0xFF) << 16)
                        | ((mcaBytes[sectorOff + 2] & 0xFF) << 8)
                        | (mcaBytes[sectorOff + 3] & 0xFF);
                int compType = mcaBytes[sectorOff + 4] & 0xFF;
                if (compType != 2 || len <= 1 || sectorOff + 4 + len > mcaBytes.length) {
                    continue;
                }
                byte[] inflated = inflateZlib(mcaBytes, sectorOff + 5, len - 1);
                out.add(new ChunkSample(rx, rz, inflated));
            }
        }
        return out;
    }

    private static byte[] inflateZlib(byte[] src, int off, int len) throws IOException {
        Inflater inf = new Inflater();
        inf.setInput(src, off, len);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(len * 3);
        byte[] tmp = new byte[8192];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(tmp);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) {
                        break;
                    }
                } else {
                    baos.write(tmp, 0, n);
                }
            }
        } catch (Exception e) {
            throw new IOException("Zlib inflate failed", e);
        } finally {
            inf.end();
        }
        return baos.toByteArray();
    }

    private static byte[] deflateZlib(byte[] raw) {
        Deflater def = new Deflater(Deflater.DEFAULT_COMPRESSION);
        def.setInput(raw);
        def.finish();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(raw.length / 2);
        byte[] tmp = new byte[8192];
        while (!def.finished()) {
            int n = def.deflate(tmp);
            baos.write(tmp, 0, n);
        }
        def.end();
        return baos.toByteArray();
    }

    private static void evictPageCache(Path file) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                "python3",
                "-c",
                "import os,sys; fd=os.open(sys.argv[1], os.O_RDONLY); "
                        + "os.posix_fadvise(fd, 0, 0, os.POSIX_FADV_DONTNEED); os.close(fd)",
                file.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            int rc = p.waitFor();
            if (rc != 0) {
                throw new IOException("posix_fadvise failed rc=" + rc);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during posix_fadvise", e);
        }
    }

    private static void writeFully(FileChannel ch, long pos, ByteBuffer buf) throws IOException {
        long p = pos;
        while (buf.hasRemaining()) {
            int n = ch.write(buf, p);
            if (n <= 0) {
                throw new IOException("Short write at " + p);
            }
            p += n;
        }
    }

    private static void readFully(FileChannel ch, long pos, ByteBuffer buf) throws IOException {
        long p = pos;
        while (buf.hasRemaining()) {
            int n = ch.read(buf, p);
            if (n < 0) {
                throw new IOException("Unexpected EOF at " + p);
            }
            p += n;
        }
    }

    /**
     * Writes chunks in Vanilla `.mca` layout (8 KiB header + 4 KiB sector-aligned Zlib payloads),
     * mimicking Minecraft's {@code RegionFile} with optional per-chunk {@code fdatasync}
     * ({@code sync-chunk-writes=true}).
     */
    private static long writeVanillaMca(
            Path file, List<ChunkSample> chunks, boolean syncPerChunk, int[] outSyscalls
    ) throws IOException {
        Files.deleteIfExists(file);
        int syscalls = 0;
        long t0 = System.nanoTime();
        try (FileChannel ch = FileChannel.open(
                file,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            writeFully(ch, 0L, ByteBuffer.wrap(new byte[8192]));
            syscalls++;
            int nextSector = 2;
            byte[] headerEntry = new byte[4];
            byte[] tsEntry = new byte[4];
            for (ChunkSample c : chunks) {
                byte[] deflated = deflateZlib(c.rawNbt);
                int payloadLen = 5 + deflated.length;
                int sectors = (payloadLen + SECTOR_BYTES - 1) / SECTOR_BYTES;
                byte[] sectorBuf = new byte[sectors * SECTOR_BYTES];
                int chunkStreamLen = deflated.length + 1;
                sectorBuf[0] = (byte) (chunkStreamLen >>> 24);
                sectorBuf[1] = (byte) (chunkStreamLen >>> 16);
                sectorBuf[2] = (byte) (chunkStreamLen >>> 8);
                sectorBuf[3] = (byte) chunkStreamLen;
                sectorBuf[4] = 2; // Zlib
                System.arraycopy(deflated, 0, sectorBuf, 5, deflated.length);

                long dataOff = (long) nextSector * SECTOR_BYTES;
                writeFully(ch, dataOff, ByteBuffer.wrap(sectorBuf));
                syscalls++;

                int idx = (c.rx & 31) + ((c.rz & 31) << 5);
                int loc = (nextSector << 8) | (sectors & 0xFF);
                headerEntry[0] = (byte) (loc >>> 24);
                headerEntry[1] = (byte) (loc >>> 16);
                headerEntry[2] = (byte) (loc >>> 8);
                headerEntry[3] = (byte) loc;
                writeFully(ch, (long) idx * 4L, ByteBuffer.wrap(headerEntry));
                syscalls++;

                int ts = (int) (System.currentTimeMillis() / 1000L);
                tsEntry[0] = (byte) (ts >>> 24);
                tsEntry[1] = (byte) (ts >>> 16);
                tsEntry[2] = (byte) (ts >>> 8);
                tsEntry[3] = (byte) ts;
                writeFully(ch, 4096L + (long) idx * 4L, ByteBuffer.wrap(tsEntry));
                syscalls++;

                if (syncPerChunk) {
                    ch.force(false);
                    syscalls++;
                }
                nextSector += sectors;
            }
            if (!syncPerChunk) {
                ch.force(false);
                syscalls++;
            }
        }
        outSyscalls[0] = syscalls;
        return System.nanoTime() - t0;
    }

    /**
     * Writes chunks in Luminol/Leaf BufferedLinear v3 (`.b_linear`) layout:
     * buffers all dirty chunks in memory into 16 buckets of 64 chunks, compresses each non-empty
     * bucket with Zstd level 1, and flushes the entire file in ONE sequential write + ONE fdatasync.
     */
    private static long writeBufferedLinearV3(
            Path file, List<ChunkSample> chunks, int zstdLevel, int[] outSyscalls
    ) throws IOException {
        Files.deleteIfExists(file);
        long t0 = System.nanoTime();
        byte[][] chunkTable = new byte[1024][];
        for (ChunkSample c : chunks) {
            chunkTable[(c.rx & 31) + ((c.rz & 31) << 5)] = c.rawNbt;
        }
        byte[][] compressedBuckets = new byte[16][];
        int[] rawBucketLens = new int[16];
        long[] bucketOffsets = new long[16];
        long cursor = 142L;
        for (int b = 0; b < 16; b++) {
            boolean any = false;
            int base = b << 6;
            for (int i = 0; i < 64; i++) {
                if (chunkTable[base + i] != null) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                continue;
            }
            ByteArrayOutputStream rawOut = new ByteArrayOutputStream(65536);
            try (DataOutputStream dos = new DataOutputStream(rawOut)) {
                for (int i = 0; i < 64; i++) {
                    byte[] nbt = chunkTable[base + i];
                    if (nbt != null) {
                        dos.writeInt(16 + nbt.length);
                        dos.writeInt(nbt.length);
                        dos.writeLong(1700000000L);
                        dos.writeInt(0);
                        dos.write(nbt);
                    } else {
                        dos.writeInt(0);
                    }
                }
            }
            byte[] raw = rawOut.toByteArray();
            byte[] comp = Zstd.compress(raw, zstdLevel);
            rawBucketLens[b] = raw.length;
            compressedBuckets[b] = comp;
            bucketOffsets[b] = cursor;
            cursor += 8L + comp.length;
        }

        ByteArrayOutputStream fileOut = new ByteArrayOutputStream((int) cursor);
        try (DataOutputStream dos = new DataOutputStream(fileOut)) {
            dos.writeLong(LinearRegionReader.BLINEAR_SIGNATURE);
            dos.writeByte(3); // v3 bucketed
            dos.writeByte(zstdLevel);
            dos.writeInt(0x0721);
            for (int b = 0; b < 16; b++) {
                dos.writeLong(bucketOffsets[b]);
            }
            for (int b = 0; b < 16; b++) {
                if (compressedBuckets[b] != null) {
                    dos.writeInt(rawBucketLens[b]);
                    dos.writeInt(compressedBuckets[b].length);
                    dos.write(compressedBuckets[b]);
                }
            }
        }
        byte[] allBytes = fileOut.toByteArray();
        try (FileChannel ch = FileChannel.open(
                file,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            writeFully(ch, 0L, ByteBuffer.wrap(allBytes));
            ch.force(false);
        }
        outSyscalls[0] = 2; // 1 sequential write + 1 fdatasync
        return System.nanoTime() - t0;
    }

    /**
     * Reads a random subset of chunks from `.mca` using vanilla per-chunk positional pread + Zlib.
     */
    private static long readVanillaMcaPositional(Path file, List<ChunkSample> order) throws IOException {
        long t0 = System.nanoTime();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer hdr = ByteBuffer.allocate(8192);
            readFully(ch, 0L, hdr);
            byte[] hdrBytes = hdr.array();
            for (ChunkSample c : order) {
                int idx = (c.rx & 31) + ((c.rz & 31) << 5);
                int loc = ((hdrBytes[idx * 4] & 0xFF) << 24)
                        | ((hdrBytes[idx * 4 + 1] & 0xFF) << 16)
                        | ((hdrBytes[idx * 4 + 2] & 0xFF) << 8)
                        | (hdrBytes[idx * 4 + 3] & 0xFF);
                int sectorOff = (loc >>> 8) * SECTOR_BYTES;
                int sectorCount = loc & 0xFF;
                ByteBuffer buf = ByteBuffer.allocate(sectorCount * SECTOR_BYTES);
                readFully(ch, sectorOff, buf);
                byte[] arr = buf.array();
                int len = ((arr[0] & 0xFF) << 24)
                        | ((arr[1] & 0xFF) << 16)
                        | ((arr[2] & 0xFF) << 8)
                        | (arr[3] & 0xFF);
                byte[] nbt = inflateZlib(arr, 5, len - 1);
                assertTrue(nbt.length > 0);
            }
        }
        return System.nanoTime() - t0;
    }

    /**
     * Reads chunks from `.b_linear` v3 using bucket-cached sequential reads (up to 16 bucket reads total).
     */
    private static long readBufferedLinearV3Bucketed(Path file, List<ChunkSample> order) throws IOException {
        long t0 = System.nanoTime();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer hdr = ByteBuffer.allocate(142);
            readFully(ch, 0L, hdr);
            hdr.flip();
            hdr.position(14);
            long[] offsets = new long[16];
            for (int b = 0; b < 16; b++) {
                offsets[b] = hdr.getLong();
            }
            byte[][][] bucketCache = new byte[16][][];
            for (ChunkSample c : order) {
                int idx = (c.rx & 31) + ((c.rz & 31) << 5);
                int b = idx >> 6;
                if (bucketCache[b] == null) {
                    long off = offsets[b];
                    ByteBuffer lens = ByteBuffer.allocate(8);
                    readFully(ch, off, lens);
                    lens.flip();
                    int rawLen = lens.getInt();
                    int compLen = lens.getInt();
                    ByteBuffer compBuf = ByteBuffer.allocate(compLen);
                    readFully(ch, off + 8L, compBuf);
                    byte[] raw = Zstd.decompress(compBuf.array(), rawLen);
                    byte[][] parsed = new byte[64][];
                    ByteBuffer rbb = ByteBuffer.wrap(raw);
                    for (int i = 0; i < 64; i++) {
                        int secSize = rbb.getInt();
                        if (secSize > 0) {
                            int uncompLen = rbb.getInt();
                            rbb.getLong(); // ts
                            rbb.getInt();  // xxhash32
                            byte[] nbt = new byte[uncompLen];
                            rbb.get(nbt);
                            parsed[i] = nbt;
                        }
                    }
                    bucketCache[b] = parsed;
                }
                byte[] nbt = bucketCache[b][idx & 63];
                assertNotNull(nbt);
            }
        }
        return System.nanoTime() - t0;
    }

    @Test
    @DisplayName("Measure HDD write & cold-read latency across .mca, .b_linear v3, current .lca, and sequential .lca")
    void measureHddPerformance(@TempDir Path tempDir) throws Exception {
        String customDirProp = System.getProperty("hdd.bench.dir", System.getenv("HDD_BENCH_DIR"));
        boolean realHddMode = customDirProp != null && !customDirProp.isBlank();
        Path workDir = realHddMode ? Path.of(customDirProp) : tempDir;
        Files.createDirectories(workDir);

        Path fixture = resolveOverworldFixture();
        byte[] mcaBytes = Files.readAllBytes(fixture);
        int maxChunks = realHddMode ? 1024 : 32;
        List<ChunkSample> allChunks = extractChunksFromMca(mcaBytes, maxChunks);
        assertTrue(allChunks.size() >= 16, "Expected real chunks in fixture");

        // Prepare random read order (simulating scattered player movement / RTP probes)
        List<ChunkSample> readOrder = new ArrayList<>(allChunks);
        Collections.shuffle(readOrder, new Random(1337));
        int readCount = realHddMode ? Math.min(256, readOrder.size()) : Math.min(16, readOrder.size());
        List<ChunkSample> coldReadSubset = readOrder.subList(0, readCount);

        Path mcaFileAsync = workDir.resolve("bench_async.mca");
        Path mcaFileSync = workDir.resolve("bench_sync.mca");
        Path bLinearFile = workDir.resolve("bench.b_linear");
        Path lcaCurrentFile = workDir.resolve("bench_current.lca");
        Path lcaBufferedFile = workDir.resolve("bench_buffered.lca");

        try {
            // --- 1. WRITE BENCHMARK ---
            int[] mcaAsyncSys = new int[1];
            long mcaAsyncNs = writeVanillaMca(mcaFileAsync, allChunks, false, mcaAsyncSys);

            // For sync-chunk-writes=true on real HDD, test 64 chunks and extrapolate to keep wall-clock < 5s
            int syncSampleSize = realHddMode ? Math.min(64, allChunks.size()) : Math.min(8, allChunks.size());
            int[] mcaSyncSys = new int[1];
            long mcaSyncNs = writeVanillaMca(mcaFileSync, allChunks.subList(0, syncSampleSize), true, mcaSyncSys);

            int[] bLinearSys = new int[1];
            long bLinearWriteNs = writeBufferedLinearV3(bLinearFile, allChunks, 1, bLinearSys);

            // Current unbuffered .lca (headerOnlyChannelMode=true -> immediate positional pwrite per chunk)
            Files.deleteIfExists(lcaCurrentFile);
            long lcaCurrT0 = System.nanoTime();
            long lcaCurrWrites;
            try (LcaRegionWriter.LcaLiveArchive archive = LcaRegionWriter.createEmptyLiveArchive(
                    lcaCurrentFile, 0, 0, -4, 24, 3953, true)) {
                for (ChunkSample c : allChunks) {
                    archive.saveChunkNbtBytes(c.rx, c.rz, c.rawNbt);
                }
                archive.flush();
                lcaCurrWrites = archive.diskChannelWriteCount();
            }
            try (FileChannel ch = FileChannel.open(lcaCurrentFile, StandardOpenOption.WRITE)) {
                ch.force(false);
            }
            long lcaCurrWriteNs = System.nanoTime() - lcaCurrT0;

            // Optimized Buffered-Write-Back .lca (real LcaLiveArchive with coalesced batch flush)
            Files.deleteIfExists(lcaBufferedFile);
            long lcaBufT0 = System.nanoTime();
            long lcaBufWrites;
            try (LcaRegionWriter.LcaLiveArchive archive = LcaRegionWriter.createEmptyLiveArchive(
                    lcaBufferedFile, 0, 0, -4, 24, 3953, true)) {
                archive.configureBufferedWriteBack(true, 1024, 16 * 1024 * 1024);
                for (ChunkSample c : allChunks) {
                    archive.saveChunkNbtBytes(c.rx, c.rz, c.rawNbt);
                }
                archive.flush();
                lcaBufWrites = archive.diskChannelWriteCount();
            }
            try (FileChannel ch = FileChannel.open(lcaBufferedFile, StandardOpenOption.WRITE)) {
                ch.force(false);
            }
            long lcaBufWriteNs = System.nanoTime() - lcaBufT0;

            // --- 2. OVERWRITE CHURN BENCHMARK (4x re-save of 64 hot chunks before flush) ---
            int churnUnique = Math.min(64, allChunks.size());
            List<ChunkSample> churnHot = allChunks.subList(0, churnUnique);
            long unbufChurnT0 = System.nanoTime();
            long unbufChurnWrites;
            try (LcaRegionWriter.LcaLiveArchive archive = LcaRegionWriter.openLiveArchive(lcaCurrentFile, 1)) {
                long before = archive.diskChannelWriteCount();
                for (int round = 0; round < 4; round++) {
                    for (ChunkSample c : churnHot) {
                        archive.saveChunkNbtBytes(c.rx, c.rz, c.rawNbt);
                    }
                }
                archive.flush();
                unbufChurnWrites = archive.diskChannelWriteCount() - before;
            }
            long unbufChurnNs = System.nanoTime() - unbufChurnT0;

            long bufChurnT0 = System.nanoTime();
            long bufChurnWrites;
            long bufCoalesced;
            try (LcaRegionWriter.LcaLiveArchive archive = LcaRegionWriter.openLiveArchive(lcaBufferedFile, 1)) {
                archive.configureBufferedWriteBack(true, 256, 4 * 1024 * 1024);
                long before = archive.diskChannelWriteCount();
                for (int round = 0; round < 4; round++) {
                    for (ChunkSample c : churnHot) {
                        archive.saveChunkNbtBytes(c.rx, c.rz, c.rawNbt);
                    }
                }
                archive.flush();
                bufChurnWrites = archive.diskChannelWriteCount() - before;
                bufCoalesced = archive.coalescedWriteCount();
            }
            long bufChurnNs = System.nanoTime() - bufChurnT0;

            // --- 3. COLD-CACHE READ BENCHMARK (posix_fadvise POSIX_FADV_DONTNEED) ---
            if (realHddMode) {
                evictPageCache(mcaFileAsync);
                evictPageCache(bLinearFile);
                evictPageCache(lcaCurrentFile);
                evictPageCache(lcaBufferedFile);
            }

            long mcaReadNs = readVanillaMcaPositional(mcaFileAsync, coldReadSubset);
            long bLinearReadNs = readBufferedLinearV3Bucketed(bLinearFile, coldReadSubset);

            // Unbuffered .lca positional read (headerOnlyChannelMode=true, read-ahead disabled)
            LcaRegionReader.invalidateFileCache(lcaCurrentFile);
            long lcaPosT0 = System.nanoTime();
            try (LcaRegionWriter.LcaLiveArchive live = LcaRegionWriter.openLiveArchive(lcaCurrentFile, 1)) {
                for (ChunkSample c : coldReadSubset) {
                    byte[] nbt = live.readChunkNbtBytes(c.rx, c.rz);
                    assertNotNull(nbt);
                }
            }
            long lcaPosReadNs = System.nanoTime() - lcaPosT0;

            // Adaptive Sequential Read-Ahead .lca read (real LcaLiveArchive with read-ahead enabled)
            LcaRegionReader.invalidateFileCache(lcaBufferedFile);
            long lcaSeqT0 = System.nanoTime();
            long readAheadPromotions;
            try (LcaRegionWriter.LcaLiveArchive live = LcaRegionWriter.openLiveArchive(lcaBufferedFile, 1)) {
                live.configureSequentialReadAhead(true, 4, 16 * 1024 * 1024);
                for (ChunkSample c : coldReadSubset) {
                    byte[] nbt = live.readChunkNbtBytes(c.rx, c.rz);
                    assertNotNull(nbt);
                }
                readAheadPromotions = live.sequentialReadAheadCount();
            }
            long lcaSeqReadNs = System.nanoTime() - lcaSeqT0;
            assertTrue(readAheadPromotions >= 1L, "Expected adaptive sequential read-ahead to trigger");

            // Verify byte sizes
            long mcaSize = Files.size(mcaFileAsync);
            long bLinearSize = Files.size(bLinearFile);
            long lcaSize = Files.size(lcaBufferedFile);

            System.out.printf(Locale.ROOT,
                    "%n=== HDD BENCHMARK (%s, chunks=%d, coldReads=%d) ===%n"
                            + "File sizes:      .mca=%,d B | .b_linear(zstd-1)=%,d B | .lca(v3)=%,d B%n"
                            + "WRITE (async+fsync):%n"
                            + "  Vanilla .mca:              %8.2f ms  (%d syscalls)%n"
                            + "  BufferedLinear v3:         %8.2f ms  (%d syscalls)%n"
                            + "  Unbuffered .lca (pos):     %8.2f ms  (%d channel writes + fsync)%n"
                            + "  Buffered .lca (batched):   %8.2f ms  (%d channel writes + fsync)%n"
                            + "WRITE (sync-chunk-writes=true, %d chunks):%n"
                            + "  Vanilla .mca (DSYNC):      %8.2f ms  (%.2f ms/chunk -> est %.0f ms for %d chunks)%n"
                            + "OVERWRITE CHURN (4x %d hot chunks + flush):%n"
                            + "  Unbuffered .lca (pos):     %8.2f ms  (%d channel writes)%n"
                            + "  Buffered .lca (coalesced): %8.2f ms  (%d channel writes, %d coalesced)%n"
                            + "COLD-CACHE RANDOM READ (%d chunks, posix_fadvise DONTNEED=%s):%n"
                            + "  Vanilla .mca (pos):        %8.2f ms  (%.2f ms/chunk)%n"
                            + "  BufferedLinear v3:         %8.2f ms  (%.2f ms/chunk)%n"
                            + "  Unbuffered .lca (pos):     %8.2f ms  (%.2f ms/chunk)%n"
                            + "  Read-Ahead .lca (seq):     %8.2f ms  (%.2f ms/chunk, promotions=%d)%n"
                            + "========================================================%n",
                    workDir, allChunks.size(), readCount,
                    mcaSize, bLinearSize, lcaSize,
                    mcaAsyncNs / 1e6, mcaAsyncSys[0],
                    bLinearWriteNs / 1e6, bLinearSys[0],
                    lcaCurrWriteNs / 1e6, lcaCurrWrites,
                    lcaBufWriteNs / 1e6, lcaBufWrites,
                    syncSampleSize,
                    mcaSyncNs / 1e6, (mcaSyncNs / 1e6) / syncSampleSize,
                    ((mcaSyncNs / 1e6) / syncSampleSize) * allChunks.size(), allChunks.size(),
                    churnUnique,
                    unbufChurnNs / 1e6, unbufChurnWrites,
                    bufChurnNs / 1e6, bufChurnWrites, bufCoalesced,
                    readCount, realHddMode,
                    mcaReadNs / 1e6, (mcaReadNs / 1e6) / readCount,
                    bLinearReadNs / 1e6, (bLinearReadNs / 1e6) / readCount,
                    lcaPosReadNs / 1e6, (lcaPosReadNs / 1e6) / readCount,
                    lcaSeqReadNs / 1e6, (lcaSeqReadNs / 1e6) / readCount, readAheadPromotions);
        } finally {
            Files.deleteIfExists(mcaFileAsync);
            Files.deleteIfExists(mcaFileSync);
            Files.deleteIfExists(bLinearFile);
            Files.deleteIfExists(lcaCurrentFile);
            Files.deleteIfExists(lcaBufferedFile);
            if (realHddMode) {
                Files.deleteIfExists(workDir);
            }
        }
    }

    private record ProcIoSnapshot(long wchar, long syscw, long writeBytes) {
        static ProcIoSnapshot read() {
            Path procIo = Path.of("/proc/self/io");
            if (!Files.isReadable(procIo)) {
                return new ProcIoSnapshot(-1L, -1L, -1L);
            }
            try {
                long wchar = 0L;
                long syscw = 0L;
                long writeBytes = 0L;
                for (String line : Files.readAllLines(procIo)) {
                    if (line.startsWith("wchar:")) {
                        wchar = Long.parseLong(line.substring("wchar:".length()).trim());
                    } else if (line.startsWith("syscw:")) {
                        syscw = Long.parseLong(line.substring("syscw:".length()).trim());
                    } else if (line.startsWith("write_bytes:")) {
                        writeBytes = Long.parseLong(line.substring("write_bytes:".length()).trim());
                    }
                }
                return new ProcIoSnapshot(wchar, syscw, writeBytes);
            } catch (IOException e) {
                return new ProcIoSnapshot(-1L, -1L, -1L);
            }
        }

        ProcIoSnapshot deltaFrom(ProcIoSnapshot before) {
            if (wchar < 0 || before.wchar < 0) {
                return new ProcIoSnapshot(-1L, -1L, -1L);
            }
            return new ProcIoSnapshot(
                    wchar - before.wchar,
                    syscw - before.syscw,
                    writeBytes - before.writeBytes);
        }
    }

    /**
     * Updates a subset of chunks inside an existing Vanilla `.mca` file (in-place when the
     * compressed payload fits its existing 4 KiB sector span, otherwise appending fresh sectors),
     * updating only the dirty chunks' sectors + 4-byte location + 4-byte timestamp entries.
     */
    private static void updateVanillaMcaSubset(
            Path file, List<ChunkSample> dirtyChunks, boolean syncPerChunk
    ) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            int nextSector = (int) ((ch.size() + SECTOR_BYTES - 1L) / SECTOR_BYTES);
            byte[] headerEntry = new byte[4];
            byte[] tsEntry = new byte[4];
            for (ChunkSample c : dirtyChunks) {
                byte[] deflated = deflateZlib(c.rawNbt);
                int payloadLen = 5 + deflated.length;
                int sectors = (payloadLen + SECTOR_BYTES - 1) / SECTOR_BYTES;
                byte[] sectorBuf = new byte[sectors * SECTOR_BYTES];
                int chunkStreamLen = deflated.length + 1;
                sectorBuf[0] = (byte) (chunkStreamLen >>> 24);
                sectorBuf[1] = (byte) (chunkStreamLen >>> 16);
                sectorBuf[2] = (byte) (chunkStreamLen >>> 8);
                sectorBuf[3] = (byte) chunkStreamLen;
                sectorBuf[4] = 2; // Zlib
                System.arraycopy(deflated, 0, sectorBuf, 5, deflated.length);

                int idx = (c.rx & 31) + ((c.rz & 31) << 5);
                ByteBuffer curLocBb = ByteBuffer.allocate(4);
                readFully(ch, (long) idx * 4L, curLocBb);
                curLocBb.flip();
                int curLoc = curLocBb.getInt();
                int curOffset = curLoc >>> 8;
                int curSectors = curLoc & 0xFF;

                int targetSector;
                if (curOffset >= 2 && sectors <= curSectors) {
                    targetSector = curOffset;
                } else {
                    targetSector = nextSector;
                    nextSector += sectors;
                }
                writeFully(ch, (long) targetSector * SECTOR_BYTES, ByteBuffer.wrap(sectorBuf));

                int newLoc = (targetSector << 8) | (sectors & 0xFF);
                headerEntry[0] = (byte) (newLoc >>> 24);
                headerEntry[1] = (byte) (newLoc >>> 16);
                headerEntry[2] = (byte) (newLoc >>> 8);
                headerEntry[3] = (byte) newLoc;
                writeFully(ch, (long) idx * 4L, ByteBuffer.wrap(headerEntry));

                int ts = (int) (System.currentTimeMillis() / 1000L);
                tsEntry[0] = (byte) (ts >>> 24);
                tsEntry[1] = (byte) (ts >>> 16);
                tsEntry[2] = (byte) (ts >>> 8);
                tsEntry[3] = (byte) ts;
                writeFully(ch, 4096L + (long) idx * 4L, ByteBuffer.wrap(tsEntry));

                if (syncPerChunk) {
                    ch.force(false);
                }
            }
            if (!syncPerChunk) {
                ch.force(false);
            }
        }
    }

    /**
     * Rewrites a BufferedLinear v3 `.b_linear` file atomically (temp file + fdatasync + rename),
     * matching Linear/BufferedLinear's full-region flush semantics.
     */
    private static void rewriteBufferedLinearAtomic(
            Path file, List<ChunkSample> allChunks, int zstdLevel
    ) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        writeBufferedLinearV3(tmp, allChunks, zstdLevel, new int[1]);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    @Test
    @DisplayName("Prove fresh-slab CoW pointer moves, non-full-region rewrites, and kernel-measured flash write savings")
    void proveCowPointerMoveAndFlashWriteSavings() throws Exception {
        Path workDir = Path.of("build/tmp/io-proof-" + System.nanoTime()).toAbsolutePath().normalize();
        Files.createDirectories(workDir);
        Path cowLcaFile = workDir.resolve("cow_proof.lca");
        Path mcaFile = workDir.resolve("io_proof.mca");
        Path bLinearFile = workDir.resolve("io_proof.b_linear");
        Path lcaUnbufFile = workDir.resolve("io_proof_unbuf.lca");
        Path lcaBufFile = workDir.resolve("io_proof_buf.lca");

        try {
        Path fixture = resolveOverworldFixture();
        byte[] mcaBytes = Files.readAllBytes(fixture);
        List<ChunkSample> allChunks = extractChunksFromMca(mcaBytes, 1024);
        assertEquals(1024, allChunks.size(), "Expected full 1,024-chunk real Overworld region");

        List<ChunkSample> sparse8 = allChunks.subList(0, 8);
        List<ChunkSample> churn32 = allChunks.subList(0, 32);
        List<ChunkSample> churn64 = allChunks.subList(0, 64);

        // =========================================================================
        // PART A: Byte-level on-disk proof of Fresh-Slab CoW + Pointer Move
        // =========================================================================
        try (LcaRegionWriter.LcaLiveArchive archive = LcaRegionWriter.createEmptyLiveArchive(
                cowLcaFile, 0, 0, -4, 24, 3953, true)) {
            archive.configureBufferedWriteBack(true, 1024, 16 * 1024 * 1024);
            for (ChunkSample c : allChunks) {
                archive.saveChunkNbtBytes(c.rx, c.rz, c.rawNbt);
            }
            archive.flush();
        }
        try (FileChannel ch = FileChannel.open(cowLcaFile, StandardOpenOption.WRITE)) {
            ch.force(false);
        }

        byte[] beforeBytes = Files.readAllBytes(cowLcaFile);
        int[] oldSlabStart = new int[8];
        int[] oldByteLen = new int[8];
        int[] oldGen = new int[8];
        for (int i = 0; i < 8; i++) {
            ChunkSample c = sparse8.get(i);
            int idx = (c.rx & 31) + ((c.rz & 31) << 5);
            long ptr = LcaRegionReader.getVolatileLongBE(
                    beforeBytes, LcaRegionReader.POINTER_DIRECTORY_OFFSET + (idx << 3));
            oldSlabStart[i] = (int) (ptr >>> 29);
            oldByteLen[i] = (int) ((ptr >>> 8) & 0xFFFFFL);
            oldGen[i] = (int) (ptr & 0xFFL);
            assertTrue(oldSlabStart[i] > 0 && oldByteLen[i] > 0);
        }
        int controlIdx = (20 & 31) + ((20 & 31) << 5);
        long controlPtrBefore = LcaRegionReader.getVolatileLongBE(
                beforeBytes, LcaRegionReader.POINTER_DIRECTORY_OFFSET + (controlIdx << 3));

        // Overwrite those 8 chunks 4 times (32 saves) in buffered mode, then flush once
        long flushWrites;
        long coalesced;
        try (LcaRegionWriter.LcaLiveArchive archive = LcaRegionWriter.openLiveArchive(cowLcaFile, 1)) {
            archive.configureBufferedWriteBack(true, 64, 512 * 1024);
            long beforeWrites = archive.diskChannelWriteCount();
            for (int round = 0; round < 4; round++) {
                for (ChunkSample c : sparse8) {
                    archive.saveChunkNbtBytes(c.rx, c.rz, c.rawNbt);
                }
            }
            assertEquals(beforeWrites, archive.diskChannelWriteCount(),
                    "No disk writes should occur while staging in pendingPayloads");
            assertEquals(24L, archive.coalescedWriteCount(),
                    "32 saves to 8 chunks must coalesce 24 redundant overwrites in RAM");
            archive.flush();
            flushWrites = archive.diskChannelWriteCount() - beforeWrites;
            coalesced = archive.coalescedWriteCount();
        }
        try (FileChannel ch = FileChannel.open(cowLcaFile, StandardOpenOption.WRITE)) {
            ch.force(false);
        }

        byte[] afterBytes = Files.readAllBytes(cowLcaFile);
        for (int i = 0; i < 8; i++) {
            ChunkSample c = sparse8.get(i);
            int idx = (c.rx & 31) + ((c.rz & 31) << 5);
            long newPtr = LcaRegionReader.getVolatileLongBE(
                    afterBytes, LcaRegionReader.POINTER_DIRECTORY_OFFSET + (idx << 3));
            int newSlabStart = (int) (newPtr >>> 29);
            int newGen = (int) (newPtr & 0xFFL);

            // 1. Pointer moved to a FRESH slab and generation incremented
            assertTrue(newSlabStart != oldSlabStart[i],
                    "Chunk " + i + " must move to a fresh slab (old=" + oldSlabStart[i] + ", new=" + newSlabStart + ")");
            assertEquals((oldGen[i] + 1) & 0xFF, newGen, "Chunk " + i + " generation must increment");

            // 2. Old slab bytes on disk remain 100% intact (CoW never overwrote them in place)
            int oldOff = oldSlabStart[i] << LcaRegionReader.SLAB_SHIFT;
            for (int b = 0; b < oldByteLen[i]; b++) {
                assertEquals(beforeBytes[oldOff + b], afterBytes[oldOff + b],
                        "Old slab byte at offset " + (oldOff + b) + " was modified in place!");
            }
        }
        // 3. Unmodified control chunk pointer is untouched
        long controlPtrAfter = LcaRegionReader.getVolatileLongBE(
                afterBytes, LcaRegionReader.POINTER_DIRECTORY_OFFSET + (controlIdx << 3));
        assertEquals(controlPtrBefore, controlPtrAfter, "Unmodified chunk pointer must not change");

        // 4. Count how many bytes inside the original file [0..beforeBytes.length) changed
        int changedPreExistingBytes = 0;
        for (int i = 0; i < beforeBytes.length; i++) {
            if (beforeBytes[i] != afterBytes[i]) {
                changedPreExistingBytes++;
            }
        }
        double untouchedPct = 100.0 * (beforeBytes.length - changedPreExistingBytes) / beforeBytes.length;
        assertTrue(untouchedPct > 99.9,
                "Expected >99.9% of existing 4.11 MB file to remain untouched (actual: " + untouchedPct + "%)");

        // =========================================================================
        // PART B: Kernel /proc/self/io proof (wchar, syscw, write_bytes) across
        //         all 4 formats & 3 workloads on real 1,024-chunk Overworld region
        // =========================================================================

        // --- Workload 1: Bulk 1,024-chunk initial write ---
        ProcIoSnapshot s0 = ProcIoSnapshot.read();
        writeVanillaMca(mcaFile, allChunks, false, new int[1]);
        ProcIoSnapshot bulkMca = ProcIoSnapshot.read().deltaFrom(s0);

        s0 = ProcIoSnapshot.read();
        rewriteBufferedLinearAtomic(bLinearFile, allChunks, 1);
        ProcIoSnapshot bulkBLinear = ProcIoSnapshot.read().deltaFrom(s0);

        s0 = ProcIoSnapshot.read();
        try (LcaRegionWriter.LcaLiveArchive a = LcaRegionWriter.createEmptyLiveArchive(
                lcaUnbufFile, 0, 0, -4, 24, 3953, true)) {
            for (ChunkSample c : allChunks) {
                a.saveChunkNbtBytes(c.rx, c.rz, c.rawNbt);
            }
            a.flush();
        }
        try (FileChannel ch = FileChannel.open(lcaUnbufFile, StandardOpenOption.WRITE)) {
            ch.force(false);
        }
        ProcIoSnapshot bulkLcaUnbuf = ProcIoSnapshot.read().deltaFrom(s0);

        s0 = ProcIoSnapshot.read();
        try (LcaRegionWriter.LcaLiveArchive a = LcaRegionWriter.createEmptyLiveArchive(
                lcaBufFile, 0, 0, -4, 24, 3953, true)) {
            a.configureBufferedWriteBack(true, 64, 512 * 1024);
            for (ChunkSample c : allChunks) {
                a.saveChunkNbtBytes(c.rx, c.rz, c.rawNbt);
            }
            a.flush();
        }
        try (FileChannel ch = FileChannel.open(lcaBufFile, StandardOpenOption.WRITE)) {
            ch.force(false);
        }
        ProcIoSnapshot bulkLcaBuf = ProcIoSnapshot.read().deltaFrom(s0);

        // --- Workload 2: Sparse auto-save (8 dirty chunks on existing 1,024-chunk region) ---
        s0 = ProcIoSnapshot.read();
        updateVanillaMcaSubset(mcaFile, sparse8, false);
        ProcIoSnapshot sparseMca = ProcIoSnapshot.read().deltaFrom(s0);

        s0 = ProcIoSnapshot.read();
        updateVanillaMcaSubset(mcaFile, sparse8, true);
        ProcIoSnapshot sparseMcaSync = ProcIoSnapshot.read().deltaFrom(s0);

        s0 = ProcIoSnapshot.read();
        rewriteBufferedLinearAtomic(bLinearFile, allChunks, 1);
        ProcIoSnapshot sparseBLinear = ProcIoSnapshot.read().deltaFrom(s0);

        s0 = ProcIoSnapshot.read();
        try (LcaRegionWriter.LcaLiveArchive a = LcaRegionWriter.openLiveArchive(lcaUnbufFile, 1)) {
            for (ChunkSample c : sparse8) {
                a.saveChunkNbtBytes(c.rx, c.rz, c.rawNbt);
            }
            a.flush();
        }
        try (FileChannel ch = FileChannel.open(lcaUnbufFile, StandardOpenOption.WRITE)) {
            ch.force(false);
        }
        ProcIoSnapshot sparseLcaUnbuf = ProcIoSnapshot.read().deltaFrom(s0);

        s0 = ProcIoSnapshot.read();
        try (LcaRegionWriter.LcaLiveArchive a = LcaRegionWriter.openLiveArchive(lcaBufFile, 1)) {
            a.configureBufferedWriteBack(true, 64, 512 * 1024);
            for (ChunkSample c : sparse8) {
                a.saveChunkNbtBytes(c.rx, c.rz, c.rawNbt);
            }
            a.flush();
        }
        try (FileChannel ch = FileChannel.open(lcaBufFile, StandardOpenOption.WRITE)) {
            ch.force(false);
        }
        ProcIoSnapshot sparseLcaBuf = ProcIoSnapshot.read().deltaFrom(s0);

        // --- Workload 3a: Coalescing hot-chunk churn (4 x 32 hot chunks = 128 saves, fits in 64-chunk buffer) ---
        s0 = ProcIoSnapshot.read();
        for (int round = 0; round < 4; round++) {
            updateVanillaMcaSubset(mcaFile, churn32, false);
        }
        ProcIoSnapshot churn32Mca = ProcIoSnapshot.read().deltaFrom(s0);

        s0 = ProcIoSnapshot.read();
        rewriteBufferedLinearAtomic(bLinearFile, allChunks, 1);
        ProcIoSnapshot churn32BLinear = ProcIoSnapshot.read().deltaFrom(s0);

        s0 = ProcIoSnapshot.read();
        try (LcaRegionWriter.LcaLiveArchive a = LcaRegionWriter.openLiveArchive(lcaUnbufFile, 1)) {
            for (int round = 0; round < 4; round++) {
                for (ChunkSample c : churn32) {
                    a.saveChunkNbtBytes(c.rx, c.rz, c.rawNbt);
                }
                a.flush();
                try (FileChannel ch = FileChannel.open(lcaUnbufFile, StandardOpenOption.WRITE)) {
                    ch.force(false);
                }
            }
        }
        ProcIoSnapshot churn32LcaUnbuf = ProcIoSnapshot.read().deltaFrom(s0);

        s0 = ProcIoSnapshot.read();
        long churn32Coalesced;
        try (LcaRegionWriter.LcaLiveArchive a = LcaRegionWriter.openLiveArchive(lcaBufFile, 1)) {
            a.configureBufferedWriteBack(true, 64, 512 * 1024);
            for (int round = 0; round < 4; round++) {
                for (ChunkSample c : churn32) {
                    a.saveChunkNbtBytes(c.rx, c.rz, c.rawNbt);
                }
            }
            churn32Coalesced = a.coalescedWriteCount();
            a.flush();
        }
        try (FileChannel ch = FileChannel.open(lcaBufFile, StandardOpenOption.WRITE)) {
            ch.force(false);
        }
        ProcIoSnapshot churn32LcaBuf = ProcIoSnapshot.read().deltaFrom(s0);

        // --- Workload 3b: Boundary churn (4 x 64 hot chunks = 256 saves, hits 64-chunk flush boundary each round) ---
        s0 = ProcIoSnapshot.read();
        for (int round = 0; round < 4; round++) {
            updateVanillaMcaSubset(mcaFile, churn64, false);
        }
        ProcIoSnapshot churn64Mca = ProcIoSnapshot.read().deltaFrom(s0);

        s0 = ProcIoSnapshot.read();
        try (LcaRegionWriter.LcaLiveArchive a = LcaRegionWriter.openLiveArchive(lcaUnbufFile, 1)) {
            for (int round = 0; round < 4; round++) {
                for (ChunkSample c : churn64) {
                    a.saveChunkNbtBytes(c.rx, c.rz, c.rawNbt);
                }
                a.flush();
                try (FileChannel ch = FileChannel.open(lcaUnbufFile, StandardOpenOption.WRITE)) {
                    ch.force(false);
                }
            }
        }
        ProcIoSnapshot churn64LcaUnbuf = ProcIoSnapshot.read().deltaFrom(s0);

        s0 = ProcIoSnapshot.read();
        try (LcaRegionWriter.LcaLiveArchive a = LcaRegionWriter.openLiveArchive(lcaBufFile, 1)) {
            a.configureBufferedWriteBack(true, 64, 512 * 1024);
            for (int round = 0; round < 4; round++) {
                for (ChunkSample c : churn64) {
                    a.saveChunkNbtBytes(c.rx, c.rz, c.rawNbt);
                }
            }
            a.flush();
        }
        try (FileChannel ch = FileChannel.open(lcaBufFile, StandardOpenOption.WRITE)) {
            ch.force(false);
        }
        ProcIoSnapshot churn64LcaBuf = ProcIoSnapshot.read().deltaFrom(s0);

        System.out.printf(Locale.ROOT,
                "%n=== EMPIRICAL PROOF: COW POINTER MOVE & KERNEL /proc/self/io WRITE ACCOUNTING ===%n"
                        + "[Part A: On-disk CoW proof on 1,024-chunk .lca (%,d B -> %,d B)]%n"
                        + "  8 chunks x 4 overwrites = 32 saves -> %d coalesced in RAM, %d channel writes on flush%n"
                        + "  All 8 pointers moved to fresh slabs (gen+1); all 8 old slab ranges 100%% byte-identical on disk%n"
                        + "  Pre-existing file bytes modified: %,d / %,d B (%.4f%% untouched — NOT a full-region rewrite)%n"
                        + "%n[Part B: Kernel /proc/self/io (wchar = syscall bytes, write_bytes = block-layer flash bytes)]%n"
                        + "  1. BULK GEN (1,024 unique chunks + fdatasync):%n"
                        + "     Vanilla .mca:           wchar=%,10d B | write_bytes=%,10d B | syscw=%,5d%n"
                        + "     BufferedLinear v3:      wchar=%,10d B | write_bytes=%,10d B | syscw=%,5d%n"
                        + "     Unbuffered .lca:        wchar=%,10d B | write_bytes=%,10d B | syscw=%,5d%n"
                        + "     Buffered .lca:          wchar=%,10d B | write_bytes=%,10d B | syscw=%,5d%n"
                        + "  2. SPARSE AUTO-SAVE (8 dirty chunks on 1,024-chunk region + fdatasync):%n"
                        + "     Vanilla .mca (async):   wchar=%,10d B | write_bytes=%,10d B | syscw=%,5d%n"
                        + "     Vanilla .mca (DSYNC):   wchar=%,10d B | write_bytes=%,10d B | syscw=%,5d%n"
                        + "     BufferedLinear v3:      wchar=%,10d B | write_bytes=%,10d B | syscw=%,5d%n"
                        + "     Unbuffered .lca:        wchar=%,10d B | write_bytes=%,10d B | syscw=%,5d%n"
                        + "     Buffered .lca:          wchar=%,10d B | write_bytes=%,10d B | syscw=%,5d%n"
                        + "  3a. COALESCING HOT CHURN (4x32 hot chunks = 128 saves, coalesced=%d):%n"
                        + "     Vanilla .mca (4 sync):  wchar=%,10d B | write_bytes=%,10d B | syscw=%,5d%n"
                        + "     BufferedLinear (1 fl):  wchar=%,10d B | write_bytes=%,10d B | syscw=%,5d%n"
                        + "     Unbuffered .lca (4 sy): wchar=%,10d B | write_bytes=%,10d B | syscw=%,5d%n"
                        + "     Buffered .lca (1 fl):   wchar=%,10d B | write_bytes=%,10d B | syscw=%,5d%n"
                        + "  3b. FLUSH-BOUNDARY CHURN (4x64 hot chunks = 256 saves, hits 64-chunk cap each round):%n"
                        + "     Vanilla .mca (4 sync):  wchar=%,10d B | write_bytes=%,10d B | syscw=%,5d%n"
                        + "     Unbuffered .lca (4 sy): wchar=%,10d B | write_bytes=%,10d B | syscw=%,5d%n"
                        + "     Buffered .lca (4 fl):   wchar=%,10d B | write_bytes=%,10d B | syscw=%,5d%n"
                        + "==================================================================================%n",
                beforeBytes.length, afterBytes.length, coalesced, flushWrites,
                changedPreExistingBytes, beforeBytes.length, untouchedPct,
                bulkMca.wchar, bulkMca.writeBytes, bulkMca.syscw,
                bulkBLinear.wchar, bulkBLinear.writeBytes, bulkBLinear.syscw,
                bulkLcaUnbuf.wchar, bulkLcaUnbuf.writeBytes, bulkLcaUnbuf.syscw,
                bulkLcaBuf.wchar, bulkLcaBuf.writeBytes, bulkLcaBuf.syscw,
                sparseMca.wchar, sparseMca.writeBytes, sparseMca.syscw,
                sparseMcaSync.wchar, sparseMcaSync.writeBytes, sparseMcaSync.syscw,
                sparseBLinear.wchar, sparseBLinear.writeBytes, sparseBLinear.syscw,
                sparseLcaUnbuf.wchar, sparseLcaUnbuf.writeBytes, sparseLcaUnbuf.syscw,
                sparseLcaBuf.wchar, sparseLcaBuf.writeBytes, sparseLcaBuf.syscw,
                churn32Coalesced,
                churn32Mca.wchar, churn32Mca.writeBytes, churn32Mca.syscw,
                churn32BLinear.wchar, churn32BLinear.writeBytes, churn32BLinear.syscw,
                churn32LcaUnbuf.wchar, churn32LcaUnbuf.writeBytes, churn32LcaUnbuf.syscw,
                churn32LcaBuf.wchar, churn32LcaBuf.writeBytes, churn32LcaBuf.syscw,
                churn64Mca.wchar, churn64Mca.writeBytes, churn64Mca.syscw,
                churn64LcaUnbuf.wchar, churn64LcaUnbuf.writeBytes, churn64LcaUnbuf.syscw,
                churn64LcaBuf.wchar, churn64LcaBuf.writeBytes, churn64LcaBuf.syscw);

        // Hard assertions on Linux kernel /proc/self/io measurements
        if (sparseLcaBuf.wchar >= 0) {
            // 1. Neither Vanilla .mca nor .lca rewrites the full region on sparse 8-chunk save; ONLY .b_linear does
            assertTrue(sparseMca.wchar < 200_000L, "Vanilla .mca must NOT rewrite full 8.35 MB region");
            assertTrue(sparseLcaBuf.wchar < 200_000L, "Buffered .lca must NOT rewrite full 4.11 MB region");
            assertTrue(sparseBLinear.wchar > 5_000_000L, "BufferedLinear .b_linear MUST rewrite full ~5.8 MB region");

            // 2. When hot chunks coalesce in RAM (4x32 <= 64-chunk buffer), Buffered .lca cuts writes drastically
            assertEquals(96L, churn32Coalesced, "Expected 96 coalesced saves in 4x32 hot churn");
            assertTrue(churn32LcaBuf.wchar * 3L < churn32LcaUnbuf.wchar,
                    "Buffered .lca coalescing churn wchar must be >3x lower than unbuffered .lca");
            assertTrue(churn32LcaBuf.wchar * 5L < churn32Mca.wchar,
                    "Buffered .lca coalescing churn wchar must be >5x lower than Vanilla .mca");

            if (sparseLcaBuf.writeBytes > 0) {
                assertTrue(sparseLcaBuf.writeBytes * 25L < sparseBLinear.writeBytes,
                        "Buffered .lca sparse block-layer write_bytes must be >25x lower than .b_linear");
                assertTrue(churn32LcaBuf.writeBytes * 15L < churn32BLinear.writeBytes,
                        "Buffered .lca coalescing churn block-layer write_bytes must be >15x lower than .b_linear");
            }
        }
        } finally {
            Files.deleteIfExists(cowLcaFile);
            Files.deleteIfExists(mcaFile);
            Files.deleteIfExists(bLinearFile);
            Files.deleteIfExists(bLinearFile.resolveSibling(bLinearFile.getFileName() + ".tmp"));
            Files.deleteIfExists(lcaUnbufFile);
            Files.deleteIfExists(lcaBufFile);
            Files.deleteIfExists(workDir);
        }
    }
}
