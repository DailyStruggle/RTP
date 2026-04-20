package io.github.dailystruggle.rtp.anvil;

import java.util.List;
import java.util.Objects;
import java.util.Set;

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

    // ---------------------------------------------------------- Phase 3b (ADR-016) typed queries

    /**
     * Returns the world-Y of the lowest emitted section floor, or {@code 0} when this
     * view has no sections. Used as the floor for heightmap arithmetic (heightmap
     * entries are relative to {@code minHeight}).
     */
    public int minHeight() {
        int min = Integer.MAX_VALUE;
        for (PaletteSection s : sections) {
            if (s.sectionY() < min) min = s.sectionY();
        }
        return (min == Integer.MAX_VALUE) ? 0 : (min * 16);
    }

    /** Looks up the {@link PaletteSection} covering world-Y {@code worldY}, or {@code null}. */
    private PaletteSection sectionForWorldY(int worldY) {
        int sy = Math.floorDiv(worldY, 16);
        for (PaletteSection s : sections) {
            if (s.sectionY() == sy) return s;
        }
        return null;
    }

    /**
     * True iff the block at {@code (x, worldY, z)} is air. Matches the live
     * {@code BukkitRTPChunk.isAir} semantics for the vanilla air identifiers.
     * Coordinates in a section not present on disk are treated as air (Y above the
     * highest emitted section) — this matches the live-chunk behaviour where a
     * never-written column reads as air all the way up to the build ceiling.
     */
    public boolean isAir(int x, int worldY, int z) {
        String id = blockIdAt(x, worldY, z);
        if (id == null) return true;
        String reconciled = AnvilPrefilter.DEFAULT_RECONCILER.apply(id);
        return "AIR".equals(reconciled)
                || "CAVE_AIR".equals(reconciled)
                || "VOID_AIR".equals(reconciled);
    }

    /**
     * True iff the block at {@code (x, worldY, z)} is <em>not</em> in the supplied
     * reconciled unsafe set. The set must already be reconciled (canonical form,
     * produced by {@link PaletteNormalizer#reconcileAll}); callers that still hold a
     * raw config-side set should reconcile once at call-site and cache. Out-of-range
     * Y values are treated as safe — they cannot carry unsafe blocks.
     */
    public boolean isSafe(int x, int worldY, int z, Set<String> reconciledUnsafe) {
        if (reconciledUnsafe == null || reconciledUnsafe.isEmpty()) return true;
        String id = blockIdAt(x, worldY, z);
        if (id == null) return true;
        String reconciled = AnvilPrefilter.DEFAULT_RECONCILER.apply(id);
        return reconciled == null || !reconciledUnsafe.contains(reconciled);
    }

    /**
     * Returns the sky-light level at {@code (x, worldY, z)}. If the covering section
     * has no {@code SkyLight} tag or the Y is out of range, returns {@code 15} —
     * matching the vanilla convention for "above the opaque column" and keeping
     * parity with {@link PaletteSection#skyLightAt(int, int, int)}.
     */
    public int getSkyLight(int x, int worldY, int z) {
        PaletteSection s = sectionForWorldY(worldY);
        if (s == null) return 15;
        int ly = Math.floorMod(worldY, 16);
        return s.skyLightAt(x, ly, z);
    }

    /**
     * Returns the world-Y of the highest motion-blocking, no-leaves column top at
     * section-local {@code (x, z)}. Mirrors
     * {@code World#getHighestBlockYAt(x, z, MOTION_BLOCKING_NO_LEAVES)} on the live
     * chunk. Returns {@link #minHeight()} (the bottom of the lowest emitted section)
     * when the column is empty or the heightmap is absent — conservative, keeps
     * callers on valid Y ranges.
     */
    public int getSurfaceHeight(int x, int z) {
        int floor = minHeight();
        if (motionBlockingNoLeaves == null || motionBlockingNoLeaves.length == 0) return floor;
        // Heightmap is a 9-bit-packed 256-entry long array (1.18+), no-cross-long.
        int bits = 9;
        int entriesPerLong = 64 / bits;
        int columnIndex = (z & 15) * 16 + (x & 15);
        int longIdx = columnIndex / entriesPerLong;
        if (longIdx >= motionBlockingNoLeaves.length) return floor;
        int slot = columnIndex - longIdx * entriesPerLong;
        long mask = (1L << bits) - 1L;
        int raw = (int) ((motionBlockingNoLeaves[longIdx] >>> (slot * bits)) & mask);
        if (raw <= 0) return floor;
        // Heightmap raw value is count of blocks above minHeight; surface block Y is
        // floor + raw - 1 (the topmost motion-blocking block, same convention the
        // Phase 3a prefilter already uses).
        return floor + raw - 1;
    }
}
