package io.github.dailystruggle.anvil;

import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.PbitResult;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RegionFormatDetector - World Directory Survey & Format Profiles")
class RegionFormatDetectorTest {

    private long originalRevalidateIntervalMillis;

    @BeforeEach
    void setUp() {
        originalRevalidateIntervalMillis = AnvilRegionByteCache.revalidateIntervalMillis();
        AnvilRegionByteCache.setRevalidateIntervalMillis(60_000L);
        RegionFormatDetector.INSTANCE.invalidateCache();
    }

    @AfterEach
    void restoreTestState() {
        RegionFormatDetector.INSTANCE.invalidateCache();
        AnvilRegionByteCache.setRevalidateIntervalMillis(originalRevalidateIntervalMillis);
    }

    @Test
    @DisplayName("Empty or non-existent directory resolves to EMPTY profile")
    void emptyDirectoryProfile(@TempDir Path tempDir) {
        RegionFormatDetector detector = RegionFormatDetector.INSTANCE;
        detector.invalidateCache();

        RegionFormatDetector.WorldFormatProfile profile = detector.detectProfile(tempDir);
        assertEquals(RegionFormatDetector.PrimaryFormat.EMPTY, profile.primaryFormat());
        assertFalse(profile.hasMixedFormats());

        RegionFormatDetector.WorldFormatProfile nonExistent =
                detector.detectProfile(tempDir.resolve("missing_region_dir"));
        assertEquals(RegionFormatDetector.PrimaryFormat.EMPTY, nonExistent.primaryFormat());
    }

    @Test
    @DisplayName("Directory with pure MCA files resolves to MCA profile")
    void pureMcaDirectory(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("r.0.0.mca"));
        Files.createFile(tempDir.resolve("r.0.1.mca"));

        RegionFormatDetector detector = RegionFormatDetector.INSTANCE;
        detector.invalidateCache();

        RegionFormatDetector.WorldFormatProfile profile = detector.detectProfile(tempDir);
        assertEquals(RegionFormatDetector.PrimaryFormat.MCA, profile.primaryFormat());
        assertFalse(profile.hasMixedFormats());
    }

    @Test
    @DisplayName("Directory with pure Linear files resolves to LINEAR profile")
    void pureLinearDirectory(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("r.0.0.linear"));
        Files.createFile(tempDir.resolve("r.1.0.linear"));

        RegionFormatDetector detector = RegionFormatDetector.INSTANCE;
        detector.invalidateCache();

        RegionFormatDetector.WorldFormatProfile profile = detector.detectProfile(tempDir);
        assertEquals(RegionFormatDetector.PrimaryFormat.LINEAR, profile.primaryFormat());
        assertFalse(profile.hasMixedFormats());
    }

    @Test
    @DisplayName("Directory with mixed formats detects both and flags hasMixedFormats")
    void mixedDirectory(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("r.0.0.mca"));
        Files.createFile(tempDir.resolve("r.0.0.linear"));

        RegionFormatDetector detector = RegionFormatDetector.INSTANCE;
        detector.invalidateCache();

        RegionFormatDetector.WorldFormatProfile profile = detector.detectProfile(tempDir);
        assertTrue(profile.hasMixedFormats());
        assertEquals(RegionFormatDetector.PrimaryFormat.LINEAR, profile.primaryFormat());
    }

    @Test
    @DisplayName("Detected format is kept until invalidateCache, even as files are added")
    void detectedFormatIsSticky(@TempDir Path tempDir) throws IOException {
        assertTrue(AnvilRegionByteCache.revalidateIntervalNanos() > 0L,
                "format detection stickiness requires a positive cache window");
        Files.createFile(tempDir.resolve("r.0.0.mca"));
        RegionFormatDetector detector = RegionFormatDetector.INSTANCE;
        detector.invalidateCache();
        assertEquals(RegionFormatDetector.PrimaryFormat.MCA, detector.detectProfile(tempDir).primaryFormat());

        Files.createFile(tempDir.resolve("r.5.5.linear"));
        RegionFormatDetector.WorldFormatProfile cached = detector.detectProfile(tempDir);
        assertEquals(RegionFormatDetector.PrimaryFormat.MCA, cached.primaryFormat());
        assertFalse(cached.hasMixedFormats());

        detector.invalidateCache();
        assertTrue(detector.detectProfile(tempDir).hasMixedFormats());
    }

    @Test
    @DisplayName("PBIT execution reports healthy status")
    void pbitExecution() {
        RegionFormatDetector detector = RegionFormatDetector.INSTANCE;
        PbitResult result = detector.executePbit();
        assertNotNull(result);
        assertTrue(result.passed());
        assertEquals(BitHealth.HEALTHY, detector.getHealthStatus());

        assertEquals(".mca", RegionFormatDetector.PrimaryFormat.MCA.defaultExtension());
        assertEquals(".linear", RegionFormatDetector.PrimaryFormat.LINEAR.defaultExtension());
        assertEquals(".lca", RegionFormatDetector.PrimaryFormat.LCA.defaultExtension());
        assertEquals(".mca", RegionFormatDetector.PrimaryFormat.EMPTY.defaultExtension());
    }

    @Test
    @DisplayName("Directory with pure and mixed .lca files resolves to LCA profile and AnvilRegionHeaderCache loads .lca headers")
    void lcaDirectoryProfileAndHeaderCache(@TempDir Path tempDir) throws IOException {
        RegionFormatDetector detector = RegionFormatDetector.INSTANCE;
        detector.invalidateCache();

        assertEquals(RegionFormatDetector.PrimaryFormat.EMPTY, detector.detectProfile(null).primaryFormat());

        Path lcaFile = tempDir.resolve("r.0.0.lca");
        byte[] headerBytes = new byte[LcaRegionReader.SUPERBLOCK_SIZE + LcaRegionReader.POINTER_DIRECTORY_SIZE];
        LcaRegionReader.LONG_BE_VIEW.set(headerBytes, 0, LcaRegionReader.LCA_SUPERBLOCK_MAGIC);
        long validPtr = (16L << 29) | (256L << 8) | 1L;
        LcaRegionReader.LONG_BE_VIEW.set(headerBytes, LcaRegionReader.POINTER_DIRECTORY_OFFSET, validPtr);
        Files.write(lcaFile, headerBytes);

        RegionFormatDetector.WorldFormatProfile pureLca = detector.detectProfile(tempDir);
        assertEquals(RegionFormatDetector.PrimaryFormat.LCA, pureLca.primaryFormat());
        assertFalse(pureLca.hasMixedFormats());

        AnvilRegionHeaderCache.invalidateAll();
        AnvilRegionHeaderCache.resetStats();
        AnvilRegionHeaderCache.Header h = AnvilRegionHeaderCache.get(lcaFile);
        assertNotNull(h);
        assertTrue(h.isOccupied(0));
        assertFalse(h.isOccupied(1));
        assertEquals(2, h.sectorOffset(0));
        assertEquals(1, h.sectorCount(0));

        // Mixed .lca + .mca (with >5 files of each to exercise early break)
        for (int i = 0; i < 7; i++) {
            Files.createFile(tempDir.resolve("r.1." + i + ".lca"));
            Files.createFile(tempDir.resolve("r.2." + i + ".mca"));
        }
        detector.invalidateCache();
        RegionFormatDetector.WorldFormatProfile mixedLca = detector.detectProfile(tempDir);
        assertEquals(RegionFormatDetector.PrimaryFormat.LCA, mixedLca.primaryFormat());
        assertTrue(mixedLca.hasMixedFormats());

        // Zero revalidate interval exercises direct survey path
        AnvilRegionByteCache.setRevalidateIntervalMillis(0L);
        assertEquals(RegionFormatDetector.PrimaryFormat.LCA, detector.detectProfile(tempDir).primaryFormat());
    }
}
