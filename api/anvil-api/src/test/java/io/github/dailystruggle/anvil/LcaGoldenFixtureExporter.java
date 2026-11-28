package io.github.dailystruggle.anvil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Deterministic cross-language golden {@code .lca} (`LeafCraft Anvil`, `LCA-ICD-1.0`) fixture,
 * synthetic container-matrix archive, `CBIT T1..T8` / `SEC-DED` fault-vector generator, and
 * 64-bit FNV-1a parity manifest exporter for verifying non-JVM implementations such as
 * PumpkinMC (`crates/pumpkin-world` + `lca-format`, `leafanvil-ADR-004`).
 *
 * @governedBy leafanvil-ADR-004
 * @governedBy anvil-api-ADR-003
 * @governedBy anvil-api-ADR-002
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class LcaGoldenFixtureExporter {

    /** 64-bit FNV-1a offset basis (`0xcbf29ce484222325L`). */
    public static final long FNV1A_64_OFFSET_BASIS = 0xcbf29ce484222325L;

    /** 64-bit FNV-1a prime multiplier (`0x100000001b3L`). */
    public static final long FNV1A_64_PRIME = 0x100000001b3L;

    private static final String[][] REAL_FIXTURES = {
        {"real_1_20_R1", "/anvil/real/1_20_R1/r.0.0.mca"},
        {"real_1_21_R1", "/anvil/real/1_21_R1/r.0.0.mca"},
        {"real_26_1_R1", "/anvil/real/26_1_R1/r.0.0.mca"},
        {"real_26_3_R1", "/anvil/real/26_3_R1/r.0.0.mca"}
    };

    private LcaGoldenFixtureExporter() {}

    /**
     * Per-section parity digest entry recorded in {@code lca-golden-manifest.json}.
     */
    public record SectionManifestEntry(
            int sectionY,
            boolean inlineUniform,
            long octreeOccupancyMask64,
            long voxelFnv1a64,
            long biomeFnv1a64,
            long skyLightFnv1a64,
            long blockLightFnv1a64) {}

    /**
     * Per-chunk directory and section manifest entry recorded in {@code lca-golden-manifest.json}
     * (`generationToken11` stores the 8-bit `generation8` counter at `ptr64 & 0xFFL` per `LCA-ICD-1.0` Section 4.3).
     */
    public record ChunkManifestEntry(
            int chunkX,
            int chunkZ,
            int dataVersion,
            int generationToken11,
            long rawPointerWord64,
            byte pointerSecDedSyndrome8,
            List<SectionManifestEntry> sections) {}

    /**
     * Deterministic single-byte fault or bit-rot vector targeting a specific `CBIT T1..T8` tripwire
     * or `SEC-DED` self-healing tier.
     */
    public record CbitVectorEntry(
            String vectorId,
            String tripwireRule,
            int mutatedByteOffset,
            byte originalByte,
            byte mutatedByte,
            boolean expectSelfHealed,
            boolean expectRejected) {}

    /**
     * Complete golden `.lca` fixture bundle pairing Mode A (`0-LZ`) and Mode B (`LCD2`) region
     * binaries with their SHA-256 digests, chunk/section FNV-1a64 digests, and `CBIT` fault vectors.
     */
    public record GoldenFixtureBundle(
            String fixtureId,
            int dataVersion,
            byte[] modeALcaBytes,
            String modeASha256,
            byte[] modeBLcaBytes,
            String modeBSha256,
            List<ChunkManifestEntry> chunks,
            List<CbitVectorEntry> cbitVectors) {}

    /**
     * Generates all canonical golden `.lca` bundles (`real_1_20_R1`, `real_1_21_R1`,
     * `real_26_1_R1`, `real_26_3_R1`, and `synthetic_all_modes`).
     */
    public static List<GoldenFixtureBundle> generateAllGoldenBundles() throws IOException {
        List<GoldenFixtureBundle> bundles = new ArrayList<>(REAL_FIXTURES.length + 1);
        for (String[] spec : REAL_FIXTURES) {
            byte[] mcaBytes = loadClasspathResource(spec[1]);
            byte[] modeA = LcaRegionWriter.transcodeFromMca(mcaBytes, 0, 0, false);
            byte[] modeB = LcaRegionWriter.transcodeFromMca(mcaBytes, 0, 0, true);
            bundles.add(buildBundleFromLcaPair(spec[0], modeA, modeB));
        }
        bundles.add(buildSyntheticAllModesBundle());
        return Collections.unmodifiableList(bundles);
    }

    /**
     * Builds the synthetic all-modes golden bundle covering all 4 traversal curves (`CURVE_YZX`,
     * `CURVE_H3`, `CURVE_Y_H2`, `CURVE_XZY`), all 4 Delta-Roaring container tags (`TAG_SINGLETON`
     * `subTag 0..3`, `TAG_DELTA_RUN` `4|4..7|1`, `TAG_DELTA_ARRAY` with `Bounded240`, `TAG_BITMAP`
     * contiguous and word-masked), `blockEncodingMode = 10` flat bit-packed ceiling (`> 96` states),
     * inline uniform (`0x8000 | id`), empty (`0x0000`), all 4 lighting modes (`00..11`), and both
     * legacy (`DataVersion = 3953 < 5006`: `"Name"`/`"Properties"`) and modern (`DataVersion = 5023 >= 5006`:
     * `"id"`/`"properties"`) palette conventions.
     */
    public static GoldenFixtureBundle buildSyntheticAllModesBundle() throws IOException {
        LinkedHashMap<String, Object> legacyChunk00 = buildSyntheticChunkRoot(0, 0, 3953, false);
        LinkedHashMap<String, Object> modernChunk10 = buildSyntheticChunkRoot(1, 0, 5023, true);

        LcaRegionWriter.LcaLiveArchive archiveA =
                LcaRegionWriter.createEmptyLiveArchive(0, 0, -4, 24, 5023, false, 4096);
        archiveA.saveChunkInitial(0, 0, legacyChunk00);
        archiveA.saveChunkInitial(1, 0, modernChunk10);
        byte[] modeA = archiveA.toByteArray();

        LcaRegionWriter.LcaLiveArchive archiveB =
                LcaRegionWriter.createEmptyLiveArchive(0, 0, -4, 24, 5023, true, 4096);
        archiveB.saveChunkInitial(0, 0, legacyChunk00);
        archiveB.saveChunkInitial(1, 0, modernChunk10);
        byte[] modeB = archiveB.toByteArray();

        return buildBundleFromLcaPair("synthetic_all_modes", modeA, modeB);
    }

    private static GoldenFixtureBundle buildBundleFromLcaPair(
            String fixtureId, byte[] modeA, byte[] modeB) throws IOException {
        List<ChunkManifestEntry> chunks = extractChunkManifests(modeA);
        int regionDataVersion = chunks.isEmpty() ? 4671 : chunks.get(0).dataVersion();
        List<CbitVectorEntry> vectors = buildCbitAndSecDedVectors(modeA, modeB, chunks);
        return new GoldenFixtureBundle(
                fixtureId,
                regionDataVersion,
                modeA,
                sha256Hex(modeA),
                modeB,
                sha256Hex(modeB),
                Collections.unmodifiableList(chunks),
                Collections.unmodifiableList(vectors));
    }

    private static List<ChunkManifestEntry> extractChunkManifests(byte[] modeALca) throws IOException {
        List<ChunkManifestEntry> entries = new ArrayList<>();
        ByteBuffer sb = ByteBuffer.wrap(modeALca);
        int minSectionY = sb.getInt(0x14);
        int sectionCount = sb.getShort(0x18) & 0xFFFF;
        long lodAndSecDed = sb.getLong(0x28);
        int secDedOff = (int) (lodAndSecDed & 0xFFFF_FFFFL);

        for (int chunkIdx = 0; chunkIdx < 1024; chunkIdx++) {
            int ptrOff = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (chunkIdx << 3);
            long rawPtr = sb.getLong(ptrOff);
            if (rawPtr == 0L) {
                continue;
            }
            int rx = chunkIdx & 31;
            int rz = (chunkIdx >>> 5) & 31;
            byte syn8 = modeALca[secDedOff + chunkIdx];
            int generationToken = (int) (rawPtr & 0xFFL);

            int frameStart = (int) ((rawPtr >>> 29) << LcaRegionReader.SLAB_SHIFT);
            int chunkFlags = modeALca[frameStart + 21] & 0xFF;
            int unitShift = ((chunkFlags & 4) != 0) ? 2 : 0;
            int sectionDirEnd = 24 + (sectionCount << 1);

            AnvilChunkView view = LcaRegionReader.readChunkView(modeALca, rx, rz);
            AnvilReader.ChunkEntry nbtEntry = LcaRegionReader.INSTANCE.readChunk(modeALca, rx, rz);
            if (view == null || nbtEntry == null || view.sections().isEmpty()) {
                continue;
            }

            Map<Integer, Map<?, ?>> nbtSectionsByY = new LinkedHashMap<>();
            Nbt.NbtList nbtSecs = AnvilReader.getSections(nbtEntry.root);
            if (nbtSecs != null) {
                for (Object item : nbtSecs.items) {
                    if (item instanceof Map<?, ?> secMap && secMap.get("Y") instanceof Number yNum) {
                        nbtSectionsByY.put(yNum.intValue(), secMap);
                    }
                }
            }

            Map<Integer, BiomePaletteSection> biomeSectionsByY = new LinkedHashMap<>();
            for (BiomePaletteSection bSec : view.biomeSections()) {
                biomeSectionsByY.put(bSec.sectionY(), bSec);
            }

            List<SectionManifestEntry> sectionManifests = new ArrayList<>(view.sections().size());
            for (PaletteSection pSec : view.sections()) {
                int sy = pSec.sectionY();
                int secIdx = sy - minSectionY;
                boolean inlineUniform = false;
                long octreeMask64 = 0L;
                if (secIdx >= 0 && secIdx < sectionCount) {
                    int entry16 = sb.getShort(frameStart + 24 + (secIdx << 1)) & 0xFFFF;
                    inlineUniform = (entry16 & 0x8000) != 0;
                    if (entry16 != 0 && !inlineUniform) {
                        int relStart = sectionDirEnd + ((entry16 - 1) << unitShift);
                        int secHdr = modeALca[frameStart + relStart] & 0xFF;
                        int bMode = (secHdr >>> 4) & 3;
                        if (bMode == LcaSectionCodec.BLOCK_MODE_DELTA_ROARING
                                || bMode == LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK) {
                            octreeMask64 = sb.getLong(frameStart + relStart + 1);
                        } else if (bMode == LcaSectionCodec.BLOCK_MODE_DELTA_ROARING_FULL_MASK) {
                            octreeMask64 = -1L;
                        }
                    }
                }

                long voxelHash = computeVoxelFnv1a64(pSec);
                long biomeHash = computeBiomeFnv1a64(biomeSectionsByY.get(sy));
                Map<?, ?> rawSecMap = nbtSectionsByY.get(sy);
                byte[] skyBytes = (rawSecMap != null && rawSecMap.get("SkyLight") instanceof byte[] b) ? b : null;
                byte[] blkBytes = (rawSecMap != null && rawSecMap.get("BlockLight") instanceof byte[] b) ? b : null;
                long skyHash = computeBytesFnv1a64(skyBytes);
                long blkHash = computeBytesFnv1a64(blkBytes);

                sectionManifests.add(new SectionManifestEntry(
                        sy, inlineUniform, octreeMask64, voxelHash, biomeHash, skyHash, blkHash));
            }

            entries.add(new ChunkManifestEntry(
                    rx,
                    rz,
                    view.dataVersion(),
                    generationToken,
                    rawPtr,
                    syn8,
                    Collections.unmodifiableList(sectionManifests)));
        }
        return entries;
    }

    private static List<CbitVectorEntry> buildCbitAndSecDedVectors(
            byte[] modeA, byte[] modeB, List<ChunkManifestEntry> chunks) {
        if (chunks.isEmpty()) {
            return Collections.emptyList();
        }
        ChunkManifestEntry firstChunk = chunks.get(0);
        int chunkIdx = (firstChunk.chunkX() & 31) | ((firstChunk.chunkZ() & 31) << 5);
        int ptrOff = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (chunkIdx << 3);

        ByteBuffer sbA = ByteBuffer.wrap(modeA);
        int sectionCount = sbA.getShort(0x18) & 0xFFFF;
        long ptrA = sbA.getLong(ptrOff);
        int frameStartA = (int) ((ptrA >>> 29) << LcaRegionReader.SLAB_SHIFT);
        int frameLenA = (int) ((ptrA >>> 8) & 0xFFFFFL);
        int chunkFlagsA = modeA[frameStartA + 21] & 0xFF;
        int unitShiftA = ((chunkFlagsA & 4) != 0) ? 2 : 0;
        int sectionDirEnd = 24 + (sectionCount << 1);

        int nonUniformSecIdx = -1;
        int nonUniformRelStart = -1;
        int firstContainerOff = -1;
        int paletteLenOff = -1;
        for (int i = 0; i < sectionCount; i++) {
            int entry16 = sbA.getShort(frameStartA + 24 + (i << 1)) & 0xFFFF;
            if (entry16 != 0 && (entry16 & 0x8000) == 0) {
                int relStart = sectionDirEnd + ((entry16 - 1) << unitShiftA);
                int secAbs = frameStartA + relStart;
                int secHdr = modeA[secAbs] & 0xFF;
                int bMode = (secHdr >>> 4) & 3;
                if (bMode == LcaSectionCodec.BLOCK_MODE_DELTA_ROARING) {
                    int pos = secAbs + 1 + 8;
                    int palLenPos = pos;
                    try {
                        int palLen = LcaSectionCodec.readVarInt(modeA, pos, frameStartA + frameLenA);
                        pos += LcaSectionCodec.varIntLen(palLen);
                        if (palLen >= 2) {
                            for (int p = 0; p < palLen; p++) {
                                int id = LcaSectionCodec.readVarInt(modeA, pos, frameStartA + frameLenA);
                                pos += LcaSectionCodec.varIntLen(id);
                            }
                            nonUniformSecIdx = i;
                            nonUniformRelStart = relStart;
                            paletteLenOff = palLenPos;
                            firstContainerOff = pos;
                            break;
                        }
                    } catch (CorruptRegionEntryException ignored) {
                        // Continue scanning sections
                    }
                }
            }
        }

        ByteBuffer sbB = ByteBuffer.wrap(modeB);
        long ptrB = sbB.getLong(ptrOff);
        int frameStartB = (int) ((ptrB >>> 29) << LcaRegionReader.SLAB_SHIFT);
        int headerWords = (sectionDirEnd + 7) >>> 3;
        int compStartB = frameStartB + sectionDirEnd + headerWords;

        List<CbitVectorEntry> vectors = new ArrayList<>(16);
        // CBIT T1: Superblock magic & Footer canary
        vectors.add(vector("CBIT_T1_MAGIC", "T1", modeA, 0, (byte) (modeA[0] ^ 0xFF), false, true));
        vectors.add(vector(
                "CBIT_T1_FOOTER",
                "T1",
                modeA,
                modeA.length - 1,
                (byte) (modeA[modeA.length - 1] ^ 0xFF),
                false,
                true));
        // CBIT T2: Superblock checksum mismatch
        vectors.add(vector("CBIT_T2_SUPERBLOCK_CRC", "T2", modeA, 0x0C, (byte) (modeA[0x0C] ^ 0x01), false, true));
        // CBIT T3: Out-of-bounds slab offset in pointer directory
        vectors.add(vector("CBIT_T3_SLAB_OOB", "T3", modeA, ptrOff, (byte) 0x7F, false, true));
        // CBIT T3b: Self-describing ChunkFrame chunkIdx mismatch
        vectors.add(vector(
                "CBIT_T3B_CHUNK_IDX_MISMATCH",
                "T3b",
                modeA,
                frameStartA + 1,
                (byte) (modeA[frameStartA + 1] ^ 0x01),
                false,
                true));
        // CBIT T3c: Mode B uncompressed header double-bit flip (DED uncorrectable)
        vectors.add(vector(
                "CBIT_T3C_MODE_B_DOUBLE_BIT",
                "T3c",
                modeB,
                frameStartB,
                (byte) (modeB[frameStartB] ^ 0x03),
                false,
                true));
        // CBIT T4: ChunkFrame byteLength exceeding region bounds / ceiling
        vectors.add(vector(
                "CBIT_T4_FRAME_LEN_OVER_16M",
                "T4",
                modeA,
                ptrOff + 5,
                (byte) 0xFF,
                false,
                true));

        if (nonUniformSecIdx >= 0) {
            int secEntryOff = frameStartA + 24 + (nonUniformSecIdx << 1);
            // CBIT T5: Section offset out of bounds (0x7FFF < 0x8000 non-uniform, past frameLen)
            vectors.add(vector("CBIT_T5_SECTION_OFFSET_OOB", "T5", modeA, secEntryOff, (byte) 0x7F, false, true));
            // CBIT T6: Minority container header mutated to invalid TAG_BITMAP (> 512 bytes)
            vectors.add(vector(
                    "CBIT_T6_RUN_CURSOR_OVERFLOW",
                    "T6",
                    modeA,
                    firstContainerOff,
                    (byte) 0xDF,
                    false,
                    true));
            // CBIT T6b: octreeOccupancyMask64 syndrome mismatch
            int octreeMaskOff = frameStartA + nonUniformRelStart + 1;
            vectors.add(vector(
                    "CBIT_T6B_OCTREE_MASK_MISMATCH",
                    "T6b",
                    modeA,
                    octreeMaskOff,
                    (byte) (modeA[octreeMaskOff] ^ 0xFF),
                    false,
                    true));
            // CBIT T7: Section paletteLen = 0 (out of bounds)
            vectors.add(vector("CBIT_T7_PALETTE_INDEX_OOB", "T7", modeA, paletteLenOff, (byte) 0x00, false, true));
        }

        // CBIT T8: Mode B LCD2 magic byte corrupted
        vectors.add(vector("CBIT_T8_MODE_B_MAGIC", "T8", modeB, compStartB, (byte) 0xFF, false, true));

        // 4-Tier SEC-DED Self-Healing Vectors
        // Tier 1: Single-bit flip in pointerDirectory[chunkIdx]
        vectors.add(vector(
                "SECDED_TIER1_PTR_1BIT",
                "SEC_DED_TIER1",
                modeA,
                ptrOff + 7,
                (byte) (modeA[ptrOff + 7] ^ 0x01),
                true,
                false));
        // Tier 2: Single-bit flip in Layer 2 Chained Dictionary payload word 1
        int dictWord1Off = LcaRegionReader.DICT_ROOT_OFFSET + 8;
        vectors.add(vector(
                "SECDED_TIER2_DICT_1BIT",
                "SEC_DED_TIER2",
                modeA,
                dictWord1Off,
                (byte) (modeA[dictWord1Off] ^ 0x01),
                true,
                false));
        // Tier 3: Single-bit flip in Mode B uncompressed ChunkFrame header word 0
        int modeBHdrByteOff = frameStartB + 4;
        vectors.add(vector(
                "SECDED_TIER3_MODE_B_HDR_1BIT",
                "SEC_DED_TIER3",
                modeB,
                modeBHdrByteOff,
                (byte) (modeB[modeBHdrByteOff] ^ 0x01),
                true,
                false));
        // Tier 4: Double-bit flip in pointerDirectory[chunkIdx] recovered via Slab Pool Reverse-Scan
        vectors.add(vector(
                "SECDED_TIER4_SLAB_SCAN",
                "SEC_DED_TIER4",
                modeA,
                ptrOff,
                (byte) (modeA[ptrOff] ^ 0x03),
                true,
                false));

        return vectors;
    }

    private static CbitVectorEntry vector(
            String id,
            String rule,
            byte[] sourceBytes,
            int offset,
            byte mutated,
            boolean expectHealed,
            boolean expectRejected) {
        byte orig = sourceBytes[offset];
        if (orig == mutated) {
            mutated = (byte) (orig ^ 0x01);
        }
        return new CbitVectorEntry(id, rule, offset, orig, mutated, expectHealed, expectRejected);
    }

    /**
     * Computes the deterministic 64-bit FNV-1a digest over all `4,096` `(x, y, z)` voxels in
     * `Y -> Z -> X` order for a {@link PaletteSection}.
     */
    public static long computeVoxelFnv1a64(PaletteSection section) {
        long h = FNV1A_64_OFFSET_BASIS;
        if (section == null) {
            return h;
        }
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    String id = section.blockIdAt(x, y, z);
                    if (id == null) {
                        id = "minecraft:air";
                    }
                    h = updateFnv1a64Utf8(h, id);
                    h = (h ^ 0L) * FNV1A_64_PRIME;
                }
            }
        }
        return h;
    }

    /**
     * Computes the deterministic 64-bit FNV-1a digest over all `64` `(cellX, cellY, cellZ)` biome
     * cells in `Y -> Z -> X` order for a {@link BiomePaletteSection}.
     */
    public static long computeBiomeFnv1a64(BiomePaletteSection biomeSection) {
        long h = FNV1A_64_OFFSET_BASIS;
        for (int cy = 0; cy < 4; cy++) {
            for (int cz = 0; cz < 4; cz++) {
                for (int cx = 0; cx < 4; cx++) {
                    String biome = (biomeSection == null)
                            ? "minecraft:plains"
                            : biomeSection.biomeIdAt(cx << 2, cy << 2, cz << 2);
                    if (biome == null) {
                        biome = "minecraft:plains";
                    }
                    h = updateFnv1a64Utf8(h, biome);
                    h = (h ^ 0L) * FNV1A_64_PRIME;
                }
            }
        }
        return h;
    }

    /**
     * Computes the deterministic 64-bit FNV-1a digest over a byte array (such as `2,048`-byte
     * `SkyLight` or `BlockLight` nibble arrays).
     */
    public static long computeBytesFnv1a64(byte[] bytes) {
        long h = FNV1A_64_OFFSET_BASIS;
        if (bytes == null) {
            return h;
        }
        for (byte b : bytes) {
            h = (h ^ (b & 0xFFL)) * FNV1A_64_PRIME;
        }
        return h;
    }

    private static long updateFnv1a64Utf8(long h, String s) {
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        for (byte b : utf8) {
            h = (h ^ (b & 0xFFL)) * FNV1A_64_PRIME;
        }
        return h;
    }

    /**
     * Serializes the golden fixture bundles into a deterministic, human- and machine-readable
     * JSON manifest (`lca-golden-manifest.json`).
     */
    public static String toDeterministicJsonManifest(List<GoldenFixtureBundle> bundles) {
        StringBuilder sb = new StringBuilder(16384);
        sb.append("{\n");
        sb.append("  \"schemaVersion\": \"LCA-GOLDEN-1.0\",\n");
        sb.append("  \"formatVersion\": 2,\n");
        sb.append("  \"bundles\": [\n");
        for (int bIdx = 0; bIdx < bundles.size(); bIdx++) {
            GoldenFixtureBundle b = bundles.get(bIdx);
            sb.append("    {\n");
            sb.append("      \"fixtureId\": \"").append(b.fixtureId()).append("\",\n");
            sb.append("      \"dataVersion\": ").append(b.dataVersion()).append(",\n");
            sb.append("      \"modeAByteLength\": ").append(b.modeALcaBytes().length).append(",\n");
            sb.append("      \"modeASha256\": \"").append(b.modeASha256()).append("\",\n");
            sb.append("      \"modeBByteLength\": ").append(b.modeBLcaBytes().length).append(",\n");
            sb.append("      \"modeBSha256\": \"").append(b.modeBSha256()).append("\",\n");
            sb.append("      \"chunks\": [\n");
            for (int cIdx = 0; cIdx < b.chunks().size(); cIdx++) {
                ChunkManifestEntry c = b.chunks().get(cIdx);
                sb.append("        {\n");
                sb.append("          \"chunkX\": ").append(c.chunkX()).append(",\n");
                sb.append("          \"chunkZ\": ").append(c.chunkZ()).append(",\n");
                sb.append("          \"dataVersion\": ").append(c.dataVersion()).append(",\n");
                sb.append("          \"generationToken11\": ").append(c.generationToken11()).append(",\n");
                sb.append("          \"rawPointerWord64\": \"").append(hex64(c.rawPointerWord64())).append("\",\n");
                sb.append("          \"pointerSecDedSyndrome8\": ").append(c.pointerSecDedSyndrome8() & 0xFF).append(",\n");
                sb.append("          \"sections\": [\n");
                for (int sIdx = 0; sIdx < c.sections().size(); sIdx++) {
                    SectionManifestEntry s = c.sections().get(sIdx);
                    sb.append("            {\n");
                    sb.append("              \"sectionY\": ").append(s.sectionY()).append(",\n");
                    sb.append("              \"inlineUniform\": ").append(s.inlineUniform()).append(",\n");
                    sb.append("              \"octreeOccupancyMask64\": \"").append(hex64(s.octreeOccupancyMask64())).append("\",\n");
                    sb.append("              \"voxelFnv1a64\": \"").append(hex64(s.voxelFnv1a64())).append("\",\n");
                    sb.append("              \"biomeFnv1a64\": \"").append(hex64(s.biomeFnv1a64())).append("\",\n");
                    sb.append("              \"skyLightFnv1a64\": \"").append(hex64(s.skyLightFnv1a64())).append("\",\n");
                    sb.append("              \"blockLightFnv1a64\": \"").append(hex64(s.blockLightFnv1a64())).append("\"\n");
                    sb.append("            }").append(sIdx + 1 < c.sections().size() ? "," : "").append("\n");
                }
                sb.append("          ]\n");
                sb.append("        }").append(cIdx + 1 < b.chunks().size() ? "," : "").append("\n");
            }
            sb.append("      ],\n");
            sb.append("      \"cbitVectors\": [\n");
            for (int vIdx = 0; vIdx < b.cbitVectors().size(); vIdx++) {
                CbitVectorEntry v = b.cbitVectors().get(vIdx);
                sb.append("        {\n");
                sb.append("          \"vectorId\": \"").append(v.vectorId()).append("\",\n");
                sb.append("          \"tripwireRule\": \"").append(v.tripwireRule()).append("\",\n");
                sb.append("          \"mutatedByteOffset\": ").append(v.mutatedByteOffset()).append(",\n");
                sb.append("          \"originalByte\": ").append(v.originalByte() & 0xFF).append(",\n");
                sb.append("          \"mutatedByte\": ").append(v.mutatedByte() & 0xFF).append(",\n");
                sb.append("          \"expectSelfHealed\": ").append(v.expectSelfHealed()).append(",\n");
                sb.append("          \"expectRejected\": ").append(v.expectRejected()).append("\n");
                sb.append("        }").append(vIdx + 1 < b.cbitVectors().size() ? "," : "").append("\n");
            }
            sb.append("      ]\n");
            sb.append("    }").append(bIdx + 1 < bundles.size() ? "," : "").append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Exports all golden `.lca` Mode A and Mode B archives plus {@code lca-golden-manifest.json}
     * to {@code outputDir}.
     */
    public static void exportToDirectory(Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        List<GoldenFixtureBundle> bundles = generateAllGoldenBundles();
        for (GoldenFixtureBundle b : bundles) {
            Files.write(outputDir.resolve(b.fixtureId() + ".modeA.lca"), b.modeALcaBytes());
            Files.write(outputDir.resolve(b.fixtureId() + ".modeB.lca"), b.modeBLcaBytes());
        }
        String json = toDeterministicJsonManifest(bundles);
        Files.writeString(outputDir.resolve("lca-golden-manifest.json"), json, StandardCharsets.UTF_8);
    }

    private static LinkedHashMap<String, Object> buildSyntheticChunkRoot(
            int cx, int cz, int dataVersion, boolean modernPaletteKeys) {
        List<Object> sections = new ArrayList<>();

        // 1. Section Y=-4: Inline uniform section (LIGHT_MODE_CONST)
        sections.add(buildUniformSection(-4, "minecraft:bedrock", modernPaletteKeys, 0, 0));

        // 2. Section Y=-3: CURVE_H3 + TAG_SINGLETON (subTags 0, 1, 2, 3) + Word-Masked TAG_BITMAP + LIGHT_MODE_GRAY_PLANES
        sections.add(buildSubModesSection(-3, modernPaletteKeys));

        // 3. Section Y=-2: CURVE_XZY vertical columns + 6|2 TAG_DELTA_RUN + multi-biome cells + LIGHT_MODE_RAW
        sections.add(buildVerticalAndRawLightSection(-2, modernPaletteKeys));

        // 4. Section Y=-1: CURVE_YZX + TAG_DELTA_ARRAY (Bounded240) + Contiguous TAG_BITMAP + LIGHT_MODE_ABSENT
        sections.add(buildSparseAndContiguousBitmapSection(-1, modernPaletteKeys));

        // 5. Section Y=0: blockEncodingMode = 10 Flat Bit-Packed ceiling (> 96 distinct states, leafrtp-ADR-072)
        sections.add(buildFlatCeilingSection(0, modernPaletteKeys));

        // 6. Section Y=1: CURVE_Y_H2 horizontal slabs + stateful block properties (stairs, chest, repeater)
        sections.add(buildHorizontalSlabStatefulSection(1, modernPaletteKeys));

        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", dataVersion);
        root.put("xPos", cx);
        root.put("zPos", cz);
        root.put("Status", "minecraft:full");

        long[] mbnl = new long[37];
        mbnl[0] = 64L | (65L << 9) | (66L << 18);
        LinkedHashMap<String, Object> heightmaps = new LinkedHashMap<>();
        heightmaps.put("MOTION_BLOCKING_NO_LEAVES", mbnl);
        root.put("Heightmaps", heightmaps);

        LinkedHashMap<String, Object> chestBe = new LinkedHashMap<>();
        chestBe.put("id", "minecraft:chest");
        chestBe.put("x", (cx << 4) + 2);
        chestBe.put("y", 17);
        chestBe.put("z", (cz << 4) + 3);
        root.put("block_entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(chestBe)));
        root.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, sections));
        return root;
    }

    private static LinkedHashMap<String, Object> buildPaletteEntry(
            String canonicalState, boolean modernPaletteKeys) {
        LinkedHashMap<String, Object> legacy =
                LcaRegionReader.parseCanonicalStateToNbtCompound(canonicalState);
        if (!modernPaletteKeys) {
            return legacy;
        }
        LinkedHashMap<String, Object> modern = new LinkedHashMap<>();
        modern.put("id", legacy.get("Name"));
        if (legacy.containsKey("Properties")) {
            modern.put("properties", legacy.get("Properties"));
        }
        return modern;
    }

    private static LinkedHashMap<String, Object> buildUniformSection(
            int sy, String state, boolean modernKeys, int skyConst, int blkConst) {
        LinkedHashMap<String, Object> sec = new LinkedHashMap<>();
        sec.put("Y", (byte) sy);
        LinkedHashMap<String, Object> bs = new LinkedHashMap<>();
        bs.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(buildPaletteEntry(state, modernKeys))));
        sec.put("block_states", bs);
        if (skyConst > 0) {
            byte[] sky = new byte[2048];
            Arrays.fill(sky, (byte) ((skyConst << 4) | skyConst));
            sec.put("SkyLight", sky);
        }
        if (blkConst > 0) {
            byte[] blk = new byte[2048];
            Arrays.fill(blk, (byte) ((blkConst << 4) | blkConst));
            sec.put("BlockLight", blk);
        }
        return sec;
    }

    private static LinkedHashMap<String, Object> buildSubModesSection(int sy, boolean modernKeys) {
        LinkedHashMap<String, Object> sec = new LinkedHashMap<>();
        sec.put("Y", (byte) sy);
        LinkedHashMap<String, Object> bs = new LinkedHashMap<>();
        bs.put(
                "palette",
                new Nbt.NbtList(
                        Nbt.TAG_COMPOUND,
                        List.of(
                                buildPaletteEntry("minecraft:stone", modernKeys),
                                buildPaletteEntry("minecraft:diamond_ore", modernKeys),
                                buildPaletteEntry("minecraft:copper_ore", modernKeys),
                                buildPaletteEntry("minecraft:lapis_ore", modernKeys),
                                buildPaletteEntry("minecraft:redstone_ore", modernKeys),
                                buildPaletteEntry("minecraft:coal_ore", modernKeys))));
        int[] yzx = new int[4096];
        int[] h3ToYzx = LcaSectionCodec.CURVE_TO_YZX[LcaSectionCodec.CURVE_H3];
        // State 1: single voxel -> TAG_SINGLETON subTag 0
        yzx[h3ToYzx[5]] = 1;
        // State 2: single run of 300 voxels -> TAG_SINGLETON subTag 2 (2B length)
        for (int k = 16; k < 316; k++) {
            yzx[h3ToYzx[k]] = 2;
        }
        // State 3: single run of 12 voxels -> TAG_SINGLETON subTag 1 (1B length)
        for (int k = 330; k < 342; k++) {
            yzx[h3ToYzx[k]] = 3;
        }
        // State 4: two runs separated by > 240 gap -> TAG_SINGLETON subTag 3
        for (int k = 360; k < 365; k++) {
            yzx[h3ToYzx[k]] = 4;
        }
        for (int k = 650; k < 656; k++) {
            yzx[h3ToYzx[k]] = 4;
        }
        // State 5: dense alternating within 2 octants -> Word-Masked TAG_BITMAP
        for (int k = 704; k < 832; k += 2) {
            yzx[h3ToYzx[k]] = 5;
        }
        bs.put("data", LcaSectionCodec.packBlockLongs(yzx, 6));
        sec.put("block_states", bs);

        byte[] graySky = new byte[2048];
        for (int i = 0; i < 512; i++) {
            graySky[i] = 0x33;
        }
        for (int i = 512; i < 1536; i++) {
            graySky[i] = 0x77;
        }
        for (int i = 1536; i < 2048; i++) {
            graySky[i] = (byte) 0xFF;
        }
        sec.put("SkyLight", graySky);
        return sec;
    }

    private static LinkedHashMap<String, Object> buildVerticalAndRawLightSection(int sy, boolean modernKeys) {
        LinkedHashMap<String, Object> sec = new LinkedHashMap<>();
        sec.put("Y", (byte) sy);
        LinkedHashMap<String, Object> bs = new LinkedHashMap<>();
        bs.put(
                "palette",
                new Nbt.NbtList(
                        Nbt.TAG_COMPOUND,
                        List.of(
                                buildPaletteEntry("minecraft:air", modernKeys),
                                buildPaletteEntry("minecraft:kelp[age=12]", modernKeys),
                                buildPaletteEntry("minecraft:magma_block", modernKeys))));
        int[] yzx = new int[4096];
        for (int y = 0; y < 16; y++) {
            yzx[(y << 8) | (2 << 4) | 2] = 1;
            yzx[(y << 8) | (10 << 4) | 10] = 1;
        }
        int[] xzyToYzx = LcaSectionCodec.CURVE_TO_YZX[LcaSectionCodec.CURVE_XZY];
        yzx[xzyToYzx[20]] = 2;
        yzx[xzyToYzx[21]] = 2;
        yzx[xzyToYzx[70]] = 2;
        yzx[xzyToYzx[71]] = 2;
        yzx[xzyToYzx[400]] = 2;
        yzx[xzyToYzx[401]] = 2;
        bs.put("data", LcaSectionCodec.packBlockLongs(yzx, 3));
        sec.put("block_states", bs);

        LinkedHashMap<String, Object> biomes = new LinkedHashMap<>();
        biomes.put(
                "palette",
                new Nbt.NbtList(
                        Nbt.TAG_STRING,
                        List.of("minecraft:plains", "minecraft:forest", "minecraft:desert", "minecraft:taiga")));
        biomes.put("data", new long[] {0x0302010003020100L, 0x0102030001020300L});
        sec.put("biomes", biomes);

        byte[] rawSky = new byte[2048];
        byte[] rawBlk = new byte[2048];
        Random rnd = new Random(0x4C434131L);
        rnd.nextBytes(rawSky);
        rnd.nextBytes(rawBlk);
        sec.put("SkyLight", rawSky);
        sec.put("BlockLight", rawBlk);
        return sec;
    }

    private static LinkedHashMap<String, Object> buildSparseAndContiguousBitmapSection(
            int sy, boolean modernKeys) {
        LinkedHashMap<String, Object> sec = new LinkedHashMap<>();
        sec.put("Y", (byte) sy);
        LinkedHashMap<String, Object> bs = new LinkedHashMap<>();
        bs.put(
                "palette",
                new Nbt.NbtList(
                        Nbt.TAG_COMPOUND,
                        List.of(
                                buildPaletteEntry("minecraft:deepslate", modernKeys),
                                buildPaletteEntry("minecraft:emerald_ore", modernKeys),
                                buildPaletteEntry("minecraft:gold_ore", modernKeys))));
        int[] yzx = new int[4096];
        // State 1: isolated singletons with large gaps -> TAG_DELTA_ARRAY with Bounded240 escape
        yzx[0] = 1;
        yzx[900] = 1;
        yzx[2100] = 1;
        yzx[3800] = 1;
        // State 2: 320 isolated singletons spread across all octants -> Contiguous TAG_BITMAP
        for (int r = 0; r < 320; r++) {
            int pos = 8 + r * 12;
            if (pos < 4096 && yzx[pos] == 0) {
                yzx[pos] = 2;
            }
        }
        bs.put("data", LcaSectionCodec.packBlockLongs(yzx, 3));
        sec.put("block_states", bs);
        return sec;
    }

    private static LinkedHashMap<String, Object> buildFlatCeilingSection(int sy, boolean modernKeys) {
        LinkedHashMap<String, Object> sec = new LinkedHashMap<>();
        sec.put("Y", (byte) sy);
        List<Object> palette = new ArrayList<>(104);
        palette.add(buildPaletteEntry("minecraft:stone", modernKeys));
        for (int i = 1; i < 104; i++) {
            palette.add(buildPaletteEntry("minecraft:note_block[note=" + (i % 25) + ",powered=" + ((i & 1) == 0) + "]", modernKeys));
        }
        int[] yzx = new int[4096];
        for (int i = 0; i < 4096; i++) {
            yzx[i] = i % 104;
        }
        LinkedHashMap<String, Object> bs = new LinkedHashMap<>();
        bs.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, palette));
        bs.put("data", LcaSectionCodec.packBlockLongs(yzx, 104));
        sec.put("block_states", bs);
        return sec;
    }

    private static LinkedHashMap<String, Object> buildHorizontalSlabStatefulSection(
            int sy, boolean modernKeys) {
        LinkedHashMap<String, Object> sec = new LinkedHashMap<>();
        sec.put("Y", (byte) sy);
        LinkedHashMap<String, Object> bs = new LinkedHashMap<>();
        bs.put(
                "palette",
                new Nbt.NbtList(
                        Nbt.TAG_COMPOUND,
                        List.of(
                                buildPaletteEntry("minecraft:air", modernKeys),
                                buildPaletteEntry(
                                        "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]",
                                        modernKeys),
                                buildPaletteEntry(
                                        "minecraft:chest[facing=south,type=single,waterlogged=false]",
                                        modernKeys),
                                buildPaletteEntry(
                                        "minecraft:repeater[delay=4,facing=west,locked=true,powered=true]",
                                        modernKeys))));
        int[] yzx = new int[4096];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                yzx[(z << 4) | x] = 1;
            }
        }
        yzx[(1 << 8) | (3 << 4) | 2] = 2;
        yzx[(1 << 8) | (5 << 4) | 5] = 3;
        bs.put("data", LcaSectionCodec.packBlockLongs(yzx, 4));
        sec.put("block_states", bs);
        return sec;
    }

    private static byte[] loadClasspathResource(String resourcePath) throws IOException {
        try (InputStream in = LcaGoldenFixtureExporter.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Missing golden fixture resource: " + resourcePath);
            }
            return in.readAllBytes();
        }
    }

    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex64(long val) {
        return String.format("0x%016x", val);
    }
}
