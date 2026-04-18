package io.github.dailystruggle.rtp.spigot.anvil;

/**
 * Decodes palette indices from the Anvil 1.16+ packed long-array layout.
 *
 * <p>Since Minecraft 1.16, chunk section {@code block_states.data} arrays pack palette
 * indices using <b>no-cross-long</b> layout: each long contains {@code floor(64 / bitsPerEntry)}
 * entries and the unused high bits of every long are zero. This differs from pre-1.16
 * chunks where entries spanned long boundaries. ADR-016 scopes the pre-filter to the
 * 1.16+ layout and treats pre-1.16 chunks as {@link Verdict#UNKNOWN} via the
 * {@link DataVersionSupport} whitelist.
 *
 * <p>Bits-per-entry is {@code max(4, ceil(log2(paletteSize)))}. The vanilla minimum of 4
 * bits applies even for palettes of size 2 or 3 (which would otherwise fit in 1 or 2 bits).
 *
 * <p>When {@code paletteSize == 1} the {@code data} array is omitted on disk; callers must
 * short-circuit to palette index {@code 0} and never invoke this decoder with a null or
 * empty {@code data}.
 */
public final class PackedPaletteDecoder {

    private PackedPaletteDecoder() {}

    /**
     * Computes the bits-per-entry for a given palette size, following the vanilla rule
     * {@code max(4, ceil(log2(paletteSize)))}.
     *
     * @param paletteSize must be {@code >= 1}
     * @return bits per packed entry; always {@code >= 4}
     */
    public static int bitsPerEntry(int paletteSize) {
        if (paletteSize < 1) {
            throw new IllegalArgumentException("paletteSize must be >= 1, got " + paletteSize);
        }
        if (paletteSize <= 16) return 4;
        // ceil(log2(n)) for n >= 2
        return 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
    }

    /**
     * Decodes the palette index at {@code entryIndex} from a packed {@code data} array.
     *
     * @param data         packed long array from {@code block_states.data}; must be non-null
     *                     and long enough to contain {@code entryIndex}
     * @param paletteSize  palette size (must be {@code >= 2} — single-entry palettes are
     *                     handled by the caller and do not have a {@code data} array)
     * @param entryIndex   section-flat index {@code (y * 16 + z) * 16 + x}, 0..4095
     * @return palette index at {@code entryIndex}
     * @throws IllegalArgumentException if {@code paletteSize < 2}
     * @throws IndexOutOfBoundsException if {@code entryIndex} falls outside the array
     */
    public static int decode(long[] data, int paletteSize, int entryIndex) {
        if (paletteSize < 2) {
            throw new IllegalArgumentException(
                    "decode() requires paletteSize >= 2 (single-entry palettes have no data array); got "
                            + paletteSize);
        }
        int bits = bitsPerEntry(paletteSize);
        int entriesPerLong = 64 / bits;
        int longIdx = entryIndex / entriesPerLong;
        int slot = entryIndex - longIdx * entriesPerLong;
        if (longIdx < 0 || longIdx >= data.length) {
            throw new IndexOutOfBoundsException(
                    "entryIndex " + entryIndex + " maps to long " + longIdx
                            + " but data.length=" + data.length + " (bits=" + bits + ")");
        }
        long word = data[longIdx];
        long mask = (1L << bits) - 1L;
        return (int) ((word >>> (slot * bits)) & mask);
    }

    /**
     * Converts a section-local {@code (x, y, z)} triple with each coordinate in {@code 0..15}
     * into the {@code data}-array entry index using Minecraft's YZX-major layout.
     *
     * @param lx 0..15
     * @param ly 0..15
     * @param lz 0..15
     * @return section-flat entry index 0..4095
     */
    public static int entryIndex(int lx, int ly, int lz) {
        if ((lx | ly | lz) < 0 || lx > 15 || ly > 15 || lz > 15) {
            throw new IndexOutOfBoundsException(
                    "section-local coords out of range 0..15: (" + lx + "," + ly + "," + lz + ")");
        }
        return (ly << 8) | (lz << 4) | lx;
    }
}
