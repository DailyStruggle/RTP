package io.github.dailystruggle.helpers.stresstestrtp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("StorageProfiler region directory discovery (legacy and 26.1+ layouts)")
class StorageProfilerRegionDirsTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("legacy layout: region, DIM-1/region and DIM1/region")
    void legacyLayout() throws Exception {
        Files.createDirectories(tmp.resolve("region"));
        Files.createDirectories(tmp.resolve("DIM-1/region"));
        Files.createDirectories(tmp.resolve("DIM1/region"));
        assertEquals(Set.of(tmp.resolve("region"), tmp.resolve("DIM-1/region"), tmp.resolve("DIM1/region")),
                new HashSet<>(StorageProfiler.regionDirs(tmp)));
    }

    @Test
    @DisplayName("26.1+ layout: every dimensions/<ns>/<key>/region directory")
    void unifiedLayout() throws Exception {
        Files.createDirectories(tmp.resolve("dimensions/minecraft/overworld/region"));
        Files.createDirectories(tmp.resolve("dimensions/minecraft/the_nether/region"));
        Files.createDirectories(tmp.resolve("dimensions/custom/mining/region"));
        Files.createDirectories(tmp.resolve("dimensions/minecraft/the_end/poi"));
        assertEquals(Set.of(
                        tmp.resolve("dimensions/minecraft/overworld/region"),
                        tmp.resolve("dimensions/minecraft/the_nether/region"),
                        tmp.resolve("dimensions/custom/mining/region")),
                new HashSet<>(StorageProfiler.regionDirs(tmp)));
    }

    @Test
    @DisplayName("no region directories: empty")
    void empty() {
        List<Path> dirs = StorageProfiler.regionDirs(tmp);
        assertTrue(dirs.isEmpty());
    }
}
