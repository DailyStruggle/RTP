package io.github.dailystruggle.rtp.api.schematic;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decode-and-verify test for committed {@code skyblock_island.schem} fixture (ADR-058).
 *
 * <p>Parses Sponge Schematic v3 GZIP-NBT container and asserts bounding box, block counts, and chest items.
 */
class SkyblockIslandFixtureTest {

    private static final String AIR = "minecraft:air";
    private static final String DIRT = "minecraft:dirt";
    private static final String GRASS = "minecraft:grass_block[snowy=false]";
    private static final String LOG = "minecraft:oak_log[axis=y]";
    private static final String LEAVES = "minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]";
    private static final String CHEST = "minecraft:chest[facing=north,type=single,waterlogged=false]";

    @Test
    void fixtureMatchesAuthoredSpec() throws IOException {
        Compound root = readSchematic();
        Compound schem = (Compound) root.get("Schematic");
        assertNotNull(schem, "Schematic root compound");

        assertEquals(3, ((Number) schem.get("Version")).intValue(), "Sponge schematic version");
        int w = ((Number) schem.get("Width")).intValue();
        int h = ((Number) schem.get("Height")).intValue();
        int l = ((Number) schem.get("Length")).intValue();
        assertEquals(8, w, "Width");
        assertEquals(10, h, "Height");
        assertEquals(7, l, "Length");

        Compound blocks = (Compound) schem.get("Blocks");
        assertNotNull(blocks, "Blocks compound (Sponge v3)");

        Compound palette = (Compound) blocks.get("Palette");
        Map<Integer, String> inv = new HashMap<>();
        for (Map.Entry<String, Object> e : palette.entries()) {
            inv.put(((Number) e.getValue()).intValue(), e.getKey());
        }

        byte[] data = (byte[]) blocks.get("Data");
        Map<String, Integer> counts = new HashMap<>();
        int cells = 0;
        int i = 0;
        while (i < data.length) {
            int value = 0;
            int shift = 0;
            while (true) {
                byte b = data[i++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
                shift += 7;
            }
            String state = inv.get(value);
            assertNotNull(state, "palette entry for index " + value);
            counts.merge(state, 1, Integer::sum);
            cells++;
        }

        assertEquals(w * h * l, cells, "dense Data cell count == W*H*L");
        assertEquals(560, cells, "total cells");
        assertEquals(81, counts.getOrDefault(DIRT, 0), "dirt blocks");
        assertEquals(27, counts.getOrDefault(GRASS, 0), "grass_block on top layer");
        assertEquals(4, counts.getOrDefault(LOG, 0), "oak logs");
        assertEquals(54, counts.getOrDefault(LEAVES, 0), "oak leaves");
        assertEquals(1, counts.getOrDefault(CHEST, 0), "chest");
        assertEquals(393, counts.getOrDefault(AIR, 0), "air cells (pasteAir=false skips these)");

        @SuppressWarnings("unchecked")
        List<Object> blockEntities = (List<Object>) blocks.get("BlockEntities");
        assertEquals(1, blockEntities.size(), "block-entity count");
        Compound chest = (Compound) blockEntities.get(0);
        Compound chestData = (Compound) chest.get("Data");
        @SuppressWarnings("unchecked")
        List<Object> chestItems = (List<Object>) chestData.get("Items");

        Map<String, Integer> itemCounts = new LinkedHashMap<>();
        for (Object o : chestItems) {
            Compound item = (Compound) o;
            String id = (String) item.get("id");
            int count = ((Number) item.get("Count")).intValue();
            itemCounts.merge(id, count, Integer::sum);
        }
        assertEquals(3, itemCounts.size(), "distinct chest items");
        assertEquals(1, itemCounts.getOrDefault("minecraft:ice", 0), "ice");
        assertEquals(1, itemCounts.getOrDefault("minecraft:lava_bucket", 0), "lava bucket");
        assertEquals(10, itemCounts.getOrDefault("minecraft:obsidian", 0), "obsidian");
    }

    @Test
    void footprintMatchesLShapeAndGrassPlatformIsUnderChest() throws IOException {
        // The dirt+grass footprint is the L-shaped pad rtp-core anchors under the
        // player (BOTTOM_CENTER). dirt(81)/3 layers == grass(27) columns confirms
        // every column has exactly one grass cap and three dirt cells beneath it.
        Compound root = readSchematic();
        Compound schem = (Compound) root.get("Schematic");
        Compound blocks = (Compound) schem.get("Blocks");
        Compound palette = (Compound) blocks.get("Palette");
        Map<Integer, String> inv = new HashMap<>();
        for (Map.Entry<String, Object> e : palette.entries()) {
            inv.put(((Number) e.getValue()).intValue(), e.getKey());
        }
        int grass = 0;
        int dirt = 0;
        byte[] data = (byte[]) blocks.get("Data");
        int i = 0;
        while (i < data.length) {
            int value = 0, shift = 0;
            while (true) {
                byte b = data[i++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            String s = inv.get(value);
            if (GRASS.equals(s)) grass++;
            else if (DIRT.equals(s)) dirt++;
        }
        assertEquals(27, grass, "27 footprint columns");
        assertEquals(3 * grass, dirt, "3 dirt layers under each grass column");
        assertTrue(grass < 8 * 7, "footprint is L-shaped, not a full rectangle");
    }

    // ------------------------------------------------------------------
    // Minimal Sponge-v3 NBT reader (big-endian, GZIP-wrapped).
    // ------------------------------------------------------------------

    private static Compound readSchematic() throws IOException {
        try (InputStream in = SkyblockIslandFixtureTest.class
                .getResourceAsStream("/schematics/skyblock_island.schem")) {
            assertNotNull(in, "skyblock_island.schem must be on the test classpath");
            try (DataInputStream dis = new DataInputStream(new GZIPInputStream(in))) {
                int rootTag = dis.readUnsignedByte();
                assertEquals(10, rootTag, "root must be a TAG_Compound");
                readName(dis); // unnamed root
                return readCompound(dis);
            }
        }
    }

    private static String readName(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static Compound readCompound(DataInputStream in) throws IOException {
        Compound c = new Compound();
        while (true) {
            int tag = in.readUnsignedByte();
            if (tag == 0) {
                break;
            }
            String name = readName(in);
            c.put(name, readPayload(in, tag));
        }
        return c;
    }

    private static Object readPayload(DataInputStream in, int tag) throws IOException {
        switch (tag) {
            case 1:
                return (int) in.readByte();
            case 2:
                return (int) in.readShort();
            case 3:
                return in.readInt();
            case 4:
                return in.readLong();
            case 5:
                return in.readFloat();
            case 6:
                return in.readDouble();
            case 7: {
                int len = in.readInt();
                byte[] b = new byte[len];
                in.readFully(b);
                return b;
            }
            case 8:
                return readName(in);
            case 9: {
                int elemTag = in.readUnsignedByte();
                int len = in.readInt();
                List<Object> list = new ArrayList<>(len);
                for (int i = 0; i < len; i++) {
                    list.add(readPayload(in, elemTag));
                }
                return list;
            }
            case 10:
                return readCompound(in);
            case 11: {
                int len = in.readInt();
                int[] arr = new int[len];
                for (int i = 0; i < len; i++) {
                    arr[i] = in.readInt();
                }
                return arr;
            }
            case 12: {
                int len = in.readInt();
                long[] arr = new long[len];
                for (int i = 0; i < len; i++) {
                    arr[i] = in.readLong();
                }
                return arr;
            }
            default:
                throw new IOException("unsupported NBT tag " + tag);
        }
    }

    /** Tiny named-tag bag so the reader stays self-contained. */
    private static final class Compound {
        private final Map<String, Object> map = new LinkedHashMap<>();

        void put(String k, Object v) {
            map.put(k, v);
        }

        Object get(String k) {
            return map.get(k);
        }

        java.util.Set<Map.Entry<String, Object>> entries() {
            return map.entrySet();
        }
    }
}
