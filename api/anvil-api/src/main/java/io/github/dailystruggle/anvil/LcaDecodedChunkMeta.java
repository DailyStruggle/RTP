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
        byte[] residualNbtBytes
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

    public boolean hasIsLightOn() {
        return (bpFlags & 8) != 0;
    }

    public boolean hasStarlightVersion() {
        return (bpFlags & 64) != 0;
    }

    public boolean hasResidualNbt() {
        return residualNbtBytes != null && residualNbtBytes.length > 0;
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
                && Arrays.equals(residualNbtBytes, other.residualNbtBytes);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                dataVersion, chunkX, chunkZ, minSectionY, statusName,
                lastUpdateTick, inhabitedTimeTicks, bpFlags, postProcessingCount,
                starlightLightVersion, biomeKeys);
        result = 31 * result + Arrays.hashCode(heightmapCodes);
        result = 31 * result + Arrays.hashCode(customHeightmapNames);
        result = 31 * result + Arrays.deepHashCode(heightmapLongs);
        result = 31 * result + Arrays.hashCode(sections);
        return 31 * result + Arrays.hashCode(residualNbtBytes);
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
                + ", residualNbtBytes=" + Arrays.toString(residualNbtBytes) + "]";
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
