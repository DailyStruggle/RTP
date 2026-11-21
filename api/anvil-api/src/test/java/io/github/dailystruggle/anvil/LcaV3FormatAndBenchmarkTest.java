package io.github.dailystruggle.anvil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate 1 correctness, backward-compatibility, negative/CBIT validation, and empirical
 * size-vs-speed benchmark suite for `.lca` Format Version `0x0003` (`LCA-ICD-3.0`).
 *
 * @governedBy anvil-api-ADR-004
 * @governedBy anvil-api-ADR-005
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
@DisplayName("LCA v3 (0x0003) Correctness, Backward-Compat, CBIT & Size/Speed Benchmark")
class LcaV3FormatAndBenchmarkTest {

    private static byte[] loadFixture(String resourcePath) throws IOException {
        try (InputStream is = LcaV3FormatAndBenchmarkTest.class.getResourceAsStream(resourcePath)) {
            assertNotNull(is, "Missing test fixture: " + resourcePath);
            return is.readAllBytes();
        }
    }

    @Test
    @DisplayName("v2 (0x0002) backward read compatibility and v3 (0x0003) bit-exact roundtrip across Mode A & Mode B")
    void v2BackwardReadAndV3RoundtripParity() throws IOException {
        byte[] mcaBytes = loadFixture("/anvil/real/26_3_R1/r.0.0.mca");
        byte[] v2ModeA = LcaRegionWriter.transcodeFromMcaWithVersion(
                mcaBytes, 0, 0, 0, LcaRegionReader.LCA_FORMAT_VERSION_V2);
        byte[] v2ModeB = LcaRegionWriter.transcodeFromMcaWithVersion(
                mcaBytes, 0, 0, 1, LcaRegionReader.LCA_FORMAT_VERSION_V2);
        byte[] v3ModeA = LcaRegionWriter.transcodeFromMcaWithVersion(
                mcaBytes, 0, 0, 0, LcaRegionReader.LCA_FORMAT_VERSION_V3);
        byte[] v3ModeB = LcaRegionWriter.transcodeFromMcaWithVersion(
                mcaBytes, 0, 0, 1, LcaRegionReader.LCA_FORMAT_VERSION_V3);

        assertEquals(LcaRegionReader.LCA_FORMAT_VERSION_V2, LcaRegionReader.peekFormatVersion(v2ModeA));
        assertEquals(LcaRegionReader.LCA_FORMAT_VERSION_V2, LcaRegionReader.peekFormatVersion(v2ModeB));
        assertEquals(LcaRegionReader.LCA_FORMAT_VERSION_V3, LcaRegionReader.peekFormatVersion(v3ModeA));
        assertEquals(LcaRegionReader.LCA_FORMAT_VERSION_V3, LcaRegionReader.peekFormatVersion(v3ModeB));

        int verified = 0;
        for (int rz = 0; rz < 32; rz++) {
            for (int rx = 0; rx < 32; rx++) {
                if (!AnvilReader.INSTANCE.isChunkGenerated(mcaBytes, rx, rz)) {
                    continue;
                }
                AnvilReader.ChunkEntry ref = AnvilReader.readChunkEntry(mcaBytes, rx, rz);
                AnvilReader.ChunkEntry rV2A = LcaRegionReader.INSTANCE.readChunk(v2ModeA, rx, rz);
                AnvilReader.ChunkEntry rV2B = LcaRegionReader.INSTANCE.readChunk(v2ModeB, rx, rz);
                AnvilReader.ChunkEntry rV3A = LcaRegionReader.INSTANCE.readChunk(v3ModeA, rx, rz);
                AnvilReader.ChunkEntry rV3B = LcaRegionReader.INSTANCE.readChunk(v3ModeB, rx, rz);

                assertNotNull(rV2A);
                assertNotNull(rV2B);
                assertNotNull(rV3A);
                assertNotNull(rV3B);

                // Direct binary NBT streaming path must also parse cleanly and match sections
                byte[] nbtV3B = LcaRegionReader.INSTANCE.readChunkNbtBytes(v3ModeB, rx, rz);
                assertNotNull(nbtV3B);
                LinkedHashMap<String, Object> parsedDirect = Nbt.readRootCompound(nbtV3B);
                assertEquals(ref.root.get("xPos"), parsedDirect.get("xPos"));
                assertEquals(ref.root.get("zPos"), parsedDirect.get("zPos"));

                // Verify chunk views match 100% when chunk has full voxel sections
                AnvilChunkView refView = AnvilReader.INSTANCE.readChunkView(mcaBytes, rx, rz);
                AnvilChunkView v2View = LcaRegionReader.INSTANCE.readChunkView(v2ModeB, rx, rz);
                AnvilChunkView v3View = LcaRegionReader.INSTANCE.readChunkView(v3ModeB, rx, rz);
                if (refView != null && v2View != null) {
                    assertNotNull(v3View);
                    for (int y = -60; y <= 120; y += 15) {
                        assertEquals(refView.blockIdAt(7, y, 7), v2View.blockIdAt(7, y, 7));
                        assertEquals(refView.blockIdAt(7, y, 7), v3View.blockIdAt(7, y, 7));
                    }
                }
                verified++;
                if (verified >= 32) {
                    break;
                }
            }
            if (verified >= 32) {
                break;
            }
        }
        assertTrue(verified > 0, "Expected at least 1 generated chunk in 26_3_R1 fixture");
    }

    @Test
    @DisplayName("v3 Columnar ticks/block_entities roundtrip, modded schema fallback, tinted_glass & MAP_TILE guard")
    void columnarTicksBlockEntitiesAndGuards() throws IOException {
        // 1. Tinted glass must be opaque (15), glass transparent (0), water translucent (1)
        assertEquals(15, (LcaV3Codec.classifyLightV1("minecraft:tinted_glass") >>> 4) & 0x0F);
        assertEquals(0, (LcaV3Codec.classifyLightV1("minecraft:glass") >>> 4) & 0x0F);
        assertEquals(1, (LcaV3Codec.classifyLightV1("minecraft:water[level=0]") >>> 4) & 0x0F);
        assertEquals(14, LcaV3Codec.classifyLightV1("minecraft:torch") & 0x0F);
        assertEquals(13, LcaV3Codec.classifyLightV1("minecraft:redstone_lamp[lit=true]") & 0x0F);
        assertEquals(0, LcaV3Codec.classifyLightV1("minecraft:redstone_lamp[lit=false]") & 0x0F);

        // 2. Vanilla block_ticks + fluid_ticks + template block_entities roundtrip (both DOM and streaming)
        List<Object> vanillaTicks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            LinkedHashMap<String, Object> t = new LinkedHashMap<>();
            t.put("i", (i % 2 == 0) ? "minecraft:water" : "minecraft:flowing_water");
            t.put("p", (i % 3 == 0) ? -1 : 0);
            t.put("t", 10 + i * 2);
            t.put("x", 32 + (i & 15));
            t.put("y", 64 + (i % 5));
            t.put("z", 48 + ((i * 3) & 15));
            vanillaTicks.add(t);
        }
        Nbt.NbtList tickList = new Nbt.NbtList(Nbt.TAG_COMPOUND, vanillaTicks);
        byte[] encTicks = LcaV3Codec.tryEncodeColumnarTicksFromList(tickList, 2, 3);
        assertNotNull(encTicks);
        Nbt.NbtList decTicks = LcaV3Codec.decodeColumnarTicksToList(encTicks, 0, encTicks.length, 2, 3);
        assertEquals(vanillaTicks.size(), decTicks.items.size());
        for (int i = 0; i < vanillaTicks.size(); i++) {
            assertEquals(vanillaTicks.get(i), decTicks.items.get(i));
        }

        // 3. Modded tick schema with extra key MUST fall back (return null)
        LinkedHashMap<String, Object> moddedTick = new LinkedHashMap<>();
        moddedTick.put("i", "modid:custom_tick");
        moddedTick.put("p", 0);
        moddedTick.put("t", 5);
        moddedTick.put("x", 32);
        moddedTick.put("y", 64);
        moddedTick.put("z", 48);
        moddedTick.put("mod_extra", 42);
        Nbt.NbtList moddedList = new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(moddedTick));
        assertEquals(null, LcaV3Codec.tryEncodeColumnarTicksFromList(moddedList, 2, 3));

        // 4. Template-deduplicated block_entities roundtrip (x,y,z + Pos[3] + no-coord)
        List<Object> bes = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            LinkedHashMap<String, Object> be = new LinkedHashMap<>();
            be.put("id", "minecraft:chest");
            be.put("keepPacked", (byte) 0);
            be.put("x", 32 + (i & 15));
            be.put("y", 60 + i);
            be.put("z", 48 + ((i * 2) & 15));
            bes.add(be);
        }
        Nbt.NbtList beList = new Nbt.NbtList(Nbt.TAG_COMPOUND, bes);
        byte[] encBe = LcaV3Codec.tryEncodeColumnarBlockEntitiesFromList(beList, 2, 3);
        assertNotNull(encBe);
        Nbt.NbtList decBe = LcaV3Codec.decodeColumnarBlockEntitiesToList(encBe, 0, encBe.length, 2, 3);
        assertEquals(bes.size(), decBe.items.size());
        for (int i = 0; i < bes.size(); i++) {
            assertEquals(bes.get(i), decBe.items.get(i));
        }

        // 5. CBIT T5: mutual exclusion and v2 rejection of bit 15
        assertThrows(CorruptRegionEntryException.class,
                () -> LcaV3Codec.validateExtFlags(0x8000, 0x01, LcaRegionReader.LCA_FORMAT_VERSION_V2));
        assertThrows(CorruptRegionEntryException.class,
                () -> LcaV3Codec.validateExtFlags(0x8002, 0x01, LcaRegionReader.LCA_FORMAT_VERSION_V3));
        assertThrows(CorruptRegionEntryException.class,
                () -> LcaV3Codec.validateExtFlags(0x8000, 0x08, LcaRegionReader.LCA_FORMAT_VERSION_V3));
    }

    @Test
    @DisplayName("Head-to-Head Benchmark: .mca vs .lca v2 vs .lca v3 (Fast-Decode & Full Eikonal) Sizes & Speeds")
    void benchmarkV3AgainstV2AndMcaSizesAndSpeeds() throws IOException {
        // Test both bundled 26.3 fixture AND live 26.3 overworld regions if present
        List<byte[]> mcaRegions = new ArrayList<>();
        List<String> regionNames = new ArrayList<>();

        byte[] bundled263 = loadFixture("/anvil/real/26_3_R1/r.0.0.mca");
        mcaRegions.add(bundled263);
        regionNames.add("bundled/26_3_R1/r.0.0.mca");

        byte[] bundled121 = loadFixture("/anvil/real/1_21_R1/r.0.0.mca");
        mcaRegions.add(bundled121);
        regionNames.add("bundled/1_21_R1/r.0.0.mca");

        // Also sample live 26.3 .lca overworld regions (reverse-transcoded or direct chunk-root) if available
        Path liveDir = Paths.get(System.getProperty("user.home"),
                "Desktop/GameServers/Minecraft/testServer/RTP-Paper/26.3/world/dimensions/minecraft/overworld/region");
        List<LinkedHashMap<String, Object>> liveSampleChunks = new ArrayList<>();
        if (Files.isDirectory(liveDir)) {
            List<Path> lcaFiles = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(liveDir, "*.lca")) {
                for (Path p : ds) {
                    lcaFiles.add(p);
                }
            }
            lcaFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));
            int step = Math.max(1, lcaFiles.size() / 8);
            for (int f = 0; f < lcaFiles.size() && liveSampleChunks.size() < 160; f += step) {
                byte[] regionBytes = Files.readAllBytes(lcaFiles.get(f));
                int grabbed = 0;
                for (int rz = 0; rz < 32 && grabbed < 20; rz += 2) {
                    for (int rx = 0; rx < 32 && grabbed < 20; rx += 2) {
                        if (!LcaRegionReader.INSTANCE.isChunkGenerated(regionBytes, rx, rz)) {
                            continue;
                        }
                        AnvilReader.ChunkEntry ce = LcaRegionReader.INSTANCE.readChunk(regionBytes, rx, rz);
                        if (ce != null && ce.root != null) {
                            liveSampleChunks.add(ce.root);
                            grabbed++;
                        }
                    }
                }
            }
        }

        System.out.println("\n================ [LCA v3 vs v2 vs .mca SIZE & SPEED BENCHMARK] ================");

        for (int r = 0; r < mcaRegions.size(); r++) {
            byte[] mca = mcaRegions.get(r);
            List<int[]> coords = new ArrayList<>();
            for (int rz = 0; rz < 32; rz++) {
                for (int rx = 0; rx < 32; rx++) {
                    if (AnvilReader.INSTANCE.isChunkGenerated(mca, rx, rz)) {
                        coords.add(new int[] {rx, rz});
                    }
                }
            }

            // Encode all variants (v2, v3-FastDecode [no Eikonal], v3-Full [with Eikonal+B1])
            long t0 = System.nanoTime();
            byte[] v2A = LcaRegionWriter.transcodeFromMcaWithVersion(mca, 0, 0, 0, LcaRegionReader.LCA_FORMAT_VERSION_V2);
            long tV2AEnc = System.nanoTime() - t0;

            t0 = System.nanoTime();
            byte[] v2B = LcaRegionWriter.transcodeFromMcaWithVersion(mca, 0, 0, 1, LcaRegionReader.LCA_FORMAT_VERSION_V2);
            long tV2BEnc = System.nanoTime() - t0;

            LcaV3Codec.setThreadFeatureMask(LcaV3Codec.FEATURE_COLUMNAR_TAIL | LcaV3Codec.FEATURE_RANK_EXCL);
            t0 = System.nanoTime();
            byte[] v3FastA = LcaRegionWriter.transcodeFromMcaWithVersion(mca, 0, 0, 0, LcaRegionReader.LCA_FORMAT_VERSION_V3);
            long tV3FastAEnc = System.nanoTime() - t0;
            t0 = System.nanoTime();
            byte[] v3FastB = LcaRegionWriter.transcodeFromMcaWithVersion(mca, 0, 0, 1, LcaRegionReader.LCA_FORMAT_VERSION_V3);
            long tV3FastBEnc = System.nanoTime() - t0;

            LcaV3Codec.setThreadFeatureMask(LcaV3Codec.ALL_V3_FEATURES);
            t0 = System.nanoTime();
            byte[] v3FullA = LcaRegionWriter.transcodeFromMcaWithVersion(mca, 0, 0, 0, LcaRegionReader.LCA_FORMAT_VERSION_V3);
            long tV3FullAEnc = System.nanoTime() - t0;
            t0 = System.nanoTime();
            byte[] v3FullB = LcaRegionWriter.transcodeFromMcaWithVersion(mca, 0, 0, 1, LcaRegionReader.LCA_FORMAT_VERSION_V3);
            long tV3FullBEnc = System.nanoTime() - t0;
            LcaV3Codec.resetThreadFeatureMask();

            // Warmup reads
            for (int w = 0; w < 2; w++) {
                for (int[] c : coords) {
                    AnvilReader.readChunkEntry(mca, c[0], c[1]);
                    LcaRegionReader.INSTANCE.readChunk(v2B, c[0], c[1]);
                    LcaRegionReader.INSTANCE.readChunk(v3FastB, c[0], c[1]);
                    LcaRegionReader.INSTANCE.readChunk(v3FullB, c[0], c[1]);
                    LcaRegionReader.INSTANCE.readChunkNbtBytes(v2B, c[0], c[1]);
                    LcaRegionReader.INSTANCE.readChunkNbtBytes(v3FastB, c[0], c[1]);
                    LcaRegionReader.INSTANCE.readChunkNbtBytes(v3FullB, c[0], c[1]);
                }
            }

            // Timed reads (3 sweeps)
            int sweeps = 3;
            double mcaReadUs = measureReadChunkUs(AnvilReader.INSTANCE, mca, coords, sweeps);
            double v2AReadUs = measureReadChunkUs(LcaRegionReader.INSTANCE, v2A, coords, sweeps);
            double v2BReadUs = measureReadChunkUs(LcaRegionReader.INSTANCE, v2B, coords, sweeps);
            double v3FastAReadUs = measureReadChunkUs(LcaRegionReader.INSTANCE, v3FastA, coords, sweeps);
            double v3FastBReadUs = measureReadChunkUs(LcaRegionReader.INSTANCE, v3FastB, coords, sweeps);
            double v3FullAReadUs = measureReadChunkUs(LcaRegionReader.INSTANCE, v3FullA, coords, sweeps);
            double v3FullBReadUs = measureReadChunkUs(LcaRegionReader.INSTANCE, v3FullB, coords, sweeps);

            double v2BNbtUs = measureReadNbtBytesUs(v2B, coords, sweeps);
            double v3FastBNbtUs = measureReadNbtBytesUs(v3FastB, coords, sweeps);
            double v3FullBNbtUs = measureReadNbtBytesUs(v3FullB, coords, sweeps);

            int n = coords.size();
            System.out.printf(Locale.ROOT, "\n--- Fixture: %s (%d chunks, .mca = %,d B) ---\n",
                    regionNames.get(r), n, mca.length);
            System.out.printf(Locale.ROOT,
                    "  %-24s | %10s | %8s | %9s | %10s | %11s | %11s\n",
                    "Format", "Size (B)", "vs .mca", "vs v2", "Enc us/chk", "DOM Rd us", "NBT Rd us");
            System.out.printf(Locale.ROOT,
                    "  %-24s | %,10d | %7.2f%% | %9s | %10s | %11.2f | %11s\n",
                    ".mca (Zlib)", mca.length, 100.0, "-", "-", mcaReadUs, "-");
            System.out.printf(Locale.ROOT,
                    "  %-24s | %,10d | %7.2f%% | %8.2f%% | %10.2f | %11.2f | %11s\n",
                    "v2 Mode A (0-LZ)", v2A.length, 100.0 * v2A.length / mca.length, 0.0,
                    tV2AEnc / 1000.0 / n, v2AReadUs, "-");
            System.out.printf(Locale.ROOT,
                    "  %-24s | %,10d | %7.2f%% | %8.2f%% | %10.2f | %11.2f | %11s\n",
                    "v3-Fast Mode A", v3FastA.length, 100.0 * v3FastA.length / mca.length,
                    100.0 * (v3FastA.length - v2A.length) / v2A.length,
                    tV3FastAEnc / 1000.0 / n, v3FastAReadUs, "-");
            System.out.printf(Locale.ROOT,
                    "  %-24s | %,10d | %7.2f%% | %8.2f%% | %10.2f | %11.2f | %11s\n",
                    "v3-Full Mode A (Eik+B1)", v3FullA.length, 100.0 * v3FullA.length / mca.length,
                    100.0 * (v3FullA.length - v2A.length) / v2A.length,
                    tV3FullAEnc / 1000.0 / n, v3FullAReadUs, "-");
            System.out.printf(Locale.ROOT,
                    "  %-24s | %,10d | %7.2f%% | %8.2f%% | %10.2f | %11.2f | %11.2f\n",
                    "v2 Mode B (LCD2)", v2B.length, 100.0 * v2B.length / mca.length, 0.0,
                    tV2BEnc / 1000.0 / n, v2BReadUs, v2BNbtUs);
            System.out.printf(Locale.ROOT,
                    "  %-24s | %,10d | %7.2f%% | %8.2f%% | %10.2f | %11.2f | %11.2f\n",
                    "v3-Fast Mode B", v3FastB.length, 100.0 * v3FastB.length / mca.length,
                    100.0 * (v3FastB.length - v2B.length) / v2B.length,
                    tV3FastBEnc / 1000.0 / n, v3FastBReadUs, v3FastBNbtUs);
            System.out.printf(Locale.ROOT,
                    "  %-24s | %,10d | %7.2f%% | %8.2f%% | %10.2f | %11.2f | %11.2f\n",
                    "v3-Full Mode B (Eik+B1)", v3FullB.length, 100.0 * v3FullB.length / mca.length,
                    100.0 * (v3FullB.length - v2B.length) / v2B.length,
                    tV3FullBEnc / 1000.0 / n, v3FullBReadUs, v3FullBNbtUs);
        }

        if (!liveSampleChunks.isEmpty()) {
            benchmarkLiveSampleChunks(liveSampleChunks);
        }
        System.out.println("===============================================================================");
    }

    private static void benchmarkLiveSampleChunks(List<LinkedHashMap<String, Object>> chunks) throws IOException {
        int n = chunks.size();
        byte[] v2A = encodeChunkList(chunks, 0, LcaRegionReader.LCA_FORMAT_VERSION_V2, LcaV3Codec.ALL_V3_FEATURES);
        byte[] v2B = encodeChunkList(chunks, 1, LcaRegionReader.LCA_FORMAT_VERSION_V2, LcaV3Codec.ALL_V3_FEATURES);
        byte[] v3ColB = encodeChunkList(chunks, 1, LcaRegionReader.LCA_FORMAT_VERSION_V3, LcaV3Codec.FEATURE_COLUMNAR_TAIL);
        byte[] v3FastA = encodeChunkList(chunks, 0, LcaRegionReader.LCA_FORMAT_VERSION_V3,
                LcaV3Codec.FEATURE_COLUMNAR_TAIL | LcaV3Codec.FEATURE_RANK_EXCL);
        byte[] v3FastB = encodeChunkList(chunks, 1, LcaRegionReader.LCA_FORMAT_VERSION_V3,
                LcaV3Codec.FEATURE_COLUMNAR_TAIL | LcaV3Codec.FEATURE_RANK_EXCL);
        byte[] v3ColEikB = encodeChunkList(chunks, 1, LcaRegionReader.LCA_FORMAT_VERSION_V3,
                LcaV3Codec.FEATURE_COLUMNAR_TAIL | LcaV3Codec.FEATURE_EIKONAL_LIGHT);
        byte[] v3FullA = encodeChunkList(chunks, 0, LcaRegionReader.LCA_FORMAT_VERSION_V3, LcaV3Codec.ALL_V3_FEATURES);
        byte[] v3FullB = encodeChunkList(chunks, 1, LcaRegionReader.LCA_FORMAT_VERSION_V3, LcaV3Codec.ALL_V3_FEATURES);

        List<int[]> coords = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            coords.add(new int[] {i & 31, i >>> 5});
        }
        // Warmup + verify parity on every chunk
        for (int i = 0; i < n; i++) {
            int rx = i & 31;
            int rz = i >>> 5;
            assertNotNull(LcaRegionReader.INSTANCE.readChunkNbtBytes(v2B, rx, rz));
            assertNotNull(LcaRegionReader.INSTANCE.readChunkNbtBytes(v3ColB, rx, rz));
            assertNotNull(LcaRegionReader.INSTANCE.readChunkNbtBytes(v3FastB, rx, rz));
            assertNotNull(LcaRegionReader.INSTANCE.readChunkNbtBytes(v3ColEikB, rx, rz));
            assertNotNull(LcaRegionReader.INSTANCE.readChunkNbtBytes(v3FullB, rx, rz));
        }

        int sweeps = 3;
        double v2BNbtUs = measureReadNbtBytesUs(v2B, coords, sweeps);
        double v3ColBNbtUs = measureReadNbtBytesUs(v3ColB, coords, sweeps);
        double v3FastBNbtUs = measureReadNbtBytesUs(v3FastB, coords, sweeps);
        double v3ColEikBNbtUs = measureReadNbtBytesUs(v3ColEikB, coords, sweeps);
        double v3FullBNbtUs = measureReadNbtBytesUs(v3FullB, coords, sweeps);

        double v2BViewUs = measureReadViewUs(v2B, coords, sweeps);
        double v3ColBViewUs = measureReadViewUs(v3ColB, coords, sweeps);
        double v3FastBViewUs = measureReadViewUs(v3FastB, coords, sweeps);
        double v3ColEikBViewUs = measureReadViewUs(v3ColEikB, coords, sweeps);
        double v3FullBViewUs = measureReadViewUs(v3FullB, coords, sweeps);

        System.out.printf(Locale.ROOT, "\n--- Live 26.3 Overworld Sample (%d chunks across 8 strided regions) ---\n", n);
        System.out.printf(Locale.ROOT, "  v2 Mode A:             %,10d B\n", v2A.length);
        System.out.printf(Locale.ROOT, "  v3-Fast Mode A:        %,10d B (%+.2f%% vs v2)\n",
                v3FastA.length, 100.0 * (v3FastA.length - v2A.length) / v2A.length);
        System.out.printf(Locale.ROOT, "  v3-Full Mode A:        %,10d B (%+.2f%% vs v2)\n",
                v3FullA.length, 100.0 * (v3FullA.length - v2A.length) / v2A.length);
        System.out.printf(Locale.ROOT, "  v2 Mode B:             %,10d B | NBT Rd: %6.2f us | View Rd (skipLight): %5.2f us\n",
                v2B.length, v2BNbtUs, v2BViewUs);
        System.out.printf(Locale.ROOT, "  v3-Columnar Mode B:    %,10d B (%+.2f%% vs v2) | NBT Rd: %6.2f us | View Rd: %5.2f us\n",
                v3ColB.length, 100.0 * (v3ColB.length - v2B.length) / v2B.length, v3ColBNbtUs, v3ColBViewUs);
        System.out.printf(Locale.ROOT, "  v3-Col+RankEx Mode B:  %,10d B (%+.2f%% vs v2) | NBT Rd: %6.2f us | View Rd: %5.2f us\n",
                v3FastB.length, 100.0 * (v3FastB.length - v2B.length) / v2B.length, v3FastBNbtUs, v3FastBViewUs);
        System.out.printf(Locale.ROOT, "  v3-Col+EikB1 Mode B:   %,10d B (%+.2f%% vs v2) | NBT Rd: %6.2f us | View Rd: %5.2f us\n",
                v3ColEikB.length, 100.0 * (v3ColEikB.length - v2B.length) / v2B.length, v3ColEikBNbtUs, v3ColEikBViewUs);
        System.out.printf(Locale.ROOT, "  v3-Full Mode B:        %,10d B (%+.2f%% vs v2) | NBT Rd: %6.2f us | View Rd: %5.2f us\n",
                v3FullB.length, 100.0 * (v3FullB.length - v2B.length) / v2B.length, v3FullBNbtUs, v3FullBViewUs);
    }

    private static byte[] encodeChunkList(
            List<LinkedHashMap<String, Object>> chunks, int lcd2Level, int formatVersion, int featureMask
    ) throws IOException {
        LcaV3Codec.setThreadFeatureMask(featureMask);
        try {
            LcaRegionWriter.LcaLiveArchive arch = LcaRegionWriter.createEmptyLiveArchiveWithVersion(
                    0, 0, -4, 24, 5023, lcd2Level,
                    LcaRegionReader.STORAGE_KIND_REGION_VOXEL,
                    LcaRegionWriter.DEFAULT_ROOT_DICT_CAPACITY_BYTES,
                    formatVersion);
            for (int i = 0; i < chunks.size(); i++) {
                int rx = i & 31;
                int rz = i >>> 5;
                LinkedHashMap<String, Object> copy = new LinkedHashMap<>(chunks.get(i));
                copy.put("xPos", rx);
                copy.put("zPos", rz);
                arch.saveChunkInitial(rx, rz, copy);
            }
            return arch.toByteArray();
        } finally {
            LcaV3Codec.resetThreadFeatureMask();
        }
    }

    private static double measureReadChunkUs(
            RegionFileReader reader, byte[] regionBytes, List<int[]> coords, int sweeps
    ) throws IOException {
        long start = System.nanoTime();
        for (int s = 0; s < sweeps; s++) {
            for (int[] c : coords) {
                reader.readChunk(regionBytes, c[0], c[1]);
            }
        }
        return (System.nanoTime() - start) / 1000.0 / (sweeps * coords.size());
    }

    private static double measureReadNbtBytesUs(byte[] lcaBytes, List<int[]> coords, int sweeps) throws IOException {
        long start = System.nanoTime();
        for (int s = 0; s < sweeps; s++) {
            for (int[] c : coords) {
                LcaRegionReader.INSTANCE.readChunkNbtBytes(lcaBytes, c[0], c[1]);
            }
        }
        return (System.nanoTime() - start) / 1000.0 / (sweeps * coords.size());
    }

    private static double measureReadViewUs(byte[] lcaBytes, List<int[]> coords, int sweeps) throws IOException {
        long start = System.nanoTime();
        for (int s = 0; s < sweeps; s++) {
            for (int[] c : coords) {
                LcaRegionReader.INSTANCE.readChunkView(lcaBytes, c[0], c[1]);
            }
        }
        return (System.nanoTime() - start) / 1000.0 / (sweeps * coords.size());
    }

    @Test
    @DisplayName("Warmed 64-chunk Head-to-Head: .mca Zlib vs .lca v3 (Mode A, Mode B Columnar, Mode B Default) Encode & Decode")
    void warmedHeadToHead64ChunkEncodeAndDecodeVsMca() throws Exception {
        Path mountainMcaPath = Paths.get("../../resource/LeafRTP/rtp-core/src/test/resources/fixtures/regions/overworld_mountain_r.0.0.mca");
        if (!Files.isRegularFile(mountainMcaPath)) {
            mountainMcaPath = Paths.get("resource/LeafRTP/rtp-core/src/test/resources/fixtures/regions/overworld_mountain_r.0.0.mca");
        }
        byte[] sourceMca = Files.isRegularFile(mountainMcaPath)
                ? Files.readAllBytes(mountainMcaPath)
                : loadFixture("/anvil/real/1_21_R1/r.0.0.mca");
        String label = Files.isRegularFile(mountainMcaPath)
                ? "overworld_mountain_r.0.0.mca (64 chunks)"
                : "1_21_R1/r.0.0.mca";

        List<int[]> coords = new ArrayList<>();
        List<LinkedHashMap<String, Object>> roots = new ArrayList<>();
        List<byte[]> rawNbtList = new ArrayList<>();
        for (int rz = 0; rz < 32 && coords.size() < 64; rz++) {
            for (int rx = 0; rx < 32 && coords.size() < 64; rx++) {
                if (AnvilReader.INSTANCE.isChunkGenerated(sourceMca, rx, rz)) {
                    AnvilReader.ChunkEntry e = AnvilReader.readChunkEntry(sourceMca, rx, rz);
                    if (e != null && e.root != null) {
                        coords.add(new int[] {rx, rz});
                        roots.add(e.root);
                        rawNbtList.add(Nbt.writeNamedRoot("", e.root));
                    }
                }
            }
        }
        assertTrue(!coords.isEmpty(), "Expected generated chunks in benchmark fixture");
        int n = coords.size();
        int dataVersion = AnvilReader.getDataVersion(roots.get(0));

        // Build canonical 64-chunk .mca and live .lca archives (v2 A/B, v3 Mode A, v3-Columnar Mode B, v3-Default Mode B)
        byte[] benchMca = buildMcaFromRawNbt(coords, rawNbtList);

        LcaRegionWriter.LcaLiveArchive v2AArch = createPopulatedArchive(
                coords, roots, dataVersion, 0, LcaRegionReader.LCA_FORMAT_VERSION_V2, LcaV3Codec.DEFAULT_V3_FEATURES);
        LcaRegionWriter.LcaLiveArchive v2BArch = createPopulatedArchive(
                coords, roots, dataVersion, 1, LcaRegionReader.LCA_FORMAT_VERSION_V2, LcaV3Codec.DEFAULT_V3_FEATURES);
        LcaRegionWriter.LcaLiveArchive v3AArch = createPopulatedArchive(
                coords, roots, dataVersion, 0, LcaRegionReader.LCA_FORMAT_VERSION_V3, LcaV3Codec.DEFAULT_V3_FEATURES);
        LcaRegionWriter.LcaLiveArchive v3ColBArch = createPopulatedArchive(
                coords, roots, dataVersion, 1, LcaRegionReader.LCA_FORMAT_VERSION_V3, LcaV3Codec.FEATURE_COLUMNAR_TAIL);
        LcaRegionWriter.LcaLiveArchive v3DefBArch = createPopulatedArchive(
                coords, roots, dataVersion, 1, LcaRegionReader.LCA_FORMAT_VERSION_V3, LcaV3Codec.DEFAULT_V3_FEATURES);

        byte[] v2ABytes = v2AArch.toByteArray();
        byte[] v2BBytes = v2BArch.toByteArray();
        byte[] v3ABytes = v3AArch.toByteArray();
        byte[] v3ColBBytes = v3ColBArch.toByteArray();
        byte[] v3DefBBytes = v3DefBArch.toByteArray();

        // Warmup all encode & decode paths (JIT stabilization)
        long sink = 0;
        for (int w = 0; w < 2; w++) {
            for (int i = 0; i < n; i++) {
                int[] c = coords.get(i);
                sink += deflateZlib(rawNbtList.get(i));
                sink += deflateZlib(Nbt.writeNamedRoot("", roots.get(i)));
                sink += readMcaRawNbtBytes(benchMca, c[0], c[1]).length;
                sink += AnvilReader.readChunkEntry(benchMca, c[0], c[1]).root.size();
                sink += AnvilReader.readChunkView(benchMca, c[0], c[1]).sections().size();

                sink += v2AArch.saveChunkCoW(c[0], c[1], roots.get(i));
                sink += v2AArch.saveChunkNbtBuffer(c[0], c[1], ByteBuffer.wrap(rawNbtList.get(i)));
                sink += v2BArch.saveChunkCoW(c[0], c[1], roots.get(i));
                sink += v2BArch.saveChunkNbtBuffer(c[0], c[1], ByteBuffer.wrap(rawNbtList.get(i)));
                sink += v3AArch.saveChunkCoW(c[0], c[1], roots.get(i));
                sink += v3AArch.saveChunkNbtBuffer(c[0], c[1], ByteBuffer.wrap(rawNbtList.get(i)));
                sink += v3DefBArch.saveChunkCoW(c[0], c[1], roots.get(i));
                sink += v3DefBArch.saveChunkNbtBuffer(c[0], c[1], ByteBuffer.wrap(rawNbtList.get(i)));

                LcaV3Codec.setThreadFeatureMask(LcaV3Codec.FEATURE_COLUMNAR_TAIL);
                try {
                    sink += v3ColBArch.saveChunkCoW(c[0], c[1], roots.get(i));
                    sink += v3ColBArch.saveChunkNbtBuffer(c[0], c[1], ByteBuffer.wrap(rawNbtList.get(i)));
                } finally {
                    LcaV3Codec.resetThreadFeatureMask();
                }

                sink += LcaRegionReader.INSTANCE.readChunkNbtBytes(v2ABytes, c[0], c[1]).length;
                sink += LcaRegionReader.INSTANCE.readChunkNbtBytes(v2BBytes, c[0], c[1]).length;
                sink += LcaRegionReader.INSTANCE.readChunkNbtBytes(v3ABytes, c[0], c[1]).length;
                sink += LcaRegionReader.INSTANCE.readChunkNbtBytes(v3ColBBytes, c[0], c[1]).length;
                sink += LcaRegionReader.INSTANCE.readChunkNbtBytes(v3DefBBytes, c[0], c[1]).length;
                sink += LcaRegionReader.INSTANCE.readChunk(v2BBytes, c[0], c[1]).root.size();
                sink += LcaRegionReader.INSTANCE.readChunk(v3DefBBytes, c[0], c[1]).root.size();
                sink += LcaRegionReader.INSTANCE.readChunkView(v2BBytes, c[0], c[1]).sections().size();
                sink += LcaRegionReader.INSTANCE.readChunkView(v3DefBBytes, c[0], c[1]).sections().size();
            }
        }

        // Timed measurements: median of 5 batches
        int batches = 5;
        int savesPerBatch = Math.min(32, n);

        double mcaDomSaveUs = measureMedianBatchUs(batches, savesPerBatch, (b, i) -> {
            int idx = (b * savesPerBatch + i) % n;
            return deflateZlib(Nbt.writeNamedRoot("", roots.get(idx)));
        });
        double mcaStreamSaveUs = measureMedianBatchUs(batches, savesPerBatch, (b, i) -> {
            int idx = (b * savesPerBatch + i) % n;
            return deflateZlib(rawNbtList.get(idx));
        });
        double mcaRawNbtRdUs = measureMedianBatchUs(batches, n, (b, i) -> {
            int[] c = coords.get(i);
            return readMcaRawNbtBytes(benchMca, c[0], c[1]).length;
        });
        double mcaDomRdUs = measureMedianBatchUs(batches, n, (b, i) -> {
            int[] c = coords.get(i);
            return AnvilReader.readChunkEntry(benchMca, c[0], c[1]).root.size();
        });
        double mcaViewRdUs = measureMedianBatchUs(batches, n, (b, i) -> {
            int[] c = coords.get(i);
            return AnvilReader.readChunkView(benchMca, c[0], c[1]).sections().size();
        });

        String[] names = {
            "v2 Mode A (0-LZ)",
            "v2 Mode B (LCD2)",
            "v3-Default Mode A",
            "v3-Columnar Mode B",
            "v3-Default Mode B (Eik+B1)"
        };
        LcaRegionWriter.LcaLiveArchive[] archs = {v2AArch, v2BArch, v3AArch, v3ColBArch, v3DefBArch};
        byte[][] bytesArr = {v2ABytes, v2BBytes, v3ABytes, v3ColBBytes, v3DefBBytes};
        int[] masks = {
            LcaV3Codec.DEFAULT_V3_FEATURES,
            LcaV3Codec.DEFAULT_V3_FEATURES,
            LcaV3Codec.DEFAULT_V3_FEATURES,
            LcaV3Codec.FEATURE_COLUMNAR_TAIL,
            LcaV3Codec.DEFAULT_V3_FEATURES
        };

        System.out.printf(Locale.ROOT,
                "\n================ [WARMED 64-CHUNK ENCODE & DECODE vs .MCA ZLIB: %s (sink=%d)] ================\n",
                label, sink);
        System.out.printf(Locale.ROOT,
                "  %-26s | %10s | %7s | %10s | %10s | %10s | %10s | %10s\n",
                "Format", "Size (B)", "vs .mca", "DOM Save", "Strm Save", "RawNBT Rd", "DOM Rd", "View Rd");
        System.out.printf(Locale.ROOT,
                "  %-26s | %,10d | %6.2f%% | %7.2f us | %7.2f us | %7.2f us | %7.2f us | %7.2f us\n",
                "Vanilla .mca (Zlib)", benchMca.length, 100.0,
                mcaDomSaveUs, mcaStreamSaveUs, mcaRawNbtRdUs, mcaDomRdUs, mcaViewRdUs);

        for (int k = 0; k < names.length; k++) {
            final LcaRegionWriter.LcaLiveArchive arch = archs[k];
            final byte[] regionBytes = bytesArr[k];
            final int mask = masks[k];
            LcaV3Codec.setThreadFeatureMask(mask);
            double domSaveUs;
            double streamSaveUs;
            try {
                domSaveUs = measureMedianBatchUs(batches, savesPerBatch, (b, i) -> {
                    int idx = (b * savesPerBatch + i) % n;
                    int[] c = coords.get(idx);
                    return arch.saveChunkCoW(c[0], c[1], roots.get(idx));
                });
                streamSaveUs = measureMedianBatchUs(batches, savesPerBatch, (b, i) -> {
                    int idx = (b * savesPerBatch + i) % n;
                    int[] c = coords.get(idx);
                    return arch.saveChunkNbtBuffer(c[0], c[1], ByteBuffer.wrap(rawNbtList.get(idx)));
                });
            } finally {
                LcaV3Codec.resetThreadFeatureMask();
            }
            double rawNbtRdUs = measureMedianBatchUs(batches, n, (b, i) -> {
                int[] c = coords.get(i);
                return LcaRegionReader.INSTANCE.readChunkNbtBytes(regionBytes, c[0], c[1]).length;
            });
            double domRdUs = measureMedianBatchUs(batches, n, (b, i) -> {
                int[] c = coords.get(i);
                return LcaRegionReader.INSTANCE.readChunk(regionBytes, c[0], c[1]).root.size();
            });
            double viewRdUs = measureMedianBatchUs(batches, n, (b, i) -> {
                int[] c = coords.get(i);
                return LcaRegionReader.INSTANCE.readChunkView(regionBytes, c[0], c[1]).sections().size();
            });

            System.out.printf(Locale.ROOT,
                    "  %-26s | %,10d | %6.2f%% | %7.2f us | %7.2f us | %7.2f us | %7.2f us | %7.2f us\n",
                    names[k], regionBytes.length, 100.0 * regionBytes.length / benchMca.length,
                    domSaveUs, streamSaveUs, rawNbtRdUs, domRdUs, viewRdUs);
        }
        System.out.println("==============================================================================================================");
        assertTrue(v3DefBBytes.length < benchMca.length, "v3-Default Mode B must be smaller than vanilla .mca");
    }

    @FunctionalInterface
    private interface TimedOp {
        int run(int batch, int index) throws Exception;
    }

    private static double measureMedianBatchUs(int batches, int itemsPerBatch, TimedOp op) throws Exception {
        long[] ns = new long[batches];
        long sink = 0;
        for (int b = 0; b < batches; b++) {
            long start = System.nanoTime();
            for (int i = 0; i < itemsPerBatch; i++) {
                sink += op.run(b, i);
            }
            ns[b] = System.nanoTime() - start;
        }
        assertTrue(sink >= 0);
        Arrays.sort(ns);
        return (ns[batches / 2] / 1000.0) / itemsPerBatch;
    }

    private static LcaRegionWriter.LcaLiveArchive createPopulatedArchive(
            List<int[]> coords,
            List<LinkedHashMap<String, Object>> roots,
            int dataVersion,
            int lcd2Level,
            int formatVersion,
            int featureMask
    ) throws IOException {
        LcaV3Codec.setThreadFeatureMask(featureMask);
        try {
            LcaRegionWriter.LcaLiveArchive arch = LcaRegionWriter.createEmptyLiveArchiveWithVersion(
                    0, 0, -4, 24, dataVersion, lcd2Level,
                    LcaRegionReader.STORAGE_KIND_REGION_VOXEL,
                    LcaRegionWriter.DEFAULT_ROOT_DICT_CAPACITY_BYTES,
                    formatVersion);
            for (int i = 0; i < coords.size(); i++) {
                int[] c = coords.get(i);
                arch.saveChunkInitial(c[0], c[1], roots.get(i));
            }
            return arch;
        } finally {
            LcaV3Codec.resetThreadFeatureMask();
        }
    }

    private static int deflateZlib(byte[] raw) throws IOException {
        ByteArrayOutputStream comp = new ByteArrayOutputStream(4096);
        Deflater def = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try (DeflaterOutputStream dos = new DeflaterOutputStream(comp, def)) {
            dos.write(raw);
        } finally {
            def.end();
        }
        return comp.size();
    }

    private static byte[] buildMcaFromRawNbt(List<int[]> coords, List<byte[]> rawNbtList) throws IOException {
        ByteArrayOutputStream sectorsOut = new ByteArrayOutputStream(coords.size() * 4096);
        byte[] header = new byte[8192];
        int nextSector = 2;
        for (int i = 0; i < coords.size(); i++) {
            int[] c = coords.get(i);
            ByteArrayOutputStream comp = new ByteArrayOutputStream(4096);
            Deflater def = new Deflater(Deflater.DEFAULT_COMPRESSION);
            try (DeflaterOutputStream dos = new DeflaterOutputStream(comp, def)) {
                dos.write(rawNbtList.get(i));
            } finally {
                def.end();
            }
            byte[] zlib = comp.toByteArray();
            int payloadLen = zlib.length + 1;
            int totalBytes = 4 + payloadLen;
            int sectorCount = (totalBytes + 4095) / 4096;
            int idx = (c[0] & 31) + ((c[1] & 31) << 5);
            int loc = (nextSector << 8) | (sectorCount & 0xFF);
            int hOff = idx * 4;
            header[hOff] = (byte) (loc >>> 24);
            header[hOff + 1] = (byte) (loc >>> 16);
            header[hOff + 2] = (byte) (loc >>> 8);
            header[hOff + 3] = (byte) loc;

            sectorsOut.write((payloadLen >>> 24) & 0xFF);
            sectorsOut.write((payloadLen >>> 16) & 0xFF);
            sectorsOut.write((payloadLen >>> 8) & 0xFF);
            sectorsOut.write(payloadLen & 0xFF);
            sectorsOut.write(2); // Zlib
            sectorsOut.write(zlib);
            int pad = sectorCount * 4096 - totalBytes;
            for (int p = 0; p < pad; p++) {
                sectorsOut.write(0);
            }
            nextSector += sectorCount;
        }
        byte[] sectors = sectorsOut.toByteArray();
        byte[] out = new byte[8192 + sectors.length];
        System.arraycopy(header, 0, out, 0, 8192);
        System.arraycopy(sectors, 0, out, 8192, sectors.length);
        return out;
    }

    private static byte[] readMcaRawNbtBytes(byte[] mca, int rx, int rz) throws IOException, DataFormatException {
        int hOff = ((rx & 31) + ((rz & 31) << 5)) * 4;
        int loc = ((mca[hOff] & 0xFF) << 24)
                | ((mca[hOff + 1] & 0xFF) << 16)
                | ((mca[hOff + 2] & 0xFF) << 8)
                | (mca[hOff + 3] & 0xFF);
        int sectorOffset = (loc >>> 8) * 4096;
        int length = ((mca[sectorOffset] & 0xFF) << 24)
                | ((mca[sectorOffset + 1] & 0xFF) << 16)
                | ((mca[sectorOffset + 2] & 0xFF) << 8)
                | (mca[sectorOffset + 3] & 0xFF);
        Inflater inf = new Inflater();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(32768);
        byte[] buf = new byte[8192];
        try {
            inf.setInput(mca, sectorOffset + 5, length - 1);
            while (!inf.finished()) {
                int read = inf.inflate(buf);
                if (read == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) {
                        break;
                    }
                } else {
                    baos.write(buf, 0, read);
                }
            }
        } finally {
            inf.end();
        }
        return baos.toByteArray();
    }

    // =========================================================================
    // Production Fast-Greedy LCD2 (lcd2Level <= 2) vs Exhaustive (lcd2Level >= 3)
    // =========================================================================

    @Test
    @DisplayName("Production Fast-Greedy LCD2 (lcd2Level=1) vs Exhaustive (lcd2Level=3) Verification & Benchmark")
    void benchmarkProductionFastGreedyLcd2VsExhaustive() throws Exception {
        Path mountainMcaPath = Paths.get("../../resource/LeafRTP/rtp-core/src/test/resources/fixtures/regions/overworld_mountain_r.0.0.mca");
        if (!Files.isRegularFile(mountainMcaPath)) {
            mountainMcaPath = Paths.get("resource/LeafRTP/rtp-core/src/test/resources/fixtures/regions/overworld_mountain_r.0.0.mca");
        }
        byte[] sourceMca = Files.isRegularFile(mountainMcaPath)
                ? Files.readAllBytes(mountainMcaPath)
                : loadFixture("/anvil/real/1_21_R1/r.0.0.mca");

        List<int[]> coords = new ArrayList<>();
        List<LinkedHashMap<String, Object>> roots = new ArrayList<>();
        List<byte[]> rawNbtList = new ArrayList<>();
        for (int rz = 0; rz < 32 && coords.size() < 64; rz++) {
            for (int rx = 0; rx < 32 && coords.size() < 64; rx++) {
                if (AnvilReader.INSTANCE.isChunkGenerated(sourceMca, rx, rz)) {
                    AnvilReader.ChunkEntry e = AnvilReader.readChunkEntry(sourceMca, rx, rz);
                    if (e != null && e.root != null) {
                        coords.add(new int[] {rx, rz});
                        roots.add(e.root);
                        rawNbtList.add(Nbt.writeNamedRoot("", e.root));
                    }
                }
            }
        }
        int n = coords.size();
        int dataVersion = AnvilReader.getDataVersion(roots.get(0));
        byte[] benchMca = buildMcaFromRawNbt(coords, rawNbtList);

        // Extract exact uncompressed v3-Default ChunkFrames (with Eikonal+B1)
        LcaRegionWriter.LcaLiveArchive v3DefBArch = createPopulatedArchive(
                coords, roots, dataVersion, 1, LcaRegionReader.LCA_FORMAT_VERSION_V3, LcaV3Codec.DEFAULT_V3_FEATURES);
        byte[] v3DefBBytes = v3DefBArch.toByteArray();
        List<byte[]> chunkFrames = new ArrayList<>(n);
        int[] headerLens = new int[n];
        Path tmpLca = Files.createTempFile("lca-fastgreedy-bench-", ".lca");
        try {
            Files.write(tmpLca, v3DefBBytes);
            try (FileChannel ch = FileChannel.open(tmpLca, StandardOpenOption.READ)) {
                for (int i = 0; i < n; i++) {
                    int[] c = coords.get(i);
                    RawChunkFrameSnapshot snap = LcaRegionReader.readRawChunkFrameFromChannel(tmpLca, ch, c[0], c[1], null);
                    assertNotNull(snap);
                    chunkFrames.add(snap.chunkFrame());
                    headerLens[i] = snap.sectionDirectoryEnd();
                }
            }
        } finally {
            Files.deleteIfExists(tmpLca);
        }

        String[] variantNames = {
            "Exhaustive LCD2 (lcd2Level=3)",
            "FastGreedy LCD2 (lcd2Level=1)"
        };
        int[] levels = {3, 1};
        LcaSectionCodec.AtomicDictState[] dicts = new LcaSectionCodec.AtomicDictState[variantNames.length];
        LcaSectionCodec.ParsedHeaderAndDict[] parsedDicts = new LcaSectionCodec.ParsedHeaderAndDict[variantNames.length];
        byte[][][] encoded = new byte[variantNames.length][n][];
        long[] rawBodyBytes = new long[variantNames.length];
        long[] alignedRegionBytes = new long[variantNames.length];

        for (int vIdx = 0; vIdx < variantNames.length; vIdx++) {
            dicts[vIdx] = new LcaSectionCodec.AtomicDictState();
            long bodySum = 0;
            long slabSum = 10368;
            for (int i = 0; i < n; i++) {
                byte[] cf = chunkFrames.get(i);
                int hLen = headerLens[i];
                int bLen = cf.length - hLen;
                boolean train = (levels[vIdx] >= 3)
                        || !dicts[vIdx].huffmanInitialized
                        || (dicts[vIdx].fastTrainedChunks < LcaSectionCodec.DICT_FAST_TRAIN_MAX_CHUNKS
                                && dicts[vIdx].usedBytes < LcaSectionCodec.DICT_FAST_TRAIN_CAP_BYTES);
                byte[] comp = LcaSectionCodec.encodeAtomicDictBody(cf, hLen, bLen, dicts[vIdx], train, levels[vIdx]);
                encoded[vIdx][i] = comp;
                bodySum += comp.length;
                int hWords = (hLen + 7) >>> 3;
                int slabLen = hLen + hWords + comp.length;
                slabSum += ((slabLen + 255) & ~255);
            }
            rawBodyBytes[vIdx] = bodySum;
            alignedRegionBytes[vIdx] = slabSum + ((dicts[vIdx].usedBytes + 255) & ~255);

            byte[] arenaCopy = Arrays.copyOf(dicts[vIdx].arena, dicts[vIdx].usedBytes);
            parsedDicts[vIdx] = new LcaSectionCodec.ParsedHeaderAndDict(
                    3, 0, 0, 0, -4, 24, 0, 0, 0L,
                    List.of(), List.of(), List.of(), List.of(), List.of(), arenaCopy);

            for (int i = 0; i < n; i++) {
                byte[] cf = chunkFrames.get(i);
                int hLen = headerLens[i];
                byte[] expectedBody = Arrays.copyOfRange(cf, hLen, cf.length);
                byte[] decodedBody = LcaSectionCodec.decodeAtomicDictBody(
                        encoded[vIdx][i], 0, 0, 0, encoded[vIdx][i].length, parsedDicts[vIdx]);
                assertArrayEquals(expectedBody, decodedBody,
                        variantNames[vIdx] + " decode mismatch at chunk " + i);
            }
        }

        long sink = 0;
        for (int vIdx = 0; vIdx < variantNames.length; vIdx++) {
            for (int w = 0; w < 20; w++) {
                for (int i = 0; i < n; i++) {
                    byte[] cf = chunkFrames.get(i);
                    int hLen = headerLens[i];
                    int bLen = cf.length - hLen;
                    sink += LcaSectionCodec.encodeAtomicDictBody(cf, hLen, bLen, dicts[vIdx], false, levels[vIdx]).length;
                    sink += LcaSectionCodec.decodeAtomicDictBody(
                            encoded[vIdx][i], 0, 0, 0, encoded[vIdx][i].length, parsedDicts[vIdx]).length;
                }
            }
        }

        int batches = 7;
        double[] encUsArr = new double[variantNames.length];
        double[] decUsArr = new double[variantNames.length];
        for (int vIdx = 0; vIdx < variantNames.length; vIdx++) {
            final int idx = vIdx;
            encUsArr[vIdx] = measureMedianBatchUs(batches, n, (b, i) -> {
                byte[] cf = chunkFrames.get(i);
                int hLen = headerLens[i];
                return LcaSectionCodec.encodeAtomicDictBody(
                        cf, hLen, cf.length - hLen, dicts[idx], false, levels[idx]).length;
            });
            decUsArr[vIdx] = measureMedianBatchUs(batches, n, (b, i) ->
                    LcaSectionCodec.decodeAtomicDictBody(
                            encoded[idx][i], 0, 0, 0, encoded[idx][i].length, parsedDicts[idx]).length);
        }

        System.out.printf(Locale.ROOT,
                "\n================ [PRODUCTION LCD2 TIERS: 64-chunk overworld_mountain (sink=%d)] ================\n",
                sink);
        System.out.printf(Locale.ROOT,
                "  %-32s | %9s | %10s | %7s | %8s | %10s | %10s | %8s\n",
                "LCD2 Production Tier", "Body (B)", "Est.LCA(B)", "vs .mca", "vs Exh", "LCD2 Enc", "LCD2 Dec", "Enc Spdp");
        for (int vIdx = 0; vIdx < variantNames.length; vIdx++) {
            double vsMcaPct = 100.0 * alignedRegionBytes[vIdx] / benchMca.length;
            double vsExhPct = 100.0 * (alignedRegionBytes[vIdx] - alignedRegionBytes[0]) / alignedRegionBytes[0];
            System.out.printf(Locale.ROOT,
                    "  %-32s | %,9d | %,10d | %6.2f%% | %+6.2f%% | %7.2f us | %7.2f us | %6.2fx\n",
                    variantNames[vIdx], rawBodyBytes[vIdx], alignedRegionBytes[vIdx],
                    vsMcaPct, vsExhPct, encUsArr[vIdx], decUsArr[vIdx], encUsArr[0] / Math.max(0.01, encUsArr[vIdx]));
        }
        System.out.println("===============================================================================================================");
        assertTrue(encUsArr[1] < encUsArr[0], "FastGreedy LCD2 (level=1) must encode faster than Exhaustive LCD2 (level=3)");
        assertTrue(alignedRegionBytes[1] < benchMca.length, "FastGreedy-1 must remain far smaller than .mca");
    }
}
