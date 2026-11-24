package io.github.dailystruggle.anvil.stream;

import io.github.dailystruggle.anvil.stream.LcaOrderlessDictWireTable.DictSlotEntry;
import io.github.dailystruggle.anvil.stream.LcaOrderlessDictWireTable.KnownSlotBitSet;
import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.SectionWireSlice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LcaOrderlessDictWireTable} covering associative slot promotion,
 * 2-way collision fallback, per-session {@link KnownSlotBitSet} tracking, and {@code ODCT}
 * wire encoding ({@code REQ-NET-ODCT-001}, {@code REQ-NET-ODCT-002}).
 */
@DisplayName("REQ-NET-ODCT-001/002: LcaOrderlessDictWireTable Verification")
class LcaOrderlessDictWireTableTest {

    private static int readVarInt(DataInputStream in) throws IOException {
        int result = 0;
        int shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
    }

    private static byte[] patternOf(int length, int seed) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) ((seed * 31 + i * 17) ^ (seed >>> 8));
        }
        return out;
    }

    @Test
    @DisplayName("Constructor rejects non-positive promotionThreshold and tag helpers normalize zero")
    void constructorAndTagHelpersHandleEdgeCases() {
        assertThrows(IllegalArgumentException.class, () -> new LcaOrderlessDictWireTable(0));
        assertThrows(IllegalArgumentException.class, () -> new LcaOrderlessDictWireTable(-3));

        assertEquals((byte) 0xA5, LcaOrderlessDictWireTable.computeTag8(0x00123456));
        assertEquals((byte) 0x7F, LcaOrderlessDictWireTable.computeTag8(0x7F123456));
        assertEquals(0x05, LcaOrderlessDictWireTable.computeTag4((byte) 0x30));
        assertEquals(0x0B, LcaOrderlessDictWireTable.computeTag4((byte) 0x3B));

        int ref = LcaOrderlessDictWireTable.packRefWord16(0x123, (byte) 0x4B);
        assertEquals((0x123 << 4) | 0x0B, ref);
        assertEquals(0x123, LcaOrderlessDictWireTable.primarySlotIndex12(0xABCD0123));
        assertNotEquals(
                LcaOrderlessDictWireTable.primarySlotIndex12(0xABCD0123),
                LcaOrderlessDictWireTable.secondarySlotIndex12(0xABCD0123));
    }

    @Test
    @DisplayName("observeOrLookup enforces size bounds, promotion threshold, and 2-way associative collisions")
    void observeOrLookupPromotesAndHandlesAssociativeCollisions() {
        LcaOrderlessDictWireTable table = new LcaOrderlessDictWireTable();
        assertEquals(0, table.populatedSlotCount());

        assertNull(table.observeOrLookup(LcaOrderlessDictWireTable.KIND_SECTION_SLICE_OR_PALETTE, new byte[3]));
        assertNull(table.observeOrLookup(
                LcaOrderlessDictWireTable.KIND_SECTION_SLICE_OR_PALETTE,
                new byte[LcaOrderlessDictWireTable.MAX_PATTERN_BYTES + 1]));

        byte[] p1 = patternOf(16, 1);
        assertNull(table.observeOrLookup(LcaOrderlessDictWireTable.KIND_SECTION_SLICE_OR_PALETTE, p1));
        DictSlotEntry entry1 = table.observeOrLookup(LcaOrderlessDictWireTable.KIND_SECTION_SLICE_OR_PALETTE, p1);
        assertNotNull(entry1);
        assertEquals(1, table.populatedSlotCount());
        assertSame(entry1, table.observeOrLookup(LcaOrderlessDictWireTable.KIND_SECTION_SLICE_OR_PALETTE, p1));
        assertEquals(LcaOrderlessDictWireTable.packRefWord16(entry1.slotId12(), entry1.tag8), entry1.refWord16());

        assertTrue(entry1.matches(entry1.tag8, entry1.kind, p1));
        assertFalse(entry1.matches((byte) (entry1.tag8 ^ 1), entry1.kind, p1));
        assertFalse(entry1.matches(entry1.tag8, (byte) 1, p1));
        assertFalse(entry1.matches(entry1.tag8, entry1.kind, patternOf(16, 2)));

        // Force a 2-way associative primary-slot collision so insertion spills into slotB,
        // and verify lookup hits existingB.
        LcaOrderlessDictWireTable immediate = new LcaOrderlessDictWireTable(1);
        int targetSlotA = LcaOrderlessDictWireTable.primarySlotIndex12(
                LcaOrderlessDictWireTable.computeContentHash32(LcaOrderlessDictWireTable.KIND_SECTION_SLICE_OR_PALETTE, p1));
        DictSlotEntry first = immediate.observeOrLookup(LcaOrderlessDictWireTable.KIND_SECTION_SLICE_OR_PALETTE, p1);
        assertNotNull(first);
        assertEquals(targetSlotA, first.slotId12());

        byte[] colliding = null;
        for (int seed = 2; seed < 200_000; seed++) {
            byte[] cand = patternOf(16, seed);
            int h = LcaOrderlessDictWireTable.computeContentHash32(
                    LcaOrderlessDictWireTable.KIND_SECTION_SLICE_OR_PALETTE, cand);
            if (LcaOrderlessDictWireTable.primarySlotIndex12(h) == targetSlotA) {
                colliding = cand;
                break;
            }
        }
        assertNotNull(colliding, "Expected to find a pattern sharing primary slotA");
        DictSlotEntry second = immediate.observeOrLookup(
                LcaOrderlessDictWireTable.KIND_SECTION_SLICE_OR_PALETTE, colliding);
        assertNotNull(second);
        assertNotEquals(first.slotId12(), second.slotId12());
        assertSame(second, immediate.observeOrLookup(
                LcaOrderlessDictWireTable.KIND_SECTION_SLICE_OR_PALETTE, colliding));

        // Pre-fill both thirdA and thirdB with distinct patterns before observing third
        // so both candidate slots are occupied and tryInsertIntoAssociativeSlots returns null.
        byte[] third = patternOf(16, 999_999);
        int h3 = LcaOrderlessDictWireTable.computeContentHash32(
                LcaOrderlessDictWireTable.KIND_SECTION_SLICE_OR_PALETTE, third);
        int thirdA = LcaOrderlessDictWireTable.primarySlotIndex12(h3);
        int thirdB = LcaOrderlessDictWireTable.secondarySlotIndex12(h3);
        boolean filledA = (thirdA == first.slotId12() || thirdA == second.slotId12());
        boolean filledB = (thirdB == first.slotId12() || thirdB == second.slotId12());
        for (int seed = 500_000; seed < 900_000 && !(filledA && filledB); seed++) {
            byte[] cand = patternOf(16, seed);
            int hc = LcaOrderlessDictWireTable.computeContentHash32(
                    LcaOrderlessDictWireTable.KIND_SECTION_SLICE_OR_PALETTE, cand);
            int slotC = LcaOrderlessDictWireTable.primarySlotIndex12(hc);
            if (!filledA && slotC == thirdA) {
                assertNotNull(immediate.observeOrLookup(LcaOrderlessDictWireTable.KIND_SECTION_SLICE_OR_PALETTE, cand));
                filledA = true;
            } else if (!filledB && slotC == thirdB) {
                assertNotNull(immediate.observeOrLookup(LcaOrderlessDictWireTable.KIND_SECTION_SLICE_OR_PALETTE, cand));
                filledB = true;
            }
        }
        assertTrue(filledA && filledB, "Expected both thirdA and thirdB to be pre-filled");
        assertNull(immediate.observeOrLookup(LcaOrderlessDictWireTable.KIND_SECTION_SLICE_OR_PALETTE, third));
    }

    @Test
    @DisplayName("encodeSectionWireSlicesWithDict emits delta learns once and handles uniform, literal, and overlay slices")
    void encodeSectionWireSlicesWithDictAndFullSnapshotRoundTrip() throws IOException {
        LcaOrderlessDictWireTable table = new LcaOrderlessDictWireTable(1);
        KnownSlotBitSet known = new KnownSlotBitSet();

        // Uniform section (bit 15 = 1), promoted 200-byte section (exercises multi-byte varint),
        // duplicate promoted section in same batch, and too-short raw literal section with dirty overlays.
        byte[] bigSlice = patternOf(200, 42);
        byte[] tinySlice = new byte[] {1, 2, 3};
        SectionWireSlice uniform = new SectionWireSlice(0, (short) 0x8001, new byte[0], new int[0]);
        SectionWireSlice promoted1 = new SectionWireSlice(1, (short) 0x0010, bigSlice, new int[] {0x12345678});
        SectionWireSlice promotedDup = new SectionWireSlice(2, (short) 0x0010, bigSlice, new int[0]);
        SectionWireSlice rawLiteral = new SectionWireSlice(3, (short) 0x0003, tinySlice, new int[] {0x01020304, 0x05060708});

        byte[] packet1 = table.encodeSectionWireSlicesWithDict(
                List.of(uniform, promoted1, promotedDup, rawLiteral), known);
        assertEquals(1, known.cardinality());

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(packet1))) {
            assertEquals(4, readVarInt(in));
            for (int i = 0; i < 4; i++) {
                assertEquals(i, in.readByte());
                in.readUnsignedShort();
            }
            int overlayMask = in.readInt();
            assertEquals((1 << 1) | (1 << 3), overlayMask);
            assertEquals(1, readVarInt(in));
            assertEquals(0x12345678, in.readInt());
            assertEquals(2, readVarInt(in));
            assertEquals(0x01020304, in.readInt());
            assertEquals(0x05060708, in.readInt());

            int odctLen = readVarInt(in);
            byte[] odct = in.readNBytes(odctLen);
            try (DataInputStream din = new DataInputStream(new ByteArrayInputStream(odct))) {
                assertEquals(LcaOrderlessDictWireTable.ORDERLESS_FRAME_MAGIC, din.readInt());
                assertEquals(1, readVarInt(din), "Duplicate promoted slice must only emit one learn entry");
                din.readUnsignedShort(); // refWord16
                assertEquals(LcaOrderlessDictWireTable.KIND_SECTION_SLICE_OR_PALETTE, din.readByte());
                din.readByte(); // tag8
                int patLen = readVarInt(din);
                assertArrayEquals(bigSlice, din.readNBytes(patLen));

                assertEquals(3, readVarInt(din), "3 non-uniform slices");
                assertEquals(LcaOrderlessDictWireTable.TOKEN_SLOT_REFERENCE, din.readUnsignedByte());
                int ref1 = din.readUnsignedShort();
                assertEquals(LcaOrderlessDictWireTable.TOKEN_SLOT_REFERENCE, din.readUnsignedByte());
                assertEquals(ref1, din.readUnsignedShort());
                assertEquals(LcaOrderlessDictWireTable.TOKEN_RAW_LITERAL, din.readUnsignedByte());
                assertArrayEquals(tinySlice, din.readNBytes(readVarInt(din)));
            }
        }

        // Second packet for same session must emit 0 newly learned entries
        byte[] packet2 = table.encodeSectionWireSlicesWithDict(List.of(promoted1), known);
        assertTrue(packet2.length < packet1.length);

        // Full dictionary snapshot into a fresh session bitset
        KnownSlotBitSet resynced = new KnownSlotBitSet();
        byte[] snap = table.encodeFullDictionarySnapshot(resynced);
        assertEquals(table.populatedSlotCount(), resynced.cardinality());
        try (DataInputStream din = new DataInputStream(new ByteArrayInputStream(snap))) {
            assertEquals(LcaOrderlessDictWireTable.ORDERLESS_FRAME_MAGIC, din.readInt());
            assertEquals(table.populatedSlotCount(), readVarInt(din));
        }
    }

    @Test
    @DisplayName("KnownSlotBitSet guards out-of-range indices and supports unionFrom, cardinality, and clear")
    void knownSlotBitSetOperations() {
        KnownSlotBitSet a = new KnownSlotBitSet();
        KnownSlotBitSet b = new KnownSlotBitSet();

        assertFalse(a.isKnown(-1));
        assertFalse(a.isKnown(LcaOrderlessDictWireTable.TABLE_SLOT_COUNT));
        a.markKnown(-1);
        a.markKnown(LcaOrderlessDictWireTable.TABLE_SLOT_COUNT);
        assertEquals(0, a.cardinality());

        a.markKnown(0);
        a.markKnown(63);
        b.markKnown(63);
        b.markKnown(LcaOrderlessDictWireTable.TABLE_SLOT_COUNT - 1);
        assertTrue(a.isKnown(0));
        assertFalse(a.isKnown(LcaOrderlessDictWireTable.TABLE_SLOT_COUNT - 1));

        a.unionFrom(b);
        assertEquals(3, a.cardinality());
        assertTrue(a.isKnown(LcaOrderlessDictWireTable.TABLE_SLOT_COUNT - 1));

        a.clear();
        assertEquals(0, a.cardinality());
        assertFalse(a.isKnown(0));
    }
}
