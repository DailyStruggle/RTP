package io.github.dailystruggle.rtp.anvil;

import java.util.List;
import java.util.Objects;

/**
 * Immutable view of a single chunk section's block palette and packed-index array.
 *
 * <p>Mirrors the on-disk shape of {@code sections[i].block_states}:
 * <ul>
 *   <li>{@link #sectionY()} — the section's Y index (e.g. {@code -4..19} in 1.18+ overworld)</li>
 *   <li>{@link #palette()} — raw NBT identifiers as found in {@code palette[i].Name}, in the
 *       exact order they appear on disk; never {@code null}, never empty</li>
 *   <li>{@link #data()} — packed palette indices (YZX-major, 4096 entries per section);
 *       {@code null} iff {@link #palette()} has exactly one entry (vanilla omits {@code data}
 *       in that case to save space)</li>
 * </ul>
 *
 * <p>This record intentionally carries raw identifier strings, not normalized forms, so the
 * verdict layer (Phase 3) can apply {@link PaletteNormalizer} / the
 * {@code rtp-api} normalizer symmetrically against the user-supplied unsafe set. See
 * ADR-016 Decision §3.
 */
public record PaletteSection(int sectionY, List<String> palette, long[] data, byte[] skyLight) {

    /**
     * Back-compat constructor for existing callers (tests, fixture builders) that do
     * not supply a {@code SkyLight} nibble array. Defaults {@code skyLight} to {@code null},
     * which is interpreted by {@link #skyLightAt(int, int, int)} as "fully lit" (15) —
     * matching the semantics of a vanilla section whose {@code SkyLight} tag is absent
     * because the whole section is above the opaque-block column and receives direct sky.
     */
    public PaletteSection(int sectionY, List<String> palette, long[] data) {
        this(sectionY, palette, data, null);
    }

    public PaletteSection {
        Objects.requireNonNull(palette, "palette");
        if (palette.isEmpty()) {
            throw new IllegalArgumentException("palette must have at least one entry");
        }
        if (skyLight != null && skyLight.length != 2048) {
            throw new IllegalArgumentException(
                    "skyLight nibble array must be exactly 2048 bytes when present, got "
                            + skyLight.length);
        }
        if (palette.size() == 1 && data != null && data.length != 0) {
            // Tolerated but non-canonical: some generators emit an all-zero data array
            // alongside a single-entry palette. We accept it silently — the decoder is
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
     * Returns the sky-light level at section-local coords {@code (lx, ly, lz)} (each
     * in {@code 0..15}). The on-disk {@code SkyLight} tag is a 2048-byte nibble array
     * indexed by the YZX-major entry index, two nibbles per byte: the low nibble is
     * the lower (even) entry, the high nibble is the upper (odd) entry.
     *
     * <p>Returns {@code 15} when this section has no {@code SkyLight} tag — the
     * Minecraft convention for "fully lit above the opaque column". The live
     * {@code chunk.isSafe(...)} re-check at teleport time is the authoritative signal,
     * so a permissive default here is safe and matches live-Chunk behaviour for
     * sections at or above the heightmap surface.
     */
    public int skyLightAt(int lx, int ly, int lz) {
        if (skyLight == null) return 15;
        // if all bytes are 0, treat as absent (15)
        boolean allZero = true;
        for (byte b : skyLight) {
            if (b != 0) {
                allZero = false;
                break;
            }
        }
        if (allZero) return 15;

        int idx = PackedPaletteDecoder.entryIndex(lx, ly, lz);
        int b = skyLight[idx >> 1] & 0xFF;
        return ((idx & 1) == 0) ? (b & 0x0F) : ((b >>> 4) & 0x0F);
    }

    /**
     * Returns the raw palette identifier at section-local coordinates {@code (lx, ly, lz)},
     * each in {@code 0..15}.
     *
     * @return the identifier string (e.g. {@code "minecraft:lava"}); never {@code null}
     * @throws IndexOutOfBoundsException if any coord is outside {@code 0..15}
     */
    public String blockIdAt(int lx, int ly, int lz) {
        if (palette.size() == 1 || data == null || data.length == 0) {
            return palette.get(0);
        }
        int idx = PackedPaletteDecoder.entryIndex(lx, ly, lz);
        int paletteIdx = PackedPaletteDecoder.decode(data, palette.size(), idx);
        if (paletteIdx < 0 || paletteIdx >= palette.size()) {
            // Defensive: corrupted index bits. Fall back to palette[0] rather than throw
            // so the pre-filter stays on the UNKNOWN path for malformed chunks.
            return palette.get(0);
        }
        return palette.get(paletteIdx);
    }
}
