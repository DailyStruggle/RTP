package io.github.dailystruggle.rtp.spigot.anvil;

import java.util.List;
import java.util.Objects;

/**
 * Immutable typed view over the subset of chunk NBT the pre-filter actually consults.
 *
 * <p>Built by {@link AnvilReader#readChunkView(byte[], int, int)} from a parsed
 * chunk root compound. Kept deliberately minimal — block palette + heightmap only,
 * no biome palette, no tile entities, no light data — because those are the only
 * fields the Phase 3 verdict layer reads. Extending the view is additive; removing
 * fields would be a breaking change.
 *
 * <p>Sections are stored in their on-disk order (generally ascending {@link PaletteSection#sectionY()},
 * but the view does not sort). A world Y below or above the emitted section range
 * returns {@code null} from {@link #blockIdAt(int, int, int)}.
 */
public record AnvilChunkView(int dataVersion, List<PaletteSection> sections, long[] motionBlockingNoLeaves) {

    public AnvilChunkView {
        Objects.requireNonNull(sections, "sections");
    }

    /**
     * Returns the raw palette identifier at world coordinates {@code (x, worldY, z)} inside
     * this chunk. {@code x} and {@code z} are chunk-local (0..15); {@code worldY} is the
     * absolute world Y (e.g. {@code -64..319} in 1.18+ overworld). Returns {@code null} if
     * no section covers that Y range, or {@code "minecraft:air"} semantics are the caller's
     * responsibility — this method never synthesises absent sections.
     *
     * @param x       chunk-local x, 0..15
     * @param worldY  absolute world Y
     * @param z       chunk-local z, 0..15
     * @return the on-disk block identifier, or {@code null} if the Y falls in a section
     *         that was not emitted on disk
     * @throws IndexOutOfBoundsException if {@code x} or {@code z} is outside {@code 0..15}
     */
    public String blockIdAt(int x, int worldY, int z) {
        int sy = Math.floorDiv(worldY, 16);
        int ly = Math.floorMod(worldY, 16);
        for (PaletteSection s : sections) {
            if (s.sectionY() == sy) {
                return s.blockIdAt(x, ly, z);
            }
        }
        return null;
    }
}
