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
 * {@link org.bukkit.Chunk} (the whole point of the pre-filter is operating on unloaded chunks)
 * and it performs no block-state or biome interpretation beyond what the structural NBT walk
 * provides — those higher-level views are the job of later phases.
 *
 * <p><b>Compression support.</b> Per observed fixtures across 1.20.4 / 1.21.5 / 26.1 (data
 * versions 3465 / 4671 / 4788), vanilla servers ship Anvil chunks as Minecraft compression
 * mode {@code 2} (zlib-wrapped Deflate). Mode {@code 1} (gzip) is supported for forward
 * compatibility. Mode {@code 3} (uncompressed) is supported. Mode {@code 4} (LZ4) and the
 * {@code 0x80}-or'd "external" variants are rejected with {@link UnsupportedAnvilFormatException}
 * so the pre-filter returns {@link Verdict#UNKNOWN} and the live load path takes over — this
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
        if (regionBytes == null || regionBytes.length < SECTOR_SIZE * 2) {
            throw new IOException("Region buffer too short: " + (regionBytes == null ? 0 : regionBytes.length));
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
            throw new IOException("Chunk entry (" + cx + "," + cz + ") spans past end of file: start="
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
            throw new IOException("Implausible declared chunk length " + declaredLength
                    + " for (" + cx + "," + cz + "); sector budget=" + (payloadBudget - 5));
        }
        int compressedLen = declaredLength - 1;

        byte[] nbtBytes = decompress(regionBytes, payloadStart + 5, compressedLen, compressionByte);
        LinkedHashMap<String, Object> root = Nbt.readRootCompound(nbtBytes);
        return new ChunkEntry(compressionByte, declaredLength, root);
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

    // -------------------------------------------------------------------- typed view (Phase 2)

    /**
     * Reads the chunk at region-local coordinates {@code (cx, cz)} and lifts the raw
     * NBT root into an {@link AnvilChunkView} typed view. Returns {@code null} if the
     * chunk is not present in the region (same semantics as {@link #readChunk}).
     *
     * <p>This overload is the preferred entry point for the Phase 3 verdict layer — it
     * exposes only the fields the pre-filter actually consults, and shields callers from
     * the {@link Nbt.NbtList} / {@link LinkedHashMap} wire shape.
     */
    public static AnvilChunkView readChunkView(byte[] regionBytes, int cx, int cz) throws IOException {
        ChunkEntry entry = readChunk(regionBytes, cx, cz);
        if (entry == null) return null;
        return toView(entry.root);
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
                // versa (malformed-section tolerance, ANVIL_BIOME_PLAN §5.1 trust model).
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

        // SkyLight lives on the section compound itself (not under block_states). It is
        // a 2048-byte TAG_Byte_Array nibble-packed to two entries per byte. Absent when
        // the section is fully above the opaque column (vanilla treats absent == 15).
        Object skyObj = sec.get("SkyLight");
        byte[] skyLight = null;
        if (skyObj instanceof byte[] sk && sk.length == 2048) {
            skyLight = sk;
        }

        return new PaletteSection(sectionY, Collections.unmodifiableList(paletteNames), data, skyLight);
    }

    /**
     * Lifts a raw section compound's {@code biomes} container into a
     * {@link BiomePaletteSection}, or returns {@code null} if the container is
     * absent / structurally malformed. Decode failures are silent (per
     * ANVIL_BIOME_PLAN §8): biome naming is advisory, not a safety signal, so a
     * missing/bad biome palette causes the caller to fall through to the live
     * {@code world.getBiome(loc)} getter rather than raise a warning.
     *
     * <p>The biome container has the same wire shape as {@code block_states} except:
     * (1) {@code palette[i]} is a bare {@code TAG_String} (namespaced identifier)
     * rather than a compound with a {@code Name} field, and (2) the packed {@code data}
     * array is 64 cells (4×4×4) rather than 4096 blocks — see ANVIL_BIOME_PLAN §3.
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
            // string — that indicates a format drift we have not characterised.
            if (!(entry instanceof String)) return null;
            paletteNames.add((String) entry);
        }

        Object dataObj = biomes.get("data");
        long[] data = (dataObj instanceof long[]) ? (long[]) dataObj : null;

        return new BiomePaletteSection(sectionY, Collections.unmodifiableList(paletteNames), data);
    }
}
