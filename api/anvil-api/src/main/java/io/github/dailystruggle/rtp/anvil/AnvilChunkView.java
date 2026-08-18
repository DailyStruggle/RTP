package io.github.dailystruggle.rtp.anvil;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable view over the subset of chunk NBT the pre-filter consults: block
 * palette + heightmap + optional biome palette. Built by
 * {@link AnvilReader#readChunkView(byte[], int, int)}. Sections are kept in
 * on-disk order (no sort). Extending fields is additive; removing is breaking.
 */
public final class AnvilChunkView {

    private final int dataVersion;
    private final List<PaletteSection> sections;
    private final long[] motionBlockingNoLeaves;
    private final List<BiomePaletteSection> biomeSections;

    /**
     * Canonical constructor. {@code sections} and {@code biomeSections} must be
     * non-null (use {@link Collections#emptyList()} for "no data").
     */
    public AnvilChunkView(int dataVersion,
                          List<PaletteSection> sections,
                          long[] motionBlockingNoLeaves,
                          List<BiomePaletteSection> biomeSections) {
        this.dataVersion = dataVersion;
        this.sections = Objects.requireNonNull(sections, "sections");
        this.motionBlockingNoLeaves = motionBlockingNoLeaves;
        this.biomeSections = Objects.requireNonNull(biomeSections, "biomeSections");
    }

    /**
     * Back-compat constructor for call sites that predate the biome palette (ADR-016).
     * Defaults {@code biomeSections} to an empty list.
     */
    public AnvilChunkView(int dataVersion, List<PaletteSection> sections, long[] motionBlockingNoLeaves) {
        this(dataVersion, sections, motionBlockingNoLeaves, Collections.emptyList());
    }

    // -------------------------------------------------- record-style accessors (kept for back-compat)

    public int dataVersion() { return dataVersion; }
    public List<PaletteSection> sections() { return sections; }
    public long[] motionBlockingNoLeaves() { return motionBlockingNoLeaves; }
    public List<BiomePaletteSection> biomeSections() { return biomeSections; }

    /**
     * Returns the raw palette identifier at world coordinates {@code (x, worldY, z)} inside
     * this chunk. {@code x} and {@code z} are chunk-local (0..15); {@code worldY} is the
     * absolute world Y (e.g. {@code -64..319} in 1.18+ overworld). Returns {@code null} if
     * no section covers that Y range, or {@code "minecraft:air"} semantics are the caller's
     * responsibility - this method never synthesises absent sections.
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

    // --- Typed queries (ADR-016) ---

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

    /**
     * True iff the block at {@code (x, worldY, z)} is air. Matches the live
     * {@code BukkitRTPChunk.isAir} semantics for the vanilla air identifiers.
     * Coordinates in a section not present on disk are treated as air (Y above the
     * highest emitted section) - this matches the live-chunk behaviour where a
     * never-written column reads as air all the way up to the build ceiling.
     *
     * <p>Vanilla-air-only overload: returns {@code true} only for the three vanilla
     * air identifiers. Use {@link #isAir(int, int, int, Set)} when the caller wants
     * the configured {@code airBlocks} list (tall grass, snow layer, leaf litter,
     * tag-expanded leaves, ...) to count as passable.
     */
    public boolean isAir(int x, int worldY, int z) {
        return isAir(x, worldY, z, Collections.emptySet());
    }

    /**
     * True iff the block at {@code (x, worldY, z)} is air OR a member of the
     * supplied reconciled {@code airBlocks} set. This is the same
     * "passable / not solid feet support" predicate the probe-fast-path
     * {@code JumpAdjustor.acceptProbeY} applies. Use this overload from any
     * code path that needs to mirror the {@code airBlocks} semantics - i.e.
     * "tall grass / leaf litter / snow layer / leaves canopy is passable" -
     * so that off-thread Anvil-backed reads agree with the live-path
     * {@code chunk.isAir(...) || airBlocks.contains(...)} predicate.
     *
     * <p>The set must already be reconciled to the canonical form (upper-case,
     * namespace-stripped, with {@code #namespace:tag} tokens already expanded
     * to their material members) - produced by {@code PaletteNormalizer.reconcileAll}
     * on Spigot/Paper/Folia or by the equivalent core-side normalizer. The vanilla
     * air identifiers ({@code AIR}, {@code CAVE_AIR}, {@code VOID_AIR}) are
     * always treated as air regardless of whether they appear in
     * {@code reconciledAir}. Out-of-range Y values (no covering section) are
     * treated as air (matches the live-chunk behaviour for never-written
     * columns).
     *
     * @param x chunk-local x, 0..15
     * @param worldY absolute world Y
     * @param z chunk-local z, 0..15
     * @param reconciledAir already-reconciled {@code airBlocks} set; may be
     *     {@code null} or empty to fall through to vanilla-only semantics
     * @return {@code true} iff the block is vanilla air or a member of
     *     {@code reconciledAir}
     */
    public boolean isAir(int x, int worldY, int z, Set<String> reconciledAir) {
        String id = blockIdAt(x, worldY, z);
        if (id == null) return true;
        String reconciled = AnvilPrefilter.DEFAULT_RECONCILER.apply(id);
        if ("AIR".equals(reconciled)
                || "CAVE_AIR".equals(reconciled)
                || "VOID_AIR".equals(reconciled)) {
            return true;
        }
        if (reconciledAir == null || reconciledAir.isEmpty()) return false;
        return reconciled != null && reconciledAir.contains(reconciled);
    }

    /**
     * True iff the block at {@code (x, worldY, z)} is <em>not</em> in the supplied
     * reconciled unsafe set. The set must already be reconciled (canonical form,
     * produced by {@code PaletteNormalizer.reconcileAll}); callers that still hold a
     * raw config-side set should reconcile once at call-site and cache. Out-of-range
     * Y values are treated as safe - they cannot carry unsafe blocks.
     */
    public boolean isSafe(int x, int worldY, int z, Set<String> reconciledUnsafe) {
        if (reconciledUnsafe == null || reconciledUnsafe.isEmpty()) return true;
        String id = blockIdAt(x, worldY, z);
        if (id == null) return true;
        String reconciled = AnvilPrefilter.DEFAULT_RECONCILER.apply(id);
        return reconciled == null || !reconciledUnsafe.contains(reconciled);
    }

    /**
     * Returns the world-Y of the highest motion-blocking, no-leaves column top at
     * section-local {@code (x, z)}. Mirrors
     * {@code World#getHighestBlockYAt(x, z, MOTION_BLOCKING_NO_LEAVES)} on the live
     * chunk. Returns {@link #minHeight()} (the bottom of the lowest emitted section)
     * when the column is empty or the heightmap is absent - conservative, keeps
     * callers on valid Y ranges.
     */
    public int getSurfaceHeight(int x, int z) {
        int floor = minHeight();
        if (motionBlockingNoLeaves == null || motionBlockingNoLeaves.length == 0) return floor;
        int bits = 9;
        int entriesPerLong = 64 / bits;
        int columnIndex = (z & 15) * 16 + (x & 15);
        int longIdx = columnIndex / entriesPerLong;
        if (longIdx >= motionBlockingNoLeaves.length) return floor;
        int slot = columnIndex - longIdx * entriesPerLong;
        long mask = (1L << bits) - 1L;
        int raw = (int) ((motionBlockingNoLeaves[longIdx] >>> (slot * bits)) & mask);
        if (raw <= 0) return floor;
        return floor + raw - 1;
    }

    // --- Biome queries (ADR-016) ---

    /**
     * Returns the raw namespaced biome identifier at world coordinate {@code (x, worldY, z)},
     * or {@code null} if {@code worldY} falls outside the decoded Y window or the covering
     * section has no biome container on disk.
     *
     * <p>Coordinates are chunk-local for {@code x} / {@code z} (0..15) and absolute for
     * {@code worldY}. The returned identifier is the on-disk form (e.g.
     * {@code "minecraft:plains"}, {@code "iris:volcanic_ash_plains"}); callers that need
     * the RTP-configuration-comparable form must run it through the platform-side
     * {@code PaletteNormalizer::reconcile}.
     *
     * <p>See ADR-016 (biome) §4 for the contract and §5 for the trust model (the
     * disk palette is authoritative for populated chunks; for an {@code UNKNOWN} /
     * absent section the platform adapter is expected to fall through to the live
     * {@code world.getBiome(loc).name()} getter).
     *
     * @param x       chunk-local x, 0..15
     * @param worldY  absolute world Y
     * @param z       chunk-local z, 0..15
     * @return the on-disk biome identifier, or {@code null} when no biome container
     *         covers that Y range in this view
     * @throws IndexOutOfBoundsException if {@code x} or {@code z} is outside {@code 0..15}
     */
    public String getBiomeAt(int x, int worldY, int z) {
        int sy = Math.floorDiv(worldY, 16);
        int ly = Math.floorMod(worldY, 16);
        for (BiomePaletteSection bs : biomeSections) {
            if (bs.sectionY() == sy) {
                return bs.biomeIdAt(x, ly, z);
            }
        }
        return null;
    }

    /**
     * Returns the union of every distinct namespaced biome identifier that appears
     * in any decoded section of this chunk. Intended for pregen biome-enumeration
     * scans (see ADR-016 (biome) §6.1). Does not include biomes from sections
     * whose biome container failed to decode (those sections are simply absent from
     * {@link #biomeSections()}).
     *
     * <p>The returned set preserves insertion order (first-seen per section / per
     * palette index) to make tab-completion output stable.
     */
    public Set<String> getBiomesPresent() {
        if (biomeSections.isEmpty()) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        for (BiomePaletteSection bs : biomeSections) {
            out.addAll(bs.palette());
        }
        return Collections.unmodifiableSet(out);
    }
}
