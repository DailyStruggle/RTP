package io.github.dailystruggle.anvil.stream;

import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.SectionWireSlice;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Platform-neutral, lock-free orderless dictionary table ({@code ODCT} {@code 0x4F444354},
 * {@code 4,096} content-addressed slots, 2-way associative buckets, {@code 512 B} per-session
 * {@link KnownSlotBitSet}) wire-compatible with {@code LcaOrderlessDictTable} in
 * {@code leafanvil-common} ({@code leafanvil-ADR-003}, {@code goldleaf-ADR-005}).
 *
 * <p>Each outbound chunk batch transmits only newly learned {@code DictLearnEntry} deltas since
 * the previous packet for that session (0 bytes once warmed) and replaces recurring section slices
 * with 2-byte {@code refWord16} references. Calling {@link KnownSlotBitSet#clear()} or
 * {@link #encodeFullDictionarySnapshot(KnownSlotBitSet)} supports deterministic full-dictionary
 * resynchronization.</p>
 *
 * @governedBy leafanvil-ADR-003, goldleaf-ADR-005, anvil-api-ADR-003, ADR-115
 * @trace REQ-NET-ODCT-001, REQ-NET-ODCT-002, REQ-RTP-S-002, REQ-RTP-S-004, REQ-RTP-S-005
 */
public final class LcaOrderlessDictWireTable {

    public static final int TABLE_SLOT_COUNT = 4096;
    public static final int SLOT_INDEX_MASK = TABLE_SLOT_COUNT - 1;
    public static final int BITSET_LONG_WORDS = TABLE_SLOT_COUNT >>> 6;
    public static final int DEFAULT_PROMOTION_THRESHOLD = 2;
    public static final int MIN_PATTERN_BYTES = 4;
    public static final int MAX_PATTERN_BYTES = 4096;

    public static final int ORDERLESS_FRAME_MAGIC = 0x4F444354; // "ODCT"
    public static final byte KIND_SECTION_SLICE_OR_PALETTE = 0;

    public static final int TOKEN_RAW_LITERAL = 0;
    public static final int TOKEN_SLOT_REFERENCE = 1;

    private final int promotionThreshold;
    private final AtomicReferenceArray<DictSlotEntry> slots = new AtomicReferenceArray<>(TABLE_SLOT_COUNT);
    private final AtomicIntegerArray candidateObservationCounts = new AtomicIntegerArray(TABLE_SLOT_COUNT);
    private final AtomicInteger populatedSlotCount = new AtomicInteger();

    public LcaOrderlessDictWireTable() {
        this(DEFAULT_PROMOTION_THRESHOLD);
    }

    public LcaOrderlessDictWireTable(int promotionThreshold) {
        if (promotionThreshold < 1) {
            throw new IllegalArgumentException("promotionThreshold must be >= 1: " + promotionThreshold);
        }
        this.promotionThreshold = promotionThreshold;
    }

    public int populatedSlotCount() {
        return this.populatedSlotCount.get();
    }

    public static int computeContentHash32(byte kind, byte[] patternBytes) {
        Objects.requireNonNull(patternBytes, "patternBytes");
        int h = 0x811C9DC5;
        h ^= (kind & 0xFF);
        h *= 0x01000193;
        for (int i = 0; i < patternBytes.length; i++) {
            h ^= (patternBytes[i] & 0xFF);
            h *= 0x01000193;
        }
        h ^= (h >>> 16);
        h *= 0x85EBCA6B;
        h ^= (h >>> 13);
        h *= 0xC2B2AE35;
        h ^= (h >>> 16);
        return h;
    }

    public static int primarySlotIndex12(int hash32) {
        return hash32 & SLOT_INDEX_MASK;
    }

    public static int secondarySlotIndex12(int hash32) {
        int primary = hash32 & SLOT_INDEX_MASK;
        int step = ((hash32 >>> 12) & SLOT_INDEX_MASK) | 1;
        return (primary ^ step) & SLOT_INDEX_MASK;
    }

    public static byte computeTag8(int hash32) {
        int raw = (hash32 >>> 24) & 0xFF;
        return (byte) (raw == 0 ? 0xA5 : raw);
    }

    public static int computeTag4(byte tag8) {
        int nib = (tag8 & 0x0F);
        return nib == 0 ? 0x05 : nib;
    }

    public static int packRefWord16(int slotId12, byte tag8) {
        return ((slotId12 & SLOT_INDEX_MASK) << 4) | (computeTag4(tag8) & 0x0F);
    }

    public DictSlotEntry observeOrLookup(byte kind, byte[] patternBytes) {
        Objects.requireNonNull(patternBytes, "patternBytes");
        if (patternBytes.length < MIN_PATTERN_BYTES || patternBytes.length > MAX_PATTERN_BYTES) {
            return null;
        }
        int hash32 = computeContentHash32(kind, patternBytes);
        byte tag8 = computeTag8(hash32);
        int slotA = primarySlotIndex12(hash32);
        int slotB = secondarySlotIndex12(hash32);

        DictSlotEntry existingA = this.slots.get(slotA);
        if (existingA != null && existingA.matches(tag8, kind, patternBytes)) {
            return existingA;
        }
        DictSlotEntry existingB = this.slots.get(slotB);
        if (existingB != null && existingB.matches(tag8, kind, patternBytes)) {
            return existingB;
        }

        int count = this.candidateObservationCounts.incrementAndGet(slotA);
        if (count < this.promotionThreshold) {
            return null;
        }
        return tryInsertIntoAssociativeSlots(slotA, slotB, tag8, kind, patternBytes);
    }

    /**
     * Encodes a chunk's section slices using the shared orderless dictionary and per-session
     * {@link KnownSlotBitSet}, prepending only newly learned dictionary entries since the last
     * packet for that session ({@code REQ-NET-ODCT-001}).
     */
    public byte[] encodeSectionWireSlicesWithDict(
            List<SectionWireSlice> sections,
            KnownSlotBitSet targetKnownSlots
    ) throws IOException {
        Objects.requireNonNull(sections, "sections");
        Objects.requireNonNull(targetKnownSlots, "targetKnownSlots");

        List<byte[]> nonUniformFrames = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            SectionWireSlice s = sections.get(i);
            if (!LcaRemoteStreamCodec.isUniformDirectoryWord(s.directoryWord16())) {
                nonUniformFrames.add(s.baseFrameSlice());
            }
        }
        byte[] encodedNonUniform = encodeSlicesForTarget(
                KIND_SECTION_SLICE_OR_PALETTE,
                nonUniformFrames,
                targetKnownSlots
        );

        ByteArrayOutputStream baos = new ByteArrayOutputStream(128 + encodedNonUniform.length);
        try (DataOutputStream out = new DataOutputStream(baos)) {
            writeVarInt(out, sections.size());
            int overlayMask = 0;
            for (int i = 0; i < sections.size(); i++) {
                SectionWireSlice s = sections.get(i);
                out.writeByte(s.sectionIndex());
                out.writeShort(s.directoryWord16());
                if (s.dirtyOverlayEntries().length > 0) {
                    overlayMask |= (1 << i);
                }
            }
            out.writeInt(overlayMask);
            for (int i = 0; i < sections.size(); i++) {
                if ((overlayMask & (1 << i)) != 0) {
                    int[] overlay = sections.get(i).dirtyOverlayEntries();
                    writeVarInt(out, overlay.length);
                    for (int packed : overlay) {
                        out.writeInt(packed);
                    }
                }
            }
            writeVarInt(out, encodedNonUniform.length);
            out.write(encodedNonUniform);
        }
        return baos.toByteArray();
    }

    /**
     * Encodes a full-dictionary {@code ODCT} snapshot containing every currently populated slot
     * (with {@code sliceCount = 0}) and marks all emitted slots known in {@code targetKnownSlots}.
     */
    public byte[] encodeFullDictionarySnapshot(KnownSlotBitSet targetKnownSlots) throws IOException {
        Objects.requireNonNull(targetKnownSlots, "targetKnownSlots");
        List<DictSlotEntry> allEntries = new ArrayList<>(Math.max(16, this.populatedSlotCount.get()));
        int estBytes = 16;
        for (int i = 0; i < TABLE_SLOT_COUNT; i++) {
            DictSlotEntry entry = this.slots.get(i);
            if (entry != null) {
                allEntries.add(entry);
                estBytes += 8 + entry.patternBytes.length;
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream(estBytes);
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeInt(ORDERLESS_FRAME_MAGIC);
            writeVarInt(out, allEntries.size());
            for (int i = 0; i < allEntries.size(); i++) {
                DictSlotEntry entry = allEntries.get(i);
                out.writeShort(packRefWord16(entry.slotId12, entry.tag8));
                out.writeByte(entry.kind);
                out.writeByte(entry.tag8);
                writeVarInt(out, entry.patternBytes.length);
                out.write(entry.patternBytes);
                targetKnownSlots.markKnown(entry.slotId12);
            }
            writeVarInt(out, 0);
        }
        return baos.toByteArray();
    }

    private byte[] encodeSlicesForTarget(
            byte kind,
            List<byte[]> rawSlices,
            KnownSlotBitSet targetKnownSlots
    ) throws IOException {
        List<DictSlotEntry> learnEntries = new ArrayList<>();
        KnownSlotBitSet newlyLearnedMask = new KnownSlotBitSet();
        int[] sliceRefWords = new int[rawSlices.size()];
        int rawTotalBytes = 0;

        for (int i = 0; i < rawSlices.size(); i++) {
            byte[] slice = rawSlices.get(i);
            rawTotalBytes += slice.length;
            DictSlotEntry slot = observeOrLookup(kind, slice);
            if (slot != null) {
                sliceRefWords[i] = slot.refWord16();
                if (!targetKnownSlots.isKnown(slot.slotId12)
                        && !newlyLearnedMask.isKnown(slot.slotId12)) {
                    learnEntries.add(slot);
                    newlyLearnedMask.markKnown(slot.slotId12);
                }
            } else {
                sliceRefWords[i] = -1;
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(64, rawTotalBytes + 64));
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeInt(ORDERLESS_FRAME_MAGIC);
            writeVarInt(out, learnEntries.size());
            for (int i = 0; i < learnEntries.size(); i++) {
                DictSlotEntry learn = learnEntries.get(i);
                out.writeShort(packRefWord16(learn.slotId12, learn.tag8));
                out.writeByte(learn.kind);
                out.writeByte(learn.tag8);
                writeVarInt(out, learn.patternBytes.length);
                out.write(learn.patternBytes);
            }

            writeVarInt(out, rawSlices.size());
            for (int i = 0; i < rawSlices.size(); i++) {
                int refWord = sliceRefWords[i];
                if (refWord >= 0) {
                    out.writeByte(TOKEN_SLOT_REFERENCE);
                    out.writeShort(refWord);
                } else {
                    byte[] raw = rawSlices.get(i);
                    out.writeByte(TOKEN_RAW_LITERAL);
                    writeVarInt(out, raw.length);
                    out.write(raw);
                }
            }
        }

        targetKnownSlots.unionFrom(newlyLearnedMask);
        return baos.toByteArray();
    }

    private DictSlotEntry tryInsertIntoAssociativeSlots(
            int slotA,
            int slotB,
            byte tag8,
            byte kind,
            byte[] patternBytes
    ) {
        DictSlotEntry candidateA = new DictSlotEntry(slotA, tag8, kind, patternBytes.clone());
        if (this.slots.compareAndSet(slotA, null, candidateA)) {
            this.populatedSlotCount.incrementAndGet();
            return candidateA;
        }
        DictSlotEntry existingA = this.slots.get(slotA);
        if (existingA != null && existingA.matches(tag8, kind, patternBytes)) {
            return existingA;
        }

        DictSlotEntry candidateB = new DictSlotEntry(slotB, tag8, kind, patternBytes.clone());
        if (this.slots.compareAndSet(slotB, null, candidateB)) {
            this.populatedSlotCount.incrementAndGet();
            return candidateB;
        }
        DictSlotEntry existingB = this.slots.get(slotB);
        if (existingB != null && existingB.matches(tag8, kind, patternBytes)) {
            return existingB;
        }
        return null;
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    public static final class DictSlotEntry {
        final int slotId12;
        final byte tag8;
        final byte kind;
        final byte[] patternBytes;

        DictSlotEntry(int slotId12, byte tag8, byte kind, byte[] patternBytes) {
            this.slotId12 = slotId12;
            this.tag8 = tag8;
            this.kind = kind;
            this.patternBytes = patternBytes;
        }

        public int slotId12() {
            return this.slotId12;
        }

        public int refWord16() {
            return packRefWord16(this.slotId12, this.tag8);
        }

        boolean matches(byte expectedTag8, byte expectedKind, byte[] candidateBytes) {
            return this.tag8 == expectedTag8
                    && this.kind == expectedKind
                    && Arrays.equals(this.patternBytes, candidateBytes);
        }
    }

    /**
     * Fixed {@code 512-byte} ({@code long[64]}) per-session bitset tracking which dictionary slots
     * have already been transmitted to a player.
     */
    public static final class KnownSlotBitSet {
        private final long[] words = new long[BITSET_LONG_WORDS];

        public synchronized boolean isKnown(int slotId12) {
            if (slotId12 < 0 || slotId12 >= TABLE_SLOT_COUNT) {
                return false;
            }
            return (this.words[slotId12 >>> 6] & (1L << (slotId12 & 63))) != 0L;
        }

        public synchronized void markKnown(int slotId12) {
            if (slotId12 < 0 || slotId12 >= TABLE_SLOT_COUNT) {
                return;
            }
            this.words[slotId12 >>> 6] |= (1L << (slotId12 & 63));
        }

        public synchronized void unionFrom(KnownSlotBitSet other) {
            for (int i = 0; i < BITSET_LONG_WORDS; i++) {
                this.words[i] |= other.words[i];
            }
        }

        public synchronized void clear() {
            Arrays.fill(this.words, 0L);
        }

        public synchronized int cardinality() {
            int sum = 0;
            for (long w : this.words) {
                sum += Long.bitCount(w);
            }
            return sum;
        }
    }
}
