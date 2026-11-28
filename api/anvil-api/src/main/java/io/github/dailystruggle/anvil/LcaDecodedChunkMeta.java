package io.github.dailystruggle.anvil;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Direct zero-NBT decoded chunk metadata carrier produced by {@link LcaSectionCodec#decodeChunkMetadataDirect}
 * for native {@code .lca} loaders ({@code anvil-api-ADR-003}, {@code anvil-api-ADR-005}).
 *
 * <p>Eliminates synthetic NBT serialization and {@code FastNBTParser} re-parsing for chunk headers,
 * heightmaps, biome palettes, and SkyLight/BlockLight arrays. Only non-empty residual NBT (such as
 * {@code block_entities}, {@code block_ticks}, {@code fluid_ticks}, {@code structures}, or PDC) is
 * carried in {@link #residualNbtBytes}.</p>
 *
 * <p><strong>Ownership contract:</strong> All arrays stored in this record and its nested
 * {@link DecodedSectionMeta} records are freshly decoded and owned by the single consumer of this
 * carrier; callers must not mutate shared biome-template arrays or light arrays in place.</p>
 *
 * @governedBy anvil-api-ADR-003
 * @governedBy anvil-api-ADR-005
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public record LcaDecodedChunkMeta(
        int dataVersion,
        int chunkX,
        int chunkZ,
        int minSectionY,
        String statusName,
        long lastUpdateTick,
        long inhabitedTimeTicks,
        int bpFlags,
        int postProcessingCount,
        int starlightLightVersion,
        List<String> biomeKeys,
        int[] heightmapCodes,
        String[] customHeightmapNames,
        long[][] heightmapLongs,
        DecodedSectionMeta[] sections,
        byte[] residualNbtBytes,
        ColumnarTicks blockTicks,
        ColumnarTicks fluidTicks,
        short[][] sparsePostProcessing,
        byte[] dictStructuresPayload,
        byte[] columnarBlockEntitiesPayload
) {
    /** Light channel absent (no array, no Starlight state unless {@code starlightState >= 0}). */
    public static final byte LIGHT_ABSENT = 0;
    /** Starlight uninitialized all-zero light ({@code state = 1}, no {@code byte[2048]} allocated). */
    public static final byte LIGHT_UNINIT_ZERO = 1;
    /** Uniform full-15 ({@code 0xFF}) light (consumer may bind shared immutable {@code 0xFF} array). */
    public static final byte LIGHT_FULL_15 = 2;
    /** Explicit 2,048-byte packed light array in {@code skyLightBytes} / {@code blockLightBytes}. */
    public static final byte LIGHT_ARRAY = 3;

    public LcaDecodedChunkMeta {
        Objects.requireNonNull(biomeKeys, "biomeKeys");
        Objects.requireNonNull(heightmapCodes, "heightmapCodes");
        Objects.requireNonNull(customHeightmapNames, "customHeightmapNames");
        Objects.requireNonNull(heightmapLongs, "heightmapLongs");
        Objects.requireNonNull(sections, "sections");
    }

    public LcaDecodedChunkMeta(
            int dataVersion,
            int chunkX,
            int chunkZ,
            int minSectionY,
            String statusName,
            long lastUpdateTick,
            long inhabitedTimeTicks,
            int bpFlags,
            int postProcessingCount,
            int starlightLightVersion,
            List<String> biomeKeys,
            int[] heightmapCodes,
            String[] customHeightmapNames,
            long[][] heightmapLongs,
            DecodedSectionMeta[] sections,
            byte[] residualNbtBytes
    ) {
        this(
                dataVersion, chunkX, chunkZ, minSectionY, statusName,
                lastUpdateTick, inhabitedTimeTicks, bpFlags, postProcessingCount,
                starlightLightVersion, biomeKeys, heightmapCodes, customHeightmapNames,
                heightmapLongs, sections, residualNbtBytes, null, null, null, null, null
        );
    }

    public LcaDecodedChunkMeta(
            int dataVersion,
            int chunkX,
            int chunkZ,
            int minSectionY,
            String statusName,
            long lastUpdateTick,
            long inhabitedTimeTicks,
            int bpFlags,
            int postProcessingCount,
            int starlightLightVersion,
            List<String> biomeKeys,
            int[] heightmapCodes,
            String[] customHeightmapNames,
            long[][] heightmapLongs,
            DecodedSectionMeta[] sections,
            byte[] residualNbtBytes,
            ColumnarTicks blockTicks,
            ColumnarTicks fluidTicks,
            short[][] sparsePostProcessing
    ) {
        this(
                dataVersion, chunkX, chunkZ, minSectionY, statusName,
                lastUpdateTick, inhabitedTimeTicks, bpFlags, postProcessingCount,
                starlightLightVersion, biomeKeys, heightmapCodes, customHeightmapNames,
                heightmapLongs, sections, residualNbtBytes, blockTicks, fluidTicks,
                sparsePostProcessing, null, null
        );
    }

    public boolean hasIsLightOn() {
        return (bpFlags & 8) != 0;
    }

    public boolean hasStarlightVersion() {
        return (bpFlags & 64) != 0;
    }

    public boolean hasResidualNbt() {
        return residualNbtBytes != null && residualNbtBytes.length > 0;
    }

    public boolean hasDictStructures() {
        return dictStructuresPayload != null && dictStructuresPayload.length > 0;
    }

    public boolean hasColumnarBlockEntities() {
        return columnarBlockEntitiesPayload != null && columnarBlockEntitiesPayload.length > 0;
    }

    /**
     * Decodes {@link #dictStructuresPayload} directly into a caller-supplied compound object via
     * {@link DirectNbtBuilder} with zero intermediate NBT byte synthesis ({@code anvil-api-ADR-007}).
     */
    public <C, L> C decodeDictStructures(DirectNbtBuilder<C, L> builder) throws CorruptRegionEntryException {
        if (dictStructuresPayload == null || dictStructuresPayload.length == 0) {
            return builder.newCompound();
        }
        return LcaV3Codec.decodeDictStructuresDirect(
                dictStructuresPayload, 0, dictStructuresPayload.length, chunkX, chunkZ, builder);
    }

    /**
     * Decodes {@link #columnarBlockEntitiesPayload} directly into a caller-supplied list of
     * compound objects via {@link DirectNbtBuilder} with zero intermediate NBT byte synthesis
     * ({@code anvil-api-ADR-007}).
     */
    public <C, L> L decodeColumnarBlockEntities(DirectNbtBuilder<C, L> builder) throws CorruptRegionEntryException {
        if (columnarBlockEntitiesPayload == null || columnarBlockEntitiesPayload.length == 0) {
            return builder.newList(Nbt.TAG_COMPOUND, 0);
        }
        return LcaV3Codec.decodeColumnarBlockEntitiesDirect(
                columnarBlockEntitiesPayload, 0, columnarBlockEntitiesPayload.length, chunkX, chunkZ, builder);
    }

    /**
     * JDK-only zero-NBT builder SPI allowing downstream engines (such as GoldLeaf) to materialize
     * v3 {@code EXT_FLAG_DICT_STRUCTURES} and {@code EXT_FLAG_COLUMNAR_BLOCK_ENTITIES} directly
     * into their native tag objects without intermediate NBT byte synthesis or
     * {@code FastNBTParser} ({@code anvil-api-ADR-007}, {@code ADR-115}).
     *
     * @param <C> compound tag type
     * @param <L> list tag type
     */
    public interface DirectNbtBuilder<C, L> {
        C newCompound();

        L newList(byte elementType, int expectedSize);

        void putByte(C compound, String key, byte value);

        void putShort(C compound, String key, short value);

        void putInt(C compound, String key, int value);

        void putLong(C compound, String key, long value);

        void putFloat(C compound, String key, float value);

        void putDouble(C compound, String key, double value);

        void putByteArray(C compound, String key, byte[] value);

        void putString(C compound, String key, String value);

        void putIntArray(C compound, String key, int[] value);

        void putLongArray(C compound, String key, long[] value);

        void putCompound(C compound, String key, C value);

        void putList(C compound, String key, L value);

        void addByte(L list, byte value);

        void addShort(L list, short value);

        void addInt(L list, int value);

        void addLong(L list, long value);

        void addFloat(L list, float value);

        void addDouble(L list, double value);

        void addByteArray(L list, byte[] value);

        void addString(L list, String value);

        void addIntArray(L list, int[] value);

        void addLongArray(L list, long[] value);

        void addCompound(L list, C value);

        void addList(L list, L value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LcaDecodedChunkMeta other)) return false;
        return dataVersion == other.dataVersion
                && chunkX == other.chunkX
                && chunkZ == other.chunkZ
                && minSectionY == other.minSectionY
                && Objects.equals(statusName, other.statusName)
                && lastUpdateTick == other.lastUpdateTick
                && inhabitedTimeTicks == other.inhabitedTimeTicks
                && bpFlags == other.bpFlags
                && postProcessingCount == other.postProcessingCount
                && starlightLightVersion == other.starlightLightVersion
                && Objects.equals(biomeKeys, other.biomeKeys)
                && Arrays.equals(heightmapCodes, other.heightmapCodes)
                && Arrays.equals(customHeightmapNames, other.customHeightmapNames)
                && Arrays.deepEquals(heightmapLongs, other.heightmapLongs)
                && Arrays.equals(sections, other.sections)
                && Arrays.equals(residualNbtBytes, other.residualNbtBytes)
                && Objects.equals(blockTicks, other.blockTicks)
                && Objects.equals(fluidTicks, other.fluidTicks)
                && Arrays.deepEquals(sparsePostProcessing, other.sparsePostProcessing)
                && Arrays.equals(dictStructuresPayload, other.dictStructuresPayload)
                && Arrays.equals(columnarBlockEntitiesPayload, other.columnarBlockEntitiesPayload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                dataVersion, chunkX, chunkZ, minSectionY, statusName,
                lastUpdateTick, inhabitedTimeTicks, bpFlags, postProcessingCount,
                starlightLightVersion, biomeKeys, blockTicks, fluidTicks);
        result = 31 * result + Arrays.hashCode(heightmapCodes);
        result = 31 * result + Arrays.hashCode(customHeightmapNames);
        result = 31 * result + Arrays.deepHashCode(heightmapLongs);
        result = 31 * result + Arrays.hashCode(sections);
        result = 31 * result + Arrays.hashCode(residualNbtBytes);
        result = 31 * result + Arrays.deepHashCode(sparsePostProcessing);
        result = 31 * result + Arrays.hashCode(dictStructuresPayload);
        return 31 * result + Arrays.hashCode(columnarBlockEntitiesPayload);
    }

    @Override
    public String toString() {
        return "LcaDecodedChunkMeta[dataVersion=" + dataVersion
                + ", chunkX=" + chunkX
                + ", chunkZ=" + chunkZ
                + ", minSectionY=" + minSectionY
                + ", statusName=" + statusName
                + ", lastUpdateTick=" + lastUpdateTick
                + ", inhabitedTimeTicks=" + inhabitedTimeTicks
                + ", bpFlags=" + bpFlags
                + ", postProcessingCount=" + postProcessingCount
                + ", starlightLightVersion=" + starlightLightVersion
                + ", biomeKeys=" + biomeKeys
                + ", heightmapCodes=" + Arrays.toString(heightmapCodes)
                + ", customHeightmapNames=" + Arrays.toString(customHeightmapNames)
                + ", heightmapLongs=" + Arrays.deepToString(heightmapLongs)
                + ", sections=" + Arrays.toString(sections)
                + ", residualNbtBytes=" + Arrays.toString(residualNbtBytes)
                + ", blockTicks=" + blockTicks
                + ", fluidTicks=" + fluidTicks
                + ", sparsePostProcessing=" + Arrays.deepToString(sparsePostProcessing)
                + ", dictStructuresPayload=" + (dictStructuresPayload != null ? dictStructuresPayload.length + "B" : "null")
                + ", columnarBlockEntitiesPayload=" + (columnarBlockEntitiesPayload != null ? columnarBlockEntitiesPayload.length + "B" : "null")
                + "]";
    }

    /**
     * Native columnar scheduled block/fluid ticks bound directly from `.lca` v3 columnar storage
     * without synthetic NBT serialization or DFU codec parsing ({@code anvil-api-ADR-007}).
     */
    public record ColumnarTicks(
            String[] uniqueIds,
            int[] idOrds,
            int[] xs,
            int[] ys,
            int[] zs,
            int[] ts,
            int[] ps
    ) {
        public int size() {
            return xs.length;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ColumnarTicks other)) return false;
            return Arrays.equals(uniqueIds, other.uniqueIds)
                    && Arrays.equals(idOrds, other.idOrds)
                    && Arrays.equals(xs, other.xs)
                    && Arrays.equals(ys, other.ys)
                    && Arrays.equals(zs, other.zs)
                    && Arrays.equals(ts, other.ts)
                    && Arrays.equals(ps, other.ps);
        }

        @Override
        public int hashCode() {
            int result = Arrays.hashCode(uniqueIds);
            result = 31 * result + Arrays.hashCode(idOrds);
            result = 31 * result + Arrays.hashCode(xs);
            result = 31 * result + Arrays.hashCode(ys);
            result = 31 * result + Arrays.hashCode(zs);
            result = 31 * result + Arrays.hashCode(ts);
            return 31 * result + Arrays.hashCode(ps);
        }

        @Override
        public String toString() {
            return "ColumnarTicks[count=" + xs.length + ", uniqueIds=" + Arrays.toString(uniqueIds) + "]";
        }
    }

    /**
     * Direct decoded metadata for a single chunk section (including low/high Starlight boundary
     * sections where {@code biomeGlobalDictIds} is {@code null}).
     */
    public record DecodedSectionMeta(
            int sectionY,
            int[] biomeGlobalDictIds,
            long[] biomePackedLongs,
            byte skyLightKind,
            byte[] skyLightBytes,
            int skyLightStarlightState,
            byte blockLightKind,
            byte[] blockLightBytes,
            int blockLightStarlightState
    ) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DecodedSectionMeta other)) return false;
            return sectionY == other.sectionY
                    && Arrays.equals(biomeGlobalDictIds, other.biomeGlobalDictIds)
                    && Arrays.equals(biomePackedLongs, other.biomePackedLongs)
                    && skyLightKind == other.skyLightKind
                    && Arrays.equals(skyLightBytes, other.skyLightBytes)
                    && skyLightStarlightState == other.skyLightStarlightState
                    && blockLightKind == other.blockLightKind
                    && Arrays.equals(blockLightBytes, other.blockLightBytes)
                    && blockLightStarlightState == other.blockLightStarlightState;
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(
                    sectionY, skyLightKind, skyLightStarlightState, blockLightKind, blockLightStarlightState);
            result = 31 * result + Arrays.hashCode(biomeGlobalDictIds);
            result = 31 * result + Arrays.hashCode(biomePackedLongs);
            result = 31 * result + Arrays.hashCode(skyLightBytes);
            return 31 * result + Arrays.hashCode(blockLightBytes);
        }

        @Override
        public String toString() {
            return "DecodedSectionMeta[sectionY=" + sectionY
                    + ", biomeGlobalDictIds=" + Arrays.toString(biomeGlobalDictIds)
                    + ", biomePackedLongs=" + Arrays.toString(biomePackedLongs)
                    + ", skyLightKind=" + skyLightKind
                    + ", skyLightBytes=" + Arrays.toString(skyLightBytes)
                    + ", skyLightStarlightState=" + skyLightStarlightState
                    + ", blockLightKind=" + blockLightKind
                    + ", blockLightBytes=" + Arrays.toString(blockLightBytes)
                    + ", blockLightStarlightState=" + blockLightStarlightState + "]";
        }
    }
}
