package io.github.dailystruggle.rtp.common.bit;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.rtp.anvil.AnvilChunkView;
import io.github.dailystruggle.rtp.anvil.AnvilReader;
import io.github.dailystruggle.rtp.anvil.BiomePaletteSection;
import io.github.dailystruggle.rtp.anvil.DataVersionSupport;
import io.github.dailystruggle.rtp.anvil.DynamicZstdBinding;
import io.github.dailystruggle.rtp.anvil.LinearRegionReader;
import io.github.dailystruggle.rtp.anvil.Nbt;
import io.github.dailystruggle.rtp.anvil.PaletteSection;
import io.github.dailystruggle.rtp.anvil.RegionFileReader;
import io.github.dailystruggle.rtp.anvil.RegionFileResolver;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Live-world chunk & NBT format version canary ({@code anvil-live-parity}) and its {@code TEST}
 * BIT codes (ADR-016, ADR-077, ADR-113, ADR-121).
 *
 * <p>Reads one existing {@code .mca} or {@code .linear} file from the primary {@link RTPWorld}
 * save directory off-thread ({@link BitSideEffect#SAFE}, zero main-thread chunk loads per S-005)
 * and verifies that {@code anvil-api} decodes a real populated chunk column with valid
 * {@code namespace:id} block/biome palettes and heightmap Y-bounds alignment.
 */
public final class TestBitCodes {
    private static final long COMPONENT_TIMEOUT_MILLIS = 5_000L;
    private static final int DEFAULT_MIN_HEIGHT = -64;
    private static final int DEFAULT_MAX_HEIGHT = 320;
    private static final Pattern NAMESPACE_ID_PATTERN =
            Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");

    public static final BitCode TEST_ANVIL_WORLD_UNAVAILABLE = RtpBitCodes.TEST.register(
            4011,
            "TEST_ANVIL_WORLD_UNAVAILABLE",
            Level.INFO,
            BitTier.PBIT,
            "anvil",
            "",
            "Generate or save at least one world region file before running live Anvil parity verification.");

    public static final BitCode TEST_ANVIL_DATA_VERSION_UNRECOGNIZED = RtpBitCodes.TEST.register(
            4012,
            "TEST_ANVIL_DATA_VERSION_UNRECOGNIZED",
            Level.WARNING,
            BitTier.PBIT,
            "anvil",
            "",
            "Verify compatibility with the server's chunk DataVersion and section NBT schema.");

    public static final BitCode TEST_ANVIL_SECTION_DECODE_FAILED = RtpBitCodes.TEST.register(
            4013,
            "TEST_ANVIL_SECTION_DECODE_FAILED",
            Level.ERROR,
            BitTier.PBIT,
            "anvil",
            "",
            "Inspect region file integrity or update anvil-api for the new chunk section/heightmap layout.");

    public static final BitCode TEST_ANVIL_BIOME_PALETTE_MISMATCH = RtpBitCodes.TEST.register(
            4014,
            "TEST_ANVIL_BIOME_PALETTE_MISMATCH",
            Level.WARNING,
            BitTier.PBIT,
            "anvil",
            "",
            "Verify that block and biome palette entries use valid namespace:id identifiers.");

    public static final BitComponent ANVIL_LIVE_PARITY = new BitComponent(
            "anvil-live-parity",
            "anvil",
            BitTier.PBIT,
            BitSideEffect.SAFE,
            COMPONENT_TIMEOUT_MILLIS,
            TestBitCodes::checkLiveWorld);

    private TestBitCodes() {}

    /**
     * Runs the live-world Anvil/Linear parity check against the primary loaded {@link RTPWorld},
     * or emits {@link #TEST_ANVIL_WORLD_UNAVAILABLE} ({@code INFO}) when no world save exists yet.
     */
    public static BitReport checkLiveWorld() {
        RTPServerAccessor accessor = RTPRuntime.serverAccessor();
        if (accessor == null) {
            return worldUnavailableReport("anvil-live-parity", "No server accessor is installed.");
        }
        List<RTPWorld<?>> worlds;
        try {
            worlds = accessor.getRTPWorlds();
        } catch (RuntimeException e) {
            return worldUnavailableReport("anvil-live-parity", "World list is unavailable: " + e.getMessage());
        }
        if (worlds == null || worlds.isEmpty()) {
            return worldUnavailableReport("anvil-live-parity", "No loaded RTPWorld is available yet.");
        }
        for (RTPWorld<?> world : worlds) {
            if (world == null) continue;
            Path worldFolder = world.anvilWorldFolder();
            if (worldFolder == null) continue;
            String dim = world.anvilDimensionSubpath();
            Path regionDir = RegionFileResolver.regionDirectoryFor(worldFolder, dim == null ? "" : dim);
            if (! findCandidateRegionFiles(regionDir).isEmpty()) {
                return checkWorld(worldFolder, dim, world.getMinHeight(), world.getMaxHeight());
            }
        }
        return worldUnavailableReport(
                "anvil-live-parity", "No world save directory contains a non-empty .mca or .linear region file yet.");
    }

    /**
     * Runs the Anvil/Linear live parity check against a specific world folder and dimension subpath.
     */
    public static BitReport checkWorld(
            Path worldFolder, String dimensionSubpath, int minHeight, int maxHeight) {
        if (worldFolder == null) {
            return worldUnavailableReport("anvil-live-parity", "World folder path is null.");
        }
        Path regionDir = RegionFileResolver.regionDirectoryFor(
                worldFolder, dimensionSubpath == null ? "" : dimensionSubpath);
        List<Path> regionFiles = findCandidateRegionFiles(regionDir);
        if (regionFiles.isEmpty()) {
            return worldUnavailableReport(
                    regionDir.toString(), "No non-empty .mca or .linear region file exists in " + regionDir);
        }
        for (Path regionFile : regionFiles) {
            BitReport report = checkRegionFile(regionFile, minHeight, maxHeight);
            boolean onlyUnavailable = report.findings().size() == 1
                    && report.findings().get(0).code().equals(TEST_ANVIL_WORLD_UNAVAILABLE.key());
            if (!onlyUnavailable) {
                return report;
            }
        }
        return checkRegionFile(regionFiles.get(0), minHeight, maxHeight);
    }

    /**
     * Reads one populated chunk column from {@code regionFile} ({@code .mca} or {@code .linear})
     * and validates its {@code DataVersion}, section decode, palettes, and heightmap bounds.
     */
    public static BitReport checkRegionFile(Path regionFile, int minHeight, int maxHeight) {
        if (regionFile == null || !Files.isRegularFile(regionFile)) {
            return worldUnavailableReport(
                    regionFile == null ? "anvil-live-parity" : regionFile.toString(),
                    "Region file does not exist on disk.");
        }
        String fileName = regionFile.getFileName().toString();
        boolean isLinear = fileName.endsWith(".linear");
        if (isLinear && !DynamicZstdBinding.isAvailable()) {
            return worldUnavailableReport(
                    regionFile.toString(),
                    "Linear region file present but host Zstandard decompressor is unavailable.");
        }
        RegionFileReader reader = isLinear ? LinearRegionReader.INSTANCE : AnvilReader.INSTANCE;
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(regionFile);
        } catch (IOException e) {
            return BitReport.of(List.of(BitFinding.of(
                    TEST_ANVIL_SECTION_DECODE_FAILED,
                    regionFile.toString(),
                    "file",
                    "Failed to read region file bytes: " + e.getMessage(),
                    "readable .mca or .linear region file",
                    TEST_ANVIL_SECTION_DECODE_FAILED.remediation())));
        }
        if (bytes.length == 0) {
            return worldUnavailableReport(regionFile.toString(), "Region file is empty (0 bytes).");
        }

        for (int rz = 0; rz < 32; rz++) {
            for (int rx = 0; rx < 32; rx++) {
                if (!reader.isChunkGenerated(bytes, bytes.length, rx, rz)) {
                    continue;
                }
                AnvilReader.ChunkEntry entry;
                try {
                    entry = reader.readChunk(bytes, bytes.length, rx, rz);
                } catch (IOException | RuntimeException e) {
                    return BitReport.of(List.of(BitFinding.of(
                            TEST_ANVIL_SECTION_DECODE_FAILED,
                            regionFile.toString(),
                            "chunk(" + rx + "," + rz + ")",
                            "Failed to decode chunk (" + rx + "," + rz + "): "
                                    + e.getClass().getSimpleName() + ": " + e.getMessage(),
                            "decodable chunk NBT payload",
                            TEST_ANVIL_SECTION_DECODE_FAILED.remediation())));
                }
                if (entry == null) {
                    continue;
                }
                if (AnvilReader.isIncompleteChunk(entry.root)) {
                    continue;
                }
                return validateChunkEntry(
                        regionFile.toString(),
                        rx,
                        rz,
                        entry,
                        minHeight < maxHeight ? minHeight : DEFAULT_MIN_HEIGHT,
                        minHeight < maxHeight ? maxHeight : DEFAULT_MAX_HEIGHT);
            }
        }
        return worldUnavailableReport(
                regionFile.toString(), "Region file contains no fully generated chunk columns yet.");
    }

    /**
     * Validates a decoded {@link AnvilReader.ChunkEntry} against schema, section, palette, and
     * heightmap invariants.
     */
    public static BitReport validateChunkEntry(
            String source, int rx, int rz, AnvilReader.ChunkEntry entry, int minHeight, int maxHeight) {
        List<BitFinding> findings = new ArrayList<>();
        String chunkPath = "chunk(" + rx + "," + rz + ")";
        if (entry == null || entry.root == null) {
            findings.add(BitFinding.of(
                    TEST_ANVIL_SECTION_DECODE_FAILED,
                    source,
                    chunkPath,
                    "Decoded chunk entry or root NBT compound is null.",
                    "non-null chunk root compound",
                    TEST_ANVIL_SECTION_DECODE_FAILED.remediation()));
            return BitReport.of(findings);
        }

        int dataVersion = AnvilReader.getDataVersion(entry.root);
        Nbt.NbtList rawSections = AnvilReader.getSections(entry.root);
        if (dataVersion < DataVersionSupport.MIN_SUPPORTED_DATA_VERSION
                || dataVersion > DataVersionSupport.MAX_SUPPORTED_DATA_VERSION
                || rawSections == null
                || rawSections.items.isEmpty()
                || !hasExpectedSectionKeys(rawSections)) {
            findings.add(BitFinding.of(
                    TEST_ANVIL_DATA_VERSION_UNRECOGNIZED,
                    source,
                    chunkPath + ".DataVersion",
                    "Chunk DataVersion=" + dataVersion + " is outside tested range ["
                            + DataVersionSupport.MIN_SUPPORTED_DATA_VERSION + ".."
                            + DataVersionSupport.MAX_SUPPORTED_DATA_VERSION
                            + "] or lacks expected section keys (Y, block_states).",
                    "DataVersion in [" + DataVersionSupport.MIN_SUPPORTED_DATA_VERSION + ".."
                            + DataVersionSupport.MAX_SUPPORTED_DATA_VERSION + "] with standard sections",
                    TEST_ANVIL_DATA_VERSION_UNRECOGNIZED.remediation()));
        }

        AnvilChunkView view = AnvilReader.toView(entry.root);
        long[] heightmap = AnvilReader.getMotionBlockingNoLeaves(entry.root);

        boolean sectionDecodeFailed = view.sections().isEmpty()
                || heightmap == null
                || heightmap.length == 0;
        if (!sectionDecodeFailed) {
            for (PaletteSection section : view.sections()) {
                if (section.hasMalformedData() || section.palette().isEmpty()) {
                    sectionDecodeFailed = true;
                    break;
                }
            }
        }
        int effectiveMinY = minHeight < maxHeight ? minHeight : DEFAULT_MIN_HEIGHT;
        int effectiveMaxY = minHeight < maxHeight ? maxHeight : DEFAULT_MAX_HEIGHT;
        int sampleSurfaceY = (heightmap != null && heightmap.length > 0)
                ? AnvilReader.readHeightmapEntry(heightmap, 8, 8, effectiveMinY)
                : Integer.MIN_VALUE;
        int viewMinY = view.minHeight();
        int viewMaxY = view.sections().stream()
                .mapToInt(s -> s.sectionY() * 16 + 15)
                .max()
                .orElse(viewMinY);

        if (sectionDecodeFailed
                || sampleSurfaceY == Integer.MIN_VALUE
                || sampleSurfaceY < effectiveMinY
                || sampleSurfaceY > effectiveMaxY
                || viewMinY < effectiveMinY - 64
                || viewMaxY > effectiveMaxY + 64) {
            findings.add(BitFinding.of(
                    TEST_ANVIL_SECTION_DECODE_FAILED,
                    source,
                    chunkPath + ".sections",
                    "Failed to decode valid block sections or heightmap within Y bounds ["
                            + effectiveMinY + ".." + effectiveMaxY + "] (decodedSections="
                            + view.sections().size() + ", sampleSurfaceY=" + sampleSurfaceY + ").",
                    "non-empty PaletteSections and heightmap aligned to ["
                            + effectiveMinY + ".." + effectiveMaxY + "]",
                    TEST_ANVIL_SECTION_DECODE_FAILED.remediation()));
        }

        boolean blockPaletteValid = !view.sections().isEmpty();
        for (PaletteSection section : view.sections()) {
            for (String blockId : section.palette()) {
                if (!isValidNamespacedId(blockId)) {
                    blockPaletteValid = false;
                    break;
                }
            }
            if (!blockPaletteValid) break;
        }

        Set<String> biomesPresent = view.getBiomesPresent();
        boolean biomePaletteValid = !view.biomeSections().isEmpty() && !biomesPresent.isEmpty();
        for (BiomePaletteSection biomeSection : view.biomeSections()) {
            if (biomeSection.palette().isEmpty()) {
                biomePaletteValid = false;
                break;
            }
            for (String biomeId : biomeSection.palette()) {
                if (!isValidNamespacedId(biomeId)) {
                    biomePaletteValid = false;
                    break;
                }
            }
            if (!biomePaletteValid) break;
        }

        if (!blockPaletteValid || !biomePaletteValid) {
            findings.add(BitFinding.of(
                    TEST_ANVIL_BIOME_PALETTE_MISMATCH,
                    source,
                    chunkPath + ".palettes",
                    "Decoded block or biome palette failed namespace:id validation (blockPaletteValid="
                            + blockPaletteValid + ", biomePaletteValid=" + biomePaletteValid
                            + ", biomes=" + biomesPresent.size() + ").",
                    "non-empty block and biome palettes with valid namespace:id entries",
                    TEST_ANVIL_BIOME_PALETTE_MISMATCH.remediation()));
        }

        List<BitFinding> effective = new ArrayList<>(findings.size());
        for (BitFinding finding : findings) {
            BitSeverityPolicy.effectiveFinding(finding).ifPresent(effective::add);
        }
        return BitReport.of(effective);
    }

    private static boolean hasExpectedSectionKeys(Nbt.NbtList rawSections) {
        boolean sawBlockStatesSection = false;
        for (Object item : rawSections.items) {
            if (!(item instanceof Map<?, ?> map)) return false;
            if (!map.containsKey("Y")) return false;
            if (map.get("block_states") instanceof Map<?, ?> bs && bs.containsKey("palette")) {
                sawBlockStatesSection = true;
            }
        }
        return sawBlockStatesSection;
    }

    static boolean isValidNamespacedId(String id) {
        return id != null && NAMESPACE_ID_PATTERN.matcher(id).matches();
    }

    private static List<Path> findCandidateRegionFiles(Path regionDir) {
        if (regionDir == null || !Files.isDirectory(regionDir)) {
            return List.of();
        }
        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "r.*.*")) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (!(name.endsWith(".mca") || name.endsWith(".linear"))) continue;
                if (Files.isRegularFile(entry) && Files.size(entry) > 0L) {
                    candidates.add(entry);
                }
            }
        } catch (IOException ignored) {
            return List.of();
        }
        candidates.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return candidates;
    }

    private static BitReport worldUnavailableReport(String source, String detail) {
        BitFinding finding = BitFinding.of(
                TEST_ANVIL_WORLD_UNAVAILABLE,
                source,
                "world.region",
                detail,
                "at least one generated .mca or .linear region file",
                TEST_ANVIL_WORLD_UNAVAILABLE.remediation());
        List<BitFinding> findings = new ArrayList<>(1);
        BitSeverityPolicy.effectiveFinding(finding).ifPresent(findings::add);
        return BitReport.of(findings);
    }
}
