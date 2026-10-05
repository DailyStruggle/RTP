package io.github.dailystruggle.rtp.anvil;

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Anvil & Linear Region Coverage-Guided Fuzz Tests")
class AnvilRegionFuzzTest {

    @FuzzTest(maxDuration = "2s")
    @DisplayName("Fuzz AnvilReader.readChunkView with arbitrary byte mutations")
    void fuzzReadChunkView(byte[] data) {
        if (data == null) {
            return;
        }

        try {
            AnvilReader.readChunkView(data, 0, 0);
        } catch (IOException | IllegalArgumentException expected) {
            // Expected safe fail-closed behavior for malformed region data
        }
    }

    @FuzzTest(maxDuration = "2s")
    @DisplayName("Fuzz AnvilReader.readColumnProbe with arbitrary byte mutations")
    void fuzzReadColumnProbe(byte[] data) {
        if (data == null) {
            return;
        }

        try {
            AnvilReader.readColumnProbe(data, 0, 0, -64, 320);
        } catch (IOException | IllegalArgumentException expected) {
            // Expected safe fail-closed behavior for malformed region data
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

    @FuzzTest(maxDuration = "2s")
    @DisplayName("Fuzz LinearRegionReader.readChunk with arbitrary byte mutations")
    void fuzzReadLinearChunk(byte[] data) {
        if (data == null) {
            return;
        }

        try {
            LinearRegionReader.INSTANCE.readChunk(data, 0, 0);
        } catch (IOException | IllegalArgumentException expected) {
            // Expected safe fail-closed behavior for malformed region data
        }
    }
}
