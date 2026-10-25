package io.github.dailystruggle.rtp.common.configuration;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ConfigLoader & LanguageBootstrap Standalone Unit Tests")
class ConfigLoaderAndLanguageBootstrapTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("LanguageBootstrap.sanitize normalizes valid locales and falls back on null/blank/unsafe")
    void languageBootstrapSanitize() {
        assertEquals("en", LanguageBootstrap.sanitize(null));
        assertEquals("en", LanguageBootstrap.sanitize(""));
        assertEquals("en", LanguageBootstrap.sanitize("   "));
        assertEquals("de", LanguageBootstrap.sanitize("  de "));
        assertEquals("pt_BR", LanguageBootstrap.sanitize("pt_BR"));
        assertEquals("zh-CN", LanguageBootstrap.sanitize("zh-CN"));
        assertEquals("en", LanguageBootstrap.sanitize("../etc/passwd"));
        assertEquals("en", LanguageBootstrap.sanitize("es/../../bad"));
        assertEquals("en", LanguageBootstrap.sanitize("fr;rm"));
        assertTrue(LanguageBootstrap.BUNDLED_LOCALES.contains("en"));
        assertTrue(LanguageBootstrap.BUNDLED_LOCALES.contains("de"));
    }

    @Test
    @DisplayName("LanguageBootstrap.resolve creates default language.yml when absent and reads configured locale")
    void languageBootstrapResolveLifecycle() throws IOException {
        assertEquals("en", LanguageBootstrap.resolve(null));

        File pluginDir = tempDir.resolve("plugin-data").toFile();
        assertFalse(pluginDir.exists());

        // First call creates directory and language.yml with default "en"
        String initial = LanguageBootstrap.resolve(pluginDir);
        assertEquals("en", initial);
        File langFile = new File(pluginDir, LanguageBootstrap.FILE_NAME);
        assertTrue(langFile.isFile());

        // Modify language.yml to "fr"
        Files.writeString(langFile.toPath(), "language: fr\n", StandardCharsets.UTF_8);
        assertEquals("fr", LanguageBootstrap.resolve(pluginDir));

        // Null / missing key falls back to "en"
        Files.writeString(langFile.toPath(), "otherKey: 123\n", StandardCharsets.UTF_8);
        assertEquals("en", LanguageBootstrap.resolve(pluginDir));

        // Unsafe value falls back to "en"
        Files.writeString(langFile.toPath(), "language: \"../bad!\"\n", StandardCharsets.UTF_8);
        assertEquals("en", LanguageBootstrap.resolve(pluginDir));

        // Corrupt YAML falls back to "en" without throwing
        Files.writeString(langFile.toPath(), "language: [unclosed\n", StandardCharsets.UTF_8);
        assertEquals("en", LanguageBootstrap.resolve(pluginDir));
    }

    @Test
    @DisplayName("ConfigLoader saves resources from classpath, handles existing files, and respects replace flag")
    void configLoaderSaveResourceAndGetResource() throws IOException {
        Path jarRoot = tempDir.resolve("jar-resources");
        Files.createDirectories(jarRoot.resolve("nested"));
        Files.writeString(jarRoot.resolve("sample.yml"), "alpha: 1\n", StandardCharsets.UTF_8);
        Files.writeString(jarRoot.resolve("nested/child.yml"), "beta: 2\n", StandardCharsets.UTF_8);

        File mainDir = tempDir.resolve("main-dir").toFile();
        assertTrue(mainDir.mkdirs());

        try (URLClassLoader loader = new URLClassLoader(new URL[]{jarRoot.toUri().toURL()}, null)) {
            ConfigLoader configLoader = new ConfigLoader() {
                @Override
                public File getMainDirectory() {
                    return mainDir;
                }

                @Override
                public ClassLoader getClassLoader() {
                    return loader;
                }
            };

            // Absent resource returns null and saveResourceFromJar no-ops
            assertNull(configLoader.getResourceFromJar("nonexistent.yml"));
            configLoader.saveResourceFromJar("nonexistent.yml", true);
            assertFalse(new File(mainDir, "nonexistent.yml").exists());

            // Save top-level resource
            configLoader.saveResourceFromJar("sample.yml", false);
            File savedSample = new File(mainDir, "sample.yml");
            assertTrue(savedSample.isFile());
            assertEquals("alpha: 1\n", Files.readString(savedSample.toPath(), StandardCharsets.UTF_8));

            // Mutate on disk; saveResourceFromJar with replace=false keeps existing content
            Files.writeString(savedSample.toPath(), "alpha: 99\n", StandardCharsets.UTF_8);
            configLoader.saveResourceFromJar("sample.yml", false);
            assertEquals("alpha: 99\n", Files.readString(savedSample.toPath(), StandardCharsets.UTF_8));

            // saveResourceFromJar with replace=true overwrites with jar resource
            configLoader.saveResourceFromJar("sample.yml", true);
            assertEquals("alpha: 1\n", Files.readString(savedSample.toPath(), StandardCharsets.UTF_8));

            // Save nested resource with backslash separator normalization
            configLoader.saveResourceFromJar("nested\\child.yml", true);
            File savedChild = new File(mainDir, "nested/child.yml");
            assertTrue(savedChild.isFile());
            assertEquals("beta: 2\n", Files.readString(savedChild.toPath(), StandardCharsets.UTF_8));
        }

        // Null classLoader fallback branch
        ConfigLoader nullClLoader = new ConfigLoader() {
            @Override
            public File getMainDirectory() {
                return mainDir;
            }

            @Override
            public ClassLoader getClassLoader() {
                return null;
            }
        };
        assertNull(nullClLoader.getResourceFromJar("definitely-not-in-system-cl.yml"));

        // Custom getResourceFromJar returning stream when file already exists but early check passed
        ConfigLoader raceLoader = new ConfigLoader() {
            @Override
            public File getMainDirectory() {
                return mainDir;
            }

            @Override
            public ClassLoader getClassLoader() {
                return null;
            }

            @Override
            public InputStream getResourceFromJar(String filename) {
                try {
                    new File(mainDir, filename).createNewFile();
                } catch (IOException ignored) {
                }
                return new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8));
            }
        };
        raceLoader.saveResourceFromJar("race.yml", false);
        assertTrue(new File(mainDir, "race.yml").exists());
    }
}
