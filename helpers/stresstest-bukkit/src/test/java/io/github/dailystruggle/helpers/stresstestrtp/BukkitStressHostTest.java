package io.github.dailystruggle.helpers.stresstestrtp;

import io.github.dailystruggle.server.api.ServerAccessor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code stresstest-bukkit} carrier contracts (ADR-122 Decisions 1, 2, 3).
 */
class BukkitStressHostTest {

    @Test
    @DisplayName("StressTestRTPPlugin implements StressHost and ServerAccessor")
    void pluginImplementsStressHostAndServerAccessor() {
        assertTrue(StressHost.class.isAssignableFrom(StressTestRTPPlugin.class));
        assertTrue(ServerAccessor.class.isAssignableFrom(StressTestRTPPlugin.class));
    }

    @Test
    @DisplayName("convertBukkitConfig copies nested sections and target-commands into StressConfig")
    void convertsBukkitFileConfiguration() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("default-concurrency", 8);
        yml.set("ramp.pass.max-mspt-p95", 45.0);
        yml.set("target-commands", List.of(
                Map.of("label", "rtp", "command", "rtp:rtp player:{player}", "sender", "console")));

        StressConfig cfg = StressTestRTPPlugin.convertBukkitConfig(yml);
        assertEquals(8, cfg.getInt("default-concurrency", 4));
        assertEquals(45.0, cfg.getDouble("ramp.pass.max-mspt-p95", 50.0), 1e-6);
        List<Targets.Entry> entries = Targets.load(cfg);
        assertEquals(1, entries.size());
        assertEquals("rtp", entries.get(0).label);
        assertFalse(entries.get(0).dispatchAsPlayer(true));
    }

    @Test
    @DisplayName("ADR-122 Decision 3: stresstest-bukkit shadowJar relocates server-api into harness namespace")
    void shadowJarRelocatesServerApi() throws Exception {
        Path jar = locateShadowJar();
        assertTrue(Files.isRegularFile(jar), "Expected shadow jar: " + jar);
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            assertNotNull(zip.getEntry("io/github/dailystruggle/helpers/stresstestrtp/shaded/server/api/ServerAccessor.class"),
                    "server-api must be shaded and relocated into harness namespace");
            assertNull(zip.getEntry("io/github/dailystruggle/server/api/ServerAccessor.class"),
                    "unrelocated server-api must not exist in shadow jar");
            assertNotNull(zip.getEntry("org/jacoco/core/data/ExecutionDataReader.class"),
                    "JaCoCo execution-data reader used by CoverageProbe must be retained");
            assertNull(zip.getEntry("org/jacoco/core/instr/Instrumenter.class"),
                    "unused JaCoCo instrumentation classes must be stripped by minimize()");
            assertNull(zip.getEntry("org/objectweb/asm/ClassVisitor.class"),
                    "unused transitive ASM classes must be stripped by minimize()");
            assertNull(zip.getEntry("org/jetbrains/annotations/NotNull.class"),
                    "compile-only JetBrains annotations must be excluded from shadow jar");
            assertNotNull(zip.getEntry("plugin.yml"));
            assertNotNull(zip.getEntry("config.yml"));
        }
    }

    private static Path locateShadowJar() {
        for (String rel : List.of(
                "build/libs/stresstest-bukkit-1.0-SNAPSHOT.jar",
                "../stresstest-bukkit/build/libs/stresstest-bukkit-1.0-SNAPSHOT.jar",
                "helpers/stresstest-bukkit/build/libs/stresstest-bukkit-1.0-SNAPSHOT.jar")) {
            Path p = Path.of(rel);
            if (Files.isRegularFile(p)) return p;
        }
        return Path.of("build/libs/stresstest-bukkit-1.0-SNAPSHOT.jar");
    }
}
