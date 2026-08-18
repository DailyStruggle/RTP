package io.github.dailystruggle.rtp.anvil;

import java.util.List;
import java.util.Objects;

/**
 * Immutable view of a section's biome palette + packed-index array (ADR-016
 * §4). Like {@link PaletteSection} but: 4×4×4 cell resolution
 * (64 cells/section) and bits-per-entry {@code max(1, ceil(log2(size)))} -
 * biome palettes have a 1-bit minimum (blocks: 4-bit), so this carries its
 * own decoder. Layout is YZX-major: {@code idx = (cellY<<4)|(cellZ<<2)|cellX}.
 * Identifiers are raw on-disk strings; caller normalises.
 */
public record BiomePaletteSection(int sectionY, List<String> palette, long[] data) {

    public BiomePaletteSection {
        Objects.requireNonNull(palette, "palette");
        if (palette.isEmpty()) {
            throw new IllegalArgumentException("biome palette must have at least one entry");
        }
        // Malformed tolerance mirrors PaletteSection: a multi-entry palette with no
        // data array is treated as "all palette[0]" rather than rejected, because
        // the Anvil subsystem's posture is "malformed → UNKNOWN, never crash"
        // (ADR-016). Single-entry palettes canonically omit the data array.
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
     * Computes the flat biome-cell index for a cell at {@code (cellX, cellY, cellZ)},
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
     * Returns the raw biome identifier at section-local block coordinates
     * {@code (lx, ly, lz)}, each in {@code 0..15}. The block coords are internally
     * down-scaled to the 4×4×4 cell grid.
     *
     * @return the identifier string (e.g. {@code "minecraft:plains"}); never {@code null}
     * @throws IndexOutOfBoundsException if any coord is outside {@code 0..15}
     */
    public String biomeIdAt(int lx, int ly, int lz) {
        if ((lx | ly | lz) < 0 || lx > 15 || ly > 15 || lz > 15) {
            throw new IndexOutOfBoundsException(
                    "section-local coords out of range 0..15: (" + lx + "," + ly + "," + lz + ")");
        }
        if (palette.size() == 1 || data == null || data.length == 0) {
            return palette.get(0);
        }
        int idx = biomeCellIndex(lx >> 2, ly >> 2, lz >> 2);
        int bits = biomeBitsPerEntry(palette.size());
        int entriesPerLong = 64 / bits;
        int longIdx = idx / entriesPerLong;
        int slot = idx - longIdx * entriesPerLong;
        if (longIdx < 0 || longIdx >= data.length) {
            // Defensive: malformed data array. Fall back to palette[0] rather than
            // throw (ADR-016 "malformed → UNKNOWN, never crash").
            return palette.get(0);
        }
        long word = data[longIdx];
        long mask = (1L << bits) - 1L;
        int paletteIdx = (int) ((word >>> (slot * bits)) & mask);
        if (paletteIdx < 0 || paletteIdx >= palette.size()) {
            return palette.get(0);
        }
        return palette.get(paletteIdx);
    }
}
