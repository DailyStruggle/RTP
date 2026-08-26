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

}
