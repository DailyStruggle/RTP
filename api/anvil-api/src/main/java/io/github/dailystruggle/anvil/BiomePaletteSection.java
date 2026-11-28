package io.github.dailystruggle.anvil;

import java.util.List;
import java.util.Objects;

/**
 * Immutable view of a section's biome palette + packed-index array (ADR-016
 * section 4). Supports both legacy 4×4×4 cell resolution (64 cells/section,
 * MC 1.18–26.3) and per-block 16×16×16 resolution (4,096 cells/section, MC
 * 26.4+, {@code Strategy.createForBiomes} with {@code bitsPerAxis = 4}).
 * Bits-per-entry is {@code max(1, ceil(log2(size)))} (1-bit floor; blocks clamp
 * to 4). Layout is YZX-major: {@code (cellY<<4)|(cellZ<<2)|cellX} for 4×4×4 and
 * {@code (ly<<8)|(lz<<4)|lx} for 16×16×16. Identifiers are raw on-disk strings;
 * caller normalises.
 */
public record BiomePaletteSection(int sectionY, List<String> palette, long[] data) {

    /** Cells per section axis for MC 1.18–26.3 4×4×4 biome sections. */
    public static final int CELLS_PER_AXIS_4X4X4 = 4;

    /** Cells per section axis for MC 26.4+ 16×16×16 per-block biome sections. */
    public static final int CELLS_PER_AXIS_16X16X16 = 16;

    /** Total biome cells in a 4×4×4 biome section. */
    public static final int CELL_COUNT_4X4X4 = 64;

    /** Total biome cells in a 16×16×16 per-block biome section. */
    public static final int CELL_COUNT_16X16X16 = 4096;

    public BiomePaletteSection {
        Objects.requireNonNull(palette, "palette");
        if (palette.isEmpty()) {
            throw new IllegalArgumentException("biome palette must have at least one entry");
        }
        // Malformed tolerance mirrors PaletteSection: a multi-entry palette with no
        // data array surfaces as null (UNKNOWN) rather than crashing (ADR-016).
        // Single-entry palettes canonically omit the data array and are resolution-invariant.
    }

    /**
     * Returns the bits-per-entry for a biome palette of the given size. Differs from
     * {@link PackedPaletteDecoder#bitsPerEntry(int)} by honouring a 1-bit minimum
     * (blocks clamp to 4). Vanilla uses {@code max(1, ceil(log2(paletteSize)))}.
     */
    public static int biomeBitsPerEntry(int paletteSize) {
        if (paletteSize < 1) {
            throw new IllegalArgumentException("paletteSize must be >= 1, got " + paletteSize);
        }
        if (paletteSize == 1) return 1;
        // ceil(log2(n)) for n >= 2, clamped to a 1-bit floor.
        return Math.max(1, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
    }

    /**
     * Returns the required packed {@code long[]} length for a biome palette of {@code paletteSize}
     * entries over {@code cellCount} cells ({@code 64} or {@code 4096}).
     */
    public static int requiredLongs(int paletteSize, int cellCount) {
        if (paletteSize <= 1) return 0;
        int bits = biomeBitsPerEntry(paletteSize);
        int entriesPerLong = 64 / bits;
        return (cellCount + entriesPerLong - 1) / entriesPerLong;
    }

    /**
     * Returns {@code true} if {@code data} encodes a 16×16×16 per-block biome grid (4,096 cells)
     * rather than a 4×4×4 grid (64 cells). For any {@code paletteSize >= 2}, the required long
     * counts are strictly disjoint ({@code 1..7} for 64 cells vs {@code 64..820} for 4,096 cells).
     */
    public static boolean isPerBlockGrid(int paletteSize, long[] data) {
        if (paletteSize <= 1 || data == null || data.length == 0) {
            return false;
        }
        return data.length >= requiredLongs(paletteSize, CELL_COUNT_16X16X16);
    }

    /**
     * Returns the resolved biome grid resolution per axis: {@code 16} for MC 26.4+ per-block
     * biome data, or {@code 4} for legacy 4×4×4 (and single-entry resolution-invariant) sections.
     */
    public int cellsPerAxis() {
        return isPerBlockGrid(palette.size(), data) ? CELLS_PER_AXIS_16X16X16 : CELLS_PER_AXIS_4X4X4;
    }

    /**
     * Computes the flat biome-cell index for a 4×4×4 cell at {@code (cellX, cellY, cellZ)},
     * each in {@code 0..3}. Layout is YZX-major, matching the on-disk packing.
     */
    public static int biomeCellIndex(int cellX, int cellY, int cellZ) {
        if ((cellX | cellY | cellZ) < 0 || cellX > 3 || cellY > 3 || cellZ > 3) {
            throw new IndexOutOfBoundsException(
                    "biome-cell coords out of range 0..3: (" + cellX + "," + cellY + "," + cellZ + ")");
        }
        return (cellY << 4) | (cellZ << 2) | cellX;
    }

    /**
     * Computes the flat biome-voxel index for a 16×16×16 cell at {@code (lx, ly, lz)},
     * each in {@code 0..15}. Layout is YZX-major, matching MC 26.4+ on-disk packing.
     */
    public static int biomeVoxelIndex(int lx, int ly, int lz) {
        if ((lx | ly | lz) < 0 || lx > 15 || ly > 15 || lz > 15) {
            throw new IndexOutOfBoundsException(
                    "section-local coords out of range 0..15: (" + lx + "," + ly + "," + lz + ")");
        }
        return (ly << 8) | (lz << 4) | lx;
    }

    /**
     * Returns the raw biome identifier at section-local block coordinates
     * {@code (lx, ly, lz)}, each in {@code 0..15}, or {@code null} if the palette
     * data is missing or malformed. Automatically dispatches between 4×4×4 and
     * 16×16×16 packing based on {@link #isPerBlockGrid(int, long[])}.
     *
     * @return the identifier string (e.g. {@code "minecraft:plains"}), or {@code null} if malformed
     * @throws IndexOutOfBoundsException if any coord is outside {@code 0..15}
     */
    public String biomeIdAt(int lx, int ly, int lz) {
        if ((lx | ly | lz) < 0 || lx > 15 || ly > 15 || lz > 15) {
            throw new IndexOutOfBoundsException(
                    "section-local coords out of range 0..15: (" + lx + "," + ly + "," + lz + ")");
        }
        int paletteSize = palette.size();
        if (paletteSize == 1) {
            return palette.get(0);
        }
        if (data == null || data.length == 0) {
            return null;
        }
        boolean perBlock = isPerBlockGrid(paletteSize, data);
        if (!perBlock) {
            if (paletteSize > CELL_COUNT_4X4X4) {
                // >64 distinct biomes is only valid in a 16x16x16 grid; shorter data is truncated.
                return null;
            }
            int longs4 = requiredLongs(paletteSize, CELL_COUNT_4X4X4);
            if (data.length != longs4) {
                // Truncated 4x4x4 or truncated 16x16x16 array: fail closed to UNKNOWN.
                return null;
            }
        }
        int idx = perBlock ? biomeVoxelIndex(lx, ly, lz) : biomeCellIndex(lx >> 2, ly >> 2, lz >> 2);
        int bits = biomeBitsPerEntry(paletteSize);
        int entriesPerLong = 64 / bits;
        int longIdx = idx / entriesPerLong;
        int slot = idx - longIdx * entriesPerLong;
        if (longIdx < 0 || longIdx >= data.length) {
            return null;
        }
        long word = data[longIdx];
        long mask = (1L << bits) - 1L;
        int paletteIdx = (int) ((word >>> (slot * bits)) & mask);
        if (paletteIdx < 0 || paletteIdx >= paletteSize) {
            return null;
        }
        return palette.get(paletteIdx);
    }

    /** True if this section contains malformed or truncated biome palette data. */
    public boolean hasMalformedData() {
        int paletteSize = palette.size();
        if (paletteSize <= 1) return false;
        if (data == null || data.length == 0) return true;
        int longs16 = requiredLongs(paletteSize, CELL_COUNT_16X16X16);
        if (paletteSize > CELL_COUNT_4X4X4) {
            return data.length < longs16;
        }
        int longs4 = requiredLongs(paletteSize, CELL_COUNT_4X4X4);
        return data.length < longs4 || (data.length > longs4 && data.length < longs16);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BiomePaletteSection that)) return false;
        return sectionY == that.sectionY
                && palette.equals(that.palette)
                && java.util.Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(sectionY, palette);
        result = 31 * result + java.util.Arrays.hashCode(data);
        return result;
    }

    @Override
    public String toString() {
        return "BiomePaletteSection[" +
                "sectionY=" + sectionY + ", " +
                "palette=" + palette + ", " +
                "data=" + java.util.Arrays.toString(data) + ']';
    }
}
