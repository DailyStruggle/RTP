package io.github.dailystruggle.rtp.anvil;

import java.util.List;
import java.util.Objects;

/**
 * Immutable view of a single chunk section's block palette and packed-index array.
 *
 * <p>Mirrors the on-disk shape of {@code sections[i].block_states}:
 * <ul>
 *   <li>{@link #sectionY()} - the section's Y index (e.g. {@code -4..19} in 1.18+ overworld)</li>
 *   <li>{@link #palette()} - raw NBT identifiers as found in {@code palette[i].Name}, in the
 *       exact order they appear on disk; never {@code null}, never empty</li>
 *   <li>{@link #data()} - packed palette indices (YZX-major, 4096 entries per section);
 *       {@code null} iff {@link #palette()} has exactly one entry (vanilla omits {@code data}
 *       in that case to save space)</li>
 * </ul>
 *
 * <p>This record intentionally carries raw identifier strings, not normalized forms, so the
 * verdict layer can apply the
 * {@code rtp-api} normalizer symmetrically against the user-supplied unsafe set. See
 * ADR-016 Decision section 3.
 */
public record PaletteSection(int sectionY, List<String> palette, long[] data) {

    public PaletteSection {
        Objects.requireNonNull(palette, "palette");
        if (palette.isEmpty()) {
            throw new IllegalArgumentException("palette must have at least one entry");
        }
        if (palette.size() == 1 && data != null && data.length != 0) {
            // Tolerated but non-canonical: some generators emit an all-zero data array
            // alongside a single-entry palette. We accept it silently - the decoder is
            // short-circuited for single-entry palettes regardless.
        }
        if (palette.size() > 1 && data == null) {
            // Malformed vanilla data: palette.size > 1 without a data array would imply
            // all-zero indices everywhere. We accept it (returning palette[0] for every
            // coord) rather than fail, because the pre-filter must treat malformed input
            // as UNKNOWN, not crash. See ADR-016.
        }
    }

    /**
     * Returns the raw palette identifier at section-local coordinates {@code (lx, ly, lz)},
     * each in {@code 0..15}, or {@code null} if the palette data is missing or malformed.
     *
     * @return the identifier string (e.g. {@code "minecraft:lava"}), or {@code null} if malformed
     * @throws IndexOutOfBoundsException if any coord is outside {@code 0..15}
     */
    public String blockIdAt(int lx, int ly, int lz) {
        if (palette.size() == 1) {
            return palette.get(0);
        }
        if (data == null || data.length == 0) {
            return null;
        }
        int idx = PackedPaletteDecoder.entryIndex(lx, ly, lz);
        int bits = PackedPaletteDecoder.bitsPerEntry(palette.size());
        int entriesPerLong = 64 / bits;
        int longIdx = idx / entriesPerLong;
        if (longIdx < 0 || longIdx >= data.length) {
            return null;
        }
        int paletteIdx = PackedPaletteDecoder.decode(data, palette.size(), idx);
        if (paletteIdx < 0 || paletteIdx >= palette.size()) {
            return null;
        }
        return palette.get(paletteIdx);
    }

    /** True if this section contains malformed or truncated palette data. */
    public boolean hasMalformedData() {
        if (palette.size() <= 1) return false;
        if (data == null || data.length == 0) return true;
        int bits = PackedPaletteDecoder.bitsPerEntry(palette.size());
        int entriesPerLong = 64 / bits;
        int requiredLongs = (4096 + entriesPerLong - 1) / entriesPerLong;
        return data.length < requiredLongs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PaletteSection(int thatSectionY, List<String> thatPalette, long[] thatData))) return false;
        return sectionY == thatSectionY
                && palette.equals(thatPalette)
                && java.util.Arrays.equals(data, thatData);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(sectionY, palette);
        result = 31 * result + java.util.Arrays.hashCode(data);
        return result;
    }

    @Override
    public String toString() {
        return "PaletteSection[" +
                "sectionY=" + sectionY + ", " +
                "palette=" + palette + ", " +
                "data=" + java.util.Arrays.toString(data) + ']';
    }
}
