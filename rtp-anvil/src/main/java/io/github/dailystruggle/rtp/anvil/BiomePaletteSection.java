package io.github.dailystruggle.rtp.anvil;

import java.util.List;
import java.util.Objects;

/**
 * Immutable view of a single chunk section's biome palette and packed-index array.
 *
 * <p>Phase 2 of ADR-016 ({@code docs/dev/ANVIL_BIOME_PLAN.md}). Mirrors the on-disk
 * shape of {@code sections[i].biomes}, which is structurally identical to
 * {@code sections[i].block_states} but differs in two observable ways:
 *
 * <ul>
 *   <li><b>Cell resolution is 4×4×4 blocks</b> (64 cells per section), not 1×1×1
 *       like blocks. The packed-index array, when present, therefore has 64 entries
 *       rather than 4096.</li>
 *   <li><b>Bit-width is {@code max(1, ceil(log2(paletteSize)))}</b> — biome palettes
 *       honour a 1-bit minimum, whereas block palettes are clamped to a 4-bit
 *       minimum by the vanilla writer. Reusing {@link PackedPaletteDecoder#decode}
 *       would therefore mis-decode a 2- or 3-entry biome palette, so this record
 *       carries its own tiny decoder in {@link #biomeIdAt(int, int, int)}.</li>
 * </ul>
 *
 * <p>Cell index mapping (§3 of the plan):
 * <pre>
 *   cellX = (x & 15) >> 2        // 0..3
 *   cellY = (y - sy*16) >> 2     // 0..3
 *   cellZ = (z & 15) >> 2        // 0..3
 *   idx   = (cellY << 4) | (cellZ << 2) | cellX  // 0..63
 * </pre>
 *
 * <p>As with {@link PaletteSection}, identifiers are the raw on-disk strings
 * (e.g. {@code "minecraft:plains"}, {@code "iris:volcanic_ash_plains"}) — the
 * caller applies the normaliser when an RTP-configuration-comparable form is
 * needed. See ANVIL_BIOME_PLAN.md §4.
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
