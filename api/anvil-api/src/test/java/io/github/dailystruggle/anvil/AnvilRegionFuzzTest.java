package io.github.dailystruggle.anvil;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Anvil Region Coverage-Guided Fuzz Tests")
class AnvilRegionFuzzTest {

    private static final Set<String> UNSAFE_LAVA = Set.of("LAVA");

    @FuzzTest(maxDuration = "2s")
    @DisplayName("Fuzz AnvilReader.readChunkView with fuzzer-chosen coordinates and view queries")
    void fuzzReadChunkView(FuzzedDataProvider data) {
        if (data == null) {
            return;
        }
        int cx = data.consumeInt(0, 31);
        int cz = data.consumeInt(0, 31);
        int lx = data.consumeInt(0, 15);
        int lz = data.consumeInt(0, 15);
        int worldY = data.consumeInt(-64, 320);
        byte[] regionBytes = data.consumeRemainingAsBytes();

        exerciseChunkView(regionBytes, 0, 0, lx, worldY, lz);
        if (cx != 0 || cz != 0) {
            exerciseChunkView(regionBytes, cx, cz, lx, worldY, lz);
        }
    }

    private static void exerciseChunkView(byte[] regionBytes, int cx, int cz, int lx, int worldY, int lz) {
        try {
            AnvilChunkView view = AnvilReader.readChunkView(regionBytes, cx, cz);
            if (view != null) {
                view.minHeight();
                view.getHeightmapTopY(lx, lz);
                view.blockIdAt(lx, worldY, lz);
                view.isAir(lx, worldY, lz);
                view.isSafe(lx, worldY, lz, UNSAFE_LAVA);
                view.getBiomeAt(lx, worldY, lz);
                view.getBiomesPresent();
                for (PaletteSection s : view.sections()) {
                    s.hasMalformedData();
                    s.blockIdAt(lx, worldY & 15, lz);
                }
                for (BiomePaletteSection bs : view.biomeSections()) {
                    bs.hasMalformedData();
                    bs.biomeIdAt(lx, worldY & 15, lz);
                }
            }
        } catch (IOException | UnsupportedAnvilFormatException expected) {
            // Expected safe fail-closed behavior for malformed region data; IllegalArgumentException
            // must not escape when coordinates are in range.
        }
    }

    @FuzzTest(maxDuration = "2s")
    @DisplayName("Fuzz AnvilReader.readColumnProbe with fuzzer-chosen coordinates and column queries")
    void fuzzReadColumnProbe(FuzzedDataProvider data) {
        if (data == null) {
            return;
        }
        int cx = data.consumeInt(0, 31);
        int cz = data.consumeInt(0, 31);
        int y1 = data.consumeInt(-64, 320);
        int y2 = data.consumeInt(-64, 320);
        int minY = Math.min(y1, y2);
        int maxY = Math.max(y1, y2);
        int queryY = data.consumeInt(minY, maxY);
        int lx = data.consumeInt(0, 15);
        int lz = data.consumeInt(0, 15);
        byte[] regionBytes = data.consumeRemainingAsBytes();

        exerciseColumnProbe(regionBytes, 0, 0, minY, maxY, lx, lz, queryY);
        if (cx != 0 || cz != 0) {
            exerciseColumnProbe(regionBytes, cx, cz, minY, maxY, lx, lz, queryY);
        }
    }

    private static void exerciseColumnProbe(
            byte[] regionBytes, int cx, int cz, int minY, int maxY, int lx, int lz, int queryY) {
        try {
            ColumnProbe probe = AnvilReader.readColumnProbe(regionBytes, cx, cz, minY, maxY);
            if (probe != null) {
                probe.hasHeightmap();
                probe.heightmapTopY();
                probe.blockAt(queryY);
                probe.blockAt(lx, lz, queryY);
                probe.biomeAt(queryY);
            }
        } catch (IOException | UnsupportedAnvilFormatException expected) {
            // Expected safe fail-closed behavior for malformed region data; IllegalArgumentException
            // must not escape when minY <= maxY and coordinates are in range.
        }
    }

    @FuzzTest(maxDuration = "2s")
    @DisplayName("Fuzz AnvilPrefilter: malformed input or malformed palette never yields ACCEPT")
    void fuzzPrefilterNeverAcceptsMalformed(FuzzedDataProvider data) throws IOException {
        if (data == null) {
            return;
        }
        Path tempWorld = Files.createTempDirectory("rtp-anvil-fuzz-");
        try {
            Path regionDir = tempWorld.resolve("region");
            Files.createDirectories(regionDir);
            Path mcaFile = regionDir.resolve("r.0.0.mca");

            boolean useSyntheticPaletteMutation = data.consumeBoolean();
            int cx = data.consumeInt(0, 31);
            int cz = data.consumeInt(0, 31);
            if (useSyntheticPaletteMutation) {
                int paletteSize = data.consumeInt(2, 8);
                String[] names = new String[paletteSize];
                names[0] = "minecraft:stone";
                for (int i = 1; i < paletteSize; i++) {
                    names[i] = i == 1 ? "minecraft:lava" : "minecraft:dirt_" + i;
                }
                // Deliberately malformed (truncated or out-of-range) packed data array
                int dataLen = data.consumeInt(0, 16);
                long[] packedData = dataLen == 0 ? null : new long[dataLen];
                for (int i = 0; i < dataLen; i++) {
                    packedData[i] = data.consumeLong();
                }
                long[] heightmap = new long[37];
                heightmap[0] = 1L;
                LinkedHashMap<String, Object> sec =
                        AnvilTestFixtures.section((byte) 0, Arrays.asList(names), packedData);
                LinkedHashMap<String, Object> root = AnvilTestFixtures.chunkRoot(
                        DataVersionSupport.MC_1_20_DATA_VERSION, heightmap, Collections.singletonList(sec));
                Files.write(mcaFile, AnvilTestFixtures.writeSingleChunkRegion(root));
                RegionFileResolver.invalidateMemo();
                AnvilRegionByteCache.resetAll();
                AnvilRegionHeaderCache.invalidateAll();

                AnvilPrefilter.ProbeResult res =
                        AnvilPrefilter.probeSyncDetailed(tempWorld, "", 0, 0, UNSAFE_LAVA);
                assertNotNull(res);
                assertNotEquals(
                        Verdict.ACCEPT,
                        res.verdict(),
                        "Multi-entry palette with truncated/malformed data must never yield ACCEPT");
            } else {
                byte[] rawBytes = data.consumeRemainingAsBytes();
                Files.write(mcaFile, rawBytes);
                RegionFileResolver.invalidateMemo();
                AnvilRegionByteCache.resetAll();
                AnvilRegionHeaderCache.invalidateAll();

                AnvilPrefilter.ProbeResult res =
                        AnvilPrefilter.probeSyncDetailed(tempWorld, "", cx, cz, UNSAFE_LAVA);
                assertNotNull(res);
                if (res.verdict() == Verdict.ACCEPT) {
                    assertNotNull(res.view(), "ACCEPT verdict must carry a non-null decoded view");
                    for (PaletteSection s : res.view().sections()) {
                        assertNotEquals(
                                true,
                                s.hasMalformedData(),
                                "ACCEPT verdict must never contain a section with malformed palette data");
                    }
                }
            }
        } finally {
            RegionFileResolver.invalidateMemo();
            AnvilRegionByteCache.resetAll();
            AnvilRegionHeaderCache.invalidateAll();
            Files.deleteIfExists(tempWorld.resolve("region").resolve("r.0.0.mca"));
            Files.deleteIfExists(tempWorld.resolve("region"));
            Files.deleteIfExists(tempWorld);
        }
    }

    @FuzzTest(maxDuration = "2s")
    @DisplayName("Fuzz Lz4BlockDecoder.decode: only CorruptRegionEntryException may escape")
    void fuzzLz4BlockDecoder(byte[] data) {
        if (data == null) {
            return;
        }

        try {
            Lz4BlockDecoder.decode(data, 0, data.length, AnvilReader.MAX_DECOMPRESSED_CHUNK_BYTES);
        } catch (CorruptRegionEntryException expected) {
            // Expected safe fail-closed behavior for malformed LZ4Block streams
        }
    }

    @FuzzTest(maxDuration = "2s")
    @DisplayName("Fuzz Nbt full and selective readers: only IOException may escape")
    void fuzzNbtReaders(byte[] data) {
        if (data == null) {
            return;
        }

        try {
            Nbt.readRootCompound(data);
        } catch (IOException expected) {
            // Expected safe fail-closed behavior for malformed NBT
        }
        try {
            Nbt.readRootCompoundSelective(data, (path, name, type) -> Nbt.SelectiveFilter.Decision.RECURSE);
        } catch (IOException expected) {
            // Expected safe fail-closed behavior for malformed NBT
        }
    }
}
