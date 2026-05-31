package io.github.dailystruggle.rtp.common.commands.prefab;

import io.github.dailystruggle.rtp.common.commands.prefab.builtin.Skyblock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link PrefabSchematicInstaller}: the {@code schematic}-knob scan and
 * the jar-to-disk extraction that backs the {@link Skyblock} prefab's
 * {@code schematic: "skyblock"} reference (ADR-058). A test-classpath copy of
 * the bundled {@code /schematics/skyblock.schem} resource lives under
 * {@code src/test/resources/schematics/}.
 */
class PrefabSchematicInstallerTest {

    @Test
    @DisplayName("schematicNames extracts the skyblock knob from the Skyblock prefab")
    void scanFindsSkyblock() {
        Set<String> names = PrefabSchematicInstaller.schematicNames(Skyblock.INSTANCE);
        assertEquals(Set.of("skyblock"), names);
    }

    @Test
    @DisplayName("schematicNames returns empty for a prefab with no schematic knob")
    void scanEmptyWhenAbsent() {
        Prefab plain = new Prefab(
                "plain", "k", "h", "d",
                Map.of(),
                Map.of(),
                Map.of("default", Map.of("shape", Map.of("radius", 100))),
                false);
        assertTrue(PrefabSchematicInstaller.schematicNames(plain).isEmpty());
    }

    @Test
    @DisplayName("install copies the bundled skyblock.schem to <pluginDir>/schematics/<region>.schem")
    void installExtractsBundledSchematic(@TempDir Path pluginDir) throws IOException {
        PrefabSchematicInstaller.Result r =
                PrefabSchematicInstaller.install(pluginDir.toFile(), Skyblock.INSTANCE);

        assertEquals(java.util.List.of("skyblock"), r.installed());
        assertTrue(r.skippedExisting().isEmpty());
        assertTrue(r.missingResource().isEmpty());

        // The Skyblock prefab overlays the "default" region, and core resolves the
        // arrival schematic by region name, so the bundled skyblock.schem must land
        // as schematics/default.schem (not schematics/skyblock.schem).
        File dest = pluginDir.resolve("schematics").resolve("default.schem").toFile();
        assertTrue(dest.isFile(), "schematic must be extracted to the region-named file");
        assertTrue(dest.length() > 0, "extracted schematic must not be empty");
        assertFalse(pluginDir.resolve("schematics").resolve("skyblock.schem").toFile().exists(),
                "must not write under the resource name; resolution is by region name");
    }

    @Test
    @DisplayName("install does not overwrite an existing schematic file")
    void installSkipsExisting(@TempDir Path pluginDir) throws IOException {
        Path dir = Files.createDirectories(pluginDir.resolve("schematics"));
        Path dest = dir.resolve("default.schem");
        byte[] sentinel = "operator-edited".getBytes();
        Files.write(dest, sentinel);

        PrefabSchematicInstaller.Result r =
                PrefabSchematicInstaller.install(pluginDir.toFile(), Skyblock.INSTANCE);

        assertEquals(java.util.List.of("skyblock"), r.skippedExisting());
        assertTrue(r.installed().isEmpty());
        assertEquals("operator-edited", new String(Files.readAllBytes(dest)),
                "existing file must be left untouched");
    }

    @Test
    @DisplayName("install records a missing bundled resource rather than throwing")
    void installRecordsMissingResource(@TempDir Path pluginDir) throws IOException {
        Prefab missing = new Prefab(
                "missing", "k", "h", "d",
                Map.of(),
                Map.of(),
                Map.of("default", Map.of("schematic", "does-not-exist")),
                false);

        PrefabSchematicInstaller.Result r =
                PrefabSchematicInstaller.install(pluginDir.toFile(), missing);

        assertEquals(java.util.List.of("does-not-exist"), r.missingResource());
        assertTrue(r.installed().isEmpty());
        assertFalse(pluginDir.resolve("schematics").resolve("default.schem")
                .toFile().exists());
    }
}
