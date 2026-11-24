package io.github.dailystruggle.anvil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Offline multi-format ({@code .mca}, {@code .lca}, {@code .linear}, {@code .b_linear}) chunk-pair
 * similarity auditor quantifying topographical, voxel, biome, cave, ore, flora, and structure
 * parity against reference Vanilla regions without live chunk loads.
 *
 * @governedBy ADR-016
 * @governedBy ADR-077
 * @governedBy ADR-115
 * @governedBy anvil-api-ADR-002
 * @governedBy anvil-api-ADR-003
 * @trace REQ-RTP-S-005
 */
public final class RegionSimilarityAuditor {

    private static final Logger LOGGER = Logger.getLogger(RegionSimilarityAuditor.class.getName());

    private static final Pattern REGION_FILE_PATTERN =
            Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.([A-Za-z0-9_]+)");

    private static final List<String> EXTENSION_PRIORITY =
            List.of(".lca", ".mca", ".linear", ".b_linear");

    private static final List<String> HEIGHTMAP_TYPES =
            List.of("WORLD_SURFACE_WG", "OCEAN_FLOOR_WG", "MOTION_BLOCKING_NO_LEAVES");

    private static final int MIN_SECTION_Y = -4;
    private static final int MAX_SECTION_Y = 19;
    private static final int SECTION_COUNT = MAX_SECTION_Y - MIN_SECTION_Y + 1; // 24
    private static final int MIN_WORLD_Y = MIN_SECTION_Y * 16; // -64
    private static final int MAX_WORLD_Y = (MAX_SECTION_Y + 1) * 16 - 1; // 319
    private static final int WORLD_HEIGHT = MAX_WORLD_Y - MIN_WORLD_Y + 1; // 384
    private static final int SEA_LEVEL_Y = 62;
    private static final int DEEP_CAVE_MAX_Y = -54;

    private static final String CANONICAL_AIR = "minecraft:air";
    private static final String DEFAULT_BIOME = "minecraft:plains";

    private static final List<String> ELEVATION_BANDS = List.of(
            "[-64,-33]",
            "[-32,0]",
            "[1,47]",
            "[48,62]",
            "[63,95]",
            "[96,127]",
            "[128,191]",
            "[192,319]");

    private static final List<String> ORE_FAMILIES =
            List.of("coal", "iron", "copper", "gold", "redstone", "lapis", "diamond", "emerald");

    private static final List<String> ORE_Y_BANDS =
            List.of("[-64,-33]", "[-32,0]", "[1,47]", "[48,127]", "[128,319]");

    private static final Set<String> AIR_BLOCKS = Set.of(
            "minecraft:air",
            "minecraft:cave_air",
            "minecraft:void_air",
            "minecraft:light",
            "minecraft:structure_void");

    private static final Set<String> FLUID_BLOCKS = Set.of(
            "minecraft:water",
            "minecraft:flowing_water",
            "minecraft:bubble_column",
            "minecraft:lava",
            "minecraft:flowing_lava");

    private static final Set<String> WATER_BLOCKS = Set.of(
            "minecraft:water",
            "minecraft:flowing_water",
            "minecraft:bubble_column");

    private static final Set<String> LAVA_BLOCKS = Set.of(
            "minecraft:lava",
            "minecraft:flowing_lava");

    private static final Set<String> SURFACE_STRATA_EXACT = Set.of(
            "minecraft:grass_block",
            "minecraft:dirt",
            "minecraft:coarse_dirt",
            "minecraft:rooted_dirt",
            "minecraft:podzol",
            "minecraft:mycelium",
            "minecraft:mud",
            "minecraft:muddy_mangrove_roots",
            "minecraft:clay",
            "minecraft:sand",
            "minecraft:red_sand",
            "minecraft:sandstone",
            "minecraft:chiseled_sandstone",
            "minecraft:cut_sandstone",
            "minecraft:smooth_sandstone",
            "minecraft:red_sandstone",
            "minecraft:chiseled_red_sandstone",
            "minecraft:cut_red_sandstone",
            "minecraft:smooth_red_sandstone",
            "minecraft:gravel",
            "minecraft:snow",
            "minecraft:snow_block",
            "minecraft:powder_snow",
            "minecraft:ice",
            "minecraft:packed_ice",
            "minecraft:blue_ice",
            "minecraft:frosted_ice",
            "minecraft:terracotta",
            "minecraft:moss_block",
            "minecraft:pale_moss_block",
            "minecraft:calcite",
            "minecraft:dripstone_block",
            "minecraft:pointed_dripstone",
            "minecraft:farmland",
            "minecraft:dirt_path",
            "minecraft:soul_sand",
            "minecraft:soul_soil");

    private static final Set<String> FLORA_EXACT = Set.of(
            "minecraft:short_grass",
            "minecraft:grass",
            "minecraft:tall_grass",
            "minecraft:fern",
            "minecraft:large_fern",
            "minecraft:short_dry_grass",
            "minecraft:tall_dry_grass",
            "minecraft:bush",
            "minecraft:firefly_bush",
            "minecraft:dead_bush",
            "minecraft:cactus",
            "minecraft:cactus_flower",
            "minecraft:sugar_cane",
            "minecraft:bamboo",
            "minecraft:bamboo_sapling",
            "minecraft:vine",
            "minecraft:glow_lichen",
            "minecraft:moss_carpet",
            "minecraft:pale_moss_carpet",
            "minecraft:pale_hanging_moss",
            "minecraft:pink_petals",
            "minecraft:wildflowers",
            "minecraft:leaf_litter",
            "minecraft:sweet_berry_bush",
            "minecraft:kelp",
            "minecraft:kelp_plant",
            "minecraft:seagrass",
            "minecraft:tall_seagrass",
            "minecraft:lily_pad",
            "minecraft:sea_pickle",
            "minecraft:azalea",
            "minecraft:flowering_azalea",
            "minecraft:spore_blossom",
            "minecraft:hanging_roots",
            "minecraft:mangrove_roots",
            "minecraft:big_dripleaf",
            "minecraft:big_dripleaf_stem",
            "minecraft:small_dripleaf",
            "minecraft:brown_mushroom",
            "minecraft:red_mushroom",
            "minecraft:brown_mushroom_block",
            "minecraft:red_mushroom_block",
            "minecraft:mushroom_stem",
            "minecraft:shroomlight",
            "minecraft:poppy",
            "minecraft:dandelion",
            "minecraft:cornflower",
            "minecraft:oxeye_daisy",
            "minecraft:allium",
            "minecraft:azure_bluet",
            "minecraft:blue_orchid",
            "minecraft:lily_of_the_valley",
            "minecraft:lilac",
            "minecraft:rose_bush",
            "minecraft:peony",
            "minecraft:sunflower",
            "minecraft:torchflower",
            "minecraft:pitcher_plant",
            "minecraft:open_eyeblossom",
            "minecraft:closed_eyeblossom",
            "minecraft:pumpkin",
            "minecraft:carved_pumpkin",
            "minecraft:melon",
            "minecraft:bee_nest",
            "minecraft:cocoa",
            "minecraft:cave_vines",
            "minecraft:cave_vines_plant",
            "minecraft:twisting_vines",
            "minecraft:twisting_vines_plant",
            "minecraft:weeping_vines",
            "minecraft:weeping_vines_plant",
            "minecraft:chorus_plant",
            "minecraft:chorus_flower");

    private static final Set<String> SOLID_FLORA_EXACT = Set.of(
            "minecraft:cactus",
            "minecraft:bamboo",
            "minecraft:azalea",
            "minecraft:flowering_azalea",
            "minecraft:mangrove_roots",
            "minecraft:brown_mushroom_block",
            "minecraft:red_mushroom_block",
            "minecraft:mushroom_stem",
            "minecraft:shroomlight",
            "minecraft:pumpkin",
            "minecraft:carved_pumpkin",
            "minecraft:melon",
            "minecraft:bee_nest",
            "minecraft:chorus_plant",
            "minecraft:chorus_flower");

    private static final List<String> STRUCTURE_DECOR_SUFFIXES = List.of(
            "_planks",
            "_fence",
            "_fence_gate",
            "_stairs",
            "_slab",
            "_wall",
            "_door",
            "_trapdoor",
            "_bed",
            "_carpet",
            "_wool",
            "_glass",
            "_glass_pane",
            "_bricks",
            "_banner",
            "_candle");

    private static final List<String> STRUCTURE_DECOR_TOKENS = List.of(
            "cobblestone",
            "stone_brick",
            "copper_grate",
            "chiseled_copper",
            "cut_copper",
            "tuff_brick",
            "polished_tuff",
            "chiseled_tuff",
            "deepslate_brick",
            "deepslate_tile",
            "polished_deepslate");

    private static final Set<String> STRUCTURE_DECOR_EXACT = Set.of(
            "minecraft:torch",
            "minecraft:wall_torch",
            "minecraft:lantern",
            "minecraft:soul_lantern",
            "minecraft:chest",
            "minecraft:trapped_chest",
            "minecraft:barrel",
            "minecraft:spawner",
            "minecraft:trial_spawner",
            "minecraft:vault",
            "minecraft:ladder",
            "minecraft:chain",
            "minecraft:iron_bars",
            "minecraft:bell",
            "minecraft:hay_block",
            "minecraft:bookshelf",
            "minecraft:crafting_table",
            "minecraft:furnace",
            "minecraft:smoker",
            "minecraft:blast_furnace",
            "minecraft:composter",
            "minecraft:cauldron",
            "minecraft:lectern",
            "minecraft:stonecutter",
            "minecraft:grindstone",
            "minecraft:loom",
            "minecraft:cartography_table",
            "minecraft:fletching_table",
            "minecraft:smithing_table",
            "minecraft:brewing_stand",
            "minecraft:rail",
            "minecraft:powered_rail",
            "minecraft:detector_rail",
            "minecraft:sculk",
            "minecraft:sculk_vein",
            "minecraft:sculk_catalyst",
            "minecraft:sculk_sensor",
            "minecraft:sculk_shrieker");

    private RegionSimilarityAuditor() {}

    /** Coarse 6-way semantic material taxonomy separating geometry/strata from exact palette IDs. */
    public enum SemanticMaterial {
        AIR,
        FLUID,
        LITHIC_BULK,
        SURFACE_STRATA,
        MINERAL_ORE,
        WOOD_FOLIAGE_DECOR
    }

    /** Eight Overworld climate classes mirroring LeafGen's climate biome quartet classifier. */
    public enum ClimateBiomeClass {
        PLAINS,
        FOREST,
        DESERT,
        BADLANDS,
        SNOWY,
        TAIGA,
        OCEAN_RIVER,
        CAVE
    }

    /** Macro topography similarity metrics for a single heightmap type across compared chunks. */
    public record HeightmapStats(
            String heightmapType,
            long sampleColumns,
            double pearsonR,
            double maeBlocks,
            double rmseBlocks,
            int maxAbsDeltaBlocks,
            int refMinY,
            int refMaxY,
            int candMinY,
            int candMaxY,
            double refLandFractionAboveSea,
            double candLandFractionAboveSea,
            Map<String, Long> refElevationHistogram,
            Map<String, Long> candElevationHistogram) {}

    /** Sub-surface cave air vs fluid voxel totals split at the deep lava horizon ({@code Y = -54}). */
    public record CaveFluidStats(
            long refUpperCaveAirVoxels,
            long candUpperCaveAirVoxels,
            long refUpperSubsurfaceWater,
            long candUpperSubsurfaceWater,
            long refDeepCaveAirVoxels,
            long candDeepCaveAirVoxels,
            long refDeepLavaVoxels,
            long candDeepLavaVoxels) {}

    /**
     * Bidirectional chunk-border migration-seam continuity metrics quantifying whether migrating
     * between Vanilla and Candidate regions introduces chunk-wall cliffs, biome border lines,
     * sheared subterranean caves, or aquifer fluid leaks.
     */
    public record MigrationSeamStats(
            long comparedSharedChunkEdges,
            long borderColumnsChecked,
            double borderSurfaceExactPct,
            double borderSurfaceWithin1BlockPct,
            double seamSurfaceMaeBlocks,
            int seamMaxInducedCliffBlocks,
            long seamInducedCliffEdges,
            double borderExactBiomePct,
            double borderStrictBlockPct,
            double interiorStrictBlockPct,
            double borderSubterraneanSolidAirAgreementPct,
            long borderSubterraneanFluidLeakVoxels) {

        /** Returns a zeroed placeholder instance for backward-compatible scorecard constructors. */
        public static MigrationSeamStats empty() {
            return new MigrationSeamStats(0L, 0L, 100.0, 100.0, 0.0, 0, 0L, 100.0, 100.0, 100.0, 100.0, 0L);
        }
    }

    /** Aggregated multi-axis similarity scorecard across all paired full chunks. */
    public record RegionSimilarityScorecard(
            String referenceLabel,
            String candidateLabel,
            int comparedFullChunks,
            int refOnlyChunks,
            int candOnlyChunks,
            Map<String, HeightmapStats> heightmaps,
            double strictBlockIdMatchPct,
            double semanticCategoryMatchPct,
            Map<SemanticMaterial, Long> refMaterialCounts,
            Map<SemanticMaterial, Long> candMaterialCounts,
            double exactBiomeQuartetMatchPct,
            double climateClassQuartetMatchPct,
            Map<ClimateBiomeClass, Long> refClimateCounts,
            Map<ClimateBiomeClass, Long> candClimateCounts,
            CaveFluidStats caveFluidStats,
            Map<String, Long> refOreCounts,
            Map<String, Long> candOreCounts,
            Map<String, Map<String, Long>> refOreYBandCounts,
            Map<String, Map<String, Long>> candOreYBandCounts,
            long refFloraAndCanopyVoxels,
            long candFloraAndCanopyVoxels,
            int refChunksWithStructureStarts,
            int candChunksWithStructureStarts,
            int refChunksWithStructureReferences,
            int candChunksWithStructureReferences,
            Map<String, Integer> refStructureStartCounts,
            Map<String, Integer> candStructureStartCounts,
            WeightedParityEvaluator.WeightedParityReport weightedParity,
            MigrationSeamStats migrationSeam) {

        /** Backward-compatible 28-argument constructor populating an empty migration-seam summary if omitted. */
        public RegionSimilarityScorecard(
                String referenceLabel,
                String candidateLabel,
                int comparedFullChunks,
                int refOnlyChunks,
                int candOnlyChunks,
                Map<String, HeightmapStats> heightmaps,
                double strictBlockIdMatchPct,
                double semanticCategoryMatchPct,
                Map<SemanticMaterial, Long> refMaterialCounts,
                Map<SemanticMaterial, Long> candMaterialCounts,
                double exactBiomeQuartetMatchPct,
                double climateClassQuartetMatchPct,
                Map<ClimateBiomeClass, Long> refClimateCounts,
                Map<ClimateBiomeClass, Long> candClimateCounts,
                CaveFluidStats caveFluidStats,
                Map<String, Long> refOreCounts,
                Map<String, Long> candOreCounts,
                Map<String, Map<String, Long>> refOreYBandCounts,
                Map<String, Map<String, Long>> candOreYBandCounts,
                long refFloraAndCanopyVoxels,
                long candFloraAndCanopyVoxels,
                int refChunksWithStructureStarts,
                int candChunksWithStructureStarts,
                int refChunksWithStructureReferences,
                int candChunksWithStructureReferences,
                Map<String, Integer> refStructureStartCounts,
                Map<String, Integer> candStructureStartCounts,
                WeightedParityEvaluator.WeightedParityReport weightedParity) {
            this(
                    referenceLabel,
                    candidateLabel,
                    comparedFullChunks,
                    refOnlyChunks,
                    candOnlyChunks,
                    heightmaps,
                    strictBlockIdMatchPct,
                    semanticCategoryMatchPct,
                    refMaterialCounts,
                    candMaterialCounts,
                    exactBiomeQuartetMatchPct,
                    climateClassQuartetMatchPct,
                    refClimateCounts,
                    candClimateCounts,
                    caveFluidStats,
                    refOreCounts,
                    candOreCounts,
                    refOreYBandCounts,
                    candOreYBandCounts,
                    refFloraAndCanopyVoxels,
                    candFloraAndCanopyVoxels,
                    refChunksWithStructureStarts,
                    candChunksWithStructureStarts,
                    refChunksWithStructureReferences,
                    candChunksWithStructureReferences,
                    refStructureStartCounts,
                    candStructureStartCounts,
                    weightedParity,
                    MigrationSeamStats.empty());
        }

        /** Backward-compatible 27-argument constructor populating an empty weighted parity report if omitted. */
        public RegionSimilarityScorecard(
                String referenceLabel,
                String candidateLabel,
                int comparedFullChunks,
                int refOnlyChunks,
                int candOnlyChunks,
                Map<String, HeightmapStats> heightmaps,
                double strictBlockIdMatchPct,
                double semanticCategoryMatchPct,
                Map<SemanticMaterial, Long> refMaterialCounts,
                Map<SemanticMaterial, Long> candMaterialCounts,
                double exactBiomeQuartetMatchPct,
                double climateClassQuartetMatchPct,
                Map<ClimateBiomeClass, Long> refClimateCounts,
                Map<ClimateBiomeClass, Long> candClimateCounts,
                CaveFluidStats caveFluidStats,
                Map<String, Long> refOreCounts,
                Map<String, Long> candOreCounts,
                Map<String, Map<String, Long>> refOreYBandCounts,
                Map<String, Map<String, Long>> candOreYBandCounts,
                long refFloraAndCanopyVoxels,
                long candFloraAndCanopyVoxels,
                int refChunksWithStructureStarts,
                int candChunksWithStructureStarts,
                int refChunksWithStructureReferences,
                int candChunksWithStructureReferences,
                Map<String, Integer> refStructureStartCounts,
                Map<String, Integer> candStructureStartCounts) {
            this(
                    referenceLabel,
                    candidateLabel,
                    comparedFullChunks,
                    refOnlyChunks,
                    candOnlyChunks,
                    heightmaps,
                    strictBlockIdMatchPct,
                    semanticCategoryMatchPct,
                    refMaterialCounts,
                    candMaterialCounts,
                    exactBiomeQuartetMatchPct,
                    climateClassQuartetMatchPct,
                    refClimateCounts,
                    candClimateCounts,
                    caveFluidStats,
                    refOreCounts,
                    candOreCounts,
                    refOreYBandCounts,
                    candOreYBandCounts,
                    refFloraAndCanopyVoxels,
                    candFloraAndCanopyVoxels,
                    refChunksWithStructureStarts,
                    candChunksWithStructureStarts,
                    refChunksWithStructureReferences,
                    candChunksWithStructureReferences,
                    refStructureStartCounts,
                    candStructureStartCounts,
                    new WeightedParityEvaluator.WeightedParityReport(
                            Math.abs(strictBlockIdMatchPct - 100.0) <= 1e-7
                                    ? WeightedParityEvaluator.ParityGateVerdict.EXACT_MATCH
                                    : WeightedParityEvaluator.ParityGateVerdict.ACCEPTABLE_EQUIVALENT,
                            strictBlockIdMatchPct,
                            0,
                            0L,
                            0L,
                            0L,
                            0L,
                            0L,
                            0L,
                            0L,
                            0L,
                            0,
                            0,
                            0,
                            0,
                            100.0,
                            List.of(),
                            List.of()));
        }
    }

    private record RegionCoord(int rx, int rz) {}

    private static final Comparator<RegionCoord> REGION_COORD_ORDER =
            Comparator.comparingInt(RegionCoord::rx).thenComparingInt(RegionCoord::rz);

    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final long[] EMPTY_LONGS = new long[0];

    private record RegionArchiveHandle(Path file, String ext, RegionFileReader reader) {}

    /**
     * Compares paired {@code r.X.Z.<ext>} region archives between {@code referenceRegionDir} and
     * {@code candidateRegionDir}, evaluating only completed {@code full} chunks on both sides.
     *
     * @param referenceRegionDir world root, {@code region/} directory, or single region archive
     * @param candidateRegionDir world root, {@code region/} directory, or single region archive
     * @param referenceLabel     display label for the reference dataset
     * @param candidateLabel     display label for the candidate dataset
     * @param maxChunks          maximum paired full chunks to evaluate ({@code <= 0} for unlimited)
     * @return aggregated {@link RegionSimilarityScorecard}
     * @throws IOException if directory traversal fails
     */
    public static RegionSimilarityScorecard compareDirectories(
            Path referenceRegionDir,
            Path candidateRegionDir,
            String referenceLabel,
            String candidateLabel,
            int maxChunks) throws IOException {
        return compareDirectories(
                referenceRegionDir,
                candidateRegionDir,
                referenceLabel,
                candidateLabel,
                maxChunks,
                List.of());
    }

    /**
     * Compares paired {@code r.X.Z.<ext>} region archives between {@code referenceRegionDir} and
     * {@code candidateRegionDir}, evaluating both raw voxel/heightmap/biome statistics and the
     * {@link WeightedParityEvaluator} relative-layout parity matrix hydrated from {@code templateNbtSources}.
     *
     * @param referenceRegionDir world root, {@code region/} directory, or single region archive
     * @param candidateRegionDir world root, {@code region/} directory, or single region archive
     * @param referenceLabel     display label for the reference dataset
     * @param candidateLabel     display label for the candidate dataset
     * @param maxChunks          maximum paired full chunks to evaluate ({@code <= 0} for unlimited)
     * @param templateNbtSources optional server JARs (flat or Paperclip) or {@code .nbt} directories/files
     * @return aggregated {@link RegionSimilarityScorecard}
     * @throws IOException if directory traversal or archive decoding fails
     */
    public static RegionSimilarityScorecard compareDirectories(
            Path referenceRegionDir,
            Path candidateRegionDir,
            String referenceLabel,
            String candidateLabel,
            int maxChunks,
            Collection<Path> templateNbtSources) throws IOException {
        Objects.requireNonNull(referenceRegionDir, "referenceRegionDir");
        Objects.requireNonNull(candidateRegionDir, "candidateRegionDir");
        Collection<Path> safeSources = templateNbtSources == null ? List.of() : List.copyOf(templateNbtSources);
        return compareDirectoriesOnWorker(
                referenceRegionDir,
                candidateRegionDir,
                referenceLabel,
                candidateLabel,
                maxChunks,
                safeSources);
    }

    private static RegionSimilarityScorecard compareDirectoriesOnWorker(
            Path referenceRegionDir,
            Path candidateRegionDir,
            String referenceLabel,
            String candidateLabel,
            int maxChunks,
            Collection<Path> templateNbtSources) throws IOException {
        RegionFormatRegistry.loadServiceProviders();
        WeightedParityEvaluator.TemplateConformityLibrary templateLibrary =
                WeightedParityEvaluator.TemplateConformityLibrary.loadFromSourcesOnWorker(templateNbtSources);
        Map<RegionCoord, RegionArchiveHandle> refRegions = discoverRegions(referenceRegionDir);
        Map<RegionCoord, RegionArchiveHandle> candRegions = discoverRegions(candidateRegionDir);
        Set<RegionCoord> allCoords = new TreeSet<>(REGION_COORD_ORDER);
        allCoords.addAll(refRegions.keySet());
        allCoords.addAll(candRegions.keySet());

        ScorecardAccumulators acc = new ScorecardAccumulators(maxChunks, templateLibrary);
        for (RegionCoord rc : allCoords) {
            if (acc.isLimitReached()) {
                break;
            }
            acc.accumulateRegionPair(rc, refRegions.get(rc), candRegions.get(rc));
        }
        return acc.build(referenceLabel, candidateLabel);
    }

    private static final class ScorecardAccumulators {
        final int maxChunks;
        final Map<String, HeightmapAccumulator> hmAccumulators = new LinkedHashMap<>();
        final EnumMap<SemanticMaterial, Long> refMaterials = initEnumMap(SemanticMaterial.class);
        final EnumMap<SemanticMaterial, Long> candMaterials = initEnumMap(SemanticMaterial.class);
        final EnumMap<ClimateBiomeClass, Long> refClimates = initEnumMap(ClimateBiomeClass.class);
        final EnumMap<ClimateBiomeClass, Long> candClimates = initEnumMap(ClimateBiomeClass.class);
        final Map<String, Long> refOres = initKeyedZeroMap(ORE_FAMILIES);
        final Map<String, Long> candOres = initKeyedZeroMap(ORE_FAMILIES);
        final Map<String, Map<String, Long>> refOreBands = initOreBandMap();
        final Map<String, Map<String, Long>> candOreBands = initOreBandMap();
        final Map<String, Integer> refStructCounts = new TreeMap<>();
        final Map<String, Integer> candStructCounts = new TreeMap<>();
        final DecodedChunkScratch refScratch = new DecodedChunkScratch();
        final DecodedChunkScratch candScratch = new DecodedChunkScratch();
        final WeightedParityEvaluator.Accumulator weightedAcc;

        long totalVoxels;
        long strictVoxelMatches;
        long semanticVoxelMatches;
        long totalBiomeQuartets;
        long exactBiomeMatches;
        long climateBiomeMatches;
        long refUpperCaveAir;
        long candUpperCaveAir;
        long refUpperSubWater;
        long candUpperSubWater;
        long refDeepCaveAir;
        long candDeepCaveAir;
        long refDeepLava;
        long candDeepLava;
        long refFloraVoxels;
        long candFloraVoxels;
        long comparedSharedChunkEdges;
        long seamDirectedColumnPairs;
        long seamExcessCliffSum;
        int seamMaxInducedCliffBlocks;
        long seamInducedCliffEdges;
        long borderColumnsChecked;
        long borderSurfaceExactColumns;
        long borderSurfaceWithin1Columns;
        long borderBiomeQuartets;
        long borderExactBiomeMatches;
        long borderVoxels;
        long borderStrictVoxelMatches;
        long interiorVoxels;
        long interiorStrictVoxelMatches;
        long borderSubterraneanVoxels;
        long borderSubterraneanSolidAirMatches;
        long borderSubterraneanFluidLeakVoxels;
        int comparedFullChunks;
        int refOnlyChunks;
        int candOnlyChunks;
        int refStructStartChunks;
        int candStructStartChunks;
        int refStructRefChunks;
        int candStructRefChunks;
        boolean limitReached;

        ScorecardAccumulators(
                int maxChunks, WeightedParityEvaluator.TemplateConformityLibrary templateLibrary) {
            this.maxChunks = maxChunks;
            this.weightedAcc = new WeightedParityEvaluator.Accumulator(templateLibrary);
            for (String hmType : HEIGHTMAP_TYPES) {
                hmAccumulators.put(hmType, new HeightmapAccumulator(hmType));
            }
        }

        boolean isLimitReached() {
            return limitReached;
        }

        void accumulateRegionPair(
                RegionCoord rc, RegionArchiveHandle refHandle, RegionArchiveHandle candHandle)
                throws IOException {
            byte[] refBytes = readBytesSafe(refHandle);
            byte[] candBytes = readBytesSafe(candHandle);
            List<WeightedParityEvaluator.ChunkParityInput> regionRefChunks = new ArrayList<>();
            List<WeightedParityEvaluator.ChunkParityInput> regionCandChunks = new ArrayList<>();
            int[][][] refSurfaceByChunk = new int[32][32][];
            int[][][] candSurfaceByChunk = new int[32][32][];
            for (int localZ = 0; localZ < 32 && !limitReached; localZ++) {
                for (int localX = 0; localX < 32; localX++) {
                    AnvilReader.ChunkEntry refEntry = readFullChunkSafe(refHandle, refBytes, localX, localZ);
                    AnvilReader.ChunkEntry candEntry = readFullChunkSafe(candHandle, candBytes, localX, localZ);
                    if (refEntry == null || candEntry == null) {
                        recordUnpairedChunk(refEntry != null, candEntry != null);
                        continue;
                    }
                    if (maxChunks > 0 && comparedFullChunks >= maxChunks) {
                        limitReached = true;
                        break;
                    }
                    accumulatePairedChunk(refEntry, candEntry, refSurfaceByChunk, candSurfaceByChunk, localX, localZ);
                    regionRefChunks.add(new WeightedParityEvaluator.ChunkParityInput(
                            localX, localZ, cloneSectionBlockIds(refScratch.sectionBlockIds), refEntry.root));
                    regionCandChunks.add(new WeightedParityEvaluator.ChunkParityInput(
                            localX, localZ, cloneSectionBlockIds(candScratch.sectionBlockIds), candEntry.root));
                }
            }
            accumulateRegionSharedSeamEdges(refSurfaceByChunk, candSurfaceByChunk);
            if (!regionRefChunks.isEmpty()) {
                weightedAcc.evaluateRegionPair(rc.rx(), rc.rz(), regionRefChunks, regionCandChunks);
            }
        }

        private static String[][] cloneSectionBlockIds(String[][] src) {
            String[][] copy = new String[SECTION_COUNT][];
            for (int i = 0; i < SECTION_COUNT; i++) {
                copy[i] = src[i].clone();
            }
            return copy;
        }

        private void recordUnpairedChunk(boolean hasRef, boolean hasCand) {
            if (hasRef) {
                refOnlyChunks++;
            } else if (hasCand) {
                candOnlyChunks++;
            }
        }

        private void accumulatePairedChunk(
                AnvilReader.ChunkEntry refEntry,
                AnvilReader.ChunkEntry candEntry,
                int[][][] refSurfaceByChunk,
                int[][][] candSurfaceByChunk,
                int localX,
                int localZ) {
            comparedFullChunks++;
            refScratch.decode(refEntry.root);
            candScratch.decode(candEntry.root);

            for (String hmType : HEIGHTMAP_TYPES) {
                int[] refHm = refScratch.extractHeightmap(refEntry.root, hmType);
                int[] candHm = candScratch.extractHeightmap(candEntry.root, hmType);
                hmAccumulators.get(hmType).recordChunk(refHm, candHm);
                if ("WORLD_SURFACE_WG".equals(hmType)) {
                    refSurfaceByChunk[localZ][localX] = refHm.clone();
                    candSurfaceByChunk[localZ][localX] = candHm.clone();
                    recordBorderSurfaceColumns(refHm, candHm);
                }
            }
            for (int syIdx = 0; syIdx < SECTION_COUNT; syIdx++) {
                accumulateSectionVoxels(syIdx);
                accumulateSectionBiomes(syIdx);
            }
            accumulateChunkStructures(refEntry.root, candEntry.root);
        }

        private void recordBorderSurfaceColumns(int[] refSurface, int[] candSurface) {
            for (int colIdx = 0; colIdx < 256; colIdx++) {
                if (!isBorderColumn(colIdx)) {
                    continue;
                }
                borderColumnsChecked++;
                int delta = Math.abs(refSurface[colIdx] - candSurface[colIdx]);
                if (delta == 0) {
                    borderSurfaceExactColumns++;
                    borderSurfaceWithin1Columns++;
                } else if (delta <= 1) {
                    borderSurfaceWithin1Columns++;
                }
            }
        }

        private void accumulateRegionSharedSeamEdges(int[][][] refSurface, int[][][] candSurface) {
            for (int lz = 0; lz < 32; lz++) {
                for (int lx = 0; lx < 32; lx++) {
                    int[] rA = refSurface[lz][lx];
                    int[] cA = candSurface[lz][lx];
                    if (rA == null || cA == null) {
                        continue;
                    }
                    if (lx + 1 < 32 && refSurface[lz][lx + 1] != null && candSurface[lz][lx + 1] != null) {
                        recordEastWestSeam(rA, cA, refSurface[lz][lx + 1], candSurface[lz][lx + 1]);
                    }
                    if (lz + 1 < 32 && refSurface[lz + 1][lx] != null && candSurface[lz + 1][lx] != null) {
                        recordNorthSouthSeam(rA, cA, refSurface[lz + 1][lx], candSurface[lz + 1][lx]);
                    }
                }
            }
        }

        private void recordEastWestSeam(int[] refA, int[] candA, int[] refB, int[] candB) {
            comparedSharedChunkEdges++;
            for (int z = 0; z < 16; z++) {
                int leftCol = (z << 4) | 15;
                int rightCol = z << 4;
                recordDirectionalSeamPair(refA[leftCol], candA[leftCol], refB[rightCol], candB[rightCol]);
            }
        }

        private void recordNorthSouthSeam(int[] refA, int[] candA, int[] refB, int[] candB) {
            comparedSharedChunkEdges++;
            for (int x = 0; x < 16; x++) {
                int northCol = (15 << 4) | x;
                int southCol = x;
                recordDirectionalSeamPair(refA[northCol], candA[northCol], refB[southCol], candB[southCol]);
            }
        }

        private void recordDirectionalSeamPair(int refAY, int candAY, int refBY, int candBY) {
            int dRef = Math.abs(refAY - refBY);
            int dV2C = Math.abs(refAY - candBY);
            int dC2V = Math.abs(candAY - refBY);
            recordSingleSeamDirection(dRef, dV2C);
            recordSingleSeamDirection(dRef, dC2V);
        }

        private void recordSingleSeamDirection(int dRef, int dMigrated) {
            seamDirectedColumnPairs++;
            int excess = Math.max(0, dMigrated - dRef);
            seamExcessCliffSum += excess;
            if (excess > seamMaxInducedCliffBlocks) {
                seamMaxInducedCliffBlocks = excess;
            }
            if (dMigrated > Math.max(1, dRef)) {
                seamInducedCliffEdges++;
            }
        }

        private static boolean isBorderColumn(int colIdx) {
            int lx = colIdx & 15;
            int lz = colIdx >>> 4;
            return lx == 0 || lx == 15 || lz == 0 || lz == 15;
        }

        private static int coarseSeamTopology(SemanticMaterial mat) {
            if (mat == SemanticMaterial.AIR) {
                return 0;
            }
            if (mat == SemanticMaterial.FLUID) {
                return 1;
            }
            return 2;
        }

        private void accumulateSectionVoxels(int syIdx) {
            int baseWorldY = (MIN_SECTION_Y + syIdx) * 16;
            for (int ly = 0; ly < 16; ly++) {
                int worldY = baseWorldY + ly;
                String oreYBand = classifyOreYBand(worldY);
                int yBase = ly << 8;
                for (int colIdx = 0; colIdx < 256; colIdx++) {
                    accumulateSingleVoxel(syIdx, yBase | colIdx, colIdx, worldY, oreYBand);
                }
            }
        }

        private void accumulateSingleVoxel(
                int syIdx, int vIdx, int colIdx, int worldY, String oreYBand) {
            String rId = refScratch.sectionBlockIds[syIdx][vIdx];
            String cId = candScratch.sectionBlockIds[syIdx][vIdx];
            SemanticMaterial rMat = refScratch.sectionMaterials[syIdx][vIdx];
            SemanticMaterial cMat = candScratch.sectionMaterials[syIdx][vIdx];

            totalVoxels++;
            boolean strictMatch = rId.equals(cId);
            if (strictMatch) {
                strictVoxelMatches++;
            }
            if (rMat == cMat) {
                semanticVoxelMatches++;
            }
            if (isBorderColumn(colIdx)) {
                borderVoxels++;
                if (strictMatch) {
                    borderStrictVoxelMatches++;
                }
                int subCeiling = Math.max(refScratch.solidCeilingY[colIdx], candScratch.solidCeilingY[colIdx]);
                if (worldY < subCeiling) {
                    borderSubterraneanVoxels++;
                    int rTopo = coarseSeamTopology(rMat);
                    int cTopo = coarseSeamTopology(cMat);
                    if (rTopo == cTopo) {
                        borderSubterraneanSolidAirMatches++;
                    } else if ((rTopo == 1 && cTopo == 0) || (rTopo == 0 && cTopo == 1)) {
                        borderSubterraneanFluidLeakVoxels++;
                    }
                }
            } else {
                interiorVoxels++;
                if (strictMatch) {
                    interiorStrictVoxelMatches++;
                }
            }
            refMaterials.put(rMat, refMaterials.get(rMat) + 1L);
            candMaterials.put(cMat, candMaterials.get(cMat) + 1L);

            if (refScratch.sectionIsFlora[syIdx][vIdx]) {
                refFloraVoxels++;
            }
            if (candScratch.sectionIsFlora[syIdx][vIdx]) {
                candFloraVoxels++;
            }
            recordOreVoxel(refScratch.sectionOreFamily[syIdx][vIdx], oreYBand, refOres, refOreBands);
            recordOreVoxel(candScratch.sectionOreFamily[syIdx][vIdx], oreYBand, candOres, candOreBands);
            recordRefCaveFluid(worldY, refScratch.solidCeilingY[colIdx], rMat, rId);
            recordCandCaveFluid(worldY, candScratch.solidCeilingY[colIdx], cMat, cId);
        }

        private static void recordOreVoxel(
                String oreFam,
                String oreYBand,
                Map<String, Long> totals,
                Map<String, Map<String, Long>> bands) {
            if (oreFam == null) {
                return;
            }
            totals.put(oreFam, totals.get(oreFam) + 1L);
            Map<String, Long> band = bands.get(oreFam);
            band.put(oreYBand, band.get(oreYBand) + 1L);
        }

        private void recordRefCaveFluid(int worldY, int ceilingY, SemanticMaterial mat, String id) {
            if (worldY >= ceilingY || worldY > SEA_LEVEL_Y) {
                return;
            }
            if (worldY <= DEEP_CAVE_MAX_Y) {
                if (mat == SemanticMaterial.AIR) refDeepCaveAir++;
                else if (LAVA_BLOCKS.contains(id)) refDeepLava++;
            } else {
                if (mat == SemanticMaterial.AIR) refUpperCaveAir++;
                else if (WATER_BLOCKS.contains(id)) refUpperSubWater++;
            }
        }

        private void recordCandCaveFluid(int worldY, int ceilingY, SemanticMaterial mat, String id) {
            if (worldY >= ceilingY || worldY > SEA_LEVEL_Y) {
                return;
            }
            if (worldY <= DEEP_CAVE_MAX_Y) {
                if (mat == SemanticMaterial.AIR) candDeepCaveAir++;
                else if (LAVA_BLOCKS.contains(id)) candDeepLava++;
            } else {
                if (mat == SemanticMaterial.AIR) candUpperCaveAir++;
                else if (WATER_BLOCKS.contains(id)) candUpperSubWater++;
            }
        }

        private void accumulateSectionBiomes(int syIdx) {
            String[] refBiomes = refScratch.sectionBiomes[syIdx];
            String[] candBiomes = candScratch.sectionBiomes[syIdx];
            for (int q = 0; q < 64; q++) {
                String rBiome = refBiomes[q];
                String cBiome = candBiomes[q];
                ClimateBiomeClass rClim = classifyClimateBiome(rBiome);
                ClimateBiomeClass cClim = classifyClimateBiome(cBiome);

                totalBiomeQuartets++;
                boolean exactBiome = rBiome.equals(cBiome);
                if (exactBiome) {
                    exactBiomeMatches++;
                }
                int qx = q & 3;
                int qz = (q >>> 2) & 3;
                if (qx == 0 || qx == 3 || qz == 0 || qz == 3) {
                    borderBiomeQuartets++;
                    if (exactBiome) {
                        borderExactBiomeMatches++;
                    }
                }
                if (rClim == cClim) {
                    climateBiomeMatches++;
                }
                refClimates.put(rClim, refClimates.get(rClim) + 1L);
                candClimates.put(cClim, candClimates.get(cClim) + 1L);
            }
        }

        private void accumulateChunkStructures(
                Map<String, Object> refRoot, Map<String, Object> candRoot) {
            if (recordStructureStarts(refRoot, refStructCounts)) {
                refStructStartChunks++;
            }
            if (recordStructureStarts(candRoot, candStructCounts)) {
                candStructStartChunks++;
            }
            if (hasStructureReferences(refRoot)) {
                refStructRefChunks++;
            }
            if (hasStructureReferences(candRoot)) {
                candStructRefChunks++;
            }
        }

        RegionSimilarityScorecard build(String referenceLabel, String candidateLabel) {
            Map<String, HeightmapStats> hmOut = new LinkedHashMap<>();
            for (Map.Entry<String, HeightmapAccumulator> e : hmAccumulators.entrySet()) {
                hmOut.put(e.getKey(), e.getValue().finish());
            }
            double strictPct = totalVoxels > 0 ? (strictVoxelMatches * 100.0) / totalVoxels : 0.0;
            double semanticPct = totalVoxels > 0 ? (semanticVoxelMatches * 100.0) / totalVoxels : 0.0;
            double exactBiomePct =
                    totalBiomeQuartets > 0 ? (exactBiomeMatches * 100.0) / totalBiomeQuartets : 0.0;
            double climateBiomePct =
                    totalBiomeQuartets > 0 ? (climateBiomeMatches * 100.0) / totalBiomeQuartets : 0.0;

            CaveFluidStats caveStats = new CaveFluidStats(
                    refUpperCaveAir,
                    candUpperCaveAir,
                    refUpperSubWater,
                    candUpperSubWater,
                    refDeepCaveAir,
                    candDeepCaveAir,
                    refDeepLava,
                    candDeepLava);
            WeightedParityEvaluator.WeightedParityReport weightedReport =
                    weightedAcc.finish(strictPct, refOres, candOres);
            MigrationSeamStats seamStats = new MigrationSeamStats(
                    comparedSharedChunkEdges,
                    borderColumnsChecked,
                    borderColumnsChecked > 0 ? (borderSurfaceExactColumns * 100.0) / borderColumnsChecked : 100.0,
                    borderColumnsChecked > 0 ? (borderSurfaceWithin1Columns * 100.0) / borderColumnsChecked : 100.0,
                    seamDirectedColumnPairs > 0 ? (double) seamExcessCliffSum / seamDirectedColumnPairs : 0.0,
                    seamMaxInducedCliffBlocks,
                    seamInducedCliffEdges,
                    borderBiomeQuartets > 0 ? (borderExactBiomeMatches * 100.0) / borderBiomeQuartets : 100.0,
                    borderVoxels > 0 ? (borderStrictVoxelMatches * 100.0) / borderVoxels : 100.0,
                    interiorVoxels > 0 ? (interiorStrictVoxelMatches * 100.0) / interiorVoxels : 100.0,
                    borderSubterraneanVoxels > 0
                            ? (borderSubterraneanSolidAirMatches * 100.0) / borderSubterraneanVoxels
                            : 100.0,
                    borderSubterraneanFluidLeakVoxels);

            return new RegionSimilarityScorecard(
                    referenceLabel == null ? "reference" : referenceLabel,
                    candidateLabel == null ? "candidate" : candidateLabel,
                    comparedFullChunks,
                    refOnlyChunks,
                    candOnlyChunks,
                    Collections.unmodifiableMap(hmOut),
                    strictPct,
                    semanticPct,
                    Collections.unmodifiableMap(refMaterials),
                    Collections.unmodifiableMap(candMaterials),
                    exactBiomePct,
                    climateBiomePct,
                    Collections.unmodifiableMap(refClimates),
                    Collections.unmodifiableMap(candClimates),
                    caveStats,
                    Collections.unmodifiableMap(refOres),
                    Collections.unmodifiableMap(candOres),
                    unmodifiableNestedMap(refOreBands),
                    unmodifiableNestedMap(candOreBands),
                    refFloraVoxels,
                    candFloraVoxels,
                    refStructStartChunks,
                    candStructStartChunks,
                    refStructRefChunks,
                    candStructRefChunks,
                    Collections.unmodifiableMap(refStructCounts),
                    Collections.unmodifiableMap(candStructCounts),
                    weightedReport,
                    seamStats);
        }
    }

    /** Classifies a canonical block identifier into one of the 6 {@link SemanticMaterial} classes. */
    public static SemanticMaterial classifySemanticMaterial(String rawBlockId) {
        String id = normalizeBlockId(rawBlockId);
        if (AIR_BLOCKS.contains(id)) {
            return SemanticMaterial.AIR;
        }
        if (FLUID_BLOCKS.contains(id)) {
            return SemanticMaterial.FLUID;
        }
        if (classifyOreFamily(id) != null
                || id.endsWith("_ore")
                || id.equals("minecraft:ancient_debris")
                || id.startsWith("minecraft:raw_") && id.endsWith("_block")) {
            return SemanticMaterial.MINERAL_ORE;
        }
        if (isFloraOrCanopyBlock(id) || isStructureDecorBlock(id)) {
            return SemanticMaterial.WOOD_FOLIAGE_DECOR;
        }
        if (SURFACE_STRATA_EXACT.contains(id) || id.endsWith("_terracotta")) {
            return SemanticMaterial.SURFACE_STRATA;
        }
        return SemanticMaterial.LITHIC_BULK;
    }

    /** Maps a Vanilla or modded biome identifier onto LeafGen's 8 {@link ClimateBiomeClass} buckets. */
    public static ClimateBiomeClass classifyClimateBiome(String rawBiomeId) {
        String id = normalizeResourceId(rawBiomeId, DEFAULT_BIOME);
        String path = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;

        if (path.contains("cave") || path.equals("deep_dark")) {
            return ClimateBiomeClass.CAVE;
        }
        if (path.equals("snowy_beach")) {
            return ClimateBiomeClass.SNOWY;
        }
        if (path.contains("ocean")
                || path.contains("river")
                || path.contains("beach")
                || path.equals("stony_shore")
                || path.equals("mushroom_fields")) {
            return ClimateBiomeClass.OCEAN_RIVER;
        }
        if (path.contains("badlands")) {
            return ClimateBiomeClass.BADLANDS;
        }
        if (path.contains("desert")) {
            return ClimateBiomeClass.DESERT;
        }
        if (path.contains("snowy")
                || path.equals("ice_spikes")
                || path.equals("frozen_peaks")
                || path.equals("jagged_peaks")
                || path.equals("grove")) {
            return ClimateBiomeClass.SNOWY;
        }
        if (path.contains("taiga")) {
            return ClimateBiomeClass.TAIGA;
        }
        if (path.contains("forest")
                || path.contains("jungle")
                || path.contains("swamp")
                || path.equals("cherry_grove")
                || path.equals("pale_garden")) {
            return ClimateBiomeClass.FOREST;
        }
        return ClimateBiomeClass.PLAINS;
    }

    /** Serializes a {@link RegionSimilarityScorecard} to deterministic UTF-8 JSON. */
    public static String toJson(RegionSimilarityScorecard sc) {
        Objects.requireNonNull(sc, "scorecard");
        StringBuilder sb = new StringBuilder(8192);
        sb.append("{\n");
        sb.append("  \"reference_label\": ").append(quote(sc.referenceLabel())).append(",\n");
        sb.append("  \"candidate_label\": ").append(quote(sc.candidateLabel())).append(",\n");
        sb.append("  \"compared_full_chunks\": ").append(sc.comparedFullChunks()).append(",\n");
        sb.append("  \"ref_only_chunks\": ").append(sc.refOnlyChunks()).append(",\n");
        sb.append("  \"cand_only_chunks\": ").append(sc.candOnlyChunks()).append(",\n");
        sb.append("  \"strict_block_id_match_pct\": ").append(fmtDouble(sc.strictBlockIdMatchPct())).append(",\n");
        sb.append("  \"semantic_category_match_pct\": ").append(fmtDouble(sc.semanticCategoryMatchPct())).append(",\n");
        sb.append("  \"exact_biome_quartet_match_pct\": ").append(fmtDouble(sc.exactBiomeQuartetMatchPct())).append(",\n");
        sb.append("  \"climate_class_quartet_match_pct\": ").append(fmtDouble(sc.climateClassQuartetMatchPct())).append(",\n");
        sb.append("  \"heightmaps\": {\n");
        int hmIdx = 0;
        for (Map.Entry<String, HeightmapStats> e : sc.heightmaps().entrySet()) {
            HeightmapStats hm = e.getValue();
            sb.append("    ").append(quote(e.getKey())).append(": {\n");
            sb.append("      \"sample_columns\": ").append(hm.sampleColumns()).append(",\n");
            sb.append("      \"pearson_r\": ").append(fmtDouble(hm.pearsonR())).append(",\n");
            sb.append("      \"mae_blocks\": ").append(fmtDouble(hm.maeBlocks())).append(",\n");
            sb.append("      \"rmse_blocks\": ").append(fmtDouble(hm.rmseBlocks())).append(",\n");
            sb.append("      \"max_abs_delta_blocks\": ").append(hm.maxAbsDeltaBlocks()).append(",\n");
            sb.append("      \"ref_min_y\": ").append(hm.refMinY()).append(",\n");
            sb.append("      \"ref_max_y\": ").append(hm.refMaxY()).append(",\n");
            sb.append("      \"cand_min_y\": ").append(hm.candMinY()).append(",\n");
            sb.append("      \"cand_max_y\": ").append(hm.candMaxY()).append(",\n");
            sb.append("      \"ref_land_fraction_above_sea\": ").append(fmtDouble(hm.refLandFractionAboveSea())).append(",\n");
            sb.append("      \"cand_land_fraction_above_sea\": ").append(fmtDouble(hm.candLandFractionAboveSea())).append(",\n");
            sb.append("      \"ref_elevation_histogram\": ").append(longMapJson(hm.refElevationHistogram())).append(",\n");
            sb.append("      \"cand_elevation_histogram\": ").append(longMapJson(hm.candElevationHistogram())).append("\n");
            sb.append("    }");
            if (++hmIdx < sc.heightmaps().size()) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  },\n");
        sb.append("  \"ref_material_counts\": ").append(enumMapJson(sc.refMaterialCounts())).append(",\n");
        sb.append("  \"cand_material_counts\": ").append(enumMapJson(sc.candMaterialCounts())).append(",\n");
        sb.append("  \"ref_climate_counts\": ").append(enumMapJson(sc.refClimateCounts())).append(",\n");
        sb.append("  \"cand_climate_counts\": ").append(enumMapJson(sc.candClimateCounts())).append(",\n");

        CaveFluidStats cf = sc.caveFluidStats();
        sb.append("  \"cave_fluid_stats\": {\n");
        sb.append("    \"ref_upper_cave_air_voxels\": ").append(cf.refUpperCaveAirVoxels()).append(",\n");
        sb.append("    \"cand_upper_cave_air_voxels\": ").append(cf.candUpperCaveAirVoxels()).append(",\n");
        sb.append("    \"ref_upper_subsurface_water\": ").append(cf.refUpperSubsurfaceWater()).append(",\n");
        sb.append("    \"cand_upper_subsurface_water\": ").append(cf.candUpperSubsurfaceWater()).append(",\n");
        sb.append("    \"ref_deep_cave_air_voxels\": ").append(cf.refDeepCaveAirVoxels()).append(",\n");
        sb.append("    \"cand_deep_cave_air_voxels\": ").append(cf.candDeepCaveAirVoxels()).append(",\n");
        sb.append("    \"ref_deep_lava_voxels\": ").append(cf.refDeepLavaVoxels()).append(",\n");
        sb.append("    \"cand_deep_lava_voxels\": ").append(cf.candDeepLavaVoxels()).append("\n");
        sb.append("  },\n");

        sb.append("  \"ref_ore_counts\": ").append(longMapJson(sc.refOreCounts())).append(",\n");
        sb.append("  \"cand_ore_counts\": ").append(longMapJson(sc.candOreCounts())).append(",\n");
        sb.append("  \"ref_ore_y_band_counts\": ").append(nestedLongMapJson(sc.refOreYBandCounts())).append(",\n");
        sb.append("  \"cand_ore_y_band_counts\": ").append(nestedLongMapJson(sc.candOreYBandCounts())).append(",\n");
        sb.append("  \"ref_flora_and_canopy_voxels\": ").append(sc.refFloraAndCanopyVoxels()).append(",\n");
        sb.append("  \"cand_flora_and_canopy_voxels\": ").append(sc.candFloraAndCanopyVoxels()).append(",\n");
        sb.append("  \"ref_chunks_with_structure_starts\": ").append(sc.refChunksWithStructureStarts()).append(",\n");
        sb.append("  \"cand_chunks_with_structure_starts\": ").append(sc.candChunksWithStructureStarts()).append(",\n");
        sb.append("  \"ref_chunks_with_structure_references\": ").append(sc.refChunksWithStructureReferences()).append(",\n");
        sb.append("  \"cand_chunks_with_structure_references\": ").append(sc.candChunksWithStructureReferences()).append(",\n");
        sb.append("  \"ref_structure_start_counts\": ").append(intMapJson(sc.refStructureStartCounts())).append(",\n");
        sb.append("  \"cand_structure_start_counts\": ").append(intMapJson(sc.candStructureStartCounts())).append(",\n");
        WeightedParityEvaluator.WeightedParityReport wp = sc.weightedParity();
        sb.append("  \"weighted_parity\": {\n");
        sb.append("    \"verdict\": ").append(quote(wp.verdict().name())).append(",\n");
        sb.append("    \"weighted_parity_score\": ").append(fmtDouble(wp.weightedParityScore())).append(",\n");
        sb.append("    \"loaded_template_count\": ").append(wp.loadedTemplateCount()).append(",\n");
        sb.append("    \"ref_critical_functional_count\": ").append(wp.refCriticalFunctionalCount()).append(",\n");
        sb.append("    \"cand_critical_functional_count\": ").append(wp.candCriticalFunctionalCount()).append(",\n");
        sb.append("    \"critical_functional_loss_count\": ").append(wp.criticalFunctionalLossCount()).append(",\n");
        sb.append("    \"missing_block_entity_count\": ").append(wp.missingBlockEntityCount()).append(",\n");
        sb.append("    \"ref_path_block_count\": ").append(wp.refPathBlockCount()).append(",\n");
        sb.append("    \"cand_path_block_count\": ").append(wp.candPathBlockCount()).append(",\n");
        sb.append("    \"path_discontinuity_count\": ").append(wp.pathDiscontinuityCount()).append(",\n");
        sb.append("    \"obstructed_doorway_count\": ").append(wp.obstructedDoorwayCount()).append(",\n");
        sb.append("    \"ref_tree_feature_count\": ").append(wp.refTreeFeatureCount()).append(",\n");
        sb.append("    \"cand_tree_feature_count\": ").append(wp.candTreeFeatureCount()).append(",\n");
        sb.append("    \"template_matched_feature_count\": ").append(wp.templateMatchedFeatureCount()).append(",\n");
        sb.append("    \"malformed_feature_count\": ").append(wp.malformedFeatureCount()).append(",\n");
        sb.append("    \"ore_conservation_match_pct\": ").append(fmtDouble(wp.oreConservationMatchPct())).append(",\n");
        sb.append("    \"breaking_tripwires\": ").append(stringListJson(wp.breakingTripwires())).append(",\n");
        sb.append("    \"matrix_cells\": [\n");
        for (int ci = 0; ci < wp.matrixCells().size(); ci++) {
            WeightedParityEvaluator.WeightedMatrixCell cell = wp.matrixCells().get(ci);
            sb.append("      {\"domain\": ").append(quote(cell.domain().name()))
                    .append(", \"mode\": ").append(quote(cell.mode().name()))
                    .append(", \"count\": ").append(cell.voxelOrInstanceCount())
                    .append(", \"weight\": ").append(fmtDouble(cell.appliedWeightMultiplier()))
                    .append(", \"penalty_points\": ").append(fmtDouble(cell.weightedPenaltyPoints()))
                    .append("}");
            if (ci + 1 < wp.matrixCells().size()) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("    ]\n");
        sb.append("  },\n");
        MigrationSeamStats ms = sc.migrationSeam() == null ? MigrationSeamStats.empty() : sc.migrationSeam();
        sb.append("  \"migration_seam\": {\n");
        sb.append("    \"compared_shared_chunk_edges\": ").append(ms.comparedSharedChunkEdges()).append(",\n");
        sb.append("    \"border_columns_checked\": ").append(ms.borderColumnsChecked()).append(",\n");
        sb.append("    \"border_surface_exact_pct\": ").append(fmtDouble(ms.borderSurfaceExactPct())).append(",\n");
        sb.append("    \"border_surface_within_1_block_pct\": ")
                .append(fmtDouble(ms.borderSurfaceWithin1BlockPct())).append(",\n");
        sb.append("    \"seam_surface_mae_blocks\": ").append(fmtDouble(ms.seamSurfaceMaeBlocks())).append(",\n");
        sb.append("    \"seam_max_induced_cliff_blocks\": ").append(ms.seamMaxInducedCliffBlocks()).append(",\n");
        sb.append("    \"seam_induced_cliff_edges\": ").append(ms.seamInducedCliffEdges()).append(",\n");
        sb.append("    \"border_exact_biome_pct\": ").append(fmtDouble(ms.borderExactBiomePct())).append(",\n");
        sb.append("    \"border_strict_block_pct\": ").append(fmtDouble(ms.borderStrictBlockPct())).append(",\n");
        sb.append("    \"interior_strict_block_pct\": ").append(fmtDouble(ms.interiorStrictBlockPct())).append(",\n");
        sb.append("    \"border_subterranean_solid_air_agreement_pct\": ")
                .append(fmtDouble(ms.borderSubterraneanSolidAirAgreementPct())).append(",\n");
        sb.append("    \"border_subterranean_fluid_leak_voxels\": ")
                .append(ms.borderSubterraneanFluidLeakVoxels()).append("\n");
        sb.append("  }\n");
        sb.append("}");
        return sb.toString();
    }

    /** Formats a {@link RegionSimilarityScorecard} as a human-readable Markdown report. */
    public static String toMarkdown(RegionSimilarityScorecard sc) {
        Objects.requireNonNull(sc, "scorecard");
        StringBuilder sb = new StringBuilder(4096);
        sb.append("### Region Similarity Scorecard: `")
                .append(sc.referenceLabel())
                .append("` vs `")
                .append(sc.candidateLabel())
                .append("`\n\n");
        sb.append("- **Compared Full Chunks:** `").append(sc.comparedFullChunks())
                .append("` (`refOnly=").append(sc.refOnlyChunks())
                .append("`, `candOnly=").append(sc.candOnlyChunks()).append("`)\n");
        sb.append("- **Strict Voxel Block-ID Match:** `")
                .append(fmtPct(sc.strictBlockIdMatchPct())).append("%`\n");
        sb.append("- **6-Class Semantic Material Match:** `")
                .append(fmtPct(sc.semanticCategoryMatchPct())).append("%`\n");
        sb.append("- **Biome Quartet Match:** exact `")
                .append(fmtPct(sc.exactBiomeQuartetMatchPct()))
                .append("%` | 8-climate bucket `")
                .append(fmtPct(sc.climateClassQuartetMatchPct())).append("%`\n\n");

        sb.append("#### Axis 1: Macro Topography & Heightmaps\n\n");
        sb.append("| Heightmap | Columns | Pearson `r` | MAE (blocks) | RMSE (blocks) | Max `|dY|` | Ref Y Range | Cand Y Range | Ref Land `>62` | Cand Land `>62` |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (HeightmapStats hm : sc.heightmaps().values()) {
            sb.append("| `").append(hm.heightmapType()).append("` | ")
                    .append(hm.sampleColumns()).append(" | ")
                    .append(fmtDouble4(hm.pearsonR())).append(" | ")
                    .append(fmtPct(hm.maeBlocks())).append(" | ")
                    .append(fmtPct(hm.rmseBlocks())).append(" | ")
                    .append(hm.maxAbsDeltaBlocks()).append(" | `[")
                    .append(hm.refMinY()).append("..").append(hm.refMaxY()).append("]` | `[")
                    .append(hm.candMinY()).append("..").append(hm.candMaxY()).append("]` | ")
                    .append(fmtPct(hm.refLandFractionAboveSea() * 100.0)).append("% | ")
                    .append(fmtPct(hm.candLandFractionAboveSea() * 100.0)).append("% |\n");
        }
        sb.append("\n");

        sb.append("#### Axis 2: Semantic Material Totals\n\n");
        sb.append("| Category | Reference Voxels | Candidate Voxels |\n");
        sb.append("|---|---:|---:|\n");
        for (SemanticMaterial mat : SemanticMaterial.values()) {
            sb.append("| `").append(mat.name()).append("` | ")
                    .append(sc.refMaterialCounts().getOrDefault(mat, 0L)).append(" | ")
                    .append(sc.candMaterialCounts().getOrDefault(mat, 0L)).append(" |\n");
        }
        sb.append("\n");

        CaveFluidStats cf = sc.caveFluidStats();
        sb.append("#### Axis 3: Caves, Ores, Flora & Structures\n\n");
        sb.append("- **Upper Subsurface (`Y in [-53, 62]`):** Ref cave air `")
                .append(cf.refUpperCaveAirVoxels()).append("`, water `")
                .append(cf.refUpperSubsurfaceWater()).append("` | Cand cave air `")
                .append(cf.candUpperCaveAirVoxels()).append("`, water `")
                .append(cf.candUpperSubsurfaceWater()).append("`\n");
        sb.append("- **Deep Subsurface (`Y <= -54`):** Ref cave air `")
                .append(cf.refDeepCaveAirVoxels()).append("`, lava `")
                .append(cf.refDeepLavaVoxels()).append("` | Cand cave air `")
                .append(cf.candDeepCaveAirVoxels()).append("`, lava `")
                .append(cf.candDeepLavaVoxels()).append("`\n");
        sb.append("- **Flora & Canopy Voxels:** Ref `").append(sc.refFloraAndCanopyVoxels())
                .append("` | Cand `").append(sc.candFloraAndCanopyVoxels()).append("`\n");
        sb.append("- **Chunks With Structure Starts / References:** Ref `")
                .append(sc.refChunksWithStructureStarts()).append("` / `")
                .append(sc.refChunksWithStructureReferences()).append("` | Cand `")
                .append(sc.candChunksWithStructureStarts()).append("` / `")
                .append(sc.candChunksWithStructureReferences()).append("`\n\n");

        sb.append("| Mineral Ore | Reference Total | Candidate Total |\n");
        sb.append("|---|---:|---:|\n");
        for (String ore : ORE_FAMILIES) {
            sb.append("| `").append(ore).append("` | ")
                    .append(sc.refOreCounts().getOrDefault(ore, 0L)).append(" | ")
                    .append(sc.candOreCounts().getOrDefault(ore, 0L)).append(" |\n");
        }

        WeightedParityEvaluator.WeightedParityReport wp = sc.weightedParity();
        sb.append("\n#### Axis 4: Weighted Semantic & Relative-Layout Parity Matrix\n\n");
        sb.append("- **Parity Gate Verdict:** `").append(wp.verdict().name())
                .append("` | **Weighted Parity Score:** `")
                .append(fmtPct(wp.weightedParityScore())).append(" / 100.00`\n");
        sb.append("- **Critical Functional Blocks & Containers (`250x`):** Ref `")
                .append(wp.refCriticalFunctionalCount()).append("`, Cand `")
                .append(wp.candCriticalFunctionalCount()).append("`, Missing Blocks `")
                .append(wp.criticalFunctionalLossCount()).append("`, Missing `block_entities` `")
                .append(wp.missingBlockEntityCount()).append("`\n");
        sb.append("- **Street & Doorway Layout (`40x`):** Ref `dirt_path` `")
                .append(wp.refPathBlockCount()).append("`, Cand `")
                .append(wp.candPathBlockCount()).append("`, Path Discontinuities `")
                .append(wp.pathDiscontinuityCount()).append("`, Obstructed Doors `")
                .append(wp.obstructedDoorwayCount()).append("`\n");
        sb.append("- **3D `.nbt` / Stamp Feature Conformity (`0x` shifted-valid, `15x` malformed):** Loaded Templates `")
                .append(wp.loadedTemplateCount()).append("`, Ref Trees `")
                .append(wp.refTreeFeatureCount()).append("`, Cand Trees `")
                .append(wp.candTreeFeatureCount()).append("` (`")
                .append(wp.templateMatchedFeatureCount()).append("` template-conformant, `")
                .append(wp.malformedFeatureCount()).append("` malformed)\n");
        sb.append("- **Mineral Conservation Overlap (`1x`):** `")
                .append(fmtPct(wp.oreConservationMatchPct())).append("%`\n");
        if (!wp.breakingTripwires().isEmpty()) {
            sb.append("- **Breaking Tripwires (`").append(wp.breakingTripwires().size()).append("`):**\n");
            for (String tw : wp.breakingTripwires()) {
                sb.append("  - `").append(tw).append("`\n");
            }
        }
        sb.append("\n| Feature Domain | Deviation Mode | Count | Penalty Weight | Penalty Points |\n");
        sb.append("|---|---|---:|---:|---:|\n");
        for (WeightedParityEvaluator.WeightedMatrixCell cell : wp.matrixCells()) {
            sb.append("| `").append(cell.domain().name())
                    .append("` | `").append(cell.mode().name())
                    .append("` | ").append(cell.voxelOrInstanceCount())
                    .append(" | ").append(fmtPct(cell.appliedWeightMultiplier()))
                    .append("x | ").append(fmtPct(cell.weightedPenaltyPoints()))
                    .append(" |\n");
        }

        MigrationSeamStats ms = sc.migrationSeam() == null ? MigrationSeamStats.empty() : sc.migrationSeam();
        sb.append("\n#### Axis 5: Migration Seam Continuity (Vanilla <-> Candidate Chunk Borders)\n\n");
        sb.append("- **Shared Chunk Edges Evaluated:** `").append(ms.comparedSharedChunkEdges())
                .append("` | **Perimeter Columns:** `").append(ms.borderColumnsChecked()).append("`\n");
        sb.append("- **Border Surface Continuity:** exact `").append(fmtPct(ms.borderSurfaceExactPct()))
                .append("%` | walkable (`|dY| <= 1`) `").append(fmtPct(ms.borderSurfaceWithin1BlockPct()))
                .append("%` | mean induced cliff `").append(fmtDouble4(ms.seamSurfaceMaeBlocks()))
                .append("` blocks | max induced cliff `").append(ms.seamMaxInducedCliffBlocks())
                .append("` blocks | synthetic cliff edges (`>= 2` blocks) `")
                .append(ms.seamInducedCliffEdges()).append("`\n");
        sb.append("- **Border Biome Match:** `").append(fmtPct(ms.borderExactBiomePct())).append("%`\n");
        sb.append("- **Perimeter vs. Interior Strict Block Match:** border `")
                .append(fmtPct(ms.borderStrictBlockPct())).append("%` | interior `")
                .append(fmtPct(ms.interiorStrictBlockPct())).append("%`\n");
        sb.append("- **Subterranean Border Cave/Aquifer Continuity:** solid/air/fluid agreement `")
                .append(fmtPct(ms.borderSubterraneanSolidAirAgreementPct()))
                .append("%` | fluid-to-air seam leaks `")
                .append(ms.borderSubterraneanFluidLeakVoxels()).append("` voxels\n");
        return sb.toString();
    }

    /**
     * Headless CLI entry point comparing two world/region paths and emitting JSON or Markdown.
     *
     * @param args {@code <refPath> <candPath> [--ref-label L] [--cand-label L] [--max-chunks N]
     *             [--format json|markdown] [--json-out P] [--md-out P]}
     * @throws IOException on filesystem read/write failure
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            byte[] usage = ("Usage: RegionSimilarityAuditor <refPath> <candPath> "
                    + "[--ref-label <label>] [--cand-label <label>] [--max-chunks <N>] "
                    + "[--format json|markdown] [--json-out <file>] [--md-out <file>]\n")
                    .getBytes(StandardCharsets.UTF_8);
            System.err.write(usage);
            System.exit(2);
            return;
        }
        CliOptions opts = parseCliOptions(args);
        String primary = executeCliOnWorker(opts);
        System.out.write((primary + "\n").getBytes(StandardCharsets.UTF_8));
        System.out.flush();
    }

    private record CliOptions(
            Path refPath,
            Path candPath,
            String refLabel,
            String candLabel,
            int maxChunks,
            String format,
            Path jsonOut,
            Path mdOut,
            List<Path> templateNbtSources) {}

    private static CliOptions parseCliOptions(String[] args) {
        Path refPath = Path.of(args[0]);
        Path candPath = Path.of(args[1]);
        String refLabel = "vanilla";
        String candLabel = "leafgen";
        int maxChunks = 0;
        String format = "json";
        Path jsonOut = null;
        Path mdOut = null;
        List<Path> templateSources = new ArrayList<>();

        for (int i = 2; i < args.length; i++) {
            String arg = args[i];
            if (i + 1 >= args.length) {
                continue;
            }
            switch (arg) {
                case "--ref-label" -> refLabel = args[++i];
                case "--cand-label" -> candLabel = args[++i];
                case "--max-chunks" -> maxChunks = Integer.parseInt(args[++i]);
                case "--format" -> format = args[++i].toLowerCase(Locale.ROOT);
                case "--json-out" -> jsonOut = Path.of(args[++i]);
                case "--md-out" -> mdOut = Path.of(args[++i]);
                case "--template-nbt-source", "--template-nbt-dir", "--server-jar" ->
                        templateSources.add(Path.of(args[++i]));
                default -> {
                }
            }
        }
        return new CliOptions(
                refPath,
                candPath,
                refLabel,
                candLabel,
                maxChunks,
                format,
                jsonOut,
                mdOut,
                Collections.unmodifiableList(templateSources));
    }

    private static String executeCliOnWorker(CliOptions opts) throws IOException {
        RegionSimilarityScorecard scorecard = compareDirectoriesOnWorker(
                opts.refPath(),
                opts.candPath(),
                opts.refLabel(),
                opts.candLabel(),
                opts.maxChunks(),
                opts.templateNbtSources());
        String json = toJson(scorecard);
        String md = toMarkdown(scorecard);
        writeOptionalOutputFile(opts.jsonOut(), json);
        writeOptionalOutputFile(opts.mdOut(), md);
        return "markdown".equals(opts.format()) || "md".equals(opts.format()) ? md : json;
    }

    private static void writeOptionalOutputFile(Path target, String content) throws IOException {
        if (target == null) {
            return;
        }
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.writeString(target, content + "\n", StandardCharsets.UTF_8);
    }

    private static Map<RegionCoord, RegionArchiveHandle> discoverRegions(Path target) throws IOException {
        Map<RegionCoord, RegionArchiveHandle> found = new TreeMap<>(REGION_COORD_ORDER);
        if (!Files.exists(target)) {
            return found;
        }
        if (Files.isRegularFile(target)) {
            registerRegionCandidate(found, target);
            return found;
        }
        Path scanRoot = target;
        for (String relCandidate : List.of(
                "dimensions/minecraft/overworld/region",
                "world/dimensions/minecraft/overworld/region",
                "region",
                "world/region")) {
            Path candDir = target.resolve(relCandidate);
            if (Files.isDirectory(candDir)) {
                scanRoot = candDir;
                break;
            }
        }
        try (Stream<Path> walk = Files.walk(scanRoot, 3)) {
            walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(file -> {
                        Path parent = file.getParent();
                        if (parent != null) {
                            String parentName = parent.getFileName().toString().toLowerCase(Locale.ROOT);
                            if ("entities".equals(parentName) || "poi".equals(parentName)) {
                                return;
                            }
                        }
                        registerRegionCandidate(found, file);
                    });
        }
        return found;
    }

    private static void registerRegionCandidate(
            Map<RegionCoord, RegionArchiveHandle> found, Path file) {
        Matcher m = REGION_FILE_PATTERN.matcher(file.getFileName().toString());
        if (!m.matches()) {
            return;
        }
        int rx = Integer.parseInt(m.group(1));
        int rz = Integer.parseInt(m.group(2));
        String ext = "." + m.group(3).toLowerCase(Locale.ROOT);
        RegionFileReader reader = RegionFormatRegistry.getReader(ext);
        if (reader == null) {
            return;
        }
        RegionCoord coord = new RegionCoord(rx, rz);
        RegionArchiveHandle existing = found.get(coord);
        if (existing == null || extensionRank(ext) < extensionRank(existing.ext())) {
            found.put(coord, new RegionArchiveHandle(file, ext, reader));
        }
    }

    private static int extensionRank(String ext) {
        int idx = EXTENSION_PRIORITY.indexOf(ext);
        return idx >= 0 ? idx : EXTENSION_PRIORITY.size();
    }

    private static byte[] readBytesSafe(RegionArchiveHandle handle) throws IOException {
        if (handle == null) {
            return EMPTY_BYTES;
        }
        try {
            return Files.readAllBytes(handle.file());
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "Failed to read region archive " + handle.file(), ex);
            throw ex;
        }
    }

    private static AnvilReader.ChunkEntry readFullChunkSafe(
            RegionArchiveHandle handle, byte[] bytes, int localX, int localZ) throws IOException {
        if (handle == null || bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            if (!handle.reader().isChunkGenerated(bytes, bytes.length, localX, localZ)) {
                return null;
            }
            AnvilReader.ChunkEntry entry = handle.reader().readChunk(bytes, bytes.length, localX, localZ);
            if (entry == null || entry.root == null || !RegionFileReader.isFullChunkRoot(entry.root)) {
                return null;
            }
            return entry;
        } catch (IOException ex) {
            LOGGER.log(
                    Level.WARNING,
                    "Failed to decode chunk (" + localX + "," + localZ + ") in " + handle.file(),
                    ex);
            throw ex;
        } catch (RuntimeException ex) {
            LOGGER.log(
                    Level.WARNING,
                    "Unexpected runtime failure decoding chunk (" + localX + "," + localZ + ") in " + handle.file(),
                    ex);
            throw ex;
        }
    }

    private static String normalizeBlockId(String raw) {
        if (raw == null || raw.isEmpty()) {
            return CANONICAL_AIR;
        }
        int bracket = raw.indexOf('[');
        String base = (bracket >= 0 ? raw.substring(0, bracket) : raw).trim().toLowerCase(Locale.ROOT);
        if (base.isEmpty()) {
            return CANONICAL_AIR;
        }
        return base.indexOf(':') >= 0 ? base : ("minecraft:" + base);
    }

    private static String normalizeResourceId(String raw, String fallback) {
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        String base = raw.trim().toLowerCase(Locale.ROOT);
        if (base.isEmpty()) {
            return fallback;
        }
        return base.indexOf(':') >= 0 ? base : ("minecraft:" + base);
    }

    private static String classifyOreFamily(String id) {
        String path = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        return switch (path) {
            case "coal_ore", "deepslate_coal_ore" -> "coal";
            case "iron_ore", "deepslate_iron_ore", "raw_iron_block" -> "iron";
            case "copper_ore", "deepslate_copper_ore", "raw_copper_block" -> "copper";
            case "gold_ore", "deepslate_gold_ore", "raw_gold_block" -> "gold";
            case "redstone_ore", "deepslate_redstone_ore" -> "redstone";
            case "lapis_ore", "deepslate_lapis_ore" -> "lapis";
            case "diamond_ore", "deepslate_diamond_ore" -> "diamond";
            case "emerald_ore", "deepslate_emerald_ore" -> "emerald";
            default -> null;
        };
    }

    private static boolean isLeavesBlock(String id) {
        return id.endsWith("_leaves")
                || id.equals("minecraft:wart_block")
                || id.equals("minecraft:nether_wart_block")
                || id.equals("minecraft:warped_wart_block");
    }

    private static boolean isFloraOrCanopyBlock(String id) {
        if (FLORA_EXACT.contains(id) || isLeavesBlock(id)) {
            return true;
        }
        return id.endsWith("_log")
                || id.endsWith("_wood")
                || id.endsWith("_stem")
                || id.endsWith("_hyphae")
                || id.endsWith("_sapling")
                || id.endsWith("_propagule")
                || id.endsWith("_tulip");
    }

    private static boolean isNonMotionBlockingPlant(String id) {
        return FLORA_EXACT.contains(id) && !SOLID_FLORA_EXACT.contains(id);
    }

    private static boolean isStructureDecorBlock(String id) {
        if (STRUCTURE_DECOR_EXACT.contains(id)) {
            return true;
        }
        for (String suffix : STRUCTURE_DECOR_SUFFIXES) {
            if (id.endsWith(suffix)) {
                return true;
            }
        }
        for (String token : STRUCTURE_DECOR_TOKENS) {
            if (id.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String classifyElevationBand(int y) {
        if (y <= -33) return "[-64,-33]";
        if (y <= 0) return "[-32,0]";
        if (y <= 47) return "[1,47]";
        if (y <= 62) return "[48,62]";
        if (y <= 95) return "[63,95]";
        if (y <= 127) return "[96,127]";
        if (y <= 191) return "[128,191]";
        return "[192,319]";
    }

    private static String classifyOreYBand(int y) {
        if (y <= -33) return "[-64,-33]";
        if (y <= 0) return "[-32,0]";
        if (y <= 47) return "[1,47]";
        if (y <= 127) return "[48,127]";
        return "[128,319]";
    }

    @SuppressWarnings("unchecked")
    private static boolean recordStructureStarts(
            Map<String, Object> root, Map<String, Integer> structureCounts) {
        Map<String, Object> structures = getStructuresCompound(root);
        if (structures == null) {
            return false;
        }
        Object startsObj = structures.get("starts");
        if (startsObj == null) {
            startsObj = structures.get("Starts");
        }
        if (!(startsObj instanceof Map<?, ?> startsMap) || startsMap.isEmpty()) {
            return false;
        }
        boolean anyActive = false;
        for (Map.Entry<?, ?> entry : startsMap.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> startCompound) || startCompound.isEmpty()) {
                continue;
            }
            Object idObj = startCompound.get("id");
            if (idObj instanceof String startId && "INVALID".equalsIgnoreCase(startId)) {
                continue;
            }
            String key = normalizeResourceId(String.valueOf(entry.getKey()), "minecraft:unknown");
            structureCounts.merge(key, 1, Integer::sum);
            anyActive = true;
        }
        return anyActive;
    }

    private static boolean hasStructureReferences(Map<String, Object> root) {
        Map<String, Object> structures = getStructuresCompound(root);
        if (structures == null) {
            return false;
        }
        Object refsObj = structures.get("References");
        if (refsObj == null) {
            refsObj = structures.get("references");
        }
        if (!(refsObj instanceof Map<?, ?> refsMap) || refsMap.isEmpty()) {
            return false;
        }
        for (Object v : refsMap.values()) {
            if (v instanceof long[] arr && arr.length > 0) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getStructuresCompound(Map<String, Object> root) {
        if (root == null) {
            return null;
        }
        Object s = root.get("structures");
        if (s == null) {
            s = root.get("Structures");
        }
        if (s == null && root.get("Level") instanceof Map<?, ?> lvl) {
            s = lvl.get("Structures");
            if (s == null) {
                s = lvl.get("structures");
            }
        }
        return (s instanceof Map<?, ?>) ? (Map<String, Object>) s : null;
    }

    private static final class DecodedChunkScratch {
        final String[][] sectionBlockIds = new String[SECTION_COUNT][4096];
        final SemanticMaterial[][] sectionMaterials = new SemanticMaterial[SECTION_COUNT][4096];
        final boolean[][] sectionIsFlora = new boolean[SECTION_COUNT][4096];
        final String[][] sectionOreFamily = new String[SECTION_COUNT][4096];
        final String[][] sectionBiomes = new String[SECTION_COUNT][64];

        final int[] derivedWorldSurfaceY = new int[256];
        final int[] derivedOceanFloorY = new int[256];
        final int[] derivedMotionBlockingNoLeavesY = new int[256];
        final int[] solidCeilingY = new int[256];
        int chunkFloorY = MIN_WORLD_Y;

        void decode(LinkedHashMap<String, Object> root) {
            AnvilChunkView view = AnvilReader.toView(root);
            chunkFloorY = resolveChunkFloorY(root, view);

            PaletteSection[] byIndex = indexBlockSections(view);
            BiomePaletteSection[] biomeByIndex = new BiomePaletteSection[SECTION_COUNT];
            BiomePaletteSection fallbackBiomeSec = indexBiomeSections(view, biomeByIndex);

            for (int syIdx = 0; syIdx < SECTION_COUNT; syIdx++) {
                decodeBlockSection(syIdx, byIndex[syIdx]);
                decodeBiomeSection(syIdx, biomeByIndex[syIdx] != null ? biomeByIndex[syIdx] : fallbackBiomeSec);
            }
            computeDerivedColumnHeights();
        }

        private static int resolveChunkFloorY(Map<String, Object> root, AnvilChunkView view) {
            Object yPosObj = root.get("yPos");
            if (yPosObj instanceof Number n) {
                return n.intValue() * 16;
            }
            int viewMin = view.minHeight();
            return view.sections().isEmpty() ? MIN_WORLD_Y : Math.min(MIN_WORLD_Y, viewMin);
        }

        private static PaletteSection[] indexBlockSections(AnvilChunkView view) {
            PaletteSection[] byIndex = new PaletteSection[SECTION_COUNT];
            for (PaletteSection ps : view.sections()) {
                int idx = ps.sectionY() - MIN_SECTION_Y;
                if (idx >= 0 && idx < SECTION_COUNT) {
                    byIndex[idx] = ps;
                }
            }
            return byIndex;
        }

        private static BiomePaletteSection indexBiomeSections(
                AnvilChunkView view, BiomePaletteSection[] biomeByIndex) {
            BiomePaletteSection fallback = null;
            for (BiomePaletteSection bs : view.biomeSections()) {
                if (fallback == null) {
                    fallback = bs;
                }
                int idx = bs.sectionY() - MIN_SECTION_Y;
                if (idx >= 0 && idx < SECTION_COUNT) {
                    biomeByIndex[idx] = bs;
                }
            }
            return fallback;
        }

        private void computeDerivedColumnHeights() {
            Arrays.fill(derivedWorldSurfaceY, MIN_WORLD_Y);
            Arrays.fill(derivedOceanFloorY, MIN_WORLD_Y);
            Arrays.fill(derivedMotionBlockingNoLeavesY, MIN_WORLD_Y);
            Arrays.fill(solidCeilingY, MIN_WORLD_Y);

            for (int colIdx = 0; colIdx < 256; colIdx++) {
                scanSingleColumnTopDown(colIdx);
            }
        }

        private void scanSingleColumnTopDown(int colIdx) {
            boolean foundSurface = false;
            boolean foundOceanFloor = false;
            boolean foundMbnl = false;

            for (int yIdx = WORLD_HEIGHT - 1; yIdx >= 0; yIdx--) {
                int syIdx = yIdx >>> 4;
                int vIdx = ((yIdx & 15) << 8) | colIdx;
                SemanticMaterial mat = sectionMaterials[syIdx][vIdx];
                if (mat == SemanticMaterial.AIR) {
                    continue;
                }
                int worldY = MIN_WORLD_Y + yIdx;
                if (!foundSurface) {
                    derivedWorldSurfaceY[colIdx] = worldY;
                    foundSurface = true;
                }
                String id = sectionBlockIds[syIdx][vIdx];
                boolean passableCanopy = isLeavesBlock(id) || isNonMotionBlockingPlant(id);
                if (!foundMbnl && !passableCanopy) {
                    derivedMotionBlockingNoLeavesY[colIdx] = worldY;
                    foundMbnl = true;
                }
                if (!foundOceanFloor && mat != SemanticMaterial.FLUID && !passableCanopy) {
                    derivedOceanFloorY[colIdx] = worldY;
                    solidCeilingY[colIdx] = worldY;
                    foundOceanFloor = true;
                }
                if (foundSurface && foundMbnl && foundOceanFloor) {
                    return;
                }
            }
        }

        private void decodeBlockSection(int syIdx, PaletteSection ps) {
            String[] ids = sectionBlockIds[syIdx];
            SemanticMaterial[] mats = sectionMaterials[syIdx];
            boolean[] flora = sectionIsFlora[syIdx];
            String[] ores = sectionOreFamily[syIdx];

            if (ps == null || ps.palette().isEmpty()) {
                Arrays.fill(ids, CANONICAL_AIR);
                Arrays.fill(mats, SemanticMaterial.AIR);
                Arrays.fill(flora, false);
                Arrays.fill(ores, null);
                return;
            }

            List<String> rawPal = ps.palette();
            int palSize = rawPal.size();
            String[] normPal = new String[palSize];
            SemanticMaterial[] matPal = new SemanticMaterial[palSize];
            boolean[] floraPal = new boolean[palSize];
            String[] orePal = new String[palSize];

            for (int i = 0; i < palSize; i++) {
                String norm = normalizeBlockId(rawPal.get(i));
                normPal[i] = norm;
                matPal[i] = classifySemanticMaterial(norm);
                floraPal[i] = isFloraOrCanopyBlock(norm);
                orePal[i] = classifyOreFamily(norm);
            }

            if (palSize == 1 || ps.data() == null || ps.data().length == 0) {
                Arrays.fill(ids, normPal[0]);
                Arrays.fill(mats, matPal[0]);
                Arrays.fill(flora, floraPal[0]);
                Arrays.fill(ores, orePal[0]);
                return;
            }

            long[] data = ps.data();
            for (int idx = 0; idx < 4096; idx++) {
                int pIdx = PackedPaletteDecoder.decode(data, palSize, idx);
                if (pIdx < 0 || pIdx >= palSize) {
                    pIdx = 0;
                }
                ids[idx] = normPal[pIdx];
                mats[idx] = matPal[pIdx];
                flora[idx] = floraPal[pIdx];
                ores[idx] = orePal[pIdx];
            }
        }

        private void decodeBiomeSection(int syIdx, BiomePaletteSection bs) {
            String[] out = sectionBiomes[syIdx];
            if (bs == null || bs.palette().isEmpty()) {
                Arrays.fill(out, DEFAULT_BIOME);
                return;
            }
            if (bs.palette().size() == 1 || bs.data() == null || bs.data().length == 0) {
                Arrays.fill(out, normalizeResourceId(bs.palette().get(0), DEFAULT_BIOME));
                return;
            }
            int q = 0;
            for (int qy = 0; qy < 4; qy++) {
                for (int qz = 0; qz < 4; qz++) {
                    for (int qx = 0; qx < 4; qx++) {
                        String raw = bs.biomeIdAt(qx << 2, qy << 2, qz << 2);
                        out[q++] = normalizeResourceId(raw, DEFAULT_BIOME);
                    }
                }
            }
        }

        int[] extractHeightmap(Map<String, Object> root, String requestedType) {
            long[] packed = findHeightmapArray(root, requestedType);
            if (packed != null && hasNonZeroWord(packed)) {
                int bits = Math.max(9, PackedPaletteDecoder.bitsPerEntry(WORLD_HEIGHT + 1));
                int entriesPerLong = 64 / bits;
                long mask = (1L << bits) - 1L;
                int[] out = new int[256];
                for (int col = 0; col < 256; col++) {
                    int longIdx = col / entriesPerLong;
                    int slot = col - longIdx * entriesPerLong;
                    if (longIdx < packed.length) {
                        int rawVal = (int) ((packed[longIdx] >>> (slot * bits)) & mask);
                        int y = (rawVal <= 0) ? chunkFloorY : (chunkFloorY + rawVal - 1);
                        out[col] = Math.max(MIN_WORLD_Y, Math.min(MAX_WORLD_Y, y));
                    } else {
                        out[col] = MIN_WORLD_Y;
                    }
                }
                return out;
            }

            return switch (requestedType) {
                case "WORLD_SURFACE_WG" -> derivedWorldSurfaceY.clone();
                case "OCEAN_FLOOR_WG" -> derivedOceanFloorY.clone();
                default -> derivedMotionBlockingNoLeavesY.clone();
            };
        }

        private static long[] findHeightmapArray(Map<String, Object> root, String requestedType) {
            if (root == null) {
                return EMPTY_LONGS;
            }
            Object hmObj = root.get("Heightmaps");
            if (!(hmObj instanceof Map<?, ?> hmMap)) {
                return EMPTY_LONGS;
            }
            Object direct = hmMap.get(requestedType);
            if (direct instanceof long[] arr && arr.length > 0) {
                return arr;
            }
            if ("WORLD_SURFACE_WG".equals(requestedType)) {
                Object alt = hmMap.get("WORLD_SURFACE");
                if (alt instanceof long[] arr && arr.length > 0) {
                    return arr;
                }
            } else if ("OCEAN_FLOOR_WG".equals(requestedType)) {
                Object alt = hmMap.get("OCEAN_FLOOR");
                if (alt instanceof long[] arr && arr.length > 0) {
                    return arr;
                }
            }
            return EMPTY_LONGS;
        }

        private static boolean hasNonZeroWord(long[] words) {
            for (long w : words) {
                if (w != 0L) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class HeightmapAccumulator {
        final String type;
        long count;
        double sumX;
        double sumY;
        double sumXX;
        double sumYY;
        double sumXY;
        long sumAbsDelta;
        long sumSqDelta;
        int maxAbsDelta;
        int refMinY = Integer.MAX_VALUE;
        int refMaxY = Integer.MIN_VALUE;
        int candMinY = Integer.MAX_VALUE;
        int candMaxY = Integer.MIN_VALUE;
        long refAboveSea;
        long candAboveSea;
        final Map<String, Long> refHist = initKeyedZeroMap(ELEVATION_BANDS);
        final Map<String, Long> candHist = initKeyedZeroMap(ELEVATION_BANDS);

        HeightmapAccumulator(String type) {
            this.type = type;
        }

        void recordChunk(int[] refColY, int[] candColY) {
            for (int i = 0; i < 256; i++) {
                int rx = refColY[i];
                int cy = candColY[i];
                int diff = Math.abs(rx - cy);

                count++;
                sumX += rx;
                sumY += cy;
                sumXX += (double) rx * rx;
                sumYY += (double) cy * cy;
                sumXY += (double) rx * cy;
                sumAbsDelta += diff;
                sumSqDelta += (long) diff * diff;
                if (diff > maxAbsDelta) {
                    maxAbsDelta = diff;
                }
                if (rx < refMinY) refMinY = rx;
                if (rx > refMaxY) refMaxY = rx;
                if (cy < candMinY) candMinY = cy;
                if (cy > candMaxY) candMaxY = cy;
                if (rx > SEA_LEVEL_Y) refAboveSea++;
                if (cy > SEA_LEVEL_Y) candAboveSea++;

                String rBand = classifyElevationBand(rx);
                refHist.put(rBand, refHist.get(rBand) + 1L);
                String cBand = classifyElevationBand(cy);
                candHist.put(cBand, candHist.get(cBand) + 1L);
            }
        }

        HeightmapStats finish() {
            if (count == 0L) {
                return new HeightmapStats(
                        type,
                        0L,
                        0.0,
                        0.0,
                        0.0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0.0,
                        0.0,
                        Collections.unmodifiableMap(refHist),
                        Collections.unmodifiableMap(candHist));
            }
            double n = (double) count;
            double cov = sumXY - (sumX * sumY) / n;
            double varX = sumXX - (sumX * sumX) / n;
            double varY = sumYY - (sumY * sumY) / n;
            double denom = Math.sqrt(Math.max(0.0, varX) * Math.max(0.0, varY));
            double pearsonR;
            if (denom > 1e-9) {
                pearsonR = Math.max(-1.0, Math.min(1.0, cov / denom));
            } else {
                pearsonR = (maxAbsDelta == 0) ? 1.0 : 0.0;
            }
            double mae = sumAbsDelta / n;
            double rmse = Math.sqrt(sumSqDelta / n);
            return new HeightmapStats(
                    type,
                    count,
                    pearsonR,
                    mae,
                    rmse,
                    maxAbsDelta,
                    refMinY,
                    refMaxY,
                    candMinY,
                    candMaxY,
                    refAboveSea / n,
                    candAboveSea / n,
                    Collections.unmodifiableMap(refHist),
                    Collections.unmodifiableMap(candHist));
        }
    }

    private static <E extends Enum<E>> EnumMap<E, Long> initEnumMap(Class<E> cls) {
        EnumMap<E, Long> map = new EnumMap<>(cls);
        for (E c : cls.getEnumConstants()) {
            map.put(c, 0L);
        }
        return map;
    }

    private static Map<String, Long> initKeyedZeroMap(List<String> keys) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (String k : keys) {
            map.put(k, 0L);
        }
        return map;
    }

    private static Map<String, Map<String, Long>> initOreBandMap() {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        for (String ore : ORE_FAMILIES) {
            out.put(ore, initKeyedZeroMap(ORE_Y_BANDS));
        }
        return out;
    }

    private static Map<String, Map<String, Long>> unmodifiableNestedMap(
            Map<String, Map<String, Long>> src) {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Long>> e : src.entrySet()) {
            out.put(e.getKey(), Collections.unmodifiableMap(e.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }

    private static String quote(String s) {
        if (s == null) {
            return "null";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String fmtDouble(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "0.0";
        }
        return String.format(Locale.ROOT, "%.6f", v);
    }

    private static String fmtDouble4(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "0.0000";
        }
        return String.format(Locale.ROOT, "%.4f", v);
    }

    private static String fmtPct(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "0.00";
        }
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static <E extends Enum<E>> String enumMapJson(Map<E, Long> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<E, Long> e : map.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(quote(e.getKey().name())).append(": ").append(e.getValue());
        }
        sb.append("}");
        return sb.toString();
    }

    private static String longMapJson(Map<String, Long> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Long> e : map.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(quote(e.getKey())).append(": ").append(e.getValue());
        }
        sb.append("}");
        return sb.toString();
    }

    private static String nestedLongMapJson(Map<String, Map<String, Long>> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Map<String, Long>> e : map.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(quote(e.getKey())).append(": ").append(longMapJson(e.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }

    private static String intMapJson(Map<String, Integer> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(quote(e.getKey())).append(": ").append(e.getValue());
        }
        sb.append("}");
        return sb.toString();
    }

    private static String stringListJson(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String item : items) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(quote(item));
        }
        sb.append("]");
        return sb.toString();
    }
}
