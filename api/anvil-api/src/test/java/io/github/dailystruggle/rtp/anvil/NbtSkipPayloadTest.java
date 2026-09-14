package io.github.dailystruggle.rtp.anvil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Exercises {@link Nbt#skipPayload} and {@link Nbt#readRootCompoundSelective}.
 *
 * <p>The selective parser is
 * only safe when {@code skipPayload} consumes <i>exactly</i> the same byte range that
 * {@link Nbt#readRootCompound}'s recursive reader would. Any drift - e.g. a
 * {@code TAG_LIST} of strings whose length is miscomputed - desynchronises the stream
 * and produces garbage on downstream tags.
 *
 * <p>Tests cover (a) skip fidelity across every tag type via byte-range comparison,
 * (b) {@code readRootCompoundSelective} preserving the subset of tags selected, and
 * (c) real-world fixtures (server-produced {@code r.0.0.mca}) surviving the selective
 * path without corrupting later tags.
 */
class NbtSkipPayloadTest {

    @Test
    @DisplayName("skipPayload consumes the same byte count as readPayload for every tag type in a real chunk")
    void skipPayloadConsumesSameBytesAsReadPayload() throws IOException {
        // Build a synthetic tree covering every tag type skipPayload must handle.
        byte[] bytes = buildEveryTagTypeRoot();

        // Baseline: full parse.
        LinkedHashMap<String, Object> full = Nbt.readRootCompound(bytes);
        assertEquals(11, full.size(), "synthetic root must exercise every scalar tag type");

        // Selective: skip every root child. Stream must terminate cleanly at TAG_End.
        LinkedHashMap<String, Object> empty = Nbt.readRootCompoundSelective(
                bytes, (path, name, type) -> Nbt.SelectiveFilter.Decision.SKIP);
        assertTrue(empty.isEmpty(), "selective parser asked to skip everything must yield an empty compound");
    }

    @Test
    @DisplayName("readRootCompoundSelective keeps exactly the children it was told to keep")
    void selectiveReaderHonoursDecisions() throws IOException {
        byte[] bytes = buildEveryTagTypeRoot();

        LinkedHashMap<String, Object> kept = Nbt.readRootCompoundSelective(
                bytes,
                (path, name, type) ->
                        ("IntValue".equals(name) || "LongArray".equals(name))
                                ? Nbt.SelectiveFilter.Decision.KEEP
                                : Nbt.SelectiveFilter.Decision.SKIP);

        assertEquals(2, kept.size(), "only the two KEEP-marked children should remain");
        assertEquals(42, kept.get("IntValue"));
        assertArrayEquals(new long[] {1L, 2L, 3L}, (long[]) kept.get("LongArray"));
    }

    @Test
    @DisplayName("RECURSE on a TAG_List descends into element compounds selectively")
    void recursingIntoListElements() throws IOException {
        byte[] bytes = buildSectionsLikeRoot();

        LinkedHashMap<String, Object> root = Nbt.readRootCompoundSelective(
                bytes,
                (path, name, type) -> {
                    if (path.isEmpty()) {
                        return "sections".equals(name)
                                ? Nbt.SelectiveFilter.Decision.RECURSE
                                : Nbt.SelectiveFilter.Decision.SKIP;
                    }
                    // Inside sections: RECURSE into each element compound, then keep only Y.
                    if (path.size() == 1 && "[]".equals(name)) {
                        return Nbt.SelectiveFilter.Decision.RECURSE;
                    }
                    if (path.size() == 2 && "Y".equals(name)) {
                        return Nbt.SelectiveFilter.Decision.KEEP;
                    }
                    return Nbt.SelectiveFilter.Decision.SKIP;
                });

        Nbt.NbtList sections = assertInstanceOf(Nbt.NbtList.class, root.get("sections"));
        assertEquals(3, sections.items.size(), "section count must round-trip");
        for (int i = 0; i < sections.items.size(); i++) {
            @SuppressWarnings("unchecked")
            LinkedHashMap<String, Object> sec = (LinkedHashMap<String, Object>) sections.items.get(i);
            assertEquals(1, sec.size(), "only Y was marked KEEP");
            assertEquals((byte) i, sec.get("Y"));
        }
    }

    @Test
    @DisplayName("readRootCompoundSelective keeping everything produces a map equivalent to readRootCompound")
    void keepAllEquivalentToFullParse() throws IOException {
        byte[] bytes = buildEveryTagTypeRoot();

        LinkedHashMap<String, Object> full = Nbt.readRootCompound(bytes);
        LinkedHashMap<String, Object> selective = Nbt.readRootCompoundSelective(
                bytes, (path, name, type) -> Nbt.SelectiveFilter.Decision.KEEP);

        assertEquals(full.keySet(), selective.keySet(), "root keys must match and preserve order");
        // Re-encode both trees and compare bytes for a structural equality check.
        byte[] fullReEncoded = Nbt.writeNamedRoot("", full);
        byte[] selectiveReEncoded = Nbt.writeNamedRoot("", selective);
        assertArrayEquals(fullReEncoded, selectiveReEncoded, "re-encoded bytes must match");
    }

    @Test
    @DisplayName("Selective parse of a real r.0.0.mca chunk preserves kept subtrees byte-for-byte")
    void realFixtureSelectiveParseKeepsSubtrees() throws IOException {
        // Use the 1.20 fixture; semantics are identical across the three supported DataVersions.
        byte[] regionBytes = loadRealFixture("1_20_R1");
        AnvilReader.ChunkEntry entry = AnvilReader.readChunkEntry(regionBytes, 0, 0);
        assertNotNull(entry);

        // Re-encode the full root to NBT bytes, then selectively re-read those bytes.
        byte[] rawNbt = Nbt.writeNamedRoot("", entry.root);

        LinkedHashMap<String, Object> selective = Nbt.readRootCompoundSelective(
                rawNbt,
                (path, name, type) -> {
                    if (path.isEmpty()) {
                        return "Heightmaps".equals(name) || "sections".equals(name)
                                ? Nbt.SelectiveFilter.Decision.KEEP
                                : Nbt.SelectiveFilter.Decision.SKIP;
                    }
                    return Nbt.SelectiveFilter.Decision.KEEP;
                });

        assertArrayEquals(
                AnvilReader.getMotionBlockingNoLeaves(entry.root),
                AnvilReader.getMotionBlockingNoLeaves(selective),
                "MOTION_BLOCKING_NO_LEAVES must survive a selective parse of a real fixture");
        assertEquals(
                sectionCount(AnvilReader.getSections(entry.root)),
                sectionCount(AnvilReader.getSections(selective)),
                "section count must survive selective parsing");
        assertNull(selective.get("block_entities"),
                "non-kept root children must be absent from the selective map");
    }

    // ---------------------------------------------------------------------------- fixtures

    /**
     * Builds an NBT root compound exercising every tag type except nested lists-of-lists
     * (those are covered separately by {@link #buildSectionsLikeRoot}).
     */
    private static byte[] buildEveryTagTypeRoot() throws IOException {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("ByteValue", (byte) 7);
        root.put("ShortValue", (short) 77);
        root.put("IntValue", 42);
        root.put("LongValue", 123456789012L);
        root.put("FloatValue", 1.25f);
        root.put("DoubleValue", 2.5d);
        root.put("ByteArray", new byte[] {1, 2, 3, 4});
        root.put("StringValue", "plains");
        root.put("IntArray", new int[] {10, 20, 30});
        root.put("LongArray", new long[] {1L, 2L, 3L});

        LinkedHashMap<String, Object> inner = new LinkedHashMap<>();
        inner.put("Nested", (byte) 1);
        root.put("Inner", inner);

        return Nbt.writeNamedRoot("", root);
    }

    /** Builds a root whose {@code sections} list mimics the Minecraft section layout. */
    private static byte[] buildSectionsLikeRoot() throws IOException {
        List<Object> sections = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            LinkedHashMap<String, Object> sec = new LinkedHashMap<>();
            sec.put("Y", (byte) i);
            LinkedHashMap<String, Object> blockStates = new LinkedHashMap<>();
            blockStates.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, new ArrayList<>()));
            sec.put("block_states", blockStates);
            sections.add(sec);
        }
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, sections));
        return Nbt.writeNamedRoot("", root);
    }

    private static int sectionCount(Nbt.NbtList sections) {
        return sections == null ? 0 : sections.items.size();
    }

    private static byte[] loadRealFixture(String dirName) throws IOException {
        String resource = "/anvil/real/" + dirName + "/r.0.0.mca";
        try (InputStream in = NbtSkipPayloadTest.class.getResourceAsStream(resource)) {
            if (in == null) fail("Real fixture not found on classpath: " + resource);
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("Nbt error handling and type inference edge cases")
    void nbtErrorHandlingAndInference() throws IOException {
        // Tag end root returns empty NamedTag
        Nbt.NamedTag endTag = Nbt.readNamedRoot(new byte[]{Nbt.TAG_END});
        assertEquals("", endTag.name);
        assertNull(endTag.value);

        // readRootCompound non-compound throws IOException
        byte[] stringRoot = Nbt.writeNamedRoot("stringRoot", "hello");
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> Nbt.readRootCompound(stringRoot));

        // readRootCompoundSelective non-compound throws IOException
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> Nbt.readRootCompoundSelective(stringRoot, (path, name, type) -> Nbt.SelectiveFilter.Decision.KEEP));

        // TAG_END selective returns empty map
        LinkedHashMap<String, Object> emptyEnd = Nbt.readRootCompoundSelective(new byte[]{Nbt.TAG_END},
                (path, name, type) -> Nbt.SelectiveFilter.Decision.KEEP);
        assertTrue(emptyEnd.isEmpty());

        // inferType edge cases
        assertEquals(Nbt.TAG_FLOAT, Nbt.inferType(1.5f));
        assertEquals(Nbt.TAG_DOUBLE, Nbt.inferType(2.5d));
        assertEquals(Nbt.TAG_SHORT, Nbt.inferType((short) 10));
        assertEquals(Nbt.TAG_BYTE, Nbt.inferType((byte) 5));
        assertEquals(Nbt.TAG_INT, Nbt.inferType(100));
        assertEquals(Nbt.TAG_LONG, Nbt.inferType(1000L));
        assertEquals(Nbt.TAG_BYTE_ARRAY, Nbt.inferType(new byte[2]));
        assertEquals(Nbt.TAG_INT_ARRAY, Nbt.inferType(new int[2]));
        assertEquals(Nbt.TAG_LONG_ARRAY, Nbt.inferType(new long[2]));
        assertEquals(Nbt.TAG_STRING, Nbt.inferType("str"));
        assertEquals(Nbt.TAG_COMPOUND, Nbt.inferType(java.util.Map.of()));
        assertEquals(Nbt.TAG_LIST, Nbt.inferType(new Nbt.NbtList(Nbt.TAG_INT, java.util.List.of())));

        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> Nbt.inferType(null));
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> Nbt.inferType(new Object()));

        // TAG_LIST of TAG_END with 0 elements
        byte[] emptyListRoot = Nbt.writeNamedRoot("emptyList",
                java.util.Map.of("list", new Nbt.NbtList(Nbt.TAG_END, java.util.List.of())));
        LinkedHashMap<String, Object> decodedList = Nbt.readRootCompound(emptyListRoot);
        Nbt.NbtList readList = (Nbt.NbtList) decodedList.get("list");
        assertEquals(Nbt.TAG_END, readList.elementType);
        assertTrue(readList.items.isEmpty());

        // skipPayload on empty list of TAG_END
        LinkedHashMap<String, Object> skippedList = Nbt.readRootCompoundSelective(emptyListRoot,
                (path, name, type) -> Nbt.SelectiveFilter.Decision.SKIP);
        assertTrue(skippedList.isEmpty());

        // Test lists of floats, doubles, byte arrays, short arrays, etc.
        LinkedHashMap<String, Object> multiTypeMap = new LinkedHashMap<>();
        multiTypeMap.put("floatList", new Nbt.NbtList(Nbt.TAG_FLOAT, java.util.List.of(1.0f, 2.0f)));
        multiTypeMap.put("doubleList", new Nbt.NbtList(Nbt.TAG_DOUBLE, java.util.List.of(1.5d, 2.5d)));
        multiTypeMap.put("byteList", new Nbt.NbtList(Nbt.TAG_BYTE, java.util.List.of((byte) 1, (byte) 2)));
        multiTypeMap.put("shortList", new Nbt.NbtList(Nbt.TAG_SHORT, java.util.List.of((short) 10, (short) 20)));
        multiTypeMap.put("intList", new Nbt.NbtList(Nbt.TAG_INT, java.util.List.of(100, 200)));
        multiTypeMap.put("longList", new Nbt.NbtList(Nbt.TAG_LONG, java.util.List.of(1000L, 2000L)));
        multiTypeMap.put("stringList", new Nbt.NbtList(Nbt.TAG_STRING, java.util.List.of("alpha", "beta")));

        byte[] multiBytes = Nbt.writeNamedRoot("multi", multiTypeMap);

        // Test skip on all these fixed and variable width list types
        LinkedHashMap<String, Object> skippedMulti = Nbt.readRootCompoundSelective(multiBytes,
                (path, name, type) -> Nbt.SelectiveFilter.Decision.SKIP);
        assertTrue(skippedMulti.isEmpty());

        // Test selective KEEP on each
        LinkedHashMap<String, Object> keptMulti = Nbt.readRootCompoundSelective(multiBytes,
                (path, name, type) -> Nbt.SelectiveFilter.Decision.KEEP);
        assertEquals(7, keptMulti.size());

        // Test RECURSE into list with element decisions
        LinkedHashMap<String, Object> elemDecided = Nbt.readRootCompoundSelective(multiBytes,
                (path, name, type) -> {
                    if ("floatList".equals(name) || "stringList".equals(name)) return Nbt.SelectiveFilter.Decision.RECURSE;
                    if ("[]".equals(name)) return Nbt.SelectiveFilter.Decision.KEEP;
                    return Nbt.SelectiveFilter.Decision.SKIP;
                });
        assertEquals(2, elemDecided.size());

        // Test list of lists selective RECURSE and element SKIP/KEEP
        List<Object> outerList = new ArrayList<>();
        outerList.add(new Nbt.NbtList(Nbt.TAG_STRING, List.of("s1", "s2")));
        outerList.add(new Nbt.NbtList(Nbt.TAG_STRING, List.of("s3", "s4")));
        byte[] listListsBytes = Nbt.writeNamedRoot("rootList",
                java.util.Map.of("matrix", new Nbt.NbtList(Nbt.TAG_LIST, outerList)));

        LinkedHashMap<String, Object> recurseListLists = Nbt.readRootCompoundSelective(listListsBytes,
                (path, name, type) -> {
                    if ("matrix".equals(name)) return Nbt.SelectiveFilter.Decision.RECURSE;
                    if ("[]".equals(name)) return Nbt.SelectiveFilter.Decision.RECURSE;
                    return Nbt.SelectiveFilter.Decision.KEEP;
                });
        assertEquals(1, recurseListLists.size());

        // skipPayload error paths: negative lengths
        assertThrows(IOException.class, () -> Nbt.skipPayload(new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF})), Nbt.TAG_BYTE_ARRAY));
        assertThrows(IOException.class, () -> Nbt.skipPayload(new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF})), Nbt.TAG_INT_ARRAY));
        assertThrows(IOException.class, () -> Nbt.skipPayload(new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF})), Nbt.TAG_LONG_ARRAY));
        assertThrows(IOException.class, () -> Nbt.skipPayload(new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(new byte[]{0})), (byte) 99)); // unknown type
        assertThrows(IOException.class, () -> Nbt.skipPayload(new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(new byte[]{0})), Nbt.TAG_END));
    }
}
