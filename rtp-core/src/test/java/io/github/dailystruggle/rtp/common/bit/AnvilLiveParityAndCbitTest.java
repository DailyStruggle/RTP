package io.github.dailystruggle.rtp.common.bit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.rtp.anvil.AnvilPrefilter;
import io.github.dailystruggle.rtp.anvil.AnvilPrefilterMetrics;
import io.github.dailystruggle.rtp.anvil.AnvilReader;
import io.github.dailystruggle.rtp.anvil.DataVersionSupport;
import io.github.dailystruggle.rtp.anvil.Nbt;
import io.github.dailystruggle.rtp.anvil.Verdict;
import io.github.dailystruggle.rtp.common.governor.ComputeGovernor;
import io.github.dailystruggle.rtp.common.governor.ComputeSignals;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link TestBitCodes} ({@code anvil-live-parity}),
 * {@link PlatformHookBitComponent} ({@code platform-hooks}),
 * {@link PipelineCbitCounters} ({@code pipeline-cbit}), and
 * {@link GovernorBitComponent} ({@code compute-governor}) (ADR-015, ADR-016, ADR-077, ADR-121).
 */
@DisplayName("REQ-API-F-009 & REQ-RTP-S-005 — anvil-live-parity, platform-hooks PBIT, and pipeline CBIT")
class AnvilLiveParityAndCbitTest {

    @TempDir
    File tempDir;

    private MockRTPServerAccessor accessor;

    @BeforeEach
    void setUp() {
        BitSeverityPolicy.resetDefaults();
        PlatformHookBitComponent.reset();
        PipelineCbitCounters.reset();
        accessor = RTPTestSetup.install(tempDir);
    }

    @AfterEach
    void tearDown() {
        PlatformHookBitComponent.reset();
        PipelineCbitCounters.reset();
        BitSeverityPolicy.resetDefaults();
    }

    private static LinkedHashMap<String, Object> paletteEntry(String name) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("Name", name);
        return m;
    }

    private static LinkedHashMap<String, Object> validSectionWithBiomes(
            byte sectionY, List<String> blockPalette, List<String> biomePalette) {
        LinkedHashMap<String, Object> sec = new LinkedHashMap<>();
        sec.put("Y", sectionY);
        LinkedHashMap<String, Object> blockStates = new LinkedHashMap<>();
        List<Object> entries = new ArrayList<>(blockPalette.size());
        for (String id : blockPalette) entries.add(paletteEntry(id));
        blockStates.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, entries));
        if (blockPalette.size() > 1) {
            blockStates.put("data", new long[256]);
        }
        sec.put("block_states", blockStates);

        LinkedHashMap<String, Object> biomes = new LinkedHashMap<>();
        Nbt.NbtList bPal = new Nbt.NbtList(
                Nbt.TAG_STRING, new ArrayList<Object>(biomePalette));
        biomes.put("palette", bPal);
        sec.put("biomes", biomes);
        return sec;
    }

    private static long[] validHeightmap(int rawEntry) {
        int[] values = new int[256];
        Arrays.fill(values, rawEntry);
        int bitsPerEntry = 9;
        int entriesPerLong = 64 / bitsPerEntry; // 7
        int longs = (256 + entriesPerLong - 1) / entriesPerLong; // 37
        long[] out = new long[longs];
        long mask = (1L << bitsPerEntry) - 1L;
        for (int i = 0; i < 256; i++) {
            int longIdx = i / entriesPerLong;
            int slot = i - longIdx * entriesPerLong;
            out[longIdx] |= (values[i] & mask) << (slot * bitsPerEntry);
        }
        return out;
    }

    private static LinkedHashMap<String, Object> chunkRoot(
            int dataVersion,
            long[] motionBlocking,
            List<LinkedHashMap<String, Object>> sections) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", dataVersion);
        LinkedHashMap<String, Object> heightmaps = new LinkedHashMap<>();
        heightmaps.put("MOTION_BLOCKING_NO_LEAVES", motionBlocking);
        root.put("Heightmaps", heightmaps);
        List<Object> sectionItems = new ArrayList<>(sections);
        root.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, sectionItems));
        return root;
    }

    private static byte[] writeSingleChunkRegion(LinkedHashMap<String, Object> root) throws IOException {
        byte[] nbtBytes = Nbt.writeNamedRoot("", root);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream(nbtBytes.length);
        try (DeflaterOutputStream z = new DeflaterOutputStream(compressed)) {
            z.write(nbtBytes);
        }
        byte[] zlib = compressed.toByteArray();
        int declaredLen = zlib.length + 1;
        int payloadTotal = 4 + 1 + zlib.length;
        int sectorCount = (payloadTotal + 4095) / 4096;
        int fileLen = 4096 + 4096 + sectorCount * 4096;
        byte[] out = new byte[fileLen];
        out[0] = 0;
        out[1] = 0;
        out[2] = 2;
        out[3] = (byte) sectorCount;
        out[4096] = 0x00;
        out[4097] = 0x00;
        out[4098] = 0x00;
        out[4099] = 0x01;
        int base = 8192;
        out[base] = (byte) ((declaredLen >>> 24) & 0xFF);
        out[base + 1] = (byte) ((declaredLen >>> 16) & 0xFF);
        out[base + 2] = (byte) ((declaredLen >>> 8) & 0xFF);
        out[base + 3] = (byte) (declaredLen & 0xFF);
        out[base + 4] = 2;
        System.arraycopy(zlib, 0, out, base + 5, zlib.length);
        return out;
    }

    @Test
    @DisplayName("anvil-live-parity emits TEST_ANVIL_WORLD_UNAVAILABLE (INFO/HEALTHY) when world save is empty")
    void anvilLiveParity_emptyWorldDir_emitsWorldUnavailableInfo() {
        BitReport report = TestBitCodes.checkWorld(tempDir.toPath(), "", -64, 320);

        assertEquals(BitHealth.HEALTHY, report.health());
        assertEquals(1, report.findings().size());
        assertEquals(
                TestBitCodes.TEST_ANVIL_WORLD_UNAVAILABLE.key(),
                report.findings().get(0).code());

        BitReport liveReport = TestBitCodes.checkLiveWorld();
        assertEquals(BitHealth.HEALTHY, liveReport.health());
        assertEquals(
                TestBitCodes.TEST_ANVIL_WORLD_UNAVAILABLE.key(),
                liveReport.findings().get(0).code());
    }

    @Test
    @DisplayName("anvil-live-parity validates valid .mca region files as HEALTHY with zero findings")
    void anvilLiveParity_validMcaFixtures_passCleanly() throws Exception {
        LinkedHashMap<String, Object> sec = validSectionWithBiomes(
                (byte) 0,
                List.of("minecraft:stone", "minecraft:air"),
                List.of("minecraft:plains", "minecraft:forest"));
        LinkedHashMap<String, Object> root = chunkRoot(
                DataVersionSupport.MC_1_21_DATA_VERSION,
                validHeightmap(65),
                List.of(sec));
        root.put("Status", "minecraft:full");

        byte[] mcaBytes = writeSingleChunkRegion(root);
        Path worldFolder = tempDir.toPath().resolve("world_mca");
        Path regionDir = Files.createDirectories(worldFolder.resolve("region"));
        Path mcaFile = Files.write(regionDir.resolve("r.0.0.mca"), mcaBytes);

        MockRTPWorld world = new MockRTPWorld("world_mca") {
            @Override
            public Path anvilWorldFolder() {
                return worldFolder;
            }
        };
        accessor.addWorld(world);

        BitReport mcaReport = TestBitCodes.checkRegionFile(mcaFile, -64, 320);
        assertEquals(BitHealth.HEALTHY, mcaReport.health(), mcaReport.toJson());
        assertTrue(mcaReport.findings().isEmpty(), mcaReport.toJson());

        // Also verify .linear region file handling on disk
        Path linearFile = regionDir.resolve("r.1.0.linear");
        Files.write(linearFile, new byte[]{1, 2, 3, 4});
        BitReport linearReport = TestBitCodes.checkRegionFile(linearFile, -64, 320);
        assertNotNull(linearReport);
    }

    @Test
    @DisplayName("anvil-live-parity flags unrecognized DataVersion, failed section decode, and biome palette mismatch")
    void anvilLiveParity_unrecognizedSchemaAndBadPalettes_emitRegisteredFindings() throws Exception {
        LinkedHashMap<String, Object> futureSec = validSectionWithBiomes(
                (byte) 0,
                List.of("minecraft:stone"),
                List.of("minecraft:plains"));
        LinkedHashMap<String, Object> futureRoot = chunkRoot(
                99999,
                validHeightmap(65),
                List.of(futureSec));
        byte[] futureMca = writeSingleChunkRegion(futureRoot);
        Path futureFile = tempDir.toPath().resolve("r.0.0.mca");
        Files.write(futureFile, futureMca);

        BitReport futureReport = TestBitCodes.checkRegionFile(futureFile, -64, 320);
        assertEquals(BitHealth.DEGRADED, futureReport.health());
        assertTrue(futureReport.findings().stream()
                .anyMatch(f -> f.code().equals(TestBitCodes.TEST_ANVIL_DATA_VERSION_UNRECOGNIZED.key())));

        LinkedHashMap<String, Object> brokenRoot = new LinkedHashMap<>();
        brokenRoot.put("DataVersion", DataVersionSupport.MC_1_21_DATA_VERSION);
        brokenRoot.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of()));
        AnvilReader.ChunkEntry brokenEntry = new AnvilReader.ChunkEntry(2, 64, brokenRoot);

        BitReport decodeFailedReport = TestBitCodes.validateChunkEntry(
                "r.0.0.mca", 0, 0, brokenEntry, -64, 320);
        assertEquals(BitHealth.FAILED, decodeFailedReport.health());
        assertTrue(decodeFailedReport.findings().stream()
                .anyMatch(f -> f.code().equals(TestBitCodes.TEST_ANVIL_SECTION_DECODE_FAILED.key())));

        LinkedHashMap<String, Object> badPaletteSec = validSectionWithBiomes(
                (byte) 0,
                List.of("INVALID_NO_NAMESPACE"),
                List.of("ALSO_BAD_BIOME"));
        LinkedHashMap<String, Object> badPaletteRoot = chunkRoot(
                DataVersionSupport.MC_1_21_DATA_VERSION,
                validHeightmap(65),
                List.of(badPaletteSec));
        AnvilReader.ChunkEntry badPaletteEntry = new AnvilReader.ChunkEntry(2, 64, badPaletteRoot);

        BitReport mismatchReport = TestBitCodes.validateChunkEntry(
                "r.0.0.mca", 0, 0, badPaletteEntry, -64, 320);
        assertEquals(BitHealth.DEGRADED, mismatchReport.health());
        assertTrue(mismatchReport.findings().stream()
                .anyMatch(f -> f.code().equals(TestBitCodes.TEST_ANVIL_BIOME_PALETTE_MISMATCH.key())));
    }

    @Test
    @DisplayName("PlatformHookBitComponent records and surfaces all 4 platform fallback PBIT codes")
    void platformHooksPbit_recordsAllDegradedSurfaces() {
        assertEquals(BitHealth.HEALTHY, PlatformHookBitComponent.report().health());

        PlatformHookBitComponent.recordAnvilInputDegraded("PaperAnvilOpener", "LinkageError");
        PlatformHookBitComponent.recordMenuRendererDegraded("book", "NoSuchMethodError");
        PlatformHookBitComponent.recordWorldBorderDegraded("world", "null border");
        PlatformHookBitComponent.recordPaletteReconcilerDegraded("Tag.REGISTRY_BLOCKS", "empty");

        BitReport report = PlatformHookBitComponent.report();
        assertEquals(BitHealth.DEGRADED, report.health());
        List<String> codes = report.findings().stream().map(BitFinding::code).sorted().toList();
        assertEquals(
                List.of(
                        PlatformHookBitComponent.ANVIL_INPUT_DEGRADED.key(),
                        PlatformHookBitComponent.MENU_RENDERER_DEGRADED.key(),
                        PlatformHookBitComponent.PALETTE_RECONCILER_DEGRADED.key(),
                        PlatformHookBitComponent.WORLD_BORDER_DEGRADED.key()),
                codes);
    }

    @Test
    @DisplayName("PipelineCbitCounters records prefilter bailouts, stale-chunk trips, and ticket high-water marks")
    void pipelineCbit_recordsPrefilterAndPipelineFindings() throws Exception {
        assertEquals(BitHealth.HEALTHY, PipelineCbitCounters.report().health());

        LinkedHashMap<String, Object> noHeightmapRoot = new LinkedHashMap<>();
        noHeightmapRoot.put("DataVersion", DataVersionSupport.MC_1_21_DATA_VERSION);
        noHeightmapRoot.put("Status", "minecraft:full");
        noHeightmapRoot.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of()));
        byte[] mcaBytes = writeSingleChunkRegion(noHeightmapRoot);
        Path worldFolder = tempDir.toPath().resolve("cbit_world");
        Path regionDir = Files.createDirectories(worldFolder.resolve("region"));
        Path regionFile = Files.write(regionDir.resolve("r.0.0.mca"), mcaBytes);

        AnvilPrefilter.ProbeResult probeResult =
                AnvilPrefilter.probeSyncDetailed(worldFolder, "", 0, 0, Set.of("LAVA"));
        assertEquals(Verdict.UNKNOWN, probeResult.verdict());
        assertTrue(AnvilPrefilterMetrics.unparseableSections.get() > 0);

        PipelineCbitCounters.recordUnsupportedCompression(regionFile, 0, 0, "compression=99");
        PipelineCbitCounters.recordStaleChunkGuardTripped("cbit_world", 3, -2);
        PipelineCbitCounters.recordChunkTicketHighWater("cbit_world", 25L, 16L);

        BitReport report = PipelineCbitCounters.report();
        assertEquals(BitHealth.DEGRADED, report.health());
        List<String> codes = report.findings().stream().map(BitFinding::code).sorted().toList();
        assertEquals(
                List.of(
                        PipelineCbitCounters.CHUNK_TICKET_HIGH_WATER.key(),
                        PipelineCbitCounters.PREFILTER_COMPRESSION_UNSUPPORTED.key(),
                        PipelineCbitCounters.PREFILTER_SECTION_UNPARSEABLE.key(),
                        PipelineCbitCounters.STALE_CHUNK_GUARD_TRIPPED.key()),
                codes);
    }

    @Test
    @DisplayName("GovernorBitComponent emits all 4 GOVERNOR CBIT codes when budget is reduced, at floor, stale, or failed")
    void governorBitComponent_emitsAllRegisteredCodes() {
        ComputeGovernor gov = new ComputeGovernor();
        long startNanos = 1_000_000_000L;
        for (int i = 0; i < 20; i++) {
            ComputeSignals pressure = ComputeSignals.builder()
                    .autoTune(true)
                    .tickStalled(true)
                    .mspt(ComputeSignals.SampledSignal.fresh(95.0, 0L))
                    .meanTps(ComputeSignals.SampledSignal.stale(
                            12.0, 0L, ComputeGovernor.STALE_WARNING_NANOS + 1_000_000_000L))
                    .region(new ComputeSignals.RegionDemand("default", 10L, 1.0, 4, 8, 2, false))
                    .build();
            gov.evaluate(pressure, startNanos + i * ComputeGovernor.EVAL_INTERVAL_NANOS);
        }
        gov.recordEvaluationFailure(startNanos, new RuntimeException("synthetic failure"));

        BitReport report = GovernorBitComponent.reportFor(
                gov, startNanos + 20 * ComputeGovernor.EVAL_INTERVAL_NANOS + ComputeGovernor.FLOOR_WARNING_NANOS + 1_000_000_000L);
        assertEquals(BitHealth.DEGRADED, report.health());
        Set<String> codes = Set.copyOf(report.findings().stream().map(BitFinding::code).toList());
        assertTrue(codes.contains(GovernorBitComponent.GOVERNOR_KNOB_REDUCED.key()), codes.toString());
        assertTrue(codes.contains(GovernorBitComponent.GOVERNOR_KNOB_AT_FLOOR.key()), codes.toString());
        assertTrue(codes.contains(GovernorBitComponent.GOVERNOR_SIGNAL_STALE.key()), codes.toString());
        assertTrue(codes.contains(GovernorBitComponent.GOVERNOR_EVALUATION_FAILED.key()), codes.toString());
        assertFalse(codes.isEmpty());
        assertNotNull(GovernorBitComponent.COMPUTE_GOVERNOR);
    }
}
