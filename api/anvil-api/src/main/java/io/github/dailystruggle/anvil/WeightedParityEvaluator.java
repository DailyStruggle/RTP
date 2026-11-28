package io.github.dailystruggle.anvil;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Multi-chunk semantic criticality and 3D relative-layout parity matrix evaluator for offline
 * region comparisons ({@code .mca}, {@code .lca}, {@code .linear}, {@code .b_linear}).
 *
 * <p>Quantifies world-generation deviations by <em>what</em> changed and whether <em>relative 3D
 * layout</em> remains intact rather than treating all {@code 98,304} voxels in a chunk equally:
 * <ul>
 *   <li><b>{@link FeatureDomain#CRITICAL_FUNCTIONAL} ({@code 250x} + Hard Tripwire):</b> Village
 *       and dungeon containers ({@code chest}, {@code trapped_chest}, {@code barrel}, {@code vault}),
 *       spawners, portal frames, bells, beds, workstations, and {@code block_entities} loot/container
 *       compounds. Losing a functional block or its container block-entity triggers
 *       {@link ParityGateVerdict#BREAKING_DEVIATION} even at {@code < 0.01%} raw voxel delta.</li>
 *   <li><b>{@link FeatureDomain#STRUCTURAL_PATH_CONNECTIVITY} ({@code 40x} + Layout Tripwire):</b>
 *       {@code dirt_path} street networks across chunk borders (step-climbability {@code |dY| <= 1}
 *       and seam alignment), doorway {@code 2}-high clearance, and structural masonry/plank shells.</li>
 *   <li><b>{@link FeatureDomain#TEMPLATE_CONFORMANT_FEATURE} ({@code 0x} penalty when template-valid,
 *       {@code 15x} when malformed):</b> Stitches 3D wood-and-foliage tree clusters across chunk
 *       seams and validates their anchor-relative 3D shape against {@code .nbt} structure/tree
 *       templates (flat/Paperclip server JARs or directories via {@link StructureTemplateNbtLoader}),
 *       registered {@link LcaDecoratorStamp} / {@link LcaStructureStamp} 8-symmetry variants, or
 *       reference cluster isomorphisms.</li>
 *   <li><b>{@link FeatureDomain#STATISTICAL_ORE_AND_BULK} ({@code 1x}):</b> Mineral Y-band count
 *       conservation and terrain bulk envelope parity where individual blob translations are benign.</li>
 * </ul>
 *
 * @governedBy ADR-016
 * @governedBy ADR-077
 * @governedBy ADR-115
 * @governedBy anvil-api-ADR-002
 * @governedBy anvil-api-ADR-003
 * @governedBy leafgen-ADR-003
 * @governedBy treegen-ADR-003
 * @trace REQ-CFG-ATLAS-001
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class WeightedParityEvaluator {

    private static final Logger LOGGER = Logger.getLogger(WeightedParityEvaluator.class.getName());

    public static final double WEIGHT_CRITICAL_FUNCTIONAL = 250.0;
    public static final double WEIGHT_STRUCTURAL_PATH = 40.0;
    public static final double WEIGHT_MALFORMED_TEMPLATE_FEATURE = 15.0;
    public static final double WEIGHT_STATISTICAL_BULK = 1.0;

    private static final int MIN_SECTION_Y = -4;
    private static final int SECTION_COUNT = 24;
    private static final int MIN_WORLD_Y = MIN_SECTION_Y * 16; // -64
    private static final int MAX_WORLD_Y = MIN_WORLD_Y + SECTION_COUNT * 16 - 1; // 319
    private static final int MAX_TREE_CLUSTER_VOXELS = 2048;

    private static final Set<String> CONTAINER_BLOCK_IDS = Set.of(
            "minecraft:chest",
            "minecraft:trapped_chest",
            "minecraft:barrel",
            "minecraft:shulker_box",
            "minecraft:decorated_pot",
            "minecraft:dispenser",
            "minecraft:dropper",
            "minecraft:hopper",
            "minecraft:vault",
            "minecraft:spawner",
            "minecraft:trial_spawner",
            "minecraft:brewing_stand",
            "minecraft:furnace",
            "minecraft:blast_furnace",
            "minecraft:smoker");

    private static final Set<String> CRITICAL_FUNCTIONAL_EXACT = Set.of(
            "minecraft:chest",
            "minecraft:trapped_chest",
            "minecraft:barrel",
            "minecraft:spawner",
            "minecraft:trial_spawner",
            "minecraft:vault",
            "minecraft:end_portal_frame",
            "minecraft:end_portal",
            "minecraft:nether_portal",
            "minecraft:bell",
            "minecraft:brewing_stand",
            "minecraft:lectern",
            "minecraft:composter",
            "minecraft:blast_furnace",
            "minecraft:smoker",
            "minecraft:furnace",
            "minecraft:grindstone",
            "minecraft:loom",
            "minecraft:cartography_table",
            "minecraft:fletching_table",
            "minecraft:smithing_table",
            "minecraft:stonecutter",
            "minecraft:cauldron",
            "minecraft:water_cauldron",
            "minecraft:lava_cauldron",
            "minecraft:anvil",
            "minecraft:chipped_anvil",
            "minecraft:damaged_anvil",
            "minecraft:enchanting_table",
            "minecraft:beacon",
            "minecraft:respawn_anchor",
            "minecraft:lodestone");

    private static final Set<String> PATH_BLOCK_IDS = Set.of(
            "minecraft:dirt_path",
            "minecraft:grass_path");

    private static final Set<String> GROUND_SUPPORT_EXACT = Set.of(
            "minecraft:grass_block",
            "minecraft:dirt",
            "minecraft:coarse_dirt",
            "minecraft:podzol",
            "minecraft:rooted_dirt",
            "minecraft:mycelium",
            "minecraft:mud",
            "minecraft:muddy_mangrove_roots",
            "minecraft:moss_block",
            "minecraft:pale_moss_block",
            "minecraft:sand",
            "minecraft:red_sand",
            "minecraft:gravel",
            "minecraft:clay",
            "minecraft:stone",
            "minecraft:deepslate",
            "minecraft:netherrack",
            "minecraft:crimson_nylium",
            "minecraft:warped_nylium",
            "minecraft:end_stone",
            "minecraft:farmland",
            "minecraft:snow_block");

    private WeightedParityEvaluator() {}

    /** Primary gameplay criticality domain (rows of the weighted parity matrix). */
    public enum FeatureDomain {
        CRITICAL_FUNCTIONAL(WEIGHT_CRITICAL_FUNCTIONAL),
        STRUCTURAL_PATH_CONNECTIVITY(WEIGHT_STRUCTURAL_PATH),
        TEMPLATE_CONFORMANT_FEATURE(WEIGHT_MALFORMED_TEMPLATE_FEATURE),
        STATISTICAL_ORE_AND_BULK(WEIGHT_STATISTICAL_BULK);

        private final double basePenaltyWeight;

        FeatureDomain(double basePenaltyWeight) {
            this.basePenaltyWeight = basePenaltyWeight;
        }

        public double basePenaltyWeight() {
            return basePenaltyWeight;
        }
    }

    /** Relative-layout deviation mode (columns of the weighted parity matrix). */
    public enum DeviationMode {
        EXACT_COORDINATE,
        TRANSLATED_ROTATED_INTACT,
        STATISTICAL_EQUIVALENT,
        LAYOUT_DISCONTINUITY,
        MISSING_OR_BROKEN
    }

    /** Top-level pass/fail gate combining weighted parity with breaking-deviation tripwires. */
    public enum ParityGateVerdict {
        EXACT_MATCH,
        ACCEPTABLE_EQUIVALENT,
        BREAKING_DEVIATION
    }

    /** Single cell in the {@code FeatureDomain x DeviationMode} weighted parity matrix. */
    public record WeightedMatrixCell(
            FeatureDomain domain,
            DeviationMode mode,
            long voxelOrInstanceCount,
            double appliedWeightMultiplier,
            double weightedPenaltyPoints) {

        public WeightedMatrixCell {
            Objects.requireNonNull(domain, "domain");
            Objects.requireNonNull(mode, "mode");
        }
    }

    /** Aggregated weighted parity report across all paired chunks and regions. */
    public record WeightedParityReport(
            ParityGateVerdict verdict,
            double weightedParityScore,
            int loadedTemplateCount,
            long refCriticalFunctionalCount,
            long candCriticalFunctionalCount,
            long criticalFunctionalLossCount,
            long missingBlockEntityCount,
            long refPathBlockCount,
            long candPathBlockCount,
            long pathDiscontinuityCount,
            long obstructedDoorwayCount,
            int refTreeFeatureCount,
            int candTreeFeatureCount,
            int templateMatchedFeatureCount,
            int malformedFeatureCount,
            double oreConservationMatchPct,
            List<WeightedMatrixCell> matrixCells,
            List<String> breakingTripwires) {

        public WeightedParityReport {
            Objects.requireNonNull(verdict, "verdict");
            matrixCells = List.copyOf(Objects.requireNonNull(matrixCells, "matrixCells"));
            breakingTripwires = List.copyOf(Objects.requireNonNull(breakingTripwires, "breakingTripwires"));
        }

        /** Looks up a single {@code (domain, mode)} cell in this matrix. */
        public WeightedMatrixCell cell(FeatureDomain domain, DeviationMode mode) {
            for (WeightedMatrixCell cell : matrixCells) {
                if (cell.domain() == domain && cell.mode() == mode) {
                    return cell;
                }
            }
            return new WeightedMatrixCell(domain, mode, 0L, 0.0, 0.0);
        }
    }

    /**
     * Normalized 8-symmetry ({@code 4 rotations x 2 mirrors}) relative-layout signature library
     * hydrated from server JARs (flat or nested Paperclip {@code META-INF/versions/<ver>/paper-<ver>.jar}),
     * {@code .nbt} directories, {@link LcaDecoratorStamp} registry, and {@link StructureTemplateNbtLoader}.
     */
    public static final class TemplateConformityLibrary {
        private final Set<String> exactStateSignatures;
        private final Set<String> speciesNormalizedSignatures;
        private final int loadedTemplateCount;

        private TemplateConformityLibrary(
                Set<String> exactStateSignatures,
                Set<String> speciesNormalizedSignatures,
                int loadedTemplateCount) {
            this.exactStateSignatures = Collections.unmodifiableSet(exactStateSignatures);
            this.speciesNormalizedSignatures = Collections.unmodifiableSet(speciesNormalizedSignatures);
            this.loadedTemplateCount = loadedTemplateCount;
        }

        public static TemplateConformityLibrary fromSources(Collection<Path> nbtSources) throws IOException {
            Collection<Path> safeSources = nbtSources == null ? List.of() : List.copyOf(nbtSources);
            return loadFromSourcesOnWorker(safeSources);
        }

        static TemplateConformityLibrary loadFromSourcesOnWorker(Collection<Path> nbtSources) throws IOException {
            Set<String> exactSigs = new LinkedHashSet<>();
            Set<String> normSigs = new LinkedHashSet<>();
            int loadedTemplates = 0;

            // 1. Registered LcaDecoratorStamp tree/canopy stamps
            for (LcaDecoratorStamp stamp : LcaDecoratorStamp.allRegisteredStamps()) {
                List<RelVoxel> voxels = extractDecoratorStampVoxels(stamp);
                if (!voxels.isEmpty()) {
                    registerEightSymmetries(voxels, exactSigs, normSigs);
                    loadedTemplates++;
                }
            }

            // 2. Built-in StructureTemplateNbtLoader variants (prewarmed 8-symmetry sets)
            for (LcaStructureStamp stamp : StructureTemplateNbtLoader.allCachedBuiltinStamps()) {
                List<RelVoxel> voxels = extractStructureStampVoxels(stamp);
                if (!voxels.isEmpty()) {
                    addNormalizedSignature(voxels, exactSigs, normSigs);
                }
            }

            // 3. Explicit .nbt files, directories, or flat / Paperclip server JARs
            if (nbtSources != null) {
                for (Path source : nbtSources) {
                    if (source == null || !Files.exists(source)) {
                        continue;
                    }
                    loadedTemplates += loadFromPathSource(source.toAbsolutePath().normalize(), exactSigs, normSigs);
                }
            }

            return new TemplateConformityLibrary(exactSigs, normSigs, loadedTemplates);
        }

        public int loadedTemplateCount() {
            return loadedTemplateCount;
        }

        boolean matchesAnyVariant(List<RelVoxel> cluster) {
            if (cluster.isEmpty()) {
                return false;
            }
            String exact = computeCanonicalSignature(cluster, true);
            if (exactStateSignatures.contains(exact)) {
                return true;
            }
            String norm = computeCanonicalSignature(cluster, false);
            return speciesNormalizedSignatures.contains(norm);
        }

        private static int loadFromPathSource(
                Path source, Set<String> exactSigs, Set<String> normSigs) throws IOException {
            Path baseDir = source.toAbsolutePath().normalize();
            if (Files.isDirectory(baseDir)) {
                int count = 0;
                try (Stream<Path> walk = Files.walk(baseDir)) {
                    List<Path> nbtFiles = walk
                            .filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nbt"))
                            .sorted()
                            .toList();
                    for (Path nbtFile : nbtFiles) {
                        Path targetPath = nbtFile.toAbsolutePath().normalize();
                        if (!targetPath.startsWith(baseDir)) {
                            continue;
                        }
                        String relId = baseDir.relativize(targetPath).toString().replace('\\', '/');
                        if (ingestSingleNbtBytes(relId, Files.readAllBytes(targetPath), exactSigs, normSigs)) {
                            count++;
                        }
                    }
                }
                return count;
            }
            String fileName = baseDir.getFileName().toString().toLowerCase(Locale.ROOT);
            if (fileName.endsWith(".nbt")) {
                return ingestSingleNbtBytes(fileName, Files.readAllBytes(baseDir), exactSigs, normSigs) ? 1 : 0;
            }
            if (fileName.endsWith(".jar") || fileName.endsWith(".zip")) {
                return loadFromArchiveJar(baseDir, exactSigs, normSigs);
            }
            return 0;
        }

        private static int loadFromArchiveJar(
                Path jarPath, Set<String> exactSigs, Set<String> normSigs) throws IOException {
            Path normJarPath = jarPath.toAbsolutePath().normalize();
            Path archiveAnchor = normJarPath.getParent() != null ? normJarPath.getParent().normalize() : normJarPath;
            int loaded = 0;
            try (ZipFile zf = new ZipFile(normJarPath.toFile())) {
                var entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String name = entry.getName();
                    if (!isSafeArchiveEntryName(archiveAnchor, name)) {
                        continue;
                    }
                    if (isStructureOrFeatureNbtEntry(name)) {
                        byte[] raw = zf.getInputStream(entry).readAllBytes();
                        if (ingestSingleNbtBytes(name, raw, exactSigs, normSigs)) {
                            loaded++;
                        }
                    } else if (name.startsWith("META-INF/versions/") && name.endsWith(".jar")) {
                        byte[] nestedJarBytes = zf.getInputStream(entry).readAllBytes();
                        loaded += loadFromNestedJarBytes(archiveAnchor, nestedJarBytes, exactSigs, normSigs);
                    }
                }
            }
            // Also check Paperclip sibling cache directory: versions/<ver>/paper-<ver>.jar
            if (loaded == 0 && normJarPath.getParent() != null) {
                Path parentDir = normJarPath.getParent().normalize();
                Path siblingVersions = parentDir.resolve("versions").normalize();
                if (!siblingVersions.startsWith(parentDir) || !Files.isDirectory(siblingVersions)) {
                    siblingVersions = parentDir.resolve("paper-base/versions").normalize();
                }
                if (siblingVersions.startsWith(parentDir) && Files.isDirectory(siblingVersions)) {
                    final Path safeRoot = siblingVersions;
                    try (Stream<Path> walk = Files.walk(safeRoot, 3)) {
                        List<Path> innerJars = walk
                                .filter(Files::isRegularFile)
                                .filter(p -> p.getFileName().toString().endsWith(".jar"))
                                .sorted()
                                .toList();
                        for (Path inner : innerJars) {
                            Path targetInner = inner.toAbsolutePath().normalize();
                            if (targetInner.startsWith(safeRoot)) {
                                loaded += loadFromArchiveJar(targetInner, exactSigs, normSigs);
                            }
                        }
                    }
                }
            }
            return loaded;
        }

        private static boolean isSafeArchiveEntryName(Path baseDir, String entryName) {
            if (entryName == null || entryName.isEmpty() || entryName.contains("..") || entryName.startsWith("/")) {
                return false;
            }
            Path candidate = baseDir.resolve(entryName).normalize();
            return candidate.startsWith(baseDir);
        }

        private static int loadFromNestedJarBytes(
                Path archiveAnchor, byte[] jarBytes, Set<String> exactSigs, Set<String> normSigs) throws IOException {
            int count = 0;
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(jarBytes), StandardCharsets.UTF_8)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (!entry.isDirectory()
                            && isSafeArchiveEntryName(archiveAnchor, name)
                            && isStructureOrFeatureNbtEntry(name)) {
                        byte[] nbtBytes = zis.readAllBytes();
                        if (ingestSingleNbtBytes(name, nbtBytes, exactSigs, normSigs)) {
                            count++;
                        }
                    }
                }
            }
            return count;
        }

        private static boolean isStructureOrFeatureNbtEntry(String entryName) {
            String low = entryName.toLowerCase(Locale.ROOT);
            if (!low.endsWith(".nbt")) {
                return false;
            }
            return low.contains("/structure/")
                    || low.contains("/structures/")
                    || low.startsWith("structures/")
                    || low.startsWith("data/");
        }

        private static boolean ingestSingleNbtBytes(
                String rawTemplateId,
                byte[] rawOrGzipBytes,
                Set<String> exactSigs,
                Set<String> normSigs) {
            try {
                StructureTemplateNbtLoader.TemplateVariantSet variantSet =
                        StructureTemplateNbtLoader.loadFromBytes(
                                sanitizeTemplateId(rawTemplateId),
                                rawOrGzipBytes,
                                LcaStructureStamp.TerrainProjection.RIGID,
                                List.of());
                boolean anyNonEmpty = false;
                for (LcaStructureStamp variant : variantSet.variants()) {
                    List<RelVoxel> allVoxels = extractStructureStampVoxels(variant);
                    if (!allVoxels.isEmpty()) {
                        addNormalizedSignature(allVoxels, exactSigs, normSigs);
                        anyNonEmpty = true;
                    }
                    // Also index wood+foliage sub-signature when template embeds a tree/canopy
                    List<RelVoxel> treeSub = new ArrayList<>();
                    for (RelVoxel rv : allVoxels) {
                        if (isTreeTrunkOrCanopyBlock(rv.baseBlockId)) {
                            treeSub.add(rv);
                        }
                    }
                    if (!treeSub.isEmpty() && treeSub.size() < allVoxels.size()) {
                        addNormalizedSignature(treeSub, exactSigs, normSigs);
                    }
                }
                return anyNonEmpty;
            } catch (IOException | RuntimeException ex) {
                LOGGER.log(
                        Level.WARNING,
                        ex,
                        () -> "Skipping unreadable structure .nbt template '" + rawTemplateId + "': " + ex.getMessage());
                return false;
            }
        }

        private static String sanitizeTemplateId(String raw) {
            String norm = raw.replace('\\', '/').toLowerCase(Locale.ROOT);
            if (norm.endsWith(".nbt")) {
                norm = norm.substring(0, norm.length() - 4);
            }
            if (norm.startsWith("data/")) {
                String rest = norm.substring("data/".length());
                int slash = rest.indexOf('/');
                if (slash > 0) {
                    String ns = rest.substring(0, slash);
                    String path = rest.substring(slash + 1);
                    if (path.startsWith("structures/")) {
                        path = path.substring("structures/".length());
                    } else if (path.startsWith("structure/")) {
                        path = path.substring("structure/".length());
                    }
                    return ns + ":" + path;
                }
            }
            return norm.indexOf(':') >= 0 ? norm : ("minecraft:" + norm);
        }
    }

    private record RelVoxel(int x, int y, int z, String baseBlockId, String roleKind) {}

    record ChunkParityInput(
            int localX,
            int localZ,
            String[][] sectionBlockIds,
            Map<String, Object> rootCompound) {
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ChunkParityInput other)) {
                return false;
            }
            return this.localX == other.localX
                    && this.localZ == other.localZ
                    && java.util.Arrays.deepEquals(this.sectionBlockIds, other.sectionBlockIds)
                    && java.util.Objects.equals(this.rootCompound, other.rootCompound);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + this.localX;
            result = 31 * result + this.localZ;
            result = 31 * result + java.util.Arrays.deepHashCode(this.sectionBlockIds);
            result = 31 * result + java.util.Objects.hashCode(this.rootCompound);
            return result;
        }

        @Override
        public String toString() {
            return "ChunkParityInput[localX=" + this.localX
                    + ", localZ=" + this.localZ
                    + ", sectionBlockIds=" + java.util.Arrays.deepToString(this.sectionBlockIds)
                    + ", rootCompound=" + this.rootCompound
                    + "]";
        }
    }

    static final class Accumulator {
        private final TemplateConformityLibrary library;

        long refCriticalFunctionalCount;
        long candCriticalFunctionalCount;
        long exactCriticalMatches;
        long shiftedIntactCriticalMatches;
        long criticalFunctionalLossCount;
        long missingBlockEntityCount;

        long refPathBlockCount;
        long candPathBlockCount;
        long exactPathMatches;
        long connectedShiftedPathCount;
        long pathDiscontinuityCount;
        long obstructedDoorwayCount;

        long exactStructureShellMatches;
        long brokenStructureShellCount;

        int refTreeFeatureCount;
        int candTreeFeatureCount;
        int exactTreeFeatureCount;
        int templateMatchedFeatureCount;
        int malformedFeatureCount;
        long exactTreeVoxels;
        long templateMatchedShiftedTreeVoxels;
        long malformedTreeVoxels;

        long exactBulkAndOreVoxels;
        long statisticalEquivalentBulkAndOreVoxels;
        long bulkTerrainEnvelopeMismatchVoxels;

        final Map<String, Long> refCriticalByType = new TreeMap<>();
        final Map<String, Long> candCriticalByType = new TreeMap<>();
        final List<String> breakingTripwires = new ArrayList<>();

        Accumulator(TemplateConformityLibrary library) {
            this.library = Objects.requireNonNull(library, "library");
        }

        void evaluateRegionPair(
                int regionX,
                int regionZ,
                List<ChunkParityInput> refChunks,
                List<ChunkParityInput> candChunks) {
            if (refChunks.isEmpty() || candChunks.isEmpty()) {
                return;
            }
            RegionStitchedView refGrid = new RegionStitchedView(regionX, regionZ, refChunks);
            RegionStitchedView candGrid = new RegionStitchedView(regionX, regionZ, candChunks);

            evaluateCriticalFunctionalParity(regionX, regionZ, refGrid, candGrid);
            evaluatePathAndDoorwayConnectivity(regionX, regionZ, refGrid, candGrid);
            evaluateCrossChunkTreeTemplates(refGrid, candGrid);
            evaluateVoxelShellAndBulkColumns(refGrid, candGrid);
        }

        private void evaluateCriticalFunctionalParity(
                int regionX,
                int regionZ,
                RegionStitchedView refGrid,
                RegionStitchedView candGrid) {
            Map<String, List<BlockPos>> refByBlock = refGrid.collectBlocksMatching(WeightedParityEvaluator::isCriticalFunctionalBlock);
            Map<String, List<BlockPos>> candByBlock = candGrid.collectBlocksMatching(WeightedParityEvaluator::isCriticalFunctionalBlock);

            Set<String> allTypes = new LinkedHashSet<>();
            allTypes.addAll(refByBlock.keySet());
            allTypes.addAll(candByBlock.keySet());

            for (String blockId : allTypes) {
                List<BlockPos> refPositions = refByBlock.getOrDefault(blockId, List.of());
                List<BlockPos> candPositions = candByBlock.getOrDefault(blockId, List.of());
                refCriticalFunctionalCount += refPositions.size();
                candCriticalFunctionalCount += candPositions.size();
                refCriticalByType.merge(blockId, (long) refPositions.size(), Long::sum);
                candCriticalByType.merge(blockId, (long) candPositions.size(), Long::sum);

                Set<BlockPos> unmatchedCand = new LinkedHashSet<>(candPositions);
                long exactType = 0L;
                for (BlockPos rp : refPositions) {
                    if (unmatchedCand.remove(rp)) {
                        exactType++;
                    }
                }
                exactCriticalMatches += exactType;

                long remainingRef = refPositions.size() - exactType;
                long shiftedMatched = 0L;
                if (remainingRef > 0 && !unmatchedCand.isEmpty()) {
                    for (BlockPos rp : refPositions) {
                        if (candPositions.contains(rp)) {
                            continue;
                        }
                        BlockPos nearest = findNearestWithinRadius(rp, unmatchedCand, 24, 12);
                        if (nearest != null) {
                            unmatchedCand.remove(nearest);
                            shiftedMatched++;
                        }
                    }
                }
                shiftedIntactCriticalMatches += shiftedMatched;

                long lost = Math.max(0L, remainingRef - shiftedMatched);
                if (lost > 0L) {
                    criticalFunctionalLossCount += lost;
                    recordTripwire(
                            "CRITICAL_FUNCTIONAL loss in r." + regionX + "." + regionZ + ": missing "
                                    + lost + "x " + blockId + " (ref=" + refPositions.size()
                                    + ", cand=" + candPositions.size() + ")");
                }

                // Verify container block_entities presence on candidate container blocks when reference
                // carries initialized container block_entities (prevents empty/broken loot chests).
                if (CONTAINER_BLOCK_IDS.contains(blockId)) {
                    int refBeForType = countMatchingBlockEntities(refPositions, refGrid.blockEntitiesByPos, blockId);
                    int candBeForType = countMatchingBlockEntities(candPositions, candGrid.blockEntitiesByPos, blockId);
                    if (refBeForType > candBeForType) {
                        int missingBe = refBeForType - candBeForType;
                        missingBlockEntityCount += missingBe;
                        recordTripwire(
                                "CRITICAL_FUNCTIONAL block_entities loss in r." + regionX + "." + regionZ
                                        + ": missing " + missingBe + "x container block_entity NBT for "
                                        + blockId + " (ref=" + refBeForType + ", cand=" + candBeForType + ")");
                    }
                }
            }
        }

        private void evaluatePathAndDoorwayConnectivity(
                int regionX,
                int regionZ,
                RegionStitchedView refGrid,
                RegionStitchedView candGrid) {
            Map<Long, Integer> refPathSurfaceByXz = refGrid.collectTopPathHeightsByXz();
            Map<Long, Integer> candPathSurfaceByXz = candGrid.collectTopPathHeightsByXz();

            refPathBlockCount += refPathSurfaceByXz.size();
            candPathBlockCount += candPathSurfaceByXz.size();

            if (!refPathSurfaceByXz.isEmpty()) {
                if (candPathSurfaceByXz.isEmpty()) {
                    pathDiscontinuityCount += refPathSurfaceByXz.size();
                    recordTripwire(
                            "STRUCTURAL_PATH_CONNECTIVITY loss in r." + regionX + "." + regionZ
                                    + ": candidate dropped all " + refPathSurfaceByXz.size()
                                    + " village dirt_path columns");
                } else {
                    for (Map.Entry<Long, Integer> entry : refPathSurfaceByXz.entrySet()) {
                        long xz = entry.getKey();
                        int wx = unpackXzX(xz);
                        int wz = unpackXzZ(xz);
                        int refY = entry.getValue();
                        Integer candSameXzY = candPathSurfaceByXz.get(xz);
                        if (candSameXzY != null && Math.abs(candSameXzY - refY) <= 1) {
                            exactPathMatches++;
                        } else if (hasNearbyPathColumn(candPathSurfaceByXz, wx, wz, refY, 1, 1)) {
                            connectedShiftedPathCount++;
                        }
                    }

                    // Check cross-chunk & street-graph edge continuity: every reference adjacent path edge
                    // with climbable step |dY| <= 1 must not shear or cliff-jump (|dY| > 1) in candidate,
                    // and candidate path tiles at chunk seams must connect cleanly without 1-block shear!
                    int refClimbableEdges = countClimbablePathEdges(refPathSurfaceByXz);
                    int candClimbableEdges = countClimbablePathEdges(candPathSurfaceByXz);
                    int candCliffOrShearBreaks = countPathSeamShearsAndCliffs(
                            refPathSurfaceByXz, candPathSurfaceByXz, refGrid, candGrid);

                    if (candCliffOrShearBreaks > 0) {
                        pathDiscontinuityCount += candCliffOrShearBreaks;
                        recordTripwire(
                                "STRUCTURAL_PATH_CONNECTIVITY break in r." + regionX + "." + regionZ
                                        + ": detected " + candCliffOrShearBreaks
                                        + " sheared or non-climbable (|dY|>1) dirt_path street transitions");
                    } else if (refClimbableEdges >= 4 && candClimbableEdges * 2 < refClimbableEdges) {
                        int edgeLoss = refClimbableEdges - candClimbableEdges;
                        pathDiscontinuityCount += edgeLoss;
                        recordTripwire(
                                "STRUCTURAL_PATH_CONNECTIVITY fragmentation in r." + regionX + "." + regionZ
                                        + ": climbable path edges dropped from " + refClimbableEdges
                                        + " to " + candClimbableEdges);
                    }
                }
            }

            // Check village/structure doorway clearance & street reachability in candidate (diffed against vanilla ref)
            List<BlockPos> candLowerDoors = candGrid.collectLowerDoorBlocks();
            List<BlockPos> refLowerDoors = refGrid.collectLowerDoorBlocks();
            Set<BlockPos> refLowerDoorSet = new HashSet<>(refLowerDoors);
            for (BlockPos door : candLowerDoors) {
                if (!candGrid.hasDoorwayEntryClearance(door)) {
                    boolean refAlsoObstructed = refLowerDoorSet.contains(door)
                            && !refGrid.hasDoorwayEntryClearance(door);
                    if (!refAlsoObstructed) {
                        obstructedDoorwayCount++;
                    }
                } else if (!refPathSurfaceByXz.isEmpty()
                        && hasAdjacentPath(refLowerDoorSet, refPathSurfaceByXz, door)
                        && !hasNearbyPathColumn(candPathSurfaceByXz, door.x, door.z, door.y - 1, 2, 1)) {
                    pathDiscontinuityCount++;
                    recordTripwire(
                            "STRUCTURAL_PATH_CONNECTIVITY doorway-to-path disconnect at ("
                                    + door.x + "," + door.y + "," + door.z + ") in r." + regionX + "." + regionZ);
                }
            }
            if (obstructedDoorwayCount > 0L) {
                recordTripwire(
                        "STRUCTURAL_PATH_CONNECTIVITY obstructed doorway clearance ("
                                + obstructedDoorwayCount + " blocked doors) in r." + regionX + "." + regionZ);
            }
        }

        private void evaluateCrossChunkTreeTemplates(
                RegionStitchedView refGrid, RegionStitchedView candGrid) {
            List<ExtractedTreeCluster> refTrees = refGrid.extractTreeClusters();
            List<ExtractedTreeCluster> candTrees = candGrid.extractTreeClusters();
            refTreeFeatureCount += refTrees.size();
            candTreeFeatureCount += candTrees.size();

            // Build an ephemeral isomorphism table from the reference region's own grounded trees
            // across all 8 symmetries so Vanilla procedural trees are also recognized if translated/rotated.
            Set<String> refExactSigs = new HashSet<>();
            Set<String> refNormSigs = new HashSet<>();
            Set<String> refWorldAnchorKeys = new HashSet<>();
            for (ExtractedTreeCluster rt : refTrees) {
                registerEightSymmetries(rt.relVoxels, refExactSigs, refNormSigs);
                refWorldAnchorKeys.add(rt.worldExactSignature);
            }

            for (ExtractedTreeCluster ct : candTrees) {
                if (refWorldAnchorKeys.contains(ct.worldExactSignature)) {
                    exactTreeFeatureCount++;
                    templateMatchedFeatureCount++;
                    exactTreeVoxels += ct.relVoxels.size();
                    continue;
                }
                boolean matchesLibrary = library.matchesAnyVariant(ct.relVoxels);
                boolean matchesRefIsomorphism = refExactSigs.contains(computeCanonicalSignature(ct.relVoxels, true))
                        || refNormSigs.contains(computeCanonicalSignature(ct.relVoxels, false));

                boolean matchedTree = ct.groundedOnSoil && ct.allLeavesConnected
                        && ((matchesLibrary || matchesRefIsomorphism)
                                || (ct.hasTrunk && ct.hasCanopy
                                        && library.loadedTemplateCount() == 0 && refTrees.isEmpty()));
                if (matchedTree) {
                    templateMatchedFeatureCount++;
                    templateMatchedShiftedTreeVoxels += ct.relVoxels.size();
                } else {
                    malformedFeatureCount++;
                    malformedTreeVoxels += ct.relVoxels.size();
                }
            }

            // Detect floating orphan leaves that had no connected log trunk at all
            long candOrphanLeafClusters = candGrid.countDisconnectedFloatingLeafClusters();
            if (candOrphanLeafClusters > 0L) {
                malformedFeatureCount += (int) candOrphanLeafClusters;
                malformedTreeVoxels += candOrphanLeafClusters * 3L;
            }
        }

        private void evaluateVoxelShellAndBulkColumns(
                RegionStitchedView refGrid, RegionStitchedView candGrid) {
            for (Long chunkKey : refGrid.chunksByLocalKey.keySet()) {
                ChunkParityInput refChunk = refGrid.chunksByLocalKey.get(chunkKey);
                ChunkParityInput candChunk = candGrid.chunksByLocalKey.get(chunkKey);
                if (refChunk == null || candChunk == null) {
                    continue;
                }
                for (int syIdx = 0; syIdx < SECTION_COUNT; syIdx++) {
                    String[] rSec = refChunk.sectionBlockIds[syIdx];
                    String[] cSec = candChunk.sectionBlockIds[syIdx];
                    for (int vIdx = 0; vIdx < 4096; vIdx++) {
                        String rId = rSec[vIdx];
                        String cId = cSec[vIdx];
                        if (isCriticalFunctionalBlock(rId) || isCriticalFunctionalBlock(cId)
                                || PATH_BLOCK_IDS.contains(rId) || PATH_BLOCK_IDS.contains(cId)
                                || isTreeTrunkOrCanopyBlock(rId) || isTreeTrunkOrCanopyBlock(cId)) {
                            continue;
                        }
                        boolean rStruct = isStructuralShellBlock(rId);
                        boolean cStruct = isStructuralShellBlock(cId);
                        if (rStruct || cStruct) {
                            if (rId.equals(cId)) {
                                exactStructureShellMatches++;
                            } else if (rStruct && cStruct) {
                                connectedShiftedPathCount++;
                            } else {
                                brokenStructureShellCount++;
                            }
                            continue;
                        }

                        if (rId.equals(cId)) {
                            exactBulkAndOreVoxels++;
                        } else {
                            RegionSimilarityAuditor.SemanticMaterial rMat =
                                    RegionSimilarityAuditor.classifySemanticMaterial(rId);
                            RegionSimilarityAuditor.SemanticMaterial cMat =
                                    RegionSimilarityAuditor.classifySemanticMaterial(cId);
                            if (rMat == cMat
                                    || rMat == RegionSimilarityAuditor.SemanticMaterial.MINERAL_ORE
                                    || cMat == RegionSimilarityAuditor.SemanticMaterial.MINERAL_ORE) {
                                // Re-blobbed ore veins in stone/deepslate or equivalent strata (stone/tuff/andesite)
                                // are evaluated at the Y-band mineral conservation level rather than voxel-by-voxel.
                                statisticalEquivalentBulkAndOreVoxels++;
                            } else {
                                bulkTerrainEnvelopeMismatchVoxels++;
                            }
                        }
                    }
                }
            }
        }

        private void recordTripwire(String message) {
            if (breakingTripwires.size() < 32) {
                breakingTripwires.add(message);
            }
        }

        WeightedParityReport finish(
                double strictBlockIdMatchPct,
                Map<String, Long> refOres,
                Map<String, Long> candOres) {
            double oreConservationPct = computeOreConservationMatchPct(refOres, candOres);
            long totalRefOres = sumMapValues(refOres);
            long totalCandOres = sumMapValues(candOres);
            long missingOreDeficit = Math.max(0L, totalRefOres - totalCandOres);

            List<WeightedMatrixCell> cells = new ArrayList<>();

            // Row 1: CRITICAL_FUNCTIONAL
            long totalCritBroken = criticalFunctionalLossCount + missingBlockEntityCount;
            double critBrokenPenalty = totalCritBroken * WEIGHT_CRITICAL_FUNCTIONAL;
            cells.add(new WeightedMatrixCell(
                    FeatureDomain.CRITICAL_FUNCTIONAL,
                    DeviationMode.EXACT_COORDINATE,
                    exactCriticalMatches,
                    0.0,
                    0.0));
            cells.add(new WeightedMatrixCell(
                    FeatureDomain.CRITICAL_FUNCTIONAL,
                    DeviationMode.TRANSLATED_ROTATED_INTACT,
                    shiftedIntactCriticalMatches,
                    0.0,
                    0.0));
            cells.add(new WeightedMatrixCell(
                    FeatureDomain.CRITICAL_FUNCTIONAL,
                    DeviationMode.MISSING_OR_BROKEN,
                    totalCritBroken,
                    WEIGHT_CRITICAL_FUNCTIONAL,
                    critBrokenPenalty));

            // Row 2: STRUCTURAL_PATH_CONNECTIVITY
            long pathAndDoorBreaks = pathDiscontinuityCount + obstructedDoorwayCount;
            double pathDiscontinuityPenalty = pathAndDoorBreaks * WEIGHT_STRUCTURAL_PATH;
            double shellPenalty = brokenStructureShellCount * (WEIGHT_STRUCTURAL_PATH * 0.25);
            cells.add(new WeightedMatrixCell(
                    FeatureDomain.STRUCTURAL_PATH_CONNECTIVITY,
                    DeviationMode.EXACT_COORDINATE,
                    exactPathMatches + exactStructureShellMatches,
                    0.0,
                    0.0));
            cells.add(new WeightedMatrixCell(
                    FeatureDomain.STRUCTURAL_PATH_CONNECTIVITY,
                    DeviationMode.TRANSLATED_ROTATED_INTACT,
                    connectedShiftedPathCount,
                    0.0,
                    0.0));
            cells.add(new WeightedMatrixCell(
                    FeatureDomain.STRUCTURAL_PATH_CONNECTIVITY,
                    DeviationMode.LAYOUT_DISCONTINUITY,
                    pathAndDoorBreaks,
                    WEIGHT_STRUCTURAL_PATH,
                    pathDiscontinuityPenalty));
            cells.add(new WeightedMatrixCell(
                    FeatureDomain.STRUCTURAL_PATH_CONNECTIVITY,
                    DeviationMode.MISSING_OR_BROKEN,
                    brokenStructureShellCount,
                    WEIGHT_STRUCTURAL_PATH * 0.25,
                    shellPenalty));

            // Row 3: TEMPLATE_CONFORMANT_FEATURE (0.0 penalty when shifted/rotated tree matches .nbt/stamp!)
            double malformedTreePenalty = malformedTreeVoxels * WEIGHT_MALFORMED_TEMPLATE_FEATURE;
            cells.add(new WeightedMatrixCell(
                    FeatureDomain.TEMPLATE_CONFORMANT_FEATURE,
                    DeviationMode.EXACT_COORDINATE,
                    exactTreeVoxels,
                    0.0,
                    0.0));
            cells.add(new WeightedMatrixCell(
                    FeatureDomain.TEMPLATE_CONFORMANT_FEATURE,
                    DeviationMode.TRANSLATED_ROTATED_INTACT,
                    templateMatchedShiftedTreeVoxels,
                    0.0,
                    0.0));
            cells.add(new WeightedMatrixCell(
                    FeatureDomain.TEMPLATE_CONFORMANT_FEATURE,
                    DeviationMode.MISSING_OR_BROKEN,
                    malformedTreeVoxels,
                    WEIGHT_MALFORMED_TEMPLATE_FEATURE,
                    malformedTreePenalty));

            // Row 4: STATISTICAL_ORE_AND_BULK
            double bulkPenalty = (bulkTerrainEnvelopeMismatchVoxels * 0.15 + missingOreDeficit * 2.0)
                    * WEIGHT_STATISTICAL_BULK;
            cells.add(new WeightedMatrixCell(
                    FeatureDomain.STATISTICAL_ORE_AND_BULK,
                    DeviationMode.EXACT_COORDINATE,
                    exactBulkAndOreVoxels,
                    0.0,
                    0.0));
            cells.add(new WeightedMatrixCell(
                    FeatureDomain.STATISTICAL_ORE_AND_BULK,
                    DeviationMode.STATISTICAL_EQUIVALENT,
                    statisticalEquivalentBulkAndOreVoxels,
                    0.0,
                    0.0));
            cells.add(new WeightedMatrixCell(
                    FeatureDomain.STATISTICAL_ORE_AND_BULK,
                    DeviationMode.MISSING_OR_BROKEN,
                    bulkTerrainEnvelopeMismatchVoxels + missingOreDeficit,
                    WEIGHT_STATISTICAL_BULK,
                    bulkPenalty));

            double totalPenalty = critBrokenPenalty + pathDiscontinuityPenalty + shellPenalty
                    + malformedTreePenalty + bulkPenalty;

            // Normalize composite score onto [0.0 .. 100.0] anchored to perceptual gameplay budget
            // so even 1 missing village chest (250 pts) visibly drops the score while shifted valid trees
            // + conserved re-blobbed ores incur 0.0 layout penalty.
            long comparedBulkScale = Math.max(
                    1000L,
                    (exactBulkAndOreVoxels + statisticalEquivalentBulkAndOreVoxels + bulkTerrainEnvelopeMismatchVoxels)
                            / 64L);
            double rawScore = 100.0 * (1.0 - (totalPenalty / (comparedBulkScale + totalPenalty)));
            if (!breakingTripwires.isEmpty()) {
                // Hard penalty deduction per breaking tripwire so gameplay-breaking regressions always
                // score strictly below acceptable shifted-feature candidates
                rawScore = Math.min(rawScore, Math.max(0.0, 82.0 - 8.0 * breakingTripwires.size()));
            }
            double clampedScore = Math.max(0.0, Math.min(100.0, rawScore));

            ParityGateVerdict verdict;
            if (!breakingTripwires.isEmpty() || totalCritBroken > 0L || pathAndDoorBreaks > 0L) {
                verdict = ParityGateVerdict.BREAKING_DEVIATION;
            } else if (Math.abs(strictBlockIdMatchPct - 100.0) <= 1e-7 && totalPenalty == 0.0) {
                verdict = ParityGateVerdict.EXACT_MATCH;
                clampedScore = 100.0;
            } else {
                verdict = ParityGateVerdict.ACCEPTABLE_EQUIVALENT;
            }

            return new WeightedParityReport(
                    verdict,
                    clampedScore,
                    library.loadedTemplateCount(),
                    refCriticalFunctionalCount,
                    candCriticalFunctionalCount,
                    criticalFunctionalLossCount,
                    missingBlockEntityCount,
                    refPathBlockCount,
                    candPathBlockCount,
                    pathDiscontinuityCount,
                    obstructedDoorwayCount,
                    refTreeFeatureCount,
                    candTreeFeatureCount,
                    templateMatchedFeatureCount,
                    malformedFeatureCount,
                    oreConservationPct,
                    cells,
                    breakingTripwires);
        }
    }

    // =========================================================================
    // Stitched Multi-Chunk 3D Region Inspection Helpers
    // =========================================================================

    private record BlockPos(int x, int y, int z) {}

    private record ExtractedTreeCluster(
            List<RelVoxel> relVoxels,
            String worldExactSignature,
            boolean groundedOnSoil,
            boolean hasTrunk,
            boolean hasCanopy,
            boolean allLeavesConnected) {}

    private static final class RegionStitchedView {
        final int regionBaseBlockX;
        final int regionBaseBlockZ;
        final Map<Long, ChunkParityInput> chunksByLocalKey = new LinkedHashMap<>();
        final Map<BlockPos, Map<String, Object>> blockEntitiesByPos = new LinkedHashMap<>();

        RegionStitchedView(int regionX, int regionZ, List<ChunkParityInput> chunks) {
            this.regionBaseBlockX = regionX << 9;
            this.regionBaseBlockZ = regionZ << 9;
            for (ChunkParityInput c : chunks) {
                chunksByLocalKey.put(packLocalKey(c.localX, c.localZ), c);
                indexChunkBlockEntities(regionX, regionZ, c);
            }
        }

        @SuppressWarnings("unchecked")
        private void indexChunkBlockEntities(int regionX, int regionZ, ChunkParityInput chunk) {
            if (chunk.rootCompound == null) {
                return;
            }
            Object rawList = chunk.rootCompound.get("block_entities");
            if (rawList == null) {
                rawList = chunk.rootCompound.get("TileEntities");
            }
            if (rawList == null && chunk.rootCompound.get("Level") instanceof Map<?, ?> lvl) {
                rawList = lvl.get("TileEntities");
                if (rawList == null) {
                    rawList = lvl.get("block_entities");
                }
            }
            List<?> items = extractNbtList(rawList);
            int chunkWorldBaseX = (regionX << 9) + (chunk.localX << 4);
            int chunkWorldBaseZ = (regionZ << 9) + (chunk.localZ << 4);
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> beMap)) {
                    continue;
                }
                Object xObj = beMap.get("x");
                Object yObj = beMap.get("y");
                Object zObj = beMap.get("z");
                if (xObj instanceof Number xn && yObj instanceof Number yn && zObj instanceof Number zn) {
                    int wx = xn.intValue();
                    int wy = yn.intValue();
                    int wz = zn.intValue();
                    // Normalize chunk-local block entity coordinates if stored in 0..15
                    if (wx >= 0 && wx < 16 && (chunkWorldBaseX != 0)) {
                        wx += chunkWorldBaseX;
                    }
                    if (wz >= 0 && wz < 16 && (chunkWorldBaseZ != 0)) {
                        wz += chunkWorldBaseZ;
                    }
                    blockEntitiesByPos.put(new BlockPos(wx, wy, wz), (Map<String, Object>) beMap);
                }
            }
        }

        String blockAtWorld(int wx, int wy, int wz) {
            if (wy < MIN_WORLD_Y || wy > MAX_WORLD_Y) {
                return "minecraft:air";
            }
            int localChunkX = (wx - regionBaseBlockX) >> 4;
            int localChunkZ = (wz - regionBaseBlockZ) >> 4;
            if (localChunkX < 0 || localChunkX >= 32 || localChunkZ < 0 || localChunkZ >= 32) {
                return null;
            }
            ChunkParityInput chunk = chunksByLocalKey.get(packLocalKey(localChunkX, localChunkZ));
            if (chunk == null) {
                return null;
            }
            int syIdx = (wy - MIN_WORLD_Y) >>> 4;
            int vIdx = ((wy & 15) << 8) | ((wz & 15) << 4) | (wx & 15);
            return chunk.sectionBlockIds[syIdx][vIdx];
        }

        Map<String, List<BlockPos>> collectBlocksMatching(java.util.function.Predicate<String> pred) {
            Map<String, List<BlockPos>> out = new TreeMap<>();
            for (ChunkParityInput c : chunksByLocalKey.values()) {
                int baseWx = regionBaseBlockX + (c.localX << 4);
                int baseWz = regionBaseBlockZ + (c.localZ << 4);
                for (int syIdx = 0; syIdx < SECTION_COUNT; syIdx++) {
                    String[] sec = c.sectionBlockIds[syIdx];
                    int baseWy = MIN_WORLD_Y + (syIdx << 4);
                    for (int vIdx = 0; vIdx < 4096; vIdx++) {
                        String id = sec[vIdx];
                        if (pred.test(id)) {
                            int lx = vIdx & 15;
                            int lz = (vIdx >>> 4) & 15;
                            int ly = (vIdx >>> 8) & 15;
                            out.computeIfAbsent(id, k -> new ArrayList<>())
                                    .add(new BlockPos(baseWx + lx, baseWy + ly, baseWz + lz));
                        }
                    }
                }
            }
            return out;
        }

        Map<Long, Integer> collectTopPathHeightsByXz() {
            Map<Long, Integer> out = new LinkedHashMap<>();
            for (ChunkParityInput c : chunksByLocalKey.values()) {
                int baseWx = regionBaseBlockX + (c.localX << 4);
                int baseWz = regionBaseBlockZ + (c.localZ << 4);
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int col = (lz << 4) | lx;
                        for (int syIdx = SECTION_COUNT - 1; syIdx >= 0; syIdx--) {
                            String[] sec = c.sectionBlockIds[syIdx];
                            int foundY = Integer.MIN_VALUE;
                            for (int ly = 15; ly >= 0; ly--) {
                                if (PATH_BLOCK_IDS.contains(sec[(ly << 8) | col])) {
                                    foundY = MIN_WORLD_Y + (syIdx << 4) + ly;
                                    break;
                                }
                            }
                            if (foundY != Integer.MIN_VALUE) {
                                out.put(packXz(baseWx + lx, baseWz + lz), foundY);
                                break;
                            }
                        }
                    }
                }
            }
            return out;
        }

        List<BlockPos> collectLowerDoorBlocks() {
            List<BlockPos> doors = new ArrayList<>();
            Map<String, List<BlockPos>> byId = collectBlocksMatching(WeightedParityEvaluator::isDoorBlock);
            Set<BlockPos> allDoorSet = new HashSet<>();
            for (List<BlockPos> list : byId.values()) {
                allDoorSet.addAll(list);
            }
            for (BlockPos pos : allDoorSet) {
                if (!allDoorSet.contains(new BlockPos(pos.x, pos.y - 1, pos.z))
                        && allDoorSet.contains(new BlockPos(pos.x, pos.y + 1, pos.z))) {
                    doors.add(pos);
                }
            }
            return doors;
        }

        boolean hasDoorwayEntryClearance(BlockPos lowerDoor) {
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            int passableSides = 0;
            for (int[] d : dirs) {
                String low = blockAtWorld(lowerDoor.x + d[0], lowerDoor.y, lowerDoor.z + d[1]);
                String high = blockAtWorld(lowerDoor.x + d[0], lowerDoor.y + 1, lowerDoor.z + d[1]);
                if (low == null || high == null) {
                    passableSides++;
                    continue;
                }
                if (isPassableHeadspace(low) && isPassableHeadspace(high)) {
                    passableSides++;
                }
            }
            // A functional doorway connects inside and outside (at least 2 passable 2-block-high sides)
            return passableSides >= 2;
        }

        private static final int[][] NEIGHBORS_26 = buildNeighbors(3);
        private static final int[][] NEIGHBORS_18 = buildNeighbors(2);

        private static int[][] buildNeighbors(int maxManhattan) {
            List<int[]> list = new ArrayList<>();
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int m = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                        if (m > 0 && m <= maxManhattan) {
                            list.add(new int[] {dx, dy, dz});
                        }
                    }
                }
            }
            return list.toArray(int[][]::new);
        }

        List<ExtractedTreeCluster> extractTreeClusters() {
            Map<String, List<BlockPos>> logBlocksByType = collectBlocksMatching(WeightedParityEvaluator::isLogOrStemBlock);
            Set<BlockPos> allLogs = new LinkedHashSet<>();
            for (List<BlockPos> list : logBlocksByType.values()) {
                allLogs.addAll(list);
            }
            if (allLogs.isEmpty()) {
                return List.of();
            }

            Set<BlockPos> visitedLogs = new HashSet<>();
            Set<BlockPos> claimedLeaves = new HashSet<>();
            List<ExtractedTreeCluster> clusters = new ArrayList<>();

            for (BlockPos seedLog : allLogs) {
                if (!visitedLogs.add(seedLog)) {
                    continue;
                }
                List<BlockPos> trunkLogs = floodFillTrunkLogs(seedLog, allLogs, visitedLogs);
                Boolean grounding = classifyTrunkGrounding(trunkLogs);
                if (grounding == null) {
                    continue;
                }
                Set<BlockPos> attachedLeaves = floodFillAttachedLeaves(trunkLogs, claimedLeaves);
                if (attachedLeaves.isEmpty() && trunkLogs.size() < 3) {
                    continue;
                }
                clusters.add(buildExtractedTreeCluster(trunkLogs, attachedLeaves, grounding));
            }
            return clusters;
        }

        private static List<BlockPos> floodFillTrunkLogs(
                BlockPos seedLog, Set<BlockPos> allLogs, Set<BlockPos> visitedLogs) {
            List<BlockPos> trunkLogs = new ArrayList<>();
            ArrayDeque<BlockPos> logQueue = new ArrayDeque<>();
            logQueue.add(seedLog);
            while (!logQueue.isEmpty() && trunkLogs.size() < MAX_TREE_CLUSTER_VOXELS) {
                BlockPos cur = logQueue.poll();
                trunkLogs.add(cur);
                for (int[] d : NEIGHBORS_26) {
                    BlockPos nb = new BlockPos(cur.x + d[0], cur.y + d[1], cur.z + d[2]);
                    if (allLogs.contains(nb) && visitedLogs.add(nb)) {
                        logQueue.add(nb);
                    }
                }
            }
            return trunkLogs;
        }

        private Boolean classifyTrunkGrounding(List<BlockPos> trunkLogs) {
            int minLogY = Integer.MAX_VALUE;
            for (BlockPos lp : trunkLogs) {
                if (lp.y < minLogY) {
                    minLogY = lp.y;
                }
            }
            boolean groundedOnSoil = false;
            boolean restsOnMasonry = false;
            for (BlockPos lp : trunkLogs) {
                if (lp.y != minLogY) {
                    continue;
                }
                String below = blockAtWorld(lp.x, lp.y - 1, lp.z);
                if (below != null && isGroundSupportBlock(below)) {
                    groundedOnSoil = true;
                } else if (below != null && isStructuralShellBlock(below)) {
                    restsOnMasonry = true;
                }
            }
            return (restsOnMasonry && !groundedOnSoil) ? null : groundedOnSoil;
        }

        private Set<BlockPos> floodFillAttachedLeaves(
                List<BlockPos> trunkLogs, Set<BlockPos> claimedLeaves) {
            Map<BlockPos, Integer> leafDist = new LinkedHashMap<>();
            ArrayDeque<BlockPos> leafQueue = new ArrayDeque<>();
            for (BlockPos lp : trunkLogs) {
                enqueueNeighborLeaves(lp, 1, claimedLeaves, leafDist, leafQueue);
            }
            while (!leafQueue.isEmpty() && (trunkLogs.size() + leafDist.size()) < MAX_TREE_CLUSTER_VOXELS) {
                BlockPos cur = leafQueue.poll();
                int dist = leafDist.get(cur);
                if (dist < 6) {
                    enqueueNeighborLeaves(cur, dist + 1, claimedLeaves, leafDist, leafQueue);
                }
            }
            return leafDist.keySet();
        }

        private void enqueueNeighborLeaves(
                BlockPos origin,
                int nextDist,
                Set<BlockPos> claimedLeaves,
                Map<BlockPos, Integer> leafDist,
                ArrayDeque<BlockPos> leafQueue) {
            for (int[] d : NEIGHBORS_18) {
                BlockPos nb = new BlockPos(origin.x + d[0], origin.y + d[1], origin.z + d[2]);
                String bid = blockAtWorld(nb.x, nb.y, nb.z);
                if (bid != null && isCanopyLeafBlock(bid) && claimedLeaves.add(nb)) {
                    leafDist.put(nb, nextDist);
                    leafQueue.add(nb);
                }
            }
        }

        private ExtractedTreeCluster buildExtractedTreeCluster(
                List<BlockPos> trunkLogs, Set<BlockPos> leaves, boolean groundedOnSoil) {
            List<RelVoxel> worldVoxels = new ArrayList<>(trunkLogs.size() + leaves.size());
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            for (BlockPos lp : trunkLogs) {
                worldVoxels.add(new RelVoxel(lp.x, lp.y, lp.z, blockAtWorld(lp.x, lp.y, lp.z), "TRUNK"));
                minX = Math.min(minX, lp.x);
                minY = Math.min(minY, lp.y);
                minZ = Math.min(minZ, lp.z);
            }
            for (BlockPos lfp : leaves) {
                worldVoxels.add(new RelVoxel(lfp.x, lfp.y, lfp.z, blockAtWorld(lfp.x, lfp.y, lfp.z), "CANOPY"));
                minX = Math.min(minX, lfp.x);
                minY = Math.min(minY, lfp.y);
                minZ = Math.min(minZ, lfp.z);
            }
            List<RelVoxel> relVoxels = new ArrayList<>(worldVoxels.size());
            for (RelVoxel wv : worldVoxels) {
                relVoxels.add(new RelVoxel(wv.x - minX, wv.y - minY, wv.z - minZ, wv.baseBlockId, wv.roleKind));
            }
            String worldExactSig = minX + "," + minY + "," + minZ + "|" + computeCanonicalSignature(relVoxels, true);
            return new ExtractedTreeCluster(
                    relVoxels,
                    worldExactSig,
                    groundedOnSoil,
                    !trunkLogs.isEmpty(),
                    !leaves.isEmpty(),
                    !leaves.isEmpty());
        }

        long countDisconnectedFloatingLeafClusters() {
            Map<String, List<BlockPos>> leavesById = collectBlocksMatching(WeightedParityEvaluator::isCanopyLeafBlock);
            Set<BlockPos> allLeaves = new LinkedHashSet<>();
            for (List<BlockPos> list : leavesById.values()) {
                allLeaves.addAll(list);
            }
            if (allLeaves.isEmpty()) {
                return 0L;
            }
            Set<BlockPos> visited = new HashSet<>();
            long orphanClusters = 0L;
            for (BlockPos seed : allLeaves) {
                if (visited.add(seed) && isLeafClusterOrphaned(seed, allLeaves, visited)) {
                    orphanClusters++;
                }
            }
            return orphanClusters;
        }

        private boolean isLeafClusterOrphaned(
                BlockPos seed, Set<BlockPos> allLeaves, Set<BlockPos> visited) {
            ArrayDeque<BlockPos> q = new ArrayDeque<>();
            q.add(seed);
            boolean touchesLogOrGround = false;
            boolean touchesUnloadedBorder = false;
            while (!q.isEmpty()) {
                BlockPos cur = q.poll();
                for (int[] d : NEIGHBORS_18) {
                    BlockPos nb = new BlockPos(cur.x + d[0], cur.y + d[1], cur.z + d[2]);
                    String bid = blockAtWorld(nb.x, nb.y, nb.z);
                    if (bid == null) {
                        touchesUnloadedBorder = true;
                    } else if (isLogOrStemBlock(bid)
                            || (d[0] == 0 && d[2] == 0 && d[1] == -1 && isGroundSupportBlock(bid))) {
                        touchesLogOrGround = true;
                    } else if (allLeaves.contains(nb) && visited.add(nb)) {
                        q.add(nb);
                    }
                }
            }
            return !touchesLogOrGround && !touchesUnloadedBorder;
        }
    }

    // =========================================================================
    // Symmetry Normalization, Stamp Extraction & Block Taxonomy
    // =========================================================================

    private static List<RelVoxel> extractDecoratorStampVoxels(LcaDecoratorStamp stamp) {
        List<String> pal = stamp.statePalette();
        List<RelVoxel> raw = new ArrayList<>(stamp.totalVoxelCount());
        for (LcaDecoratorStamp.ChunkCanopySlice slice : stamp.chunkSlices()) {
            int baseX = slice.deltaChunkX() << 4;
            int baseZ = slice.deltaChunkZ() << 4;
            for (LcaDecoratorStamp.HilbertRun run : slice.runs()) {
                int pIdx = run.paletteIdx();
                if (pIdx < 0 || pIdx >= pal.size()) {
                    continue;
                }
                String baseId = stripStateProperties(pal.get(pIdx));
                if (isAirLike(baseId)) {
                    continue;
                }
                int baseY = run.relSectionY() << 4;
                String role = classifyTreeVoxelRole(baseId);
                for (int h = run.startH3(), end = run.startH3() + run.runLength(); h < end; h++) {
                    int yzx = LcaDecoratorStamp.h3ToYzx(h);
                    int lx = yzx & 15;
                    int lz = (yzx >>> 4) & 15;
                    int ly = (yzx >>> 8) & 15;
                    raw.add(new RelVoxel(baseX + lx, baseY + ly, baseZ + lz, baseId, role));
                }
            }
        }
        return normalizeToBoundingOrigin(raw);
    }

    private static List<RelVoxel> extractStructureStampVoxels(LcaStructureStamp stamp) {
        List<String> pal = stamp.statePalette();
        List<RelVoxel> raw = new ArrayList<>(stamp.totalVoxelCount());
        for (LcaStructureStamp.ChunkStampSlice slice : stamp.chunkSlices()) {
            int baseX = slice.dChunkX() << 4;
            int baseZ = slice.dChunkZ() << 4;
            for (LcaDecoratorStamp.HilbertRun run : slice.runs()) {
                int pIdx = run.paletteIdx();
                if (pIdx < 0 || pIdx >= pal.size()) {
                    continue;
                }
                String baseId = stripStateProperties(pal.get(pIdx));
                if (isAirLike(baseId)) {
                    continue;
                }
                int baseY = run.relSectionY() << 4;
                String role = classifyTreeVoxelRole(baseId);
                for (int h = run.startH3(), end = run.startH3() + run.runLength(); h < end; h++) {
                    int yzx = LcaDecoratorStamp.h3ToYzx(h);
                    int lx = yzx & 15;
                    int lz = (yzx >>> 4) & 15;
                    int ly = (yzx >>> 8) & 15;
                    raw.add(new RelVoxel(baseX + lx, baseY + ly, baseZ + lz, baseId, role));
                }
            }
        }
        return normalizeToBoundingOrigin(raw);
    }

    private static String classifyTreeVoxelRole(String baseId) {
        if (isLogOrStemBlock(baseId)) {
            return "TRUNK";
        }
        return isCanopyLeafBlock(baseId) ? "CANOPY" : baseId;
    }

    private static void registerEightSymmetries(
            List<RelVoxel> baseVoxels, Set<String> exactOut, Set<String> normOut) {
        List<RelVoxel> normBase = normalizeToBoundingOrigin(baseVoxels);
        if (normBase.isEmpty()) {
            return;
        }
        int maxX = 0;
        int maxZ = 0;
        for (RelVoxel v : normBase) {
            if (v.x > maxX) maxX = v.x;
            if (v.z > maxZ) maxZ = v.z;
        }
        for (int mirror = 0; mirror <= 1; mirror++) {
            for (int rot = 0; rot < 4; rot++) {
                List<RelVoxel> transformed = new ArrayList<>(normBase.size());
                for (RelVoxel v : normBase) {
                    int mx = (mirror == 1) ? (maxX - v.x) : v.x;
                    int mz = v.z;
                    int rx;
                    int rz;
                    switch (rot) {
                        case 1 -> {
                            rx = maxZ - mz;
                            rz = mx;
                        }
                        case 2 -> {
                            rx = maxX - mx;
                            rz = maxZ - mz;
                        }
                        case 3 -> {
                            rx = mz;
                            rz = maxX - mx;
                        }
                        default -> {
                            rx = mx;
                            rz = mz;
                        }
                    }
                    transformed.add(new RelVoxel(rx, v.y, rz, v.baseBlockId, v.roleKind));
                }
                addNormalizedSignature(transformed, exactOut, normOut);
            }
        }
    }

    private static void addNormalizedSignature(
            List<RelVoxel> voxels, Set<String> exactOut, Set<String> normOut) {
        List<RelVoxel> norm = normalizeToBoundingOrigin(voxels);
        if (norm.isEmpty()) {
            return;
        }
        exactOut.add(computeCanonicalSignature(norm, true));
        normOut.add(computeCanonicalSignature(norm, false));
    }

    private static List<RelVoxel> normalizeToBoundingOrigin(List<RelVoxel> voxels) {
        if (voxels.isEmpty()) {
            return List.of();
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (RelVoxel v : voxels) {
            if (v.x < minX) minX = v.x;
            if (v.y < minY) minY = v.y;
            if (v.z < minZ) minZ = v.z;
        }
        List<RelVoxel> shifted = new ArrayList<>(voxels.size());
        for (RelVoxel v : voxels) {
            shifted.add(new RelVoxel(v.x - minX, v.y - minY, v.z - minZ, v.baseBlockId, v.roleKind));
        }
        shifted.sort(Comparator.comparingInt(RelVoxel::y)
                .thenComparingInt(RelVoxel::z)
                .thenComparingInt(RelVoxel::x)
                .thenComparing(RelVoxel::baseBlockId));
        return shifted;
    }

    private static String computeCanonicalSignature(List<RelVoxel> rawVoxels, boolean includeExactBlockId) {
        List<RelVoxel> sorted = normalizeToBoundingOrigin(rawVoxels);
        StringBuilder sb = new StringBuilder(sorted.size() * 20);
        for (RelVoxel v : sorted) {
            sb.append(v.x).append(',').append(v.y).append(',').append(v.z).append(':');
            sb.append(includeExactBlockId ? v.baseBlockId : v.roleKind).append(';');
        }
        return sb.toString();
    }

    private static int countMatchingBlockEntities(
            List<BlockPos> blockPositions,
            Map<BlockPos, Map<String, Object>> blockEntitiesByPos,
            String expectedBlockId) {
        int matched = 0;
        String expectedShort = expectedBlockId.startsWith("minecraft:")
                ? expectedBlockId.substring("minecraft:".length())
                : expectedBlockId;
        for (BlockPos pos : blockPositions) {
            Map<String, Object> be = blockEntitiesByPos.get(pos);
            if (be == null) {
                continue;
            }
            Object idObj = be.get("id");
            if (idObj instanceof String beId) {
                String normBe = stripStateProperties(beId);
                if (normBe.equals(expectedBlockId) || normBe.endsWith(":" + expectedShort)) {
                    matched++;
                }
            } else {
                matched++;
            }
        }
        return matched;
    }

    private static BlockPos findNearestWithinRadius(
            BlockPos target, Set<BlockPos> candidates, int maxHorizDist, int maxVertDist) {
        BlockPos best = null;
        int bestDistSq = Integer.MAX_VALUE;
        for (BlockPos cand : candidates) {
            int dx = Math.abs(cand.x - target.x);
            int dy = Math.abs(cand.y - target.y);
            int dz = Math.abs(cand.z - target.z);
            if (dx <= maxHorizDist && dz <= maxHorizDist && dy <= maxVertDist) {
                int dSq = dx * dx + dy * dy + dz * dz;
                if (dSq < bestDistSq) {
                    bestDistSq = dSq;
                    best = cand;
                }
            }
        }
        return best;
    }

    private static boolean hasNearbyPathColumn(
            Map<Long, Integer> pathByXz, int wx, int wz, int refY, int radiusXz, int maxDeltaY) {
        for (int dz = -radiusXz; dz <= radiusXz; dz++) {
            for (int dx = -radiusXz; dx <= radiusXz; dx++) {
                Integer y = pathByXz.get(packXz(wx + dx, wz + dz));
                if (y != null && Math.abs(y - refY) <= maxDeltaY) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasAdjacentPath(
            Set<BlockPos> refDoorSet,
            Map<Long, Integer> refPathByXz,
            BlockPos candDoor) {
        if (refDoorSet.contains(candDoor)) {
            return hasNearbyPathColumn(refPathByXz, candDoor.x, candDoor.z, candDoor.y - 1, 2, 1);
        }
        BlockPos nearest = findNearestWithinRadius(candDoor, refDoorSet, 2, 2);
        return nearest != null && hasNearbyPathColumn(refPathByXz, nearest.x, nearest.z, nearest.y - 1, 2, 1);
    }

    private static int countClimbablePathEdges(Map<Long, Integer> pathByXz) {
        int edges = 0;
        for (Map.Entry<Long, Integer> e : pathByXz.entrySet()) {
            int x = unpackXzX(e.getKey());
            int z = unpackXzZ(e.getKey());
            int y = e.getValue();
            Integer eastY = pathByXz.get(packXz(x + 1, z));
            if (eastY != null && Math.abs(eastY - y) <= 1) {
                edges++;
            }
            Integer southY = pathByXz.get(packXz(x, z + 1));
            if (southY != null && Math.abs(southY - y) <= 1) {
                edges++;
            }
        }
        return edges;
    }

    private static int countPathSeamShearsAndCliffs(
            Map<Long, Integer> refPathByXz,
            Map<Long, Integer> candPathByXz,
            RegionStitchedView refGrid,
            RegionStitchedView candGrid) {
        int breaks = 0;
        int[][] cardinal = {{1, 0}, {0, 1}};
        for (Map.Entry<Long, Integer> e : candPathByXz.entrySet()) {
            long xz = e.getKey();
            int x = unpackXzX(xz);
            int z = unpackXzZ(xz);
            int y = e.getValue();
            Integer refY = refPathByXz.get(xz);

            // 1. Direct adjacent path cliff jump (|dY| > 1) introduced by candidate (not already present in vanilla ref)
            for (int[] d : cardinal) {
                long adjXz = packXz(x + d[0], z + d[1]);
                Integer adjY = candPathByXz.get(adjXz);
                if (adjY != null && Math.abs(adjY - y) > 1) {
                    Integer refAdjY = refPathByXz.get(adjXz);
                    boolean refAlreadyHadCliff = refY != null && refAdjY != null && Math.abs(refAdjY - refY) > 1;
                    if (!refAlreadyHadCliff) {
                        breaks++;
                    }
                }
            }

            // 2. Buried path tile (non-passable block directly above dirt_path) introduced by candidate
            String above = candGrid.blockAtWorld(x, y + 1, z);
            if (above != null && !isPassableHeadspace(above)) {
                boolean refAlreadyBuried = false;
                if (refY != null && refGrid != null) {
                    String refAbove = refGrid.blockAtWorld(x, refY + 1, z);
                    refAlreadyBuried = refAbove != null && !isPassableHeadspace(refAbove);
                }
                if (!refAlreadyBuried) {
                    breaks++;
                }
            }
        }

        // 3. Cross-chunk / street-segment 1-block shear: reference has a cardinal path transition (x,z)->(x+dx,z+dz),
        // candidate retains (x,z) on the west/north side, drops (x+dx,z+dz), and instead places a laterally
        // sheared path at (x+dx +- perp, z+dz +- perp) without a connecting bridge tile!
        int[][] dirs4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (Map.Entry<Long, Integer> refEntry : refPathByXz.entrySet()) {
            long xz = refEntry.getKey();
            if (!candPathByXz.containsKey(xz)) {
                continue;
            }
            int x = unpackXzX(xz);
            int z = unpackXzZ(xz);
            int candY = candPathByXz.get(xz);
            for (int[] d : dirs4) {
                int nx = x + d[0];
                int nz = z + d[1];
                if (!refPathByXz.containsKey(packXz(nx, nz))) {
                    continue;
                }
                if (candPathByXz.containsKey(packXz(nx, nz))) {
                    continue;
                }
                // Check perpendicular +/- 1 offset shear on the neighbor step
                int px = d[1];
                int pz = d[0];
                for (int sign : new int[] {-1, 1}) {
                    int sx = nx + sign * px;
                    int sz = nz + sign * pz;
                    Integer shearedY = candPathByXz.get(packXz(sx, sz));
                    if (shearedY != null) {
                        boolean bridgedViaCorner = candPathByXz.containsKey(packXz(x + sign * px, z + sign * pz));
                        if (!bridgedViaCorner || Math.abs(shearedY - candY) > 1) {
                            breaks++;
                        }
                    }
                }
            }
        }
        return breaks;
    }

    private static double computeOreConservationMatchPct(
            Map<String, Long> refOres, Map<String, Long> candOres) {
        long refTotal = sumMapValues(refOres);
        long candTotal = sumMapValues(candOres);
        if (refTotal == 0L && candTotal == 0L) {
            return 100.0;
        }
        if (refTotal == 0L || candTotal == 0L) {
            return 0.0;
        }
        long overlap = 0L;
        long union = 0L;
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(refOres.keySet());
        keys.addAll(candOres.keySet());
        for (String k : keys) {
            long r = refOres.getOrDefault(k, 0L);
            long c = candOres.getOrDefault(k, 0L);
            overlap += Math.min(r, c);
            union += Math.max(r, c);
        }
        return union > 0L ? (overlap * 100.0) / union : 100.0;
    }

    private static long sumMapValues(Map<String, Long> map) {
        long sum = 0L;
        if (map != null) {
            for (Long v : map.values()) {
                if (v != null) {
                    sum += v;
                }
            }
        }
        return sum;
    }

    static boolean isCriticalFunctionalBlock(String rawBlockId) {
        String id = stripStateProperties(rawBlockId);
        if (CRITICAL_FUNCTIONAL_EXACT.contains(id)) {
            return true;
        }
        return id.endsWith("_bed") || id.endsWith("_shulker_box");
    }

    private static boolean isDoorBlock(String rawBlockId) {
        String id = stripStateProperties(rawBlockId);
        return id.endsWith("_door") && !id.endsWith("_trapdoor");
    }

    private static boolean isStructuralShellBlock(String rawBlockId) {
        String id = stripStateProperties(rawBlockId);
        if (isCriticalFunctionalBlock(id) || PATH_BLOCK_IDS.contains(id) || isTreeTrunkOrCanopyBlock(id)) {
            return false;
        }
        return id.contains("cobblestone")
                || id.contains("brick")
                || id.endsWith("_planks")
                || id.endsWith("_stairs")
                || id.endsWith("_slab")
                || id.endsWith("_wall")
                || id.endsWith("_fence")
                || id.endsWith("_fence_gate")
                || id.endsWith("_door")
                || id.endsWith("_trapdoor")
                || id.endsWith("_glass")
                || id.endsWith("_glass_pane")
                || id.endsWith("_wool")
                || id.endsWith("_carpet")
                || id.equals("minecraft:torch")
                || id.equals("minecraft:wall_torch")
                || id.equals("minecraft:lantern")
                || id.equals("minecraft:ladder")
                || id.equals("minecraft:hay_block")
                || id.equals("minecraft:bookshelf")
                || id.equals("minecraft:crafting_table");
    }

    private static boolean isLogOrStemBlock(String rawBlockId) {
        String id = stripStateProperties(rawBlockId);
        return id.endsWith("_log")
                || id.endsWith("_wood")
                || id.equals("minecraft:crimson_stem")
                || id.equals("minecraft:warped_stem")
                || id.equals("minecraft:crimson_hyphae")
                || id.equals("minecraft:warped_hyphae")
                || id.equals("minecraft:mushroom_stem")
                || id.equals("minecraft:mangrove_roots");
    }

    private static boolean isCanopyLeafBlock(String rawBlockId) {
        String id = stripStateProperties(rawBlockId);
        return id.endsWith("_leaves")
                || id.equals("minecraft:wart_block")
                || id.equals("minecraft:nether_wart_block")
                || id.equals("minecraft:warped_wart_block")
                || id.equals("minecraft:brown_mushroom_block")
                || id.equals("minecraft:red_mushroom_block")
                || id.equals("minecraft:shroomlight");
    }

    private static boolean isTreeTrunkOrCanopyBlock(String rawBlockId) {
        return isLogOrStemBlock(rawBlockId) || isCanopyLeafBlock(rawBlockId);
    }

    private static boolean isGroundSupportBlock(String rawBlockId) {
        String id = stripStateProperties(rawBlockId);
        return GROUND_SUPPORT_EXACT.contains(id) || id.endsWith("_terracotta");
    }

    private static boolean isPassableHeadspace(String rawBlockId) {
        String id = stripStateProperties(rawBlockId);
        return isAirLike(id)
                || isDoorBlock(id)
                || id.equals("minecraft:torch")
                || id.equals("minecraft:wall_torch")
                || id.equals("minecraft:lantern")
                || id.endsWith("_carpet")
                || id.endsWith("_pressure_plate")
                || id.endsWith("_button")
                || id.endsWith("_sign")
                || id.endsWith("_banner")
                || id.equals("minecraft:short_grass")
                || id.equals("minecraft:tall_grass")
                || id.equals("minecraft:snow");
    }

    private static boolean isAirLike(String baseBlockId) {
        return "minecraft:air".equals(baseBlockId)
                || "minecraft:cave_air".equals(baseBlockId)
                || "minecraft:void_air".equals(baseBlockId)
                || "minecraft:structure_void".equals(baseBlockId)
                || "minecraft:light".equals(baseBlockId);
    }

    private static String stripStateProperties(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "minecraft:air";
        }
        int bracket = raw.indexOf('[');
        String base = (bracket >= 0 ? raw.substring(0, bracket) : raw).trim().toLowerCase(Locale.ROOT);
        if (base.isEmpty()) {
            return "minecraft:air";
        }
        return base.indexOf(':') >= 0 ? base : ("minecraft:" + base);
    }

    private static List<?> extractNbtList(Object raw) {
        if (raw instanceof Nbt.NbtList nbtList) {
            return nbtList.items;
        }
        if (raw instanceof List<?> list) {
            return list;
        }
        return List.of();
    }

    private static long packLocalKey(int localX, int localZ) {
        return (((long) localZ) << 32) | (localX & 0xFFFFFFFFL);
    }

    private static long packXz(int wx, int wz) {
        return (((long) wx) << 32) | (wz & 0xFFFFFFFFL);
    }

    private static int unpackXzX(long packed) {
        return (int) (packed >>> 32);
    }

    private static int unpackXzZ(long packed) {
        return (int) packed;
    }
}
