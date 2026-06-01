package io.github.dailystruggle.rtp.bukkitplatform.anvil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.DeflaterOutputStream;

/**
 * Synthetic region-file generator used by {@link AnvilFixtureParityTest}.
 *
 * <p>Emits a minimally-populated single-chunk {@code r.0.0.mca} in-memory buffer that
 * the production {@link AnvilReader} can parse cleanly. The structure mirrors the
 * subset of the vanilla chunk layout that the pre-filter actually reads:
 * <ul>
 *   <li>{@code DataVersion} — an Int tag,</li>
 *   <li>{@code Heightmaps.MOTION_BLOCKING_NO_LEAVES} — a packed long array,</li>
 *   <li>{@code sections} — a List of section compounds, each with a
 *       {@code block_states.palette} List containing resource-ID strings.</li>
 * </ul>
 *
 * <p>Compression is always zlib (Minecraft mode {@code 2}), matching 100% of the real
 * fixtures committed under {@code src/test/resources/anvil/real/}. The writer is
 * deliberately separate from any production class — it lives only in the test tree so
 * it cannot be reached from runtime code paths.
 */
final class AnvilTestFixtures {

    private AnvilTestFixtures() {}

    /** A single palette entry. {@code name} is the resource id (e.g. {@code "minecraft:stone"}). */
    static LinkedHashMap<String, Object> paletteEntry(String name) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("Name", name);
        return m;
    }

    /**
     * Builds a single chunk-section compound with the given palette. The surrounding
     * {@code data} long-array is intentionally omitted — the pre-filter's palette
     * probe can {@code REJECT} on palette contents alone when every entry is unsafe,
     * without decoding block indices.
     */
    static LinkedHashMap<String, Object> section(byte yIndex, List<String> palette) throws IOException {
        return section(yIndex, palette, null);
    }

    /**
     * Builds a chunk-section compound whose {@code block_states} carries both the palette
     * and an explicit packed {@code data} long-array. {@code data} may be {@code null} to
     * reproduce the single-entry-palette shape vanilla actually writes (no {@code data}
     * tag emitted at all).
     */
    static LinkedHashMap<String, Object> section(byte yIndex, List<String> palette, long[] data) throws IOException {
        LinkedHashMap<String, Object> sec = new LinkedHashMap<>();
        sec.put("Y", yIndex);

        LinkedHashMap<String, Object> blockStates = new LinkedHashMap<>();
        List<Object> entries = new ArrayList<>(palette.size());
        for (String id : palette) entries.add(paletteEntry(id));
        blockStates.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, entries));
        if (data != null) {
            blockStates.put("data", data);
        }
        sec.put("block_states", blockStates);

        return sec;
    }

    /**
     * Packs 4096 palette indices (YZX-major, 16³) into a {@code long[]} using the
     * Anvil 1.16+ no-cross-long layout. Mirrors the decoder used by {@link PackedPaletteDecoder}
     * so tests can round-trip known placements.
     *
     * @param bitsPerEntry bits per entry; must be {@code >= 1} and divide reasonably into 64
     * @param indices      flat palette indices, length must be 4096
     * @return packed data array sized to {@code ceil(4096 / floor(64 / bitsPerEntry))}
     */
    static long[] packIndices(int bitsPerEntry, int[] indices) {
        if (indices.length != 4096) {
            throw new IllegalArgumentException("indices must have length 4096, got " + indices.length);
        }
        int entriesPerLong = 64 / bitsPerEntry;
        int longs = (4096 + entriesPerLong - 1) / entriesPerLong;
        long[] out = new long[longs];
        long mask = (1L << bitsPerEntry) - 1L;
        for (int i = 0; i < 4096; i++) {
            int longIdx = i / entriesPerLong;
            int slot = i - longIdx * entriesPerLong;
            out[longIdx] |= (indices[i] & mask) << (slot * bitsPerEntry);
        }
        return out;
    }

    /**
     * Builds a minimal chunk root compound. {@code motionBlocking} is the raw long
     * array exactly as it would appear on disk; callers can pass a handcrafted packed
     * vector here for parity assertions.
     */
    static LinkedHashMap<String, Object> chunkRoot(
            int dataVersion,
            long[] motionBlocking,
            List<LinkedHashMap<String, Object>> sections) throws IOException {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", dataVersion);

        LinkedHashMap<String, Object> heightmaps = new LinkedHashMap<>();
        heightmaps.put("MOTION_BLOCKING_NO_LEAVES", motionBlocking);
        root.put("Heightmaps", heightmaps);

        List<Object> sectionItems = new ArrayList<>(sections.size());
        sectionItems.addAll(sections);
        root.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, sectionItems));
        return root;
    }

    /**
     * Encodes a root compound into a full single-chunk {@code r.0.0.mca} byte buffer,
     * zlib-compressed. Output layout matches the production format byte-for-byte at the
     * region-header level: 4 KB locations + 4 KB timestamps + chunk payload starting at
     * sector 2, padded to a 4 KB sector boundary.
     */
    static byte[] writeSingleChunkRegion(LinkedHashMap<String, Object> root) throws IOException {
        byte[] nbtBytes = Nbt.writeNamedRoot("", root);

        ByteArrayOutputStream compressed = new ByteArrayOutputStream(nbtBytes.length);
        try (DeflaterOutputStream z = new DeflaterOutputStream(compressed)) {
            z.write(nbtBytes);
        }
        byte[] zlib = compressed.toByteArray();

        // Anvil header: 4 bytes length prefix (bytes following, including compression byte)
        //             + 1 byte compression type + zlib payload.
        int declaredLen = zlib.length + 1;
        int payloadTotal = 4 + 1 + zlib.length;
        int sectorCount = (payloadTotal + 4095) / 4096;
        int fileLen = 4096 + 4096 + sectorCount * 4096;

        byte[] out = new byte[fileLen];

        // Location table entry for chunk (0,0): offset = sector 2, count = sectorCount.
        out[0] = 0;
        out[1] = 0;
        out[2] = 2;
        out[3] = (byte) sectorCount;

        // Timestamp entry (arbitrary fixed value; not consumed by the reader).
        out[4096] = 0x00;
        out[4097] = 0x00;
        out[4098] = 0x00;
        out[4099] = 0x01;

        // Chunk payload at sector 2 (offset 8192).
        int p = 8192;
        out[p]     = (byte) ((declaredLen >>> 24) & 0xFF);
        out[p + 1] = (byte) ((declaredLen >>> 16) & 0xFF);
        out[p + 2] = (byte) ((declaredLen >>> 8)  & 0xFF);
        out[p + 3] = (byte)  (declaredLen         & 0xFF);
        out[p + 4] = 2; // compression type: zlib
        System.arraycopy(zlib, 0, out, p + 5, zlib.length);

        return out;
    }
}
