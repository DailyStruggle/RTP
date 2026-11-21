package io.github.dailystruggle.anvil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Gate 1 contract tests verifying lossless canonical blockstate views across {@link AnvilReader}
 * and {@link LcaRegionReader} while preserving bare-name {@code ADR-016} {@code readChunkView}
 * behavior for {@code RTP} safety prefilters.
 *
 * @governedBy ADR-016
 * @governedBy anvil-api-ADR-003
 */
class AnvilCanonicalBlockStateViewTest {

    @Test
    void formatsCanonicalBlockStatesDeterministicallyAndPreservesBareView() throws Exception {
        LinkedHashMap<String, Object> props = new LinkedHashMap<>();
        props.put("waterlogged", "false");
        props.put("half", "top");
        props.put("facing", "east");
        props.put("shape", "outer_left");
        LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
        entry.put("Name", "minecraft:crimson_stairs");
        entry.put("Properties", props);

        assertEquals(
                "minecraft:crimson_stairs[facing=east,half=top,shape=outer_left,waterlogged=false]",
                AnvilReader.formatCanonicalBlockState(entry)
        );

        byte[] nbtChunk = buildMinimalChunkNbt(3953, 2, 3, List.of(
                stateCompound("minecraft:air", Map.of()),
                stateCompound("minecraft:mangrove_stairs", Map.of(
                        "facing", "south",
                        "half", "top",
                        "shape", "straight",
                        "waterlogged", "false"
                )),
                stateCompound("minecraft:stripped_oak_log", Map.of("axis", "x")),
                stateCompound("minecraft:oak_trapdoor", Map.of(
                        "facing", "west",
                        "half", "top",
                        "open", "true",
                        "powered", "false",
                        "waterlogged", "false"
                ))
        ));

        byte[] emptyMca = new byte[8192];
        byte[] mcaBytes = AnvilReader.writeCompressedChunkToMcaBytes(emptyMca, 2, 3, nbtChunk);

        // 1. Existing ADR-016 bare-name readChunkView MUST remain unchanged
        AnvilChunkView bareView = AnvilReader.readChunkView(mcaBytes, 2, 3);
        assertNotNull(bareView);
        assertEquals("minecraft:mangrove_stairs", bareView.blockIdAt(1, 64, 0));
        assertEquals("minecraft:stripped_oak_log", bareView.blockIdAt(2, 64, 0));
        assertEquals("minecraft:oak_trapdoor", bareView.blockIdAt(3, 64, 0));

        // 2. New canonical state view MUST preserve full sorted [k=v,...] properties
        AnvilChunkView stateView = AnvilReader.readChunkStateView(mcaBytes, 2, 3);
        assertNotNull(stateView);
        assertEquals(
                "minecraft:mangrove_stairs[facing=south,half=top,shape=straight,waterlogged=false]",
                stateView.blockIdAt(1, 64, 0)
        );
        assertEquals(
                "minecraft:stripped_oak_log[axis=x]",
                stateView.blockIdAt(2, 64, 0)
        );
        assertEquals(
                "minecraft:oak_trapdoor[facing=west,half=top,open=true,powered=false,waterlogged=false]",
                stateView.blockIdAt(3, 64, 0)
        );

        // 3. Sector-level reader + normalizeCanonicalBlockState + DataVersion 5023+ lowercase properties
        int offsetSectors = ((mcaBytes[4 * (2 + 3 * 32)] & 0xFF) << 16)
                | ((mcaBytes[4 * (2 + 3 * 32) + 1] & 0xFF) << 8)
                | (mcaBytes[4 * (2 + 3 * 32) + 2] & 0xFF);
        int sectorCount = mcaBytes[4 * (2 + 3 * 32) + 3] & 0xFF;
        byte[] sectorSlice = java.util.Arrays.copyOfRange(
                mcaBytes, offsetSectors * 4096, (offsetSectors + sectorCount) * 4096);
        AnvilChunkView fromSectors = AnvilReader.readChunkStateViewFromSectors(sectorSlice, 2, 3);
        assertNotNull(fromSectors);
        assertEquals("minecraft:stripped_oak_log[axis=x]", fromSectors.blockIdAt(2, 64, 0));

        assertEquals("minecraft:air", AnvilReader.normalizeCanonicalBlockState(null));
        assertEquals("minecraft:air", AnvilReader.normalizeCanonicalBlockState("   "));
        assertEquals("minecraft:stone", AnvilReader.normalizeCanonicalBlockState("stone"));
        assertEquals("minecraft:stone", AnvilReader.normalizeCanonicalBlockState("minecraft:stone[]"));
        assertEquals("minecraft:oak_stairs[facing=west,half=top]",
                AnvilReader.normalizeCanonicalBlockState("oak_stairs[half=top,facing=west]"));

        LinkedHashMap<String, Object> dv5023 = new LinkedHashMap<>();
        dv5023.put("id", "minecraft:cherry_log");
        dv5023.put("properties", Map.of("axis", "z"));
        assertEquals("minecraft:cherry_log[axis=z]", AnvilReader.formatCanonicalBlockState(dv5023));
        assertEquals("minecraft:stone", AnvilReader.formatCanonicalBlockState("minecraft:stone"));
    }

    @Test
    void lcaRegionReaderPreservesCanonicalStatesForBothUniformAndMixedSections(@TempDir Path tempDir) throws Exception {
        byte[] nbtChunk = buildChunkWithUniformAndMixedSections(3953, 0, 0);
        byte[] mcaBytes = AnvilReader.writeCompressedChunkToMcaBytes(new byte[8192], 0, 0, nbtChunk);
        Path lcaFile = tempDir.resolve("r.0.0.lca");
        byte[] lcaBytes = LcaRegionWriter.transcodeFromMca(mcaBytes, 0, 0, false);
        java.nio.file.Files.write(lcaFile, lcaBytes);

        // Bare ADR-016 view strips properties
        AnvilChunkView bareLca = LcaRegionReader.readChunkView(lcaBytes, 0, 0);
        assertNotNull(bareLca);
        assertEquals("minecraft:dark_oak_log", bareLca.blockIdAt(0, 80, 0));
        assertEquals("minecraft:deepslate_tile_stairs", bareLca.blockIdAt(1, 64, 0));

        // Canonical state view preserves properties in both uniform inline and mixed sections
        AnvilChunkView stateLca = LcaRegionReader.readChunkStateView(lcaBytes, 0, 0);
        assertNotNull(stateLca);
        assertEquals("minecraft:dark_oak_log[axis=z]",
                AnvilReader.normalizeCanonicalBlockState(stateLca.blockIdAt(0, 80, 0)));
        assertEquals(
                "minecraft:deepslate_tile_stairs[facing=west,half=top,shape=inner_right,waterlogged=false]",
                AnvilReader.normalizeCanonicalBlockState(stateLca.blockIdAt(1, 64, 0))
        );
    }

    private static Map<String, Object> stateCompound(String name, Map<String, String> props) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("Name", name);
        if (!props.isEmpty()) {
            m.put("Properties", new LinkedHashMap<>(props));
        }
        return m;
    }

    private static byte[] buildMinimalChunkNbt(int dataVersion, int cx, int cz, List<Map<String, Object>> palette)
            throws Exception {
        long[] packed = new long[256]; // 4 bits per entry * 4096 / 64 = 256 longs
        // voxel (0,0,0)=0, (1,0,0)=1, (2,0,0)=2, (3,0,0)=3
        packed[0] = (0L) | (1L << 4) | (2L << 8) | (3L << 12);
        return writeChunkRootNbt(dataVersion, cx, cz, List.of(
                new SectionSpec(4, palette, packed)
        ));
    }

    private static byte[] buildChunkWithUniformAndMixedSections(int dataVersion, int cx, int cz) throws Exception {
        long[] packed = new long[256];
        packed[0] = (0L) | (1L << 4);
        return writeChunkRootNbt(dataVersion, cx, cz, List.of(
                new SectionSpec(4, List.of(
                        stateCompound("minecraft:air", Map.of()),
                        stateCompound("minecraft:deepslate_tile_stairs", Map.of(
                                "facing", "west",
                                "half", "top",
                                "shape", "inner_right",
                                "waterlogged", "false"
                        ))
                ), packed),
                new SectionSpec(5, List.of(
                        stateCompound("minecraft:dark_oak_log", Map.of("axis", "z"))
                ), null)
        ));
    }

    private record SectionSpec(int sectionY, List<Map<String, Object>> palette, long[] data) {}

    private static byte[] writeChunkRootNbt(int dataVersion, int cx, int cz, List<SectionSpec> sections)
            throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeByte(10); // TAG_Compound root
            writeUtf(out, "");

            out.writeByte(3); // TAG_Int DataVersion
            writeUtf(out, "DataVersion");
            out.writeInt(dataVersion);

            out.writeByte(3); // TAG_Int xPos
            writeUtf(out, "xPos");
            out.writeInt(cx);

            out.writeByte(3); // TAG_Int zPos
            writeUtf(out, "zPos");
            out.writeInt(cz);

            out.writeByte(8); // TAG_String Status
            writeUtf(out, "Status");
            writeUtf(out, "minecraft:full");

            out.writeByte(9); // TAG_List sections
            writeUtf(out, "sections");
            out.writeByte(10); // list of compounds
            out.writeInt(sections.size());

            for (SectionSpec sec : sections) {
                out.writeByte(1); // TAG_Byte Y
                writeUtf(out, "Y");
                out.writeByte((byte) sec.sectionY());

                out.writeByte(10); // TAG_Compound block_states
                writeUtf(out, "block_states");

                out.writeByte(9); // TAG_List palette
                writeUtf(out, "palette");
                out.writeByte(10);
                out.writeInt(sec.palette().size());
                for (Map<String, Object> entry : sec.palette()) {
                    out.writeByte(8); // TAG_String Name
                    writeUtf(out, "Name");
                    writeUtf(out, (String) entry.get("Name"));
                    @SuppressWarnings("unchecked")
                    Map<String, String> props = (Map<String, String>) entry.get("Properties");
                    if (props != null && !props.isEmpty()) {
                        out.writeByte(10); // TAG_Compound Properties
                        writeUtf(out, "Properties");
                        for (Map.Entry<String, String> pe : props.entrySet()) {
                            out.writeByte(8);
                            writeUtf(out, pe.getKey());
                            writeUtf(out, pe.getValue());
                        }
                        out.writeByte(0); // end Properties
                    }
                    out.writeByte(0); // end palette entry
                }

                if (sec.data() != null) {
                    out.writeByte(12); // TAG_Long_Array data
                    writeUtf(out, "data");
                    out.writeInt(sec.data().length);
                    for (long v : sec.data()) {
                        out.writeLong(v);
                    }
                }

                out.writeByte(0); // end block_states
                out.writeByte(0); // end section
            }

            out.writeByte(0); // end root
        }
        return baos.toByteArray();
    }

    private static void writeUtf(DataOutputStream out, String s) throws Exception {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }
}
