package io.github.dailystruggle.rtp.anvil;

import io.github.dailystruggle.rtp.anvil.bit.BitHealth;
import io.github.dailystruggle.rtp.anvil.bit.PbitResult;
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
        assertEquals(".mca", RegionFormatDetector.PrimaryFormat.EMPTY.defaultExtension());
    }
}
