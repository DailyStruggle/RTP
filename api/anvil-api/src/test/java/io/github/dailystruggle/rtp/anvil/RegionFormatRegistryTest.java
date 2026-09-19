package io.github.dailystruggle.rtp.anvil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ADR-077 - RegionFormatRegistry & Pluggable Region Format")
class RegionFormatRegistryTest {

    @BeforeEach
    @AfterEach
    void reset() {
        RegionFormatRegistry.reset();
    }

    @Test
    @DisplayName(".mca is registered by default and cannot be unregistered")
    void testMcaDefault() {
        assertTrue(RegionFormatRegistry.isRegistered(".mca"));
        assertEquals(AnvilReader.INSTANCE, RegionFormatRegistry.getReader(".mca"));
        assertEquals(AnvilReader.INSTANCE, RegionFormatRegistry.getReader("mca"));

        assertNull(RegionFormatRegistry.unregister(".mca"));
        assertTrue(RegionFormatRegistry.isRegistered(".mca"));
    }

    @Test
    @DisplayName("Custom format can be registered and unregistered")
    void testCustomFormatRegistration() {
        RegionFileReader dummyReader = new RegionFileReader() {
            @Override
            public AnvilReader.ChunkEntry readChunk(byte[] regionBytes, int rx, int rz) {
                return null;
            }

            @Override
            public boolean isChunkGenerated(byte[] regionBytes, int rx, int rz) {
                return false;
            }
        };

        RegionFormatRegistry.register(".custom", dummyReader);
        assertTrue(RegionFormatRegistry.isRegistered(".custom"));
        assertTrue(RegionFormatRegistry.isRegistered("custom"));
        assertEquals(dummyReader, RegionFormatRegistry.getReader(".custom"));

        RegionFileReader removed = RegionFormatRegistry.unregister(".custom");
        assertEquals(dummyReader, removed);
        assertFalse(RegionFormatRegistry.isRegistered(".custom"));
        assertNull(RegionFormatRegistry.getReader(".custom"));
    }

    @Test
    @DisplayName("RegionFileResolver resolves registered formats or defaults to .mca")
    void testRegionFileResolverCustom(@TempDir Path tempDir) throws IOException {
        Path regionDir = tempDir.resolve("region");
        Files.createDirectories(regionDir);

        Path mcaFile = regionDir.resolve("r.0.0.mca");
        Path customFile = regionDir.resolve("r.0.0.srf");

        RegionFileReader dummyReader = new RegionFileReader() {
            @Override
            public AnvilReader.ChunkEntry readChunk(byte[] regionBytes, int rx, int rz) {
                return null;
            }

            @Override
            public boolean isChunkGenerated(byte[] regionBytes, int rx, int rz) {
                return true;
            }
        };

        RegionFormatRegistry.register(".srf", dummyReader);

        // When neither exists -> default to .mca path and Anvil reader
        RegionFileResolver.ResolvedRegion resNone = RegionFileResolver.resolve(tempDir, "", 0, 0);
        assertEquals(mcaFile, resNone.path());
        assertEquals(AnvilReader.INSTANCE, resNone.reader());

        // When .srf exists -> resolves .srf
        Files.write(customFile, new byte[10]);
        RegionFileResolver.ResolvedRegion resCustom = RegionFileResolver.resolve(tempDir, "", 0, 0);
        assertEquals(customFile, resCustom.path());
        assertEquals(dummyReader, resCustom.reader());
    }
}
