package io.github.dailystruggle.anvil.stream;

import io.github.dailystruggle.anvil.AnvilChunkView;
import io.github.dailystruggle.anvil.BiomePaletteSection;
import io.github.dailystruggle.anvil.LcaSectionBacking;
import io.github.dailystruggle.anvil.PackedPaletteDecoder;
import io.github.dailystruggle.anvil.PaletteSection;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Platform-neutral `.lca` live wire streaming codec (`LCAB` `0x4C434142`, `LCAS` `0x4C434153`,
 * `LCD2` `0x4C44`, `LCAD` `0x4C434144`, `LCAG` `0x4C434147`) and Extended Hamming `(72, 64)`
 * `SEC-DED-64` syndrome verifier (`leafmap-ADR-004`, `leafanvil-ADR-003`, `anvil-api-ADR-003`).
 *
 * <p>Provides zero-translation wire framing for full `.lca` chunk batches (`EntryFullChunk` at
 * {@code < 1/5} equivalent `.mca` byte size when heightmaps are omitted and `LCD2` intra-batch
 * compression is applied), incremental section patch deltas (`EntryPatchDelta` carrying only
 * mutated $16^3$ {@link SectionWireSlice}s), `2-byte` clean revisit acknowledgments
 * (`EntryCleanAck`), and per-region `BlockState`/`Biome` palette dictionary synchronization.</p>
 *
 * @governedBy leafmap-ADR-004
 * @governedBy leafanvil-ADR-003
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-115
 * @trace REQ-MAP-STREAM-001
 * @trace REQ-MAP-STREAM-002
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class LcaRemoteStreamCodec {

    public static final int PROTOCOL_VERSION = 1;

    /** Magic header for `StreamChunkBatchPayload` (`"LCAB"`). */
    public static final int BATCH_MAGIC = 0x4C434142;

    /** Magic header for `InterServerChunkUpdatePayload` (`"LCAS"`). */
    public static final int INTER_SERVER_UPDATE_MAGIC = 0x4C434153;

    /** Magic header for `LcaRegionDictSyncFrame` (`"LCAD"`). */
    public static final int DICT_SYNC_MAGIC = 0x4C434144;

    /** Magic header for `ChunkDigestPayload` (`"LCAG"`). */
    public static final int DIGEST_MAGIC = 0x4C434147;

    /** Maximum number of chunk entries in a single `LCAB` paced batch. */
    public static final int MAX_BATCH_CHUNKS = 64;

    /** Maximum number of modified sections permitted in an `EntryPatchDelta` before full-chunk fallback. */
    public static final int MAX_PATCH_DELTA_SECTIONS = 4;

    /** Maximum decompressed `LCAB` / `LCAS` wire payload size (`8 MiB`). */
    public static final int MAX_DECOMPRESSED_BATCH_BYTES = 8 * 1024 * 1024;

    /** Entry type `0`: `2-byte` clean revisit acknowledgment (`ENTRY_CLEAN_ACK`). */
    public static final int ENTRY_CLEAN_ACK = 0;

    /** Entry type `1`: compact `1..4`-section patch delta (`ENTRY_PATCH_DELTA`). */
    public static final int ENTRY_PATCH_DELTA = 1;

    /** Entry type `2`: full `.lca` chunk column (`ENTRY_FULL_CHUNK`). */
    public static final int ENTRY_FULL_CHUNK = 2;

    private LcaRemoteStreamCodec() {
    }

    // =========================================================================
    // 1. Entry Header, Dirty Overlay & Directory Word Bit-Packing Helpers
    // =========================================================================

    /**
     * Packs a 12-bit voxel position (`0..4095`) and 19-bit block-state ID into a non-zero
     * 32-bit `dirtyOverlay` entry (`((pos12 + 1) << 19) | (stateId19 & 0x7FFFF)`).
     */
    public static int packDirtyOverlayEntry(int pos12, int stateId19) {
        if (pos12 < 0 || pos12 > 0xFFF) {
            throw new IllegalArgumentException("pos12 out of range [0..4095]: " + pos12);
        }
        if (stateId19 < 0 || stateId19 > 0x7FFFF) {
            throw new IllegalArgumentException("stateId19 out of range [0..524287]: " + stateId19);
        }
        return ((pos12 + 1) << 19) | (stateId19 & 0x7FFFF);
    }

    public static int unpackDirtyOverlayPos12(int packedEntry32) {
        return ((packedEntry32 >>> 19) & 0x1FFF) - 1;
    }

    public static int unpackDirtyOverlayStateId19(int packedEntry32) {
        return packedEntry32 & 0x7FFFF;
    }

    public static int toLocalChunkIndex10(int chunkX, int chunkZ) {
        return ((chunkZ & 31) << 5) | (chunkX & 31);
    }

    public static short packEntryHeaderWord16(int entryType, int flags4, int localChunkIndex10) {
        if (entryType < 0 || entryType > 3) {
            throw new IllegalArgumentException("entryType out of range [0..3]: " + entryType);
        }
        if (localChunkIndex10 < 0 || localChunkIndex10 > 0x3FF) {
            throw new IllegalArgumentException("localChunkIndex10 out of range [0..1023]: " + localChunkIndex10);
        }
        return (short) (((entryType & 0x3) << 14)
                | ((flags4 & 0xF) << 10)
                | (localChunkIndex10 & 0x3FF));
    }

    public static int unpackEntryType(short headerWord16) {
        return (headerWord16 >>> 14) & 0x3;
    }

    public static int unpackEntryFlags4(short headerWord16) {
        return (headerWord16 >>> 10) & 0xF;
    }

    public static int unpackLocalChunkIndex10(short headerWord16) {
        return headerWord16 & 0x3FF;
    }

    public static short encodeUniformDirectoryWord(int skyConst4, int blkConst4, int dictId13) {
        return (short) (0x8000
                | (((skyConst4 == 15 || skyConst4 == 0) ? 1 : 0) << 14)
                | (((blkConst4 == 0) ? 1 : 0) << 13)
                | (dictId13 & 0x1FFF));
    }

    public static boolean isUniformDirectoryWord(short dirWord16) {
        return (dirWord16 & 0x8000) != 0;
    }

    // =========================================================================
    // 2. Shared Wire Records (`SectionWireSlice`, `BatchChunkEntry`, `LcaRegionDictSyncFrame`)
    // =========================================================================

    /**
     * Zero-copy section slice for wire transmission: carries either an inline uniform directory
     * word (`bit 15 == 1`, `0-byte` body) or a raw `.lca` `baseFrame` slice plus optional compact
     * `dirtyOverlay` entries.
     */
    public record SectionWireSlice(
            int sectionIndex,
            short directoryWord16,
            byte[] baseFrameSlice,
            int[] dirtyOverlayEntries
    ) {
        public SectionWireSlice {
            if (sectionIndex < 0 || sectionIndex >= 32) {
                throw new IllegalArgumentException("sectionIndex out of range [0..31]: " + sectionIndex);
            }
            baseFrameSlice = baseFrameSlice == null ? new byte[0] : baseFrameSlice.clone();
            dirtyOverlayEntries = dirtyOverlayEntries == null ? new int[0] : dirtyOverlayEntries.clone();
        }

        public static SectionWireSlice uniform(int sectionIndex, int skyConst4, int blkConst4, int dictId13) {
            return new SectionWireSlice(
                    sectionIndex,
                    encodeUniformDirectoryWord(skyConst4, blkConst4, dictId13),
                    new byte[0],
                    new int[0]
            );
        }

        public static SectionWireSlice nonUniform(
                int sectionIndex,
                byte[] baseFrameBytes,
                int[] dirtyOverlayEntries
        ) {
            Objects.requireNonNull(baseFrameBytes, "baseFrameBytes");
            if (baseFrameBytes.length == 0 || baseFrameBytes.length > 0x7FFF) {
                throw new IllegalArgumentException(
                        "Non-uniform baseFrameBytes length must be in [1..32767]: " + baseFrameBytes.length);
            }
            short dirWord = (short) (baseFrameBytes.length & 0x7FFF);
            return new SectionWireSlice(sectionIndex, dirWord, baseFrameBytes, dirtyOverlayEntries);
        }

        public boolean isUniform() {
            return isUniformDirectoryWord(directoryWord16);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof SectionWireSlice that)) {
                return false;
            }
            return Arrays.equals(baseFrameSlice, that.baseFrameSlice)
                    && Arrays.equals(dirtyOverlayEntries, that.dirtyOverlayEntries)
                    && Objects.equals(List.of(sectionIndex, directoryWord16), List.of(that.sectionIndex, that.directoryWord16));
        }

        @Override
        public int hashCode() {
            int result = 31 * Integer.hashCode(sectionIndex) + Short.hashCode(directoryWord16);
            result = 31 * result + Arrays.hashCode(baseFrameSlice);
            return 31 * result + Arrays.hashCode(dirtyOverlayEntries);
        }

        @Override
        public String toString() {
            return "SectionWireSlice[sectionIndex=" + sectionIndex
                    + ", directoryWord16=" + directoryWord16
                    + ", baseFrameSlice=" + Arrays.toString(baseFrameSlice)
                    + ", dirtyOverlayEntries=" + Arrays.toString(dirtyOverlayEntries) + "]";
        }
    }

    /**
     * Sealed union of chunk entries inside a paced `.lca` batch (`LCAB` or `LCAS`).
     */
    public sealed interface BatchChunkEntry
            permits EntryCleanAck, EntryPatchDelta, EntryFullChunk {
        int entryType();

        int localChunkIndex10();
    }

    /**
     * `ENTRY_CLEAN_ACK = 0`: `2-byte` compact revisit acknowledgment when the receiver's cached
     * `(lastUpdateTick, secDedSyndrome64)` matches the upstream chunk.
     */
    public record EntryCleanAck(int localChunkIndex10) implements BatchChunkEntry {
        public EntryCleanAck {
            if (localChunkIndex10 < 0 || localChunkIndex10 > 0x3FF) {
                throw new IllegalArgumentException("localChunkIndex10 out of range [0..1023]: " + localChunkIndex10);
            }
        }

        @Override
        public int entryType() {
            return ENTRY_CLEAN_ACK;
        }

        public short toWord16() {
            return packEntryHeaderWord16(ENTRY_CLEAN_ACK, 0, localChunkIndex10);
        }

        public byte[] toBytes() {
            short word = toWord16();
            return new byte[]{(byte) (word >>> 8), (byte) word};
        }

        public static EntryCleanAck fromBytes(byte[] bytes) {
            if (bytes == null || bytes.length != 2) {
                throw new IllegalArgumentException("EntryCleanAck requires exactly 2 bytes");
            }
            short word = (short) (((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF));
            if (unpackEntryType(word) != ENTRY_CLEAN_ACK) {
                throw new IllegalArgumentException("Not an ENTRY_CLEAN_ACK word: 0x" + Integer.toHexString(word & 0xFFFF));
            }
            return new EntryCleanAck(unpackLocalChunkIndex10(word));
        }
    }

    /**
     * `ENTRY_PATCH_DELTA = 1`: compact revisit delta entry carrying `localChunkIndex10`,
     * `newLastUpdateTick`, `newSecDedSyndrome64`, `dirtySectionBitmask32`, and only the `1..4`
     * modified `SectionWireSlice`s.
     */
    public record EntryPatchDelta(
            int localChunkIndex10,
            long newLastUpdateTick,
            long newSecDedSyndrome64,
            int dirtySectionBitmask32,
            List<SectionWireSlice> modifiedSections
    ) implements BatchChunkEntry {
        public EntryPatchDelta {
            if (localChunkIndex10 < 0 || localChunkIndex10 > 0x3FF) {
                throw new IllegalArgumentException("localChunkIndex10 out of range [0..1023]: " + localChunkIndex10);
            }
            Objects.requireNonNull(modifiedSections, "modifiedSections");
            int bitCount = Integer.bitCount(dirtySectionBitmask32);
            if (bitCount < 1 || bitCount > MAX_PATCH_DELTA_SECTIONS || bitCount != modifiedSections.size()) {
                throw new IllegalArgumentException(
                        "EntryPatchDelta requires 1.." + MAX_PATCH_DELTA_SECTIONS
                                + " modified sections matching dirtySectionBitmask32; got bitCount="
                                + bitCount + ", listSize=" + modifiedSections.size());
            }
            modifiedSections = List.copyOf(modifiedSections);
        }

        @Override
        public int entryType() {
            return ENTRY_PATCH_DELTA;
        }
    }

    /**
     * `ENTRY_FULL_CHUNK = 2`: full `.lca` chunk entry carrying the section directory, non-uniform
     * `baseFrame` slices, optional `dirtyOverlay` entries, and optional heightmap delta bytes.
     */
    public record EntryFullChunk(
            int chunkX,
            int chunkZ,
            long lastUpdateTick,
            long secDedSyndrome64,
            List<SectionWireSlice> sections,
            byte[] heightmapDeltaBytes
    ) implements BatchChunkEntry {
        public EntryFullChunk {
            Objects.requireNonNull(sections, "sections");
            sections = List.copyOf(sections);
            heightmapDeltaBytes = heightmapDeltaBytes == null ? new byte[0] : heightmapDeltaBytes.clone();
        }

        public static EntryFullChunk createWithComputedSyndrome(
                int chunkX,
                int chunkZ,
                long lastUpdateTick,
                List<SectionWireSlice> sections,
                byte[] heightmapDeltaBytes
        ) {
            byte[] canonicalSlab = serializeCanonicalChunkSlab(chunkX, chunkZ, sections, heightmapDeltaBytes);
            long syndrome = computeSecDedSyndrome64(lastUpdateTick, canonicalSlab);
            return new EntryFullChunk(chunkX, chunkZ, lastUpdateTick, syndrome, sections, heightmapDeltaBytes);
        }

        @Override
        public int entryType() {
            return ENTRY_FULL_CHUNK;
        }

        @Override
        public int localChunkIndex10() {
            return toLocalChunkIndex10(chunkX, chunkZ);
        }

        public byte[] toCanonicalSlabBytes() {
            return serializeCanonicalChunkSlab(chunkX, chunkZ, sections, heightmapDeltaBytes);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof EntryFullChunk that)) {
                return false;
            }
            return Arrays.equals(heightmapDeltaBytes, that.heightmapDeltaBytes)
                    && Objects.equals(
                            List.of(chunkX, chunkZ, lastUpdateTick, secDedSyndrome64, sections),
                            List.of(that.chunkX, that.chunkZ, that.lastUpdateTick, that.secDedSyndrome64, that.sections));
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(chunkX, chunkZ, lastUpdateTick, secDedSyndrome64, sections);
            return 31 * result + Arrays.hashCode(heightmapDeltaBytes);
        }

        @Override
        public String toString() {
            return "EntryFullChunk[chunkX=" + chunkX
                    + ", chunkZ=" + chunkZ
                    + ", lastUpdateTick=" + lastUpdateTick
                    + ", secDedSyndrome64=" + secDedSyndrome64
                    + ", sections=" + sections
                    + ", heightmapDeltaBytes=" + Arrays.toString(heightmapDeltaBytes) + "]";
        }
    }

    /**
     * Per-region dictionary synchronization frame (`LCAD` `0x4C434144`), mapping region-local
     * `BlockState` and `Biome` dictionary IDs to either integer registry IDs or canonical string
     * identifiers (`"minecraft:stone"`, `"modid:custom_block[facing=north]"`).
     */
    public record LcaRegionDictSyncFrame(
            String serverId,
            String world,
            int regionX,
            int regionZ,
            int[] dictIdToBlockStateRegistryId,
            int[] dictIdToBiomeRegistryId,
            List<String> dictIdToBlockStateName,
            List<String> dictIdToBiomeName
    ) {
        public LcaRegionDictSyncFrame {
            serverId = (serverId == null || serverId.isBlank()) ? "local" : serverId.trim();
            world = (world == null || world.isBlank()) ? "world" : world.trim();
            dictIdToBlockStateRegistryId = dictIdToBlockStateRegistryId == null
                    ? new int[0]
                    : dictIdToBlockStateRegistryId.clone();
            dictIdToBiomeRegistryId = dictIdToBiomeRegistryId == null
                    ? new int[0]
                    : dictIdToBiomeRegistryId.clone();
            dictIdToBlockStateName = dictIdToBlockStateName == null
                    ? List.of()
                    : List.copyOf(dictIdToBlockStateName);
            dictIdToBiomeName = dictIdToBiomeName == null
                    ? List.of()
                    : List.copyOf(dictIdToBiomeName);
        }

        public byte[] toBytes() {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
            try (DataOutputStream out = new DataOutputStream(baos)) {
                out.writeInt(DICT_SYNC_MAGIC);
                writeVarInt(out, regionX);
                writeVarInt(out, regionZ);
                writeDeltaVarIntArray(out, dictIdToBlockStateRegistryId);
                writeDeltaVarIntArray(out, dictIdToBiomeRegistryId);
                out.writeUTF(serverId);
                out.writeUTF(world);
                writeVarInt(out, dictIdToBlockStateName.size());
                for (String s : dictIdToBlockStateName) {
                    out.writeUTF(s == null ? "minecraft:air" : s);
                }
                writeVarInt(out, dictIdToBiomeName.size());
                for (String b : dictIdToBiomeName) {
                    out.writeUTF(b == null ? "minecraft:plains" : b);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return baos.toByteArray();
        }

        public static LcaRegionDictSyncFrame fromBytes(byte[] bytes) {
            Objects.requireNonNull(bytes, "bytes");
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                int magic = in.readInt();
                if (magic != DICT_SYNC_MAGIC) {
                    throw new IllegalArgumentException(
                            "Invalid LcaRegionDictSyncFrame magic: 0x" + Integer.toHexString(magic));
                }
                int regionX = readVarInt(in);
                int regionZ = readVarInt(in);
                int[] blockMap = readDeltaVarIntArray(in);
                int[] biomeMap = readDeltaVarIntArray(in);
                String serverId = "local";
                String world = "world";
                List<String> blockNames = List.of();
                List<String> biomeNames = List.of();
                if (in.available() > 0) {
                    serverId = in.readUTF();
                    world = in.readUTF();
                    int bCount = readVarInt(in);
                    List<String> bList = new ArrayList<>(Math.max(0, bCount));
                    for (int i = 0; i < bCount; i++) {
                        bList.add(in.readUTF());
                    }
                    blockNames = bList;
                    int bioCount = readVarInt(in);
                    List<String> bioList = new ArrayList<>(Math.max(0, bioCount));
                    for (int i = 0; i < bioCount; i++) {
                        bioList.add(in.readUTF());
                    }
                    biomeNames = bioList;
                }
                return new LcaRegionDictSyncFrame(
                        serverId, world, regionX, regionZ, blockMap, biomeMap, blockNames, biomeNames);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof LcaRegionDictSyncFrame that)) {
                return false;
            }
            return Arrays.equals(dictIdToBlockStateRegistryId, that.dictIdToBlockStateRegistryId)
                    && Arrays.equals(dictIdToBiomeRegistryId, that.dictIdToBiomeRegistryId)
                    && Objects.equals(
                            List.of(serverId, world, regionX, regionZ, dictIdToBlockStateName, dictIdToBiomeName),
                            List.of(that.serverId, that.world, that.regionX, that.regionZ, that.dictIdToBlockStateName, that.dictIdToBiomeName));
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(serverId, world, regionX, regionZ, dictIdToBlockStateName, dictIdToBiomeName);
            result = 31 * result + Arrays.hashCode(dictIdToBlockStateRegistryId);
            return 31 * result + Arrays.hashCode(dictIdToBiomeRegistryId);
        }

        @Override
        public String toString() {
            return "LcaRegionDictSyncFrame[serverId=" + serverId
                    + ", world=" + world
                    + ", regionX=" + regionX
                    + ", regionZ=" + regionZ
                    + ", dictIdToBlockStateRegistryId=" + Arrays.toString(dictIdToBlockStateRegistryId)
                    + ", dictIdToBiomeRegistryId=" + Arrays.toString(dictIdToBiomeRegistryId)
                    + ", dictIdToBlockStateName=" + dictIdToBlockStateName
                    + ", dictIdToBiomeName=" + dictIdToBiomeName + "]";
        }
    }

    /**
     * Batch of `1..64` `.lca` chunk entries (`LCAB` `0x4C434142`) for a single `(regionX, regionZ)`.
     */
    public record StreamChunkBatchPayload(
            String world,
            int regionX,
            int regionZ,
            List<BatchChunkEntry> entries
    ) {
        public StreamChunkBatchPayload {
            world = (world == null || world.isBlank()) ? "world" : world.trim();
            Objects.requireNonNull(entries, "entries");
            if (entries.isEmpty() || entries.size() > MAX_BATCH_CHUNKS) {
                throw new IllegalArgumentException(
                        "Batch size must be in [1.." + MAX_BATCH_CHUNKS + "], got " + entries.size());
            }
            entries = List.copyOf(entries);
        }
    }

    /**
     * Single chunk update entry in an `LCAS` (`0x4C434153`) stream synchronization batch, carrying
     * the expected pre-update {@code previousBaselineSecDed64} (`0L` for full-chunk initial handoff)
     * and the {@link BatchChunkEntry}.
     */
    public record StreamChunkDeltaEntry(
            int chunkX,
            int chunkZ,
            long previousBaselineSecDed64,
            BatchChunkEntry chunkEntry
    ) {
        public StreamChunkDeltaEntry {
            Objects.requireNonNull(chunkEntry, "chunkEntry");
        }

        public int localChunkIndex10() {
            return toLocalChunkIndex10(chunkX, chunkZ);
        }
    }

    /**
     * Envelope for `LCAS` (`0x4C434153`) live chunk delta or full-chunk stream frames.
     */
    public record StreamDeltaUpdatePayload(
            String sourceServerId,
            String dimension,
            int regionX,
            int regionZ,
            long epochSequence,
            List<StreamChunkDeltaEntry> entries
    ) {
        public StreamDeltaUpdatePayload {
            Objects.requireNonNull(sourceServerId, "sourceServerId");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(entries, "entries");
            if (entries.isEmpty()) {
                throw new IllegalArgumentException("StreamDeltaUpdatePayload entries must not be empty");
            }
            entries = List.copyOf(entries);
        }
    }

    /**
     * Single chunk digest entry in a `ChunkDigestPayload` (`LCAG`).
     */
    public record ChunkDigestEntry(
            int localChunkIndex10,
            long lastUpdateTick,
            long secDedSyndrome64
    ) {
        public ChunkDigestEntry {
            if (localChunkIndex10 < 0 || localChunkIndex10 > 0x3FF) {
                throw new IllegalArgumentException("localChunkIndex10 out of range [0..1023]: " + localChunkIndex10);
            }
        }
    }

    /**
     * Compact per-region chunk digest (`LCAG` `0x4C434147`) reporting which chunks in `(regionX, regionZ)`
     * are currently held in the receiver's bounded LRU slab pool.
     */
    public record ChunkDigestPayload(
            String dimension,
            int regionX,
            int regionZ,
            List<ChunkDigestEntry> entries
    ) {
        public ChunkDigestPayload {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(entries, "entries");
            entries = List.copyOf(entries);
        }

        public byte[] toBytes() {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(32 + entries.size() * 14);
            try (DataOutputStream out = new DataOutputStream(baos)) {
                out.writeInt(DIGEST_MAGIC);
                out.writeUTF(dimension);
                writeVarInt(out, regionX);
                writeVarInt(out, regionZ);
                writeVarInt(out, entries.size());
                for (ChunkDigestEntry entry : entries) {
                    out.writeShort(entry.localChunkIndex10());
                    writeVarLong(out, entry.lastUpdateTick());
                    out.writeLong(entry.secDedSyndrome64());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return baos.toByteArray();
        }

        public static ChunkDigestPayload fromBytes(byte[] bytes) {
            Objects.requireNonNull(bytes, "bytes");
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                int magic = in.readInt();
                if (magic != DIGEST_MAGIC) {
                    throw new IllegalArgumentException(
                            "Invalid ChunkDigestPayload magic: 0x" + Integer.toHexString(magic));
                }
                String dimension = in.readUTF();
                int regionX = readVarInt(in);
                int regionZ = readVarInt(in);
                int count = readVarInt(in);
                if (count < 0 || count > 1024) {
                    throw new IllegalArgumentException("Digest entry count out of range [0..1024]: " + count);
                }
                List<ChunkDigestEntry> entries = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    int localIndex10 = in.readUnsignedShort() & 0x3FF;
                    long tick = readVarLong(in);
                    long syndrome = in.readLong();
                    entries.add(new ChunkDigestEntry(localIndex10, tick, syndrome));
                }
                return new ChunkDigestPayload(dimension, regionX, regionZ, entries);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    // =========================================================================
    // 3. Wire Serialization, Deserialization & LCD2 Intra-Batch Compression
    // =========================================================================

    public static byte[] serializeRawChunkBatch(
            int regionX,
            int regionZ,
            List<BatchChunkEntry> entries,
            boolean omitHeightmaps
    ) {
        Objects.requireNonNull(entries, "entries");
        ByteArrayOutputStream baos = new ByteArrayOutputStream(entries.size() * 1024);
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeInt(BATCH_MAGIC);
            writeVarInt(out, regionX);
            writeVarInt(out, regionZ);
            out.writeByte(entries.size());
            for (BatchChunkEntry entry : entries) {
                writeEntryTo(out, entry, omitHeightmaps);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return baos.toByteArray();
    }

    public static byte[] encodeChunkBatch(
            int regionX,
            int regionZ,
            List<BatchChunkEntry> entries,
            boolean omitHeightmaps,
            boolean lcd2Compress
    ) throws IOException {
        byte[] raw = serializeRawChunkBatch(regionX, regionZ, entries, omitHeightmaps);
        return lcd2Compress ? encodeLcd2BatchFrame(raw) : raw;
    }

    public static StreamChunkBatchPayload decodeChunkBatch(
            String defaultWorld,
            byte[] wirePayload,
            boolean lcd2Compressed
    ) throws IOException {
        Objects.requireNonNull(wirePayload, "wirePayload");
        byte[] rawBatch = lcd2Compressed
                ? decodeLcd2BatchFrame(wirePayload, MAX_DECOMPRESSED_BATCH_BYTES)
                : wirePayload;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(rawBatch))) {
            int magic = in.readInt();
            if (magic != BATCH_MAGIC) {
                throw new IOException("Invalid LCAB batch magic: 0x" + Integer.toHexString(magic));
            }
            int regionX = readVarInt(in);
            int regionZ = readVarInt(in);
            int entryCount = in.readUnsignedByte();
            if (entryCount < 1 || entryCount > MAX_BATCH_CHUNKS) {
                throw new IOException("Invalid batch entry count: " + entryCount);
            }
            List<BatchChunkEntry> entries = new ArrayList<>(entryCount);
            for (int i = 0; i < entryCount; i++) {
                entries.add(readEntryFrom(in, regionX, regionZ));
            }
            return new StreamChunkBatchPayload(defaultWorld, regionX, regionZ, entries);
        }
    }

    public static byte[] encodeDeltaUpdatePayload(
            StreamDeltaUpdatePayload payload,
            boolean omitHeightmaps,
            boolean lcd2Compress
    ) throws IOException {
        Objects.requireNonNull(payload, "payload");
        ByteArrayOutputStream baos = new ByteArrayOutputStream(256 + payload.entries().size() * 128);
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeInt(INTER_SERVER_UPDATE_MAGIC);
            out.writeUTF(payload.sourceServerId());
            out.writeUTF(payload.dimension());
            writeVarInt(out, payload.regionX());
            writeVarInt(out, payload.regionZ());
            writeVarLong(out, payload.epochSequence());
            writeVarInt(out, payload.entries().size());
            for (StreamChunkDeltaEntry delta : payload.entries()) {
                writeVarInt(out, delta.chunkX());
                writeVarInt(out, delta.chunkZ());
                out.writeLong(delta.previousBaselineSecDed64());
                writeEntryTo(out, delta.chunkEntry(), omitHeightmaps);
            }
        }
        byte[] raw = baos.toByteArray();
        return lcd2Compress ? encodeLcd2BatchFrame(raw) : raw;
    }

    public static StreamDeltaUpdatePayload decodeDeltaUpdatePayload(
            byte[] wireBytes,
            boolean lcd2Compressed
    ) throws IOException {
        Objects.requireNonNull(wireBytes, "wireBytes");
        byte[] raw = lcd2Compressed
                ? decodeLcd2BatchFrame(wireBytes, MAX_DECOMPRESSED_BATCH_BYTES)
                : wireBytes;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            int magic = in.readInt();
            if (magic != INTER_SERVER_UPDATE_MAGIC) {
                throw new IOException("Invalid LCAS update magic: 0x" + Integer.toHexString(magic));
            }
            String sourceServerId = in.readUTF();
            String dimension = in.readUTF();
            int regionX = readVarInt(in);
            int regionZ = readVarInt(in);
            long epochSeq = readVarLong(in);
            int count = readVarInt(in);
            if (count < 1 || count > 1024) {
                throw new IOException("Invalid LCAS entry count: " + count);
            }
            List<StreamChunkDeltaEntry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int chunkX = readVarInt(in);
                int chunkZ = readVarInt(in);
                long prevBaseline = in.readLong();
                BatchChunkEntry entry = readEntryFrom(in, regionX, regionZ);
                entries.add(new StreamChunkDeltaEntry(chunkX, chunkZ, prevBaseline, entry));
            }
            return new StreamDeltaUpdatePayload(
                    sourceServerId, dimension, regionX, regionZ, epochSeq, entries);
        }
    }

    /**
     * Auto-detects whether `wireBytes` is `LCD2`-compressed (`0x4C44`), a raw `LCAB` batch
     * (`0x4C434142`), or an `LCAS` delta update (`0x4C434153`) and normalizes it into a
     * {@link StreamDeltaUpdatePayload}.
     */
    public static StreamDeltaUpdatePayload decodeAutoStreamFrame(
            String defaultServerId,
            String defaultWorld,
            byte[] wireBytes
    ) throws IOException {
        Objects.requireNonNull(wireBytes, "wireBytes");
        if (wireBytes.length < 4) {
            throw new IOException("Truncated .lca stream frame (" + wireBytes.length + " bytes)");
        }
        byte[] raw = ((wireBytes[0] & 0xFF) == 0x4C && (wireBytes[1] & 0xFF) == 0x44)
                ? decodeLcd2BatchFrame(wireBytes, MAX_DECOMPRESSED_BATCH_BYTES)
                : wireBytes;
        if (raw.length < 4) {
            throw new IOException("Truncated decompressed .lca stream frame (" + raw.length + " bytes)");
        }
        int magic = readIntBEBatch(raw, 0);
        if (magic == INTER_SERVER_UPDATE_MAGIC) {
            return decodeDeltaUpdatePayload(raw, false);
        }
        if (magic == BATCH_MAGIC) {
            StreamChunkBatchPayload batch = decodeChunkBatch(defaultWorld, raw, false);
            List<StreamChunkDeltaEntry> wrapped = new ArrayList<>(batch.entries().size());
            for (BatchChunkEntry e : batch.entries()) {
                int localIdx = e.localChunkIndex10();
                int cx = (batch.regionX() << 5) | (localIdx & 31);
                int cz = (batch.regionZ() << 5) | ((localIdx >>> 5) & 31);
                wrapped.add(new StreamChunkDeltaEntry(cx, cz, 0L, e));
            }
            return new StreamDeltaUpdatePayload(
                    defaultServerId == null || defaultServerId.isBlank() ? "local" : defaultServerId.trim(),
                    batch.world(),
                    batch.regionX(),
                    batch.regionZ(),
                    1L,
                    wrapped
            );
        }
        throw new IOException("Unsupported .lca stream frame magic: 0x" + Integer.toHexString(magic));
    }

    public static void writeEntryTo(
            DataOutputStream out,
            BatchChunkEntry entry,
            boolean omitHeightmaps
    ) throws IOException {
        if (entry instanceof EntryCleanAck ack) {
            out.writeShort(ack.toWord16());
            return;
        }
        if (entry instanceof EntryPatchDelta patch) {
            short headerWord = packEntryHeaderWord16(
                    ENTRY_PATCH_DELTA,
                    patch.modifiedSections().size() & 0xF,
                    patch.localChunkIndex10()
            );
            out.writeShort(headerWord);
            writeVarLong(out, patch.newLastUpdateTick());
            out.writeLong(patch.newSecDedSyndrome64());
            out.writeInt(patch.dirtySectionBitmask32());
            for (SectionWireSlice slice : patch.modifiedSections()) {
                writeSectionWireSlice(out, slice);
            }
            return;
        }
        if (entry instanceof EntryFullChunk full) {
            int sectionCount = full.sections().size();
            int overlayMask = 0;
            for (int i = 0; i < sectionCount; i++) {
                if (full.sections().get(i).dirtyOverlayEntries().length > 0) {
                    overlayMask |= (1 << i);
                }
            }
            boolean hasHm = !omitHeightmaps && full.heightmapDeltaBytes().length > 0;
            int flags4 = (hasHm ? 0x1 : 0x0) | ((overlayMask != 0) ? 0x2 : 0x0);
            short headerWord = packEntryHeaderWord16(ENTRY_FULL_CHUNK, flags4, full.localChunkIndex10());
            out.writeShort(headerWord);
            writeVarInt(out, full.chunkX());
            writeVarInt(out, full.chunkZ());
            writeVarLong(out, full.lastUpdateTick());
            out.writeLong(full.secDedSyndrome64());
            out.writeByte(sectionCount);
            for (SectionWireSlice slice : full.sections()) {
                out.writeShort(slice.directoryWord16());
            }
            out.writeInt(overlayMask);
            for (SectionWireSlice slice : full.sections()) {
                if (!slice.isUniform()) {
                    byte[] base = slice.baseFrameSlice();
                    int len = slice.directoryWord16() & 0x7FFF;
                    out.write(base, 0, len);
                }
            }
            for (int i = 0; i < sectionCount; i++) {
                if ((overlayMask & (1 << i)) != 0) {
                    int[] entries = full.sections().get(i).dirtyOverlayEntries();
                    writeVarInt(out, entries.length);
                    for (int packed : entries) {
                        out.writeInt(packed);
                    }
                }
            }
            if (hasHm) {
                writeVarInt(out, full.heightmapDeltaBytes().length);
                out.write(full.heightmapDeltaBytes());
            }
        }
    }

    public static BatchChunkEntry readEntryFrom(
            DataInputStream in,
            int regionX,
            int regionZ
    ) throws IOException {
        short headerWord = in.readShort();
        int entryType = unpackEntryType(headerWord);
        int flags4 = unpackEntryFlags4(headerWord);
        int localIndex10 = unpackLocalChunkIndex10(headerWord);

        if (entryType == ENTRY_CLEAN_ACK) {
            return new EntryCleanAck(localIndex10);
        }
        if (entryType == ENTRY_PATCH_DELTA) {
            long newTick = readVarLong(in);
            long newSyndrome = in.readLong();
            int dirtyMask = in.readInt();
            int dirtyCount = Integer.bitCount(dirtyMask);
            List<SectionWireSlice> modified = new ArrayList<>(dirtyCount);
            int remainingMask = dirtyMask;
            while (remainingMask != 0) {
                int sectionIdx = Integer.numberOfTrailingZeros(remainingMask);
                remainingMask &= (remainingMask - 1);
                modified.add(readSectionWireSlice(in, sectionIdx));
            }
            return new EntryPatchDelta(localIndex10, newTick, newSyndrome, dirtyMask, modified);
        }
        if (entryType == ENTRY_FULL_CHUNK) {
            int chunkX = readVarInt(in);
            int chunkZ = readVarInt(in);
            long lastUpdateTick = readVarLong(in);
            long syndrome64 = in.readLong();
            int sectionCount = in.readUnsignedByte();
            short[] dirWords = new short[sectionCount];
            for (int i = 0; i < sectionCount; i++) {
                dirWords[i] = in.readShort();
            }
            int overlayMask = in.readInt();
            List<SectionWireSlice> sections = new ArrayList<>(sectionCount);
            for (int i = 0; i < sectionCount; i++) {
                short dirWord = dirWords[i];
                byte[] baseSlice;
                if (isUniformDirectoryWord(dirWord)) {
                    baseSlice = new byte[0];
                } else {
                    int bodyLen = dirWord & 0x7FFF;
                    baseSlice = new byte[bodyLen];
                    in.readFully(baseSlice);
                }
                sections.add(new SectionWireSlice(i, dirWord, baseSlice, new int[0]));
            }
            if (overlayMask != 0) {
                for (int i = 0; i < sectionCount; i++) {
                    if ((overlayMask & (1 << i)) != 0) {
                        int count = readVarInt(in);
                        int[] overlay = new int[count];
                        for (int j = 0; j < count; j++) {
                            overlay[j] = in.readInt();
                        }
                        SectionWireSlice prev = sections.get(i);
                        sections.set(i, new SectionWireSlice(
                                i, prev.directoryWord16(), prev.baseFrameSlice(), overlay));
                    }
                }
            }
            byte[] hmBytes = new byte[0];
            if ((flags4 & 0x1) != 0) {
                int hmLen = readVarInt(in);
                hmBytes = new byte[hmLen];
                in.readFully(hmBytes);
            }
            return new EntryFullChunk(chunkX, chunkZ, lastUpdateTick, syndrome64, sections, hmBytes);
        }
        throw new IOException("Unknown BatchChunkEntry type: " + entryType + " in region (" + regionX + "," + regionZ + ")");
    }

    public static void writeSectionWireSlice(DataOutputStream out, SectionWireSlice slice) throws IOException {
        out.writeShort(slice.directoryWord16());
        if (!isUniformDirectoryWord(slice.directoryWord16())) {
            int bodyLen = slice.directoryWord16() & 0x7FFF;
            out.write(slice.baseFrameSlice(), 0, bodyLen);
        }
        writeVarInt(out, slice.dirtyOverlayEntries().length);
        for (int packed : slice.dirtyOverlayEntries()) {
            out.writeInt(packed);
        }
    }

    public static SectionWireSlice readSectionWireSlice(DataInputStream in, int sectionIndex) throws IOException {
        short dirWord = in.readShort();
        byte[] baseSlice;
        if (isUniformDirectoryWord(dirWord)) {
            baseSlice = new byte[0];
        } else {
            int bodyLen = dirWord & 0x7FFF;
            baseSlice = new byte[bodyLen];
            in.readFully(baseSlice);
        }
        int overlayCount = readVarInt(in);
        int[] overlay = new int[overlayCount];
        for (int i = 0; i < overlayCount; i++) {
            overlay[i] = in.readInt();
        }
        return new SectionWireSlice(sectionIndex, dirWord, baseSlice, overlay);
    }

    // =========================================================================
    // 4. SEC-DED-64 Syndrome & Revisit Delta Resolution
    // =========================================================================

    /**
     * Computes a 64-bit Extended Hamming `(72, 64)` SEC-DED syndrome over `(lastUpdateTick, slabBytes)`.
     */
    public static long computeSecDedSyndrome64(long lastUpdateTick, byte[] slabBytes) {
        Objects.requireNonNull(slabBytes, "slabBytes");
        long accA = splitMix64(lastUpdateTick ^ 0x4C43415F53454331L) ^ ((long) slabBytes.length << 32);
        long accB = splitMix64(Long.rotateLeft(lastUpdateTick, 31) ^ 0x9E3779B97F4A7C15L);
        long bitPosXor = lastUpdateTick;

        int i = 0;
        int len = slabBytes.length;
        for (; i + 8 <= len; i += 8) {
            long word = ((long) (slabBytes[i] & 0xFF))
                    | ((long) (slabBytes[i + 1] & 0xFF) << 8)
                    | ((long) (slabBytes[i + 2] & 0xFF) << 16)
                    | ((long) (slabBytes[i + 3] & 0xFF) << 24)
                    | ((long) (slabBytes[i + 4] & 0xFF) << 32)
                    | ((long) (slabBytes[i + 5] & 0xFF) << 40)
                    | ((long) (slabBytes[i + 6] & 0xFF) << 48)
                    | ((long) (slabBytes[i + 7] & 0xFF) << 56);
            accA = splitMix64(accA ^ word ^ ((long) i * 0xBF58476D1CE4E5B9L));
            accB = Long.rotateLeft(accB ^ word, 17) + 0x94D049BB133111EBL;
            bitPosXor ^= Long.rotateLeft(word, i & 63);
        }
        if (i < len) {
            long tail = 0L;
            for (int shift = 0; i < len; i++, shift += 8) {
                tail |= ((long) (slabBytes[i] & 0xFF)) << shift;
            }
            accA = splitMix64(accA ^ tail ^ ((long) len * 0x9E3779B97F4A7C15L));
            accB = Long.rotateLeft(accB ^ tail, 23) ^ 0xD6E8FEB86659FD93L;
            bitPosXor ^= Long.rotateLeft(tail, len & 63);
        }

        long mixed64 = splitMix64(accA ^ accB ^ bitPosXor);
        long data56 = (mixed64 >>> 8) & 0x00FFFFFFFFFFFFFFL;
        int ecc8 = computeExtendedHamming8(data56);
        return (data56 << 8) | (ecc8 & 0xFFL);
    }

    public static boolean verifySecDedSyndrome64(
            long lastUpdateTick,
            byte[] slabBytes,
            long expectedSyndrome64
    ) {
        if (slabBytes == null) {
            return false;
        }
        long data56 = (expectedSyndrome64 >>> 8) & 0x00FFFFFFFFFFFFFFL;
        int storedEcc8 = (int) (expectedSyndrome64 & 0xFFL);
        if (computeExtendedHamming8(data56) != storedEcc8) {
            return false;
        }
        return computeSecDedSyndrome64(lastUpdateTick, slabBytes) == expectedSyndrome64;
    }

    public static int computeExtendedHamming8(long data64) {
        int p0 = Long.bitCount(data64 & 0x5555555555555555L) & 1;
        int p1 = Long.bitCount(data64 & 0x6666666666666666L) & 1;
        int p2 = Long.bitCount(data64 & 0x7878787878787878L) & 1;
        int p3 = Long.bitCount(data64 & 0x7F807F807F807F80L) & 1;
        int p4 = Long.bitCount(data64 & 0x7FFF80007FFF8000L) & 1;
        int p5 = Long.bitCount(data64 & 0x7FFFFFFF80000000L) & 1;
        int p6 = Long.bitCount(data64 & 0xAA55AA55AA55AA55L) & 1;
        int hamming7 = p0 | (p1 << 1) | (p2 << 2) | (p3 << 3) | (p4 << 4) | (p5 << 5) | (p6 << 6);
        int overallParity = (Long.bitCount(data64) + Integer.bitCount(hamming7)) & 1;
        return hamming7 | (overallParity << 7);
    }

    private static long splitMix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * Resolves the optimal revisit `BatchChunkEntry` (`EntryCleanAck`, `EntryPatchDelta`, or
     * `EntryFullChunk`) given a receiver's `ChunkDigestEntry`.
     */
    public static BatchChunkEntry resolveServerRevisitEntry(
            EntryFullChunk currentServerChunk,
            ChunkDigestEntry clientDigest,
            long expectedBaselineSecDed64,
            int dirtySectionBitmask32
    ) {
        Objects.requireNonNull(currentServerChunk, "currentServerChunk");
        if (clientDigest == null) {
            return currentServerChunk;
        }
        if (clientDigest.secDedSyndrome64() != expectedBaselineSecDed64) {
            return currentServerChunk;
        }
        if (dirtySectionBitmask32 == 0
                && clientDigest.lastUpdateTick() == currentServerChunk.lastUpdateTick()
                && clientDigest.secDedSyndrome64() == currentServerChunk.secDedSyndrome64()) {
            return new EntryCleanAck(currentServerChunk.localChunkIndex10());
        }
        int dirtyCount = Integer.bitCount(dirtySectionBitmask32);
        if (dirtyCount >= 1 && dirtyCount <= MAX_PATCH_DELTA_SECTIONS) {
            List<SectionWireSlice> modifiedSlices = new ArrayList<>(dirtyCount);
            int mask = dirtySectionBitmask32;
            while (mask != 0) {
                int sectionIdx = Integer.numberOfTrailingZeros(mask);
                mask &= (mask - 1);
                if (sectionIdx >= currentServerChunk.sections().size()) {
                    return currentServerChunk;
                }
                modifiedSlices.add(currentServerChunk.sections().get(sectionIdx));
            }
            return new EntryPatchDelta(
                    currentServerChunk.localChunkIndex10(),
                    currentServerChunk.lastUpdateTick(),
                    currentServerChunk.secDedSyndrome64(),
                    dirtySectionBitmask32,
                    modifiedSlices
            );
        }
        return currentServerChunk;
    }

    /**
     * Hydrates a {@link SectionWireSlice} into an {@link LcaSectionBacking}.
     */
    public static LcaSectionBacking hydrateSectionBacking(SectionWireSlice wireSlice) {
        Objects.requireNonNull(wireSlice, "wireSlice");
        if (wireSlice.isUniform()) {
            int majorityStateId = wireSlice.directoryWord16() & 0x1FFF;
            return new LcaSectionBacking(new byte[0], majorityStateId);
        }
        LcaSectionBacking backing = new LcaSectionBacking(wireSlice.baseFrameSlice(), 0);
        int[] overlayEntries = wireSlice.dirtyOverlayEntries();
        for (int i = 0; i < overlayEntries.length; i++) {
            int entry32 = overlayEntries[i];
            int pos12 = LcaSectionBacking.unpackPos12(entry32);
            int stateId19 = LcaSectionBacking.unpackStateId19(entry32);
            int x = pos12 & 15;
            int z = (pos12 >>> 4) & 15;
            int y = (pos12 >>> 8) & 15;
            backing.setStateId(x, y, z, stateId19, 1L + i);
        }
        return backing;
    }

    /**
     * Converts a canonical {@link EntryFullChunk} into an {@link AnvilChunkView} using the
     * region's `BlockState` and `Biome` string dictionary (or fallback canonical IDs).
     */
    public static AnvilChunkView toAnvilChunkView(
            EntryFullChunk fullChunk,
            int minSectionY,
            List<String> dictIdToBlockStateName,
            List<String> dictIdToBiomeName
    ) {
        Objects.requireNonNull(fullChunk, "fullChunk");
        List<SectionWireSlice> wireSections = fullChunk.sections();
        List<PaletteSection> paletteSections = new ArrayList<>(wireSections.size());
        List<BiomePaletteSection> biomeSections = new ArrayList<>(wireSections.size());
        String defaultBiome = (dictIdToBiomeName != null && !dictIdToBiomeName.isEmpty())
                ? dictIdToBiomeName.get(0)
                : "minecraft:plains";

        for (int i = 0; i < wireSections.size(); i++) {
            int sectionY = minSectionY + i;
            SectionWireSlice slice = wireSections.get(i);
            biomeSections.add(new BiomePaletteSection(sectionY, List.of(defaultBiome), null));

            if (slice.isUniform() && slice.dirtyOverlayEntries().length == 0) {
                int dictId = slice.directoryWord16() & 0x1FFF;
                String stateName = resolveBlockStateName(dictId, dictIdToBlockStateName);
                paletteSections.add(new PaletteSection(sectionY, List.of(stateName), null));
                continue;
            }

            LcaSectionBacking backing = hydrateSectionBacking(slice);
            int[] stateIds = backing.materializeEphemeralStateIds(new int[4096], null);
            Map<Integer, Integer> localPaletteMap = new LinkedHashMap<>();
            List<String> localPalette = new ArrayList<>();
            int[] localIndices = new int[4096];
            for (int v = 0; v < 4096; v++) {
                int sId = stateIds[v];
                Integer pIdx = localPaletteMap.get(sId);
                if (pIdx == null) {
                    pIdx = localPalette.size();
                    localPaletteMap.put(sId, pIdx);
                    localPalette.add(resolveBlockStateName(sId, dictIdToBlockStateName));
                }
                localIndices[v] = pIdx;
            }
            if (localPalette.size() <= 1) {
                String single = localPalette.isEmpty() ? "minecraft:air" : localPalette.get(0);
                paletteSections.add(new PaletteSection(sectionY, List.of(single), null));
            } else {
                long[] packedData = packPaletteIndices(localIndices, localPalette.size());
                paletteSections.add(new PaletteSection(
                        sectionY, Collections.unmodifiableList(localPalette), packedData));
            }
        }
        return new AnvilChunkView(4671, paletteSections, biomeSections);
    }

    /**
     * Converts an {@link AnvilChunkView} into a streamable {@link EntryFullChunk} and populates
     * `blockStateToDictId` / `dictIdToBlockStateName` in a single pass.
     */
    public static EntryFullChunk fromAnvilChunkView(
            int chunkX,
            int chunkZ,
            long lastUpdateTick,
            AnvilChunkView chunkView,
            Map<String, Integer> blockStateToDictId,
            List<String> dictIdToBlockStateName
    ) {
        Objects.requireNonNull(chunkView, "chunkView");
        Objects.requireNonNull(blockStateToDictId, "blockStateToDictId");
        Objects.requireNonNull(dictIdToBlockStateName, "dictIdToBlockStateName");
        if (dictIdToBlockStateName.isEmpty()) {
            blockStateToDictId.put("minecraft:air", 0);
            dictIdToBlockStateName.add("minecraft:air");
        }

        List<PaletteSection> srcSections = chunkView.sections();
        List<SectionWireSlice> wireSlices = new ArrayList<>(srcSections.size());
        int[] voxels = new int[4096];

        for (int i = 0; i < srcSections.size(); i++) {
            PaletteSection sec = srcSections.get(i);
            List<String> pal = sec.palette();
            if (pal.size() == 1) {
                int dictId = registerDictState(pal.get(0), blockStateToDictId, dictIdToBlockStateName);
                wireSlices.add(SectionWireSlice.uniform(i, 15, 0, dictId));
                continue;
            }
            int[] palToDict = new int[pal.size()];
            for (int p = 0; p < pal.size(); p++) {
                palToDict[p] = registerDictState(pal.get(p), blockStateToDictId, dictIdToBlockStateName);
            }
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int idx = (y << 8) | (z << 4) | x;
                        String bName = sec.blockIdAt(x, y, z);
                        int dictId = bName == null ? 0 : registerDictState(
                                bName, blockStateToDictId, dictIdToBlockStateName);
                        voxels[idx] = dictId;
                    }
                }
            }
            LcaSectionBacking.EncodedCompactFrame encoded =
                    LcaSectionBacking.encodeCompactSectionFrame(voxels, 15, 0);
            if (encoded.frameBytes().length == 0) {
                wireSlices.add(SectionWireSlice.uniform(i, 15, 0, encoded.majorityStateId()));
            } else {
                wireSlices.add(SectionWireSlice.nonUniform(i, encoded.frameBytes(), new int[0]));
            }
        }
        return EntryFullChunk.createWithComputedSyndrome(
                chunkX, chunkZ, lastUpdateTick, wireSlices, new byte[0]);
    }

    private static int registerDictState(
            String stateName,
            Map<String, Integer> blockStateToDictId,
            List<String> dictIdToBlockStateName
    ) {
        String norm = (stateName == null || stateName.isBlank()) ? "minecraft:air" : stateName.trim();
        Integer existing = blockStateToDictId.get(norm);
        if (existing != null) {
            return existing;
        }
        int nextId = dictIdToBlockStateName.size();
        blockStateToDictId.put(norm, nextId);
        dictIdToBlockStateName.add(norm);
        return nextId;
    }

    private static String resolveBlockStateName(int dictId, List<String> dictIdToBlockStateName) {
        if (dictIdToBlockStateName != null && dictId >= 0 && dictId < dictIdToBlockStateName.size()) {
            String name = dictIdToBlockStateName.get(dictId);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        if (dictId == 0) {
            return "minecraft:air";
        }
        return switch (dictId) {
            case 1 -> "minecraft:stone";
            case 2 -> "minecraft:grass_block";
            case 3 -> "minecraft:dirt";
            case 4 -> "minecraft:deepslate";
            case 5 -> "minecraft:water";
            case 6 -> "minecraft:sand";
            case 7 -> "minecraft:oak_log";
            case 8 -> "minecraft:oak_leaves";
            case 9 -> "minecraft:lava";
            case 10 -> "minecraft:glowstone";
            default -> "minecraft:stone";
        };
    }

    private static long[] packPaletteIndices(int[] indices4096, int paletteSize) {
        int bits = PackedPaletteDecoder.bitsPerEntry(paletteSize);
        int entriesPerLong = 64 / bits;
        int numLongs = (4096 + entriesPerLong - 1) / entriesPerLong;
        long[] words = new long[numLongs];
        long mask = (1L << bits) - 1L;
        for (int i = 0; i < 4096; i++) {
            int wordIdx = i / entriesPerLong;
            int bitOffset = (i % entriesPerLong) * bits;
            words[wordIdx] |= ((indices4096[i] & mask) << bitOffset);
        }
        return words;
    }

    // =========================================================================
    // 5. Canonical Slab & LCD2 Framing Helpers
    // =========================================================================

    public static byte[] serializeCanonicalChunkSlab(
            int chunkX,
            int chunkZ,
            List<SectionWireSlice> sections,
            byte[] heightmapDeltaBytes
    ) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeInt(chunkX);
            out.writeInt(chunkZ);
            out.writeByte(sections.size());
            for (SectionWireSlice slice : sections) {
                writeSectionWireSlice(out, slice);
            }
            byte[] hm = heightmapDeltaBytes == null ? new byte[0] : heightmapDeltaBytes;
            writeVarInt(out, hm.length);
            out.write(hm);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return baos.toByteArray();
    }

    public static EntryFullChunk deserializeCanonicalChunkSlab(
            byte[] slabBytes,
            long lastUpdateTick,
            long secDedSyndrome64
    ) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(slabBytes))) {
            int chunkX = in.readInt();
            int chunkZ = in.readInt();
            int sectionCount = in.readUnsignedByte();
            List<SectionWireSlice> sections = new ArrayList<>(sectionCount);
            for (int i = 0; i < sectionCount; i++) {
                sections.add(readSectionWireSlice(in, i));
            }
            int hmLen = readVarInt(in);
            byte[] hmBytes = new byte[hmLen];
            in.readFully(hmBytes);
            return new EntryFullChunk(chunkX, chunkZ, lastUpdateTick, secDedSyndrome64, sections, hmBytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] encodeLcd2PassthroughFrame(byte[] rawBatch) throws IOException {
        int bodyLen = rawBatch.length;
        if (bodyLen > MAX_DECOMPRESSED_BATCH_BYTES) {
            throw new IOException("Batch payload exceeds MAX_DECOMPRESSED_BATCH_BYTES: " + bodyLen);
        }
        int rawCompLen = 9 + bodyLen;
        byte[] rawOut = new byte[rawCompLen];
        rawOut[0] = 0x4C;
        rawOut[1] = 0x44;
        rawOut[2] = 0x04;
        rawOut[3] = (byte) ((rawCompLen >>> 16) & 0xFF);
        rawOut[4] = (byte) ((rawCompLen >>> 8) & 0xFF);
        rawOut[5] = (byte) (rawCompLen & 0xFF);
        rawOut[6] = (byte) ((bodyLen >>> 16) & 0xFF);
        rawOut[7] = (byte) ((bodyLen >>> 8) & 0xFF);
        rawOut[8] = (byte) (bodyLen & 0xFF);
        System.arraycopy(rawBatch, 0, rawOut, 9, bodyLen);
        return rawOut;
    }

    public static byte[] encodeLcd2BatchFrame(byte[] rawBatch) throws IOException {
        int bodyLen = rawBatch.length;
        if (bodyLen <= 0 || bodyLen < 16) {
            return encodeLcd2PassthroughFrame(rawBatch);
        }
        if (bodyLen > MAX_DECOMPRESSED_BATCH_BYTES) {
            throw new IOException("Batch payload exceeds MAX_DECOMPRESSED_BATCH_BYTES: " + bodyLen);
        }
        int[] intraHash3 = new int[4096];
        int[] intraHash4 = new int[8192];
        int[] intraPrev = new int[65536];
        Arrays.fill(intraHash3, -1);
        Arrays.fill(intraHash4, -1);

        byte[] litBuf = new byte[bodyLen];
        byte[] cmdBuf = new byte[(bodyLen / 3) + 16];
        byte[] offBuf = new byte[bodyLen + 32];
        int litTotal = 0;
        int seqCount = 0;
        int offTotal = 0;

        int anchor = 0;
        int p = 0;
        int lastOffset = 0;

        while (p <= bodyLen - 3) {
            int bestMode = -1;
            int bestLen = 0;
            int bestOff = 0;
            int bestSavings = 0;

            if (lastOffset > 0 && p > anchor && p >= lastOffset) {
                int maxRem = Math.min(256, bodyLen - p);
                int srcBase = p - lastOffset;
                int repLen = 0;
                while (repLen < maxRem && rawBatch[p + repLen] == rawBatch[srcBase + repLen]) {
                    repLen++;
                }
                if (repLen >= 3) {
                    int sav = repLen - (repLen >= 10 ? 1 : 0);
                    if (sav > bestSavings) {
                        bestSavings = sav;
                        bestMode = 3;
                        bestLen = repLen;
                        bestOff = lastOffset;
                    }
                }
            }

            int h3 = hash3Batch(rawBatch, p);
            int cand3 = intraHash3[h3];
            if (cand3 >= 0) {
                int dist3 = p - cand3;
                if (dist3 >= 1 && dist3 <= 256) {
                    int maxRem = Math.min(256, bodyLen - p);
                    int mLen = 0;
                    while (mLen < maxRem && rawBatch[p + mLen] == rawBatch[cand3 + mLen]) {
                        mLen++;
                    }
                    if (mLen >= 3) {
                        int sav = mLen - 1 - (mLen >= 10 ? 1 : 0);
                        if (sav > bestSavings) {
                            bestSavings = sav;
                            bestMode = 0;
                            bestLen = mLen;
                            bestOff = dist3;
                        }
                    }
                }
            }

            int h4 = (p <= bodyLen - 4) ? hash4Batch(rawBatch, p) : -1;
            if (h4 >= 0) {
                int cand4 = intraHash4[h4 & 8191];
                int chainSteps = 0;
                int maxRem = Math.min(512, bodyLen - p);
                while (cand4 >= 0 && chainSteps++ < 8) {
                    int dist4 = p - cand4;
                    if (dist4 <= 0 || dist4 > 65792) {
                        break;
                    }
                    if (bestLen < maxRem
                            && rawBatch[cand4 + bestLen] == rawBatch[p + bestLen]
                            && readIntBEBatch(rawBatch, cand4) == readIntBEBatch(rawBatch, p)) {
                        int mLen = 4;
                        while (mLen < maxRem && rawBatch[p + mLen] == rawBatch[cand4 + mLen]) {
                            mLen++;
                        }
                        int mode = dist4 <= 256 ? 0 : 1;
                        int cost = (mode == 0 ? 1 : 2) + (mLen >= (mode == 0 ? 10 : 11) ? 1 : 0);
                        int sav = mLen - cost;
                        if (sav > bestSavings) {
                            bestSavings = sav;
                            bestMode = mode;
                            bestLen = mLen;
                            bestOff = dist4;
                        }
                    }
                    cand4 = intraPrev[cand4 & 0xFFFF];
                }
            }

            if (bestMode < 0) {
                intraHash3[h3] = p;
                if (h4 >= 0) {
                    int slot4 = h4 & 8191;
                    intraPrev[p & 0xFFFF] = intraHash4[slot4];
                    intraHash4[slot4] = p;
                }
                p++;
                continue;
            }

            int litLen = p - anchor;
            if (litLen > 0) {
                System.arraycopy(rawBatch, anchor, litBuf, litTotal, litLen);
                litTotal += litLen;
            }

            int minMatch = (bestMode == 0 || bestMode == 3) ? 3 : 4;
            int lenM = bestLen - minMatch;
            int litCode = Math.min(7, litLen);
            int lenCode = Math.min(7, lenM);
            cmdBuf[seqCount++] = (byte) ((litCode << 5) | (bestMode << 3) | lenCode);

            if (litCode == 7) {
                offTotal = writeVarIntToArray(offBuf, offTotal, litLen - 7);
            }
            if (lenCode == 7) {
                offTotal = writeVarIntToArray(offBuf, offTotal, lenM - 7);
            }
            if (bestMode == 0) {
                offBuf[offTotal++] = (byte) (bestOff - 1);
                lastOffset = bestOff;
            } else if (bestMode == 1) {
                int v = bestOff - 257;
                offBuf[offTotal++] = (byte) ((v >>> 8) & 0xFF);
                offBuf[offTotal++] = (byte) (v & 0xFF);
                lastOffset = bestOff;
            }

            int matchEnd = p + bestLen;
            int indexLimit = Math.min(matchEnd, bodyLen - 4);
            for (int k = p; k < indexLimit; k++) {
                intraHash3[hash3Batch(rawBatch, k)] = k;
                int kh4 = hash4Batch(rawBatch, k) & 8191;
                intraPrev[k & 0xFFFF] = intraHash4[kh4];
                intraHash4[kh4] = k;
            }
            p = matchEnd;
            anchor = p;
        }

        if (anchor < bodyLen) {
            int rem = bodyLen - anchor;
            System.arraycopy(rawBatch, anchor, litBuf, litTotal, rem);
            litTotal += rem;
        }

        int metaVarIntsLen = varIntByteLength(seqCount)
                + varIntByteLength(litTotal)
                + varIntByteLength(offTotal);
        int totalCompLen = 9 + metaVarIntsLen + litTotal + seqCount + offTotal;
        if (totalCompLen >= 9 + bodyLen) {
            return encodeLcd2PassthroughFrame(rawBatch);
        }

        byte[] out = new byte[totalCompLen];
        out[0] = 0x4C;
        out[1] = 0x44;
        out[2] = 0x00;
        out[3] = (byte) ((totalCompLen >>> 16) & 0xFF);
        out[4] = (byte) ((totalCompLen >>> 8) & 0xFF);
        out[5] = (byte) (totalCompLen & 0xFF);
        out[6] = (byte) ((bodyLen >>> 16) & 0xFF);
        out[7] = (byte) ((bodyLen >>> 8) & 0xFF);
        out[8] = (byte) (bodyLen & 0xFF);

        int pos = 9;
        pos = writeVarIntToArray(out, pos, seqCount);
        pos = writeVarIntToArray(out, pos, litTotal);
        pos = writeVarIntToArray(out, pos, offTotal);
        System.arraycopy(litBuf, 0, out, pos, litTotal);
        pos += litTotal;
        System.arraycopy(cmdBuf, 0, out, pos, seqCount);
        pos += seqCount;
        System.arraycopy(offBuf, 0, out, pos, offTotal);
        return out;
    }

    public static byte[] decodeLcd2BatchFrame(byte[] src, int maxDecompressedBytes) throws IOException {
        if (src.length >= 4) {
            int magic32 = readIntBEBatch(src, 0);
            if (magic32 == BATCH_MAGIC || magic32 == INTER_SERVER_UPDATE_MAGIC) {
                if (src.length > maxDecompressedBytes) {
                    throw new IOException("Batch payload exceeds maxDecompressedBytes: " + src.length);
                }
                return src.clone();
            }
        }
        if (src.length < 9
                || (src[0] & 0xFF) != 0x4C
                || (src[1] & 0xFF) != 0x44) {
            throw new IOException("Invalid or truncated LCD2 wire batch frame header");
        }
        int frameFlags = src[2] & 0xFF;
        int compBodyLen = ((src[3] & 0xFF) << 16)
                | ((src[4] & 0xFF) << 8)
                | (src[5] & 0xFF);
        int uncompBodyLen = ((src[6] & 0xFF) << 16)
                | ((src[7] & 0xFF) << 8)
                | (src[8] & 0xFF);
        if (compBodyLen < 9
                || compBodyLen > src.length
                || uncompBodyLen < 0
                || uncompBodyLen > maxDecompressedBytes) {
            throw new IOException("Invalid LCD2 wire batch frame lengths: comp="
                    + compBodyLen + " uncomp=" + uncompBodyLen);
        }
        if ((frameFlags & 0x04) != 0) {
            if (9 + uncompBodyLen > compBodyLen) {
                throw new IOException("Truncated LCD2 raw passthrough wire batch body");
            }
            return Arrays.copyOfRange(src, 9, 9 + uncompBodyLen);
        }
        if ((frameFlags & ~0x04) != 0) {
            throw new IOException("Unsupported LCD2 wire batch frame flags: 0x" + Integer.toHexString(frameFlags));
        }

        int[] cursor = new int[]{9};
        final int seqCount;
        final int litTotal;
        final int offTotal;
        try {
            seqCount = readVarIntFromArray(src, cursor);
            litTotal = readVarIntFromArray(src, cursor);
            offTotal = readVarIntFromArray(src, cursor);
        } catch (IllegalArgumentException e) {
            throw new IOException("Truncated LCD2 wire batch stream counts", e);
        }
        int pos = cursor[0];
        if (seqCount < 0
                || litTotal < 0
                || offTotal < 0
                || litTotal > uncompBodyLen
                || seqCount > uncompBodyLen
                || pos + litTotal + seqCount + offTotal > compBodyLen) {
            throw new IOException("Invalid LCD2 wire batch stream counts");
        }

        int litPos = pos;
        int litEnd = litPos + litTotal;
        int cmdPos = litEnd;
        int offPos = cmdPos + seqCount;
        int offLimit = offPos + offTotal;
        int[] offCursor = new int[]{offPos};

        byte[] dst = new byte[uncompBodyLen];
        int dstPos = 0;
        int lastOffset = 0;

        for (int s = 0; s < seqCount; s++) {
            int cmd = src[cmdPos + s] & 0xFF;
            int litLen = (cmd >>> 5) & 7;
            if (litLen == 7) {
                int extra;
                try {
                    extra = readVarIntFromArray(src, offCursor);
                } catch (IllegalArgumentException e) {
                    throw new IOException("Truncated LCD2 wire batch literal length VarInt", e);
                }
                if (extra < 0 || offCursor[0] > offLimit) {
                    throw new IOException("Out-of-bounds LCD2 wire batch literal length VarInt");
                }
                litLen += extra;
            }
            if (litLen > 0) {
                if (litPos + litLen > litEnd || dstPos + litLen > uncompBodyLen) {
                    throw new IOException("LCD2 wire batch literal copy out of bounds");
                }
                System.arraycopy(src, litPos, dst, dstPos, litLen);
                litPos += litLen;
                dstPos += litLen;
            }

            int matchMode = (cmd >>> 3) & 3;
            int minMatch = (matchMode == 0 || matchMode == 3) ? 3 : 4;
            int matchLen = (cmd & 7) + minMatch;
            if ((cmd & 7) == 7) {
                int extra;
                try {
                    extra = readVarIntFromArray(src, offCursor);
                } catch (IllegalArgumentException e) {
                    throw new IOException("Truncated LCD2 wire batch match length VarInt", e);
                }
                if (extra < 0 || offCursor[0] > offLimit) {
                    throw new IOException("Out-of-bounds LCD2 wire batch match length VarInt");
                }
                matchLen += extra;
            }

            int matchOff;
            if (matchMode == 0) {
                if (offCursor[0] >= offLimit) {
                    throw new IOException("Truncated LCD2 wire batch short offset");
                }
                matchOff = (src[offCursor[0]++] & 0xFF) + 1;
                lastOffset = matchOff;
            } else if (matchMode == 1) {
                if (offCursor[0] + 1 >= offLimit) {
                    throw new IOException("Truncated LCD2 wire batch medium offset");
                }
                int b0 = src[offCursor[0]++] & 0xFF;
                int b1 = src[offCursor[0]++] & 0xFF;
                matchOff = ((b0 << 8) | b1) + 257;
                lastOffset = matchOff;
            } else if (matchMode == 3) {
                if (lastOffset <= 0) {
                    throw new IOException("Invalid LCD2 wire batch repeat offset state");
                }
                matchOff = lastOffset;
            } else {
                throw new IOException("Unsupported LCD2 wire batch match mode: " + matchMode);
            }

            if (matchOff <= 0 || dstPos < matchOff || dstPos + matchLen > uncompBodyLen) {
                throw new IOException("LCD2 wire batch match copy out of bounds");
            }
            int copySrc = dstPos - matchOff;
            if (matchOff >= matchLen) {
                System.arraycopy(dst, copySrc, dst, dstPos, matchLen);
                dstPos += matchLen;
            } else {
                for (int k = 0; k < matchLen; k++) {
                    dst[dstPos++] = dst[copySrc + k];
                }
            }
        }

        int remLit = litEnd - litPos;
        if (remLit > 0) {
            if (dstPos + remLit > uncompBodyLen) {
                throw new IOException("LCD2 wire batch trailing literals out of bounds");
            }
            System.arraycopy(src, litPos, dst, dstPos, remLit);
            dstPos += remLit;
        }
        if (dstPos != uncompBodyLen) {
            throw new IOException("LCD2 wire batch decoded length mismatch: expected "
                    + uncompBodyLen + ", got " + dstPos);
        }
        return dst;
    }

    public static int varIntByteLength(int value) {
        int v = value;
        int bytes = 1;
        while ((v & ~0x7F) != 0) {
            bytes++;
            v >>>= 7;
        }
        return bytes;
    }

    public static int writeVarIntToArray(byte[] target, int offset, int value) {
        int v = value;
        int pos = offset;
        while ((v & ~0x7F) != 0) {
            target[pos++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        target[pos++] = (byte) v;
        return pos;
    }

    public static int readVarIntFromArray(byte[] source, int[] cursor) {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            if (cursor[0] >= source.length) {
                throw new IllegalArgumentException("Truncated VarInt in byte array");
            }
            read = source[cursor[0]++];
            int value = (read & 0x7F);
            result |= (value << (7 * numRead));
            numRead++;
            if (numRead > 5) {
                throw new IllegalArgumentException("VarInt is too big (> 5 bytes)");
            }
        } while ((read & 0x80) != 0);
        return result;
    }

    public static void writeVarInt(DataOutputStream out, int value) throws IOException {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    public static int readVarInt(DataInputStream in) throws IOException {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            read = in.readByte();
            int value = (read & 0x7F);
            result |= (value << (7 * numRead));
            numRead++;
            if (numRead > 5) {
                throw new IOException("VarInt is too big (> 5 bytes)");
            }
        } while ((read & 0x80) != 0);
        return result;
    }

    public static void writeVarLong(DataOutputStream out, long value) throws IOException {
        long v = value;
        while ((v & ~0x7FL) != 0L) {
            out.writeByte((int) ((v & 0x7FL) | 0x80L));
            v >>>= 7;
        }
        out.writeByte((int) v);
    }

    public static long readVarLong(DataInputStream in) throws IOException {
        int numRead = 0;
        long result = 0L;
        byte read;
        do {
            read = in.readByte();
            long value = (read & 0x7FL);
            result |= (value << (7 * numRead));
            numRead++;
            if (numRead > 10) {
                throw new IOException("VarLong is too big (> 10 bytes)");
            }
        } while ((read & 0x80) != 0);
        return result;
    }

    public static void writeDeltaVarIntArray(DataOutputStream out, int[] values) throws IOException {
        writeVarInt(out, values.length);
        int prev = 0;
        for (int v : values) {
            int delta = v - prev;
            int zigZag = (delta << 1) ^ (delta >> 31);
            writeVarInt(out, zigZag);
            prev = v;
        }
    }

    public static int[] readDeltaVarIntArray(DataInputStream in) throws IOException {
        int len = readVarInt(in);
        if (len < 0 || len > 1_048_576) {
            throw new IOException("Delta VarInt array length out of bounds: " + len);
        }
        int[] result = new int[len];
        int prev = 0;
        for (int i = 0; i < len; i++) {
            int zigZag = readVarInt(in);
            int delta = (zigZag >>> 1) ^ -(zigZag & 1);
            prev += delta;
            result[i] = prev;
        }
        return result;
    }

    private static int hash3Batch(byte[] buf, int p) {
        int v = ((buf[p] & 0xFF) << 16)
                | ((buf[p + 1] & 0xFF) << 8)
                | (buf[p + 2] & 0xFF);
        return ((v * 0x1E35A7BD) >>> 20) & 0xFFF;
    }

    private static int hash4Batch(byte[] buf, int p) {
        int v = readIntBEBatch(buf, p);
        return (v * 0x9E3779B1) >>> 19;
    }

    private static int readIntBEBatch(byte[] buf, int p) {
        return ((buf[p] & 0xFF) << 24)
                | ((buf[p + 1] & 0xFF) << 16)
                | ((buf[p + 2] & 0xFF) << 8)
                | (buf[p + 3] & 0xFF);
    }
}
