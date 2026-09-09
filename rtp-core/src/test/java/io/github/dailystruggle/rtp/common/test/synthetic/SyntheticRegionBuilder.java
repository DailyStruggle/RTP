package io.github.dailystruggle.rtp.common.test.synthetic;

import io.github.dailystruggle.rtp.anvil.Nbt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.DeflaterOutputStream;

/**
 * Parametric in-memory MCA (Anvil) region file generator (ADR-090).
 * <p>
 * Emits binary-accurate {@code r.X.Z.mca} byte arrays conforming to the Minecraft Anvil specification:
 * <ul>
 *   <li>4096-byte chunk location table (3 bytes sector offset + 1 byte sector count)</li>
 *   <li>4096-byte chunk timestamp table</li>
 *   <li>4096-byte aligned sectors containing 4-byte length prefix + 1-byte compression type (2 = zlib) + Deflate NBT payload</li>
 * </ul>
 */
public final class SyntheticRegionBuilder {

    private static final int SECTOR_SIZE = 4096;
    private static final int DEFAULT_DATA_VERSION = 3465; // MC 1.20.4 baseline

    private final LinkedHashMap<Integer, ChunkSpec> chunks = new LinkedHashMap<>();

    public static final class ChunkSpec {
        public final int cx;
        public final int cz;
        public final int surfaceY;
        public final String biome;
        public final String surfaceBlock;
        public final String subBlock;
        public final boolean isVoid;

        public ChunkSpec(int cx, int cz, int surfaceY, String biome, String surfaceBlock, String subBlock, boolean isVoid) {
            this.cx = cx;
            this.cz = cz;
            this.surfaceY = surfaceY;
            this.biome = biome;
            this.surfaceBlock = surfaceBlock;
            this.subBlock = subBlock;
            this.isVoid = isVoid;
        }
    }

    public SyntheticRegionBuilder() {}

    /**
     * Adds or overrides a chunk specification at region-local coordinates (cx, cz).
     */
    public SyntheticRegionBuilder setChunk(int cx, int cz, int surfaceY, String biome, String surfaceBlock, String subBlock) {
        if (cx < 0 || cx > 31 || cz < 0 || cz > 31) {
            throw new IllegalArgumentException("Region-local chunk coordinates must be in [0..31]");
        }
        chunks.put((cz << 5) | cx, new ChunkSpec(cx, cz, surfaceY, biome, surfaceBlock, subBlock, false));
        return this;
    }

    /**
     * Adds an empty/void chunk at (cx, cz).
     */
    public SyntheticRegionBuilder setVoidChunk(int cx, int cz) {
        if (cx < 0 || cx > 31 || cz < 0 || cz > 31) {
            throw new IllegalArgumentException("Region-local chunk coordinates must be in [0..31]");
        }
        chunks.put((cz << 5) | cx, new ChunkSpec(cx, cz, 0, "minecraft:the_void", "minecraft:air", "minecraft:air", true));
        return this;
    }

    /**
     * Generates a checkerboard lattice of safe land and hazard (lava/void) chunks.
     * Useful for verifying spatial striding downsampling resilience against aliasing.
     *
     * @param safeStep chunk stride for safe tiles
     */
    public static SyntheticRegionBuilder checkerboard(int safeStep) {
        SyntheticRegionBuilder builder = new SyntheticRegionBuilder();
        for (int cz = 0; cz < 32; cz++) {
            for (int cx = 0; cx < 32; cx++) {
                boolean isSafe = ((cx / safeStep) + (cz / safeStep)) % 2 == 0;
                if (isSafe) {
                    builder.setChunk(cx, cz, 64, "minecraft:plains", "minecraft:grass_block", "minecraft:dirt");
                } else {
                    builder.setChunk(cx, cz, 64, "minecraft:plains", "minecraft:lava", "minecraft:lava");
                }
            }
        }
        return builder;
    }

    /**
     * Generates a step-gradient cliff: chunks with x &lt; splitX are at yLow, x &gt;= splitX are at yHigh.
     */
    public static SyntheticRegionBuilder steepStep(int splitX, int yLow, int yHigh) {
        SyntheticRegionBuilder builder = new SyntheticRegionBuilder();
        for (int cz = 0; cz < 32; cz++) {
            for (int cx = 0; cx < 32; cx++) {
                int y = (cx < splitX) ? yLow : yHigh;
                builder.setChunk(cx, cz, y, "minecraft:plains", "minecraft:grass_block", "minecraft:stone");
            }
        }
        return builder;
    }

    /**
     * Generates a uniform region where every chunk is filled with the specified hazard.
     */
    public static SyntheticRegionBuilder allHazard(String hazardBlock) {
        SyntheticRegionBuilder builder = new SyntheticRegionBuilder();
        for (int cz = 0; cz < 32; cz++) {
            for (int cx = 0; cx < 32; cx++) {
                builder.setChunk(cx, cz, 64, "minecraft:nether_wastes", hazardBlock, hazardBlock);
            }
        }
        return builder;
    }

    /**
     * Builds and serializes the complete binary Anvil (.mca) byte array.
     */
    public byte[] build() throws IOException {
        byte[] locationTable = new byte[SECTOR_SIZE];
        byte[] timestampTable = new byte[SECTOR_SIZE];
        ByteArrayOutputStream chunkPayloads = new ByteArrayOutputStream();

        int currentSector = 2; // Sectors 0 and 1 are location and timestamp headers

        for (int cz = 0; cz < 32; cz++) {
            for (int cx = 0; cx < 32; cx++) {
                int index = (cz << 5) | cx;
                ChunkSpec spec = chunks.get(index);
                if (spec == null) {
                    continue; // Absent chunk
                }

                byte[] compressedChunk = encodeChunk(spec);
                int totalLength = 4 + 1 + compressedChunk.length; // 4 bytes len + 1 byte compression + payload
                int sectorsNeeded = (totalLength + SECTOR_SIZE - 1) / SECTOR_SIZE;

                // Write location entry: 3 bytes sector offset + 1 byte sector count
                int locOffset = index * 4;
                locationTable[locOffset] = (byte) ((currentSector >>> 16) & 0xFF);
                locationTable[locOffset + 1] = (byte) ((currentSector >>> 8) & 0xFF);
                locationTable[locOffset + 2] = (byte) (currentSector & 0xFF);
                locationTable[locOffset + 3] = (byte) (sectorsNeeded & 0xFF);

                // Write timestamp entry (arbitrary non-zero epoch)
                timestampTable[locOffset] = 0;
                timestampTable[locOffset + 1] = 0;
                timestampTable[locOffset + 2] = 0x20;
                timestampTable[locOffset + 3] = 0x01;

                // Write chunk sector payload
                int declaredLen = compressedChunk.length + 1; // includes compression byte
                ByteBuffer header = ByteBuffer.allocate(5);
                header.putInt(declaredLen);
                header.put((byte) 2); // zlib
                chunkPayloads.write(header.array());
                chunkPayloads.write(compressedChunk);

                // Pad payload up to sector boundary
                int padding = (sectorsNeeded * SECTOR_SIZE) - totalLength;
                if (padding > 0) {
                    chunkPayloads.write(new byte[padding]);
                }

                currentSector += sectorsNeeded;
            }
        }

        byte[] payloads = chunkPayloads.toByteArray();
        byte[] region = new byte[SECTOR_SIZE * 2 + payloads.length];
        System.arraycopy(locationTable, 0, region, 0, SECTOR_SIZE);
        System.arraycopy(timestampTable, 0, region, SECTOR_SIZE, SECTOR_SIZE);
        System.arraycopy(payloads, 0, region, SECTOR_SIZE * 2, payloads.length);
        return region;
    }

    private byte[] encodeChunk(ChunkSpec spec) throws IOException {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", DEFAULT_DATA_VERSION);

        // Encode Heightmap
        long[] motionBlocking = packHeightmap(spec.surfaceY);
        LinkedHashMap<String, Object> heightmaps = new LinkedHashMap<>();
        heightmaps.put("MOTION_BLOCKING_NO_LEAVES", motionBlocking);
        root.put("Heightmaps", heightmaps);

        // Encode Sections
        List<Object> sections = new ArrayList<>();
        if (!spec.isVoid) {
            int targetSecY = spec.surfaceY >> 4;
            // Build surface section
            LinkedHashMap<String, Object> surfSec = new LinkedHashMap<>();
            surfSec.put("Y", (byte) targetSecY);

            LinkedHashMap<String, Object> blockStates = new LinkedHashMap<>();
            List<Object> palEntries = new ArrayList<>();
            palEntries.add(paletteEntry("minecraft:air"));
            palEntries.add(paletteEntry(spec.surfaceBlock));
            palEntries.add(paletteEntry(spec.subBlock));
            blockStates.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, palEntries));
            surfSec.put("block_states", blockStates);

            // Biome palette
            LinkedHashMap<String, Object> biomes = new LinkedHashMap<>();
            List<Object> biomePal = new ArrayList<>();
            biomePal.add(spec.biome);
            biomes.put("palette", new Nbt.NbtList(Nbt.TAG_STRING, biomePal));
            surfSec.put("biomes", biomes);

            sections.add(surfSec);
        }
        root.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, sections));

        // Serialize NBT and Deflate (zlib)
        byte[] nbtBytes = Nbt.writeNamedRoot("", root);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream(nbtBytes.length);
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            deflater.write(nbtBytes);
        }
        return compressed.toByteArray();
    }

    private static LinkedHashMap<String, Object> paletteEntry(String name) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("Name", name);
        return m;
    }

    private static long[] packHeightmap(int surfaceY) {
        // 256 entries packed at 9 bits per entry = 37 longs (for heights up to 511)
        long[] data = new long[37];
        int bitsPerEntry = 9;
        int entriesPerLong = 64 / bitsPerEntry; // 7
        long mask = (1L << bitsPerEntry) - 1L;
        long val = Math.max(0, surfaceY) & mask;

        for (int i = 0; i < 256; i++) {
            int longIdx = i / entriesPerLong;
            int slot = i % entriesPerLong;
            data[longIdx] |= (val << (slot * bitsPerEntry));
        }
        return data;
    }
}
