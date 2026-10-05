package io.github.dailystruggle.rtp.anvil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import net.jpountz.lz4.LZ4BlockInputStream;
import net.jpountz.lz4.LZ4BlockOutputStream;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.xxhash.XXHashFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in real-world corpus check: transcodes real chunks (any mode) to vanilla mode 4 and reads
 * them back through the production path. Set {@code RTP_REAL_WORLD_DIR} to a world root.
 */
@EnabledIfEnvironmentVariable(named = "RTP_REAL_WORLD_DIR", matches = ".+")
@DisplayName("ADR-016: real-world chunks transcoded to mode 4 decode identically")
class RealWorldLz4TranscodeTest {

    private static final int SECTOR = 4096;
    private static final int SEED = 0x9747b28c;

    @Test
    void transcodeRealRegions() throws IOException {
        Path root = Paths.get(System.getenv("RTP_REAL_WORLD_DIR"));
        int perDim = Integer.parseInt(System.getenv().getOrDefault("RTP_REAL_WORLD_FILES_PER_DIM", "12"));

        List<Path> regionDirs;
        try (Stream<Path> s = Files.walk(root, 6)) {
            regionDirs = s.filter(Files::isDirectory).filter(p -> p.getFileName().toString().equals("region"))
                    .sorted().collect(Collectors.toList());
        }
        List<Path> files = new ArrayList<>();
        for (Path dir : regionDirs) {
            List<Path> all;
            try (Stream<Path> s = Files.list(dir)) {
                all = s.filter(p -> p.toString().endsWith(".mca")).sorted().collect(Collectors.toList());
            }
            if (all.isEmpty()) continue;
            int step = Math.max(1, all.size() / perDim);
            for (int i = 0; i < all.size() && files.size() < Integer.MAX_VALUE; i += step) files.add(all.get(i));
            all.sort((a, b) -> Long.compare(b.toFile().length(), a.toFile().length()));
            if (!files.contains(all.get(0))) files.add(all.get(0)); // densest file per dimension
        }

        Map<Integer, Long> modes = new TreeMap<>();
        long chunks = 0, external = 0, origViewOk = 0, lz4ViewOk = 0, rawBytes = 0, srcBytes = 0, lz4Bytes = 0;
        long multiBlock = 0, maxRaw = 0, minDv = Long.MAX_VALUE, maxDv = Long.MIN_VALUE;
        long nsOurs = 0, nsRef = 0, nsSrc = 0;
        List<String> failures = new ArrayList<>();
        List<String> sourceUnreadable = new ArrayList<>(); // pre-existing reader limits, not LZ4

        for (Path file : files) {
            byte[] region = Files.readAllBytes(file);
            if (region.length < 2 * SECTOR) continue;
            ByteArrayOutputStream rebuilt = new ByteArrayOutputStream(region.length);
            rebuilt.write(new byte[2 * SECTOR]);
            byte[] header = new byte[2 * SECTOR];
            System.arraycopy(region, SECTOR, header, SECTOR, SECTOR); // timestamps
            List<byte[]> fileLz4 = new ArrayList<>();
            List<byte[]> fileSrc = new ArrayList<>();
            List<Integer> fileSrcMode = new ArrayList<>();

            for (int idx = 0; idx < 1024; idx++) {
                int loc = intBE(region, idx * 4);
                if (loc == 0) continue;
                int off = (loc >>> 8) * SECTOR;
                if (off + 5 > region.length) { failures.add(file.getFileName() + "#" + idx + " offset past EOF"); continue; }
                int len = intBE(region, off);
                int mode = region[off + 4] & 0xFF;
                modes.merge(mode, 1L, Long::sum);
                chunks++;
                if ((mode & 0x80) != 0) { external++; continue; }
                if (len < 1 || off + 4 + len > region.length) { failures.add(file.getFileName() + "#" + idx + " bad length"); continue; }
                int cx = idx & 31, cz = idx >>> 5;
                try {
                    if (AnvilReader.readChunkView(region, cx, cz) != null) origViewOk++;
                } catch (IOException e) {
                    sourceUnreadable.add(file.getFileName() + "#" + idx + ": " + e.getMessage());
                    continue;
                }
                byte[] payload = java.util.Arrays.copyOfRange(region, off + 5, off + 4 + len);
                byte[] nbt = inflate(payload, mode);
                byte[] lz4 = vanillaLz4(nbt);
                rawBytes += nbt.length;
                srcBytes += payload.length;
                lz4Bytes += lz4.length;
                maxRaw = Math.max(maxRaw, nbt.length);
                if (nbt.length > 65536) multiBlock++;
                fileLz4.add(lz4);
                fileSrc.add(payload);
                fileSrcMode.add(mode);

                assertArrayEquals(nbt, Lz4BlockDecoder.decode(lz4, 0, lz4.length,
                        AnvilReader.MAX_DECOMPRESSED_CHUNK_BYTES), file.getFileName() + "#" + idx);

                int sectorStart = rebuilt.size() / SECTOR;
                int entryLen = lz4.length + 1;
                int sectors = (4 + entryLen + SECTOR - 1) / SECTOR;
                byte[] entry = new byte[sectors * SECTOR];
                writeIntBE(entry, 0, entryLen);
                entry[4] = 4;
                System.arraycopy(lz4, 0, entry, 5, lz4.length);
                rebuilt.write(entry);
                writeIntBE(header, idx * 4, (sectorStart << 8) | Math.min(sectors, 255));
            }

            byte[] lz4Region = rebuilt.toByteArray();
            System.arraycopy(header, 0, lz4Region, 0, header.length);
            for (int idx = 0; idx < 1024; idx++) {
                if (intBE(header, idx * 4) == 0) continue;
                try {
                    AnvilReader.ChunkEntry a = AnvilReader.readChunkEntry(region, idx & 31, idx >>> 5);
                    AnvilReader.ChunkEntry b = AnvilReader.readChunkEntry(lz4Region, idx & 31, idx >>> 5);
                    assertNotNull(b);
                    assertEquals(4, b.compressionType);
                    int dv = AnvilReader.getDataVersion(b.root);
                    assertEquals(AnvilReader.getDataVersion(a.root), dv);
                    minDv = Math.min(minDv, dv);
                    maxDv = Math.max(maxDv, dv);
                    if (AnvilReader.readChunkView(lz4Region, idx & 31, idx >>> 5) != null) lz4ViewOk++;
                } catch (IOException e) {
                    failures.add(file.getFileName() + "#" + idx + " lz4 region: " + e);
                }
            }

            // Throughput: same payloads, ours vs lz4-java safe stream vs source codec.
            long t0 = System.nanoTime();
            for (byte[] p : fileLz4) Lz4BlockDecoder.decode(p, 0, p.length, AnvilReader.MAX_DECOMPRESSED_CHUNK_BYTES);
            long t1 = System.nanoTime();
            for (byte[] p : fileLz4) refDecode(p);
            long t2 = System.nanoTime();
            for (int i = 0; i < fileSrc.size(); i++) inflate(fileSrc.get(i), fileSrcMode.get(i));
            long t3 = System.nanoTime();
            nsOurs += t1 - t0;
            nsRef += t2 - t1;
            nsSrc += t3 - t2;
        }

        double mib = rawBytes / 1048576.0;
        StringBuilder r = new StringBuilder();
        r.append("files=").append(files.size()).append(" from ").append(regionDirs.size()).append(" region dirs\n");
        r.append("chunks=").append(chunks).append(" modes=").append(modes).append(" external=").append(external).append('\n');
        r.append("DataVersion range=").append(minDv).append("..").append(maxDv).append('\n');
        r.append("original readChunkView ok=").append(origViewOk).append(", lz4 readChunkView ok=").append(lz4ViewOk).append('\n');
        r.append(String.format("NBT=%.1f MiB, source=%.1f MiB (%.1f%%), lz4=%.1f MiB (%.1f%%)%n", mib,
                srcBytes / 1048576.0, 100.0 * srcBytes / rawBytes, lz4Bytes / 1048576.0, 100.0 * lz4Bytes / rawBytes));
        r.append("max chunk NBT=").append(maxRaw).append(" B, multi-block chunks (>64 KiB)=").append(multiBlock).append('\n');
        r.append(String.format("decode MiB/s: in-house=%.0f lz4-java(safe)=%.0f source-codec=%.0f%n",
                mib / (nsOurs / 1e9), mib / (nsRef / 1e9), mib / (nsSrc / 1e9)));
        r.append("source chunks unreadable by AnvilReader (excluded)=").append(sourceUnreadable.size()).append('\n');
        sourceUnreadable.stream().limit(5).forEach(f -> r.append("  ").append(f).append('\n'));
        r.append("lz4 failures=").append(failures.size()).append('\n');
        failures.stream().limit(20).forEach(f -> r.append("  ").append(f).append('\n'));
        System.out.println("[real-world-lz4]\n" + r);
        Files.write(Paths.get("build", "real-world-lz4-report.txt"), r.toString().getBytes());
        assertEquals(0, failures.size(), r.toString());
    }

    /** Vanilla RegionFileVersion.VERSION_LZ4 writer: {@code new LZ4BlockOutputStream(out)} defaults. */
    private static byte[] vanillaLz4(byte[] nbt) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(nbt.length / 2 + 64);
        try (LZ4BlockOutputStream out = new LZ4BlockOutputStream(bos, 1 << 16,
                LZ4Factory.safeInstance().fastCompressor(),
                XXHashFactory.safeInstance().newStreamingHash32(SEED).asChecksum(), false)) {
            out.write(nbt);
        }
        return bos.toByteArray();
    }

    @SuppressWarnings("deprecation")
    private static void refDecode(byte[] p) throws IOException {
        try (InputStream in = new LZ4BlockInputStream(new ByteArrayInputStream(p),
                LZ4Factory.safeInstance().fastDecompressor(),
                XXHashFactory.safeInstance().newStreamingHash32(SEED).asChecksum())) {
            in.readAllBytes();
        }
    }

    private static byte[] inflate(byte[] payload, int mode) throws IOException {
        switch (mode) {
            case 1: try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(payload))) { return in.readAllBytes(); }
            case 2: try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(payload))) { return in.readAllBytes(); }
            case 3: return payload;
            case 4: return Lz4BlockDecoder.decode(payload, 0, payload.length, AnvilReader.MAX_DECOMPRESSED_CHUNK_BYTES);
            default: throw new IOException("mode " + mode);
        }
    }

    private static int intBE(byte[] b, int i) {
        return ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
    }

    private static void writeIntBE(byte[] b, int i, int v) {
        b[i] = (byte) (v >>> 24);
        b[i + 1] = (byte) (v >>> 16);
        b[i + 2] = (byte) (v >>> 8);
        b[i + 3] = (byte) v;
    }
}
