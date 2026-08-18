package io.github.dailystruggle.rtp.anvil;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import net.jpountz.lz4.LZ4FrameInputStream;

/**
 * Read-only Anvil region-file parser used by the pre-filter (ADR-016).
 *
 * <p><b>Scope.</b> This class is deliberately narrow: it opens an {@code r.X.Z.mca} byte
 * buffer, locates the chunk entry at region-local coordinates {@code (cx, cz)}, decompresses
 * it, and hands the decoded root {@code TAG_Compound} back to the caller. It never imports
 * {@code org.bukkit.Chunk} (the whole point of the pre-filter is operating on unloaded chunks)
 * and it performs no block-state or biome interpretation beyond what the structural NBT walk
 * provides - those higher-level views are the job of later phases.
 *
 * <p><b>Compression support.</b> Per observed fixtures across 1.20.4 / 1.21.5 / 26.1 (data
 * versions 3465 / 4671 / 4788), vanilla servers ship Anvil chunks as Minecraft compression
 * mode {@code 2} (zlib-wrapped Deflate). Mode {@code 1} (gzip) is supported for forward
 * compatibility. Mode {@code 3} (uncompressed) is supported. Mode {@code 4} (LZ4) and the
 * {@code 0x80}-or'd "external" variants are rejected with {@link UnsupportedAnvilFormatException}
 * so the pre-filter returns {@link Verdict#UNKNOWN} and the live load path takes over - this
 * is the deliberate safe fallback for formats we have not yet validated against real data.
 *
 * <p>All methods are thread-safe: the class is stateless and operates on caller-owned buffers.
 */
public final class AnvilReader {

    private static final int SECTOR_SIZE = 4096;

    /** Bit flag set on the compression byte when the chunk is stored in an external file. */
    private static final int EXTERNAL_FLAG = 0x80;

    private AnvilReader() {}

    /**
     * Decoded chunk header + root compound. {@code declaredSectionLength} is the Anvil
     * length prefix (number of bytes that follow, including the compression-type byte).
     */
    public static final class ChunkEntry {
        public final int compressionType;
        public final int declaredSectionLength;
        public final LinkedHashMap<String, Object> root;

        public ChunkEntry(int compressionType, int declaredSectionLength, LinkedHashMap<String, Object> root) {
            this.compressionType = compressionType;
            this.declaredSectionLength = declaredSectionLength;
            this.root = root;
        }
    }

    /**
     * Reads the chunk at region-local coordinates {@code (cx, cz)} from {@code regionBytes}.
     *
     * @param regionBytes the full {@code r.X.Z.mca} byte array
     * @param cx          region-local chunk x, 0..31
     * @param cz          region-local chunk z, 0..31
     * @return the decoded entry, or {@code null} if the chunk is not present in this region
     * @throws UnsupportedAnvilFormatException if the compression mode is not supported
     * @throws IOException                     on malformed headers or NBT
     */
    public static ChunkEntry readChunk(byte[] regionBytes, int cx, int cz) throws IOException {
        RawChunk raw = readRawChunk(regionBytes, cx, cz);
        if (raw == null) return null;
        LinkedHashMap<String, Object> root = Nbt.readRootCompound(raw.nbtBytes);
        return new ChunkEntry(raw.compressionByte, raw.declaredLength, root);
    }

    /**
     * Locates the chunk at {@code (cx, cz)} and inflates its NBT payload without parsing
     * the tag tree. Returns {@code null} if the chunk is absent from the region. Shared
     * by {@link #readChunk} and {@link #readColumnProbe} to avoid duplicating the
     * header-walk and decompression logic.
     */
    private static RawChunk readRawChunk(byte[] regionBytes, int cx, int cz) throws IOException {
        if (regionBytes == null || regionBytes.length < SECTOR_SIZE * 2) {
            throw new CorruptRegionEntryException("Region buffer too short: " + (regionBytes == null ? 0 : regionBytes.length));
        }
        if (cx < 0 || cx > 31 || cz < 0 || cz > 31) {
            throw new IllegalArgumentException("Region-local (cx,cz) out of range: (" + cx + "," + cz + ")");
        }

        int index = (cx & 31) + ((cz & 31) << 5);
        int locationEntryOffset = index * 4;
        int sectorOffset =
                ((regionBytes[locationEntryOffset]     & 0xFF) << 16) |
                ((regionBytes[locationEntryOffset + 1] & 0xFF) << 8)  |
                 (regionBytes[locationEntryOffset + 2] & 0xFF);
        int sectorCount = regionBytes[locationEntryOffset + 3] & 0xFF;
        if (sectorOffset == 0 || sectorCount == 0) {
            return null;
        }

        int payloadStart = sectorOffset * SECTOR_SIZE;
        int payloadBudget = sectorCount * SECTOR_SIZE;
        if (payloadStart + payloadBudget > regionBytes.length) {
            throw new CorruptRegionEntryException("Chunk entry (" + cx + "," + cz + ") spans past end of file: start="
                    + payloadStart + " budget=" + payloadBudget + " fileLen=" + regionBytes.length);
        }

        ByteBuffer bb = ByteBuffer.wrap(regionBytes, payloadStart, payloadBudget);
        int declaredLength = bb.getInt();
        int compressionByte = bb.get() & 0xFF;
        if ((compressionByte & EXTERNAL_FLAG) != 0) {
            throw new UnsupportedAnvilFormatException(
                    "External-file compression flag (0x80) not supported for chunk (" + cx + "," + cz + ")");
        }
        if (declaredLength <= 0 || declaredLength - 1 > payloadBudget - 5) {
            throw new CorruptRegionEntryException("Implausible declared chunk length " + declaredLength
                    + " for (" + cx + "," + cz + "); sector budget=" + (payloadBudget - 5));
        }
        int compressedLen = declaredLength - 1;

        byte[] nbtBytes = decompress(regionBytes, payloadStart + 5, compressedLen, compressionByte);
        return new RawChunk(compressionByte, declaredLength, nbtBytes);
    }

    private static final class RawChunk {
        final int compressionByte;
        final int declaredLength;
        final byte[] nbtBytes;
        RawChunk(int compressionByte, int declaredLength, byte[] nbtBytes) {
            this.compressionByte = compressionByte;
            this.declaredLength = declaredLength;
            this.nbtBytes = nbtBytes;
        }
    }

    /** Decompresses a chunk payload according to Minecraft Anvil compression modes. */
    private static byte[] decompress(byte[] src, int off, int len, int mode) throws IOException {
        java.io.InputStream wrapped;
        switch (mode) {
            case 1:
                wrapped = new GZIPInputStream(new ByteArrayInputStream(src, off, len));
                break;
            case 2:
                wrapped = new InflaterInputStream(new ByteArrayInputStream(src, off, len));
                break;
            case 3:
                byte[] copy = new byte[len];
                System.arraycopy(src, off, copy, 0, len);
                return copy;
            case 4:
                wrapped = new LZ4FrameInputStream(new ByteArrayInputStream(src, off, len));
                break;
            default:
                throw new UnsupportedAnvilFormatException("Unknown Anvil compression mode " + mode);
        }
        try (java.io.InputStream in = wrapped;
             java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(Math.max(1024, len * 4))) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
            return baos.toByteArray();
        }
    }

    // --------------------------------------------------------------------- convenience accessors

    /** Reads the {@code DataVersion} int from a chunk root compound, or {@code -1} if absent. */
    public static int getDataVersion(LinkedHashMap<String, Object> root) {
        Object v = root.get("DataVersion");
        return (v instanceof Integer) ? (Integer) v : -1;
    }

    /**
     * Extracts the {@code MOTION_BLOCKING_NO_LEAVES} packed long array from the chunk's
     * {@code Heightmaps} compound, or {@code null} if not present.
     */
    @SuppressWarnings("unchecked")
    public static long[] getMotionBlockingNoLeaves(LinkedHashMap<String, Object> root) {
        Object heightmaps = root.get("Heightmaps");
        if (!(heightmaps instanceof java.util.Map)) return null;
        Object value = ((java.util.Map<String, Object>) heightmaps).get("MOTION_BLOCKING_NO_LEAVES");
        return (value instanceof long[]) ? (long[]) value : null;
    }

    /**
     * Returns the {@code sections} list payload of a chunk root compound, or {@code null}
     * if the chunk has no section array (unexpected for a populated chunk).
     */
    public static Nbt.NbtList getSections(LinkedHashMap<String, Object> root) {
        Object v = root.get("sections");
        return (v instanceof Nbt.NbtList) ? (Nbt.NbtList) v : null;
    }

    // -------------------------------------------------------------------- typed view

    /**
     * Reads the chunk at region-local coordinates {@code (cx, cz)} and lifts the raw
     * NBT root into an {@link AnvilChunkView} typed view. Returns {@code null} if the
     * chunk is not present in the region (same semantics as {@link #readChunk}).
     *
     * <p>This overload is the preferred entry point for the verdict layer - it
     * exposes only the fields the pre-filter actually consults, and shields callers from
     * the {@link Nbt.NbtList} / {@link LinkedHashMap} wire shape.
     */
    public static AnvilChunkView readChunkView(byte[] regionBytes, int cx, int cz) throws IOException {
        ChunkEntry entry = readChunk(regionBytes, cx, cz);
        if (entry == null) return null;
        return toView(entry.root);
    }

    // ----------------------------------------------------------- column probe (ADR-016)

    /**
     * Reads the chunk at region-local {@code (cx, cz)} and returns a lean
     * {@link ColumnProbe} covering the caller-supplied world-Y window {@code [minY, maxY]}.
     *
     * <p>Semantically equivalent to {@code readChunkView(...).blockIdAt(8, y, 8)} /
     * {@code .getBiomeAt(8, y, 8)} / {@code .getSurfaceHeight(8, 8)} for every Y in the
     * window - the probe exists to make those queries cheaper, not to change what they
     * answer. Internally the NBT parse skips every root child we do not need
     * ({@code block_entities}, {@code structures},
     * {@code Entities}, tick queues, {@code Heightmaps} entries other than
     * {@code MOTION_BLOCKING_NO_LEAVES}, etc.) and every section child other than
     * {@code Y}, {@code block_states}, and {@code biomes}.
     *
     * @return the probe, or {@code null} if the chunk is not present in the region
     * @throws UnsupportedAnvilFormatException if the compression mode is not supported
     * @throws IOException                     on malformed headers or NBT
     */
    public static ColumnProbe readColumnProbe(byte[] regionBytes, int cx, int cz, int minY, int maxY)
            throws IOException {
        if (minY > maxY) {
            throw new IllegalArgumentException("minY=" + minY + " must be <= maxY=" + maxY);
        }
        RawChunk raw = readRawChunk(regionBytes, cx, cz);
        if (raw == null) return null;

        LinkedHashMap<String, Object> root = Nbt.readRootCompoundSelective(
                raw.nbtBytes, AnvilReader::columnProbeDecision);

        long[] heightmap = getMotionBlockingNoLeaves(root);
        Nbt.NbtList sections = getSections(root);
        List<PaletteSection> sectionsOut;
        List<BiomePaletteSection> biomeSectionsOut;
        if (sections == null || sections.items.isEmpty()) {
            sectionsOut = Collections.emptyList();
            biomeSectionsOut = Collections.emptyList();
        } else {
            sectionsOut = new ArrayList<>(sections.items.size());
            biomeSectionsOut = new ArrayList<>(sections.items.size());
            for (Object rawSection : sections.items) {
                PaletteSection ps = sectionFromCompound(rawSection);
                if (ps != null) sectionsOut.add(ps);
                BiomePaletteSection bs = biomeSectionFromCompound(rawSection);
                if (bs != null) biomeSectionsOut.add(bs);
            }
        }

        int heightmapTop = computeHeightmapTop(heightmap, sectionsOut);
        return new ColumnProbe(minY, maxY, heightmapTop,
                Collections.unmodifiableList(sectionsOut),
                Collections.unmodifiableList(biomeSectionsOut));
    }

    /**
     * Selective-parser decision for {@link #readColumnProbe}. Keeps only the subset of
     * the chunk root that center-column queries need: {@code DataVersion} (for future
     * version-gating), {@code Heightmaps.MOTION_BLOCKING_NO_LEAVES}, and each section's
     * {@code Y}/{@code block_states}/{@code biomes}. Everything else is skipped without
     * allocation. Sections outside the probe's Y window are dropped entirely.
     */
    private static Nbt.SelectiveFilter.Decision columnProbeDecision(
            List<String> path, String name, byte type) {
        int depth = path.size();
        if (depth == 0) {
            // Root compound
            if ("sections".equals(name) && type == Nbt.TAG_LIST) return Nbt.SelectiveFilter.Decision.RECURSE;
            if ("Heightmaps".equals(name) && type == Nbt.TAG_COMPOUND) return Nbt.SelectiveFilter.Decision.RECURSE;
            if ("DataVersion".equals(name)) return Nbt.SelectiveFilter.Decision.KEEP;
            return Nbt.SelectiveFilter.Decision.SKIP;
        }
        String top = path.get(0);
        if ("Heightmaps".equals(top)) {
            // Only the surface heightmap matters; every other heightmap variant is skipped.
            return "MOTION_BLOCKING_NO_LEAVES".equals(name)
                    ? Nbt.SelectiveFilter.Decision.KEEP
                    : Nbt.SelectiveFilter.Decision.SKIP;
        }
        if ("sections".equals(top)) {
            if (depth == 1) {
                // Each list element is a compound - recurse to filter its children.
                return Nbt.SelectiveFilter.Decision.RECURSE;
            }
            if (depth == 2) {
                // Inside sections[*]: keep Y, recurse block_states/biomes, skip lights/etc.
                if ("Y".equals(name)) return Nbt.SelectiveFilter.Decision.KEEP;
                if ("block_states".equals(name) || "biomes".equals(name)) {
                    return Nbt.SelectiveFilter.Decision.RECURSE;
                }
                return Nbt.SelectiveFilter.Decision.SKIP;
            }
            if (depth == 3) {
                // Inside sections[*].block_states or sections[*].biomes: keep palette + data.
                if ("palette".equals(name) || "data".equals(name)) {
                    return Nbt.SelectiveFilter.Decision.KEEP;
                }
                return Nbt.SelectiveFilter.Decision.SKIP;
            }
        }
        return Nbt.SelectiveFilter.Decision.SKIP;
    }

    /**
     * Extracts the surface Y at {@code (8, 8)} from a decoded {@code MOTION_BLOCKING_NO_LEAVES}
     * heightmap, using the same bit-packing + floor convention as
     * {@link AnvilChunkView#getSurfaceHeight(int, int)}. Returns {@link Integer#MIN_VALUE}
     * when the heightmap or sections list is absent/empty.
     */
    private static int computeHeightmapTop(long[] heightmap, List<PaletteSection> sectionsOut) {
        if (heightmap == null || heightmap.length == 0) return Integer.MIN_VALUE;
        int floor = Integer.MAX_VALUE;
        for (PaletteSection s : sectionsOut) {
            if (s.sectionY() < floor) floor = s.sectionY();
        }
        if (floor == Integer.MAX_VALUE) return Integer.MIN_VALUE;
        int minHeight = floor * 16;
        int bits = 9;
        int entriesPerLong = 64 / bits;
        int columnIndex = (ColumnProbe.CENTER_LOCAL_Z & 15) * 16 + (ColumnProbe.CENTER_LOCAL_X & 15);
        int longIdx = columnIndex / entriesPerLong;
        if (longIdx >= heightmap.length) return Integer.MIN_VALUE;
        int slot = columnIndex - longIdx * entriesPerLong;
        long mask = (1L << bits) - 1L;
        int rawVal = (int) ((heightmap[longIdx] >>> (slot * bits)) & mask);
        if (rawVal <= 0) return minHeight;
        return minHeight + rawVal - 1;
    }

    /**
     * Lifts an already-parsed chunk root compound into an {@link AnvilChunkView}. Exposed
     * for tests and for callers that want to inspect the raw root before deciding to build
     * the typed view.
     *
     * <p>Robust to malformed sections: entries missing {@code Y} or {@code block_states}
     * are silently skipped rather than propagated as exceptions, consistent with ADR-016's
     * "malformed → UNKNOWN, never crash" posture.
     */
    public static AnvilChunkView toView(LinkedHashMap<String, Object> root) {
        int dataVersion = getDataVersion(root);
        long[] heightmap = getMotionBlockingNoLeaves(root);
        Nbt.NbtList sections = getSections(root);
        List<PaletteSection> out;
        List<BiomePaletteSection> biomeOut;
        if (sections == null || sections.items.isEmpty()) {
            out = Collections.emptyList();
            biomeOut = Collections.emptyList();
        } else {
            out = new ArrayList<>(sections.items.size());
            biomeOut = new ArrayList<>(sections.items.size());
            for (Object raw : sections.items) {
                PaletteSection ps = sectionFromCompound(raw);
                if (ps != null) out.add(ps);
                // Biome container is decoded independently: a section may carry a
                // valid biome palette even if its block_states is missing, and vice
                // versa (malformed-section tolerance, ADR-016 (biome) §5.1 trust model).
                BiomePaletteSection bs = biomeSectionFromCompound(raw);
                if (bs != null) biomeOut.add(bs);
            }
        }
        return new AnvilChunkView(
                dataVersion,
                Collections.unmodifiableList(out),
                heightmap,
                Collections.unmodifiableList(biomeOut));
    }

    @SuppressWarnings("unchecked")
    private static PaletteSection sectionFromCompound(Object raw) {
        if (!(raw instanceof Map)) return null;
        Map<String, Object> sec = (Map<String, Object>) raw;

        Object yObj = sec.get("Y");
        if (!(yObj instanceof Number)) return null;
        int sectionY = ((Number) yObj).intValue();

        Object bsObj = sec.get("block_states");
        if (!(bsObj instanceof Map)) return null;
        Map<String, Object> blockStates = (Map<String, Object>) bsObj;

        Object palObj = blockStates.get("palette");
        if (!(palObj instanceof Nbt.NbtList)) return null;
        Nbt.NbtList palList = (Nbt.NbtList) palObj;
        if (palList.items.isEmpty()) return null;

        List<String> paletteNames = new ArrayList<>(palList.items.size());
        for (Object entry : palList.items) {
            if (!(entry instanceof Map)) return null;
            Object name = ((Map<String, Object>) entry).get("Name");
            if (!(name instanceof String)) return null;
            paletteNames.add((String) name);
        }

        Object dataObj = blockStates.get("data");
        long[] data = (dataObj instanceof long[]) ? (long[]) dataObj : null;

        return new PaletteSection(sectionY, Collections.unmodifiableList(paletteNames), data);
    }

    /**
     * Lifts a raw section compound's {@code biomes} container into a
     * {@link BiomePaletteSection}, or returns {@code null} if the container is
     * absent / structurally malformed. Decode failures are silent (per
     * ADR-016 (biome) §8): biome naming is advisory, not a safety signal, so a
     * missing/bad biome palette causes the caller to fall through to the live
     * {@code world.getBiome(loc)} getter rather than raise a warning.
     *
     * <p>The biome container has the same wire shape as {@code block_states} except:
     * (1) {@code palette[i]} is a bare {@code TAG_String} (namespaced identifier)
     * rather than a compound with a {@code Name} field, and (2) the packed {@code data}
     * array is 64 cells (4×4×4) rather than 4096 blocks - see ADR-016 (biome) §3.
     */
    @SuppressWarnings("unchecked")
    private static BiomePaletteSection biomeSectionFromCompound(Object raw) {
        if (!(raw instanceof Map)) return null;
        Map<String, Object> sec = (Map<String, Object>) raw;

        Object yObj = sec.get("Y");
        if (!(yObj instanceof Number)) return null;
        int sectionY = ((Number) yObj).intValue();

        Object biomesObj = sec.get("biomes");
        if (!(biomesObj instanceof Map)) return null;
        Map<String, Object> biomes = (Map<String, Object>) biomesObj;

        Object palObj = biomes.get("palette");
        if (!(palObj instanceof Nbt.NbtList)) return null;
        Nbt.NbtList palList = (Nbt.NbtList) palObj;
        if (palList.items.isEmpty()) return null;

        List<String> paletteNames = new ArrayList<>(palList.items.size());
        for (Object entry : palList.items) {
            // Unlike the block palette, the biome palette stores bare strings, not
            // compounds with a Name field. Reject the section if an entry is not a
            // string - that indicates a format drift we have not characterised.
            if (!(entry instanceof String)) return null;
            paletteNames.add((String) entry);
        }

        Object dataObj = biomes.get("data");
        long[] data = (dataObj instanceof long[]) ? (long[]) dataObj : null;

        return new BiomePaletteSection(sectionY, Collections.unmodifiableList(paletteNames), data);
    }
}
