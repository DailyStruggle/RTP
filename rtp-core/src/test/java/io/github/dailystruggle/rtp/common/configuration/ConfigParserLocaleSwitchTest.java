package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for the locale-switch migration in {@link ConfigParser#check}.
 * Reproduces the {@code rtp info} symptom reported when an operator changed
 * {@code language.yml} from {@code en} to {@code es} but the on-disk
 * {@code messages.yml} kept its English keys: most enum lookups resolved to
 * {@code null} and lines were silently skipped.
 *
 * <p>Verifies that:
 * <ol>
 *   <li>A foreign-locale on-disk file is detected.
 *   <li>The original file is preserved as {@code <name>.old1} (no data loss).
 *   <li>User-customized scalar values survive the migration under the new
 *       locale's key names.
 *   <li>Enum lookups resolve after migration (no more empty strings).
 * </ol>
 */
public class ConfigParserLocaleSwitchTest {

    @TempDir
    Path tempDir;

    public enum TestKeys {
        alpha,
        beta,
        gamma,
        version
    }

    @BeforeEach
    void setUp() {
        RTPServerAccessor serverAccessor = mock(RTPServerAccessor.class);
        RTPScheduler scheduler = mock(RTPScheduler.class);
        when(serverAccessor.getPluginDirectory()).thenReturn(tempDir.toFile());
        when(serverAccessor.createTaskPipe()).thenReturn(
                mock(io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe.class));
        RTP.serverAccessor = serverAccessor;
        RTP.scheduler = scheduler;

        RTPAPI.serverAccessor = null;

        RTP rtp = new RTP() {};
        try {
            java.lang.reflect.Field instanceField = RTP.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            instanceField.set(null, rtp);
        } catch (Exception ignored) {
        }
    }

    @Test
    void localeSwitchMigratesForeignFileAndPreservesCustomizations() throws IOException {
        // Simulate the user's prior state: an English on-disk test.yml with a
        // customized 'alpha' value (10 instead of the default).
        File configFile = tempDir.resolve("test.yml").toFile();
        Files.writeString(configFile.toPath(),
                "alpha: 10\nbeta: 20\nversion: 1.0\n");

        // Simulate the language switch: lang/test.lang.yml now points enum
        // names at Spanish key names. This emulates extraction of
        // lang/<locale>/test.lang.yml by LanguageBootstrap.
        File langDir = tempDir.resolve("lang").toFile();
        assertTrue(langDir.mkdirs() || langDir.isDirectory());
        File langFile = new File(langDir, "test.lang.yml");
        Files.writeString(langFile.toPath(),
                "alpha: alfa\nbeta: beta_es\ngamma: gamma_es\nversion: version\n");

        YamlFileDatabase fileDatabase = new YamlFileDatabase(tempDir.toFile());

        ConfigParser<TestKeys> parser = new ConfigParser<>(
                TestKeys.class,
                "test",
                "1.0",
                tempDir.toFile(),
                langFile,
                fileDatabase,
                getClass().getClassLoader(),
                "es"
        );

        // The original (English-keyed) file must have been backed up.
        File backup = tempDir.resolve("test.yml.old1").toFile();
        assertTrue(backup.exists(),
                "Foreign-locale file should be preserved as test.yml.old1");

        // Customized values must round-trip under the enum constants regardless
        // of whether the user's source file used English or Spanish keys.
        Object alphaVal = parser.getData(TestKeys.alpha);
        Object betaVal = parser.getData(TestKeys.beta);
        assertEquals(10, alphaVal,
                "User customization for alpha should survive the en→es migration");
        assertEquals(20, betaVal,
                "User customization for beta should survive the en→es migration");

        // The new on-disk file must use the active locale's key names so
        // subsequent lookups via language_mapping resolve. (This is the actual
        // condition that was failing in the rtp info bug report.)
        String onDisk = Files.readString(configFile.toPath());
        assertTrue(onDisk.contains("alfa:") || onDisk.contains("alfa "),
                "Migrated file should be written using active-locale keys; "
                        + "found:\n" + onDisk);
    }

    @Test
    void localeSwitchEvictsStaleFileDatabaseCache() throws IOException {
        // Reproduces the exact "rtp info shows nothing" bug: the file-database
        // cache was populated from the previous English run (at plugin start),
        // then language.yml was switched to es and /rtp reload ran. Without
        // cache eviction in detectAndPreserveLocaleMismatch, ConfigParser.check
        // would short-circuit fileDatabase.connect() and continue reading the
        // stale English YamlFile, returning null for every Spanish key lookup
        // and producing empty values in the data map.
        File configFile = tempDir.resolve("test.yml").toFile();
        Files.writeString(configFile.toPath(),
                "alpha: 10\nbeta: 20\nversion: 1.0\n");

        YamlFileDatabase fileDatabase = new YamlFileDatabase(tempDir.toFile());
        // Simulate plugin startup: fileDatabase populates its cache from the
        // English file currently on disk.
        fileDatabase.connect();
        assertNotNull(fileDatabase.cachedLookup.get().get("test.yml"),
                "Pre-condition: cache is populated with the English file");

        // Now write the locale mapping (as LanguageBootstrap would) and
        // construct the locale-switched parser.
        File langDir = tempDir.resolve("lang").toFile();
        assertTrue(langDir.mkdirs() || langDir.isDirectory());
        File langFile = new File(langDir, "test.lang.yml");
        Files.writeString(langFile.toPath(),
                "alpha: alfa\nbeta: beta_es\ngamma: gamma_es\nversion: version\n");

        ConfigParser<TestKeys> parser = new ConfigParser<>(
                TestKeys.class,
                "test",
                "1.0",
                tempDir.toFile(),
                langFile,
                fileDatabase,
                getClass().getClassLoader(),
                "es"
        );

        // After migration, parser.data must resolve via the new locale's keys.
        assertEquals(10, parser.getData(TestKeys.alpha),
                "Alpha must resolve after cache eviction; null indicates stale cache");
        assertEquals(20, parser.getData(TestKeys.beta),
                "Beta must resolve after cache eviction; null indicates stale cache");
    }

    @Test
    void corruptYamlIsQuarantinedAndDefaultsRestored() throws IOException {
        // Reproduces the user's stack trace: an invalid YAML on disk crashed
        // ConfigParser during locale-switch detection. The fix should: (1) not
        // propagate the parse exception, (2) move the broken file to
        // <name>.corrupt-<timestamp>, and (3) leave the parser in a usable
        // state (empty data is acceptable; a crash is not).
        File configFile = tempDir.resolve("test.yml").toFile();
        // Invalid YAML: flow-sequence opener with block-style entry inside.
        Files.writeString(configFile.toPath(),
                "alpha: [\n  - \"broken entry\n");

        File langDir = tempDir.resolve("lang").toFile();
        assertTrue(langDir.mkdirs() || langDir.isDirectory());
        File langFile = new File(langDir, "test.lang.yml");
        Files.writeString(langFile.toPath(),
                "alpha: alfa\nbeta: beta_es\ngamma: gamma_es\nversion: version\n");

        YamlFileDatabase fileDatabase = new YamlFileDatabase(tempDir.toFile());

        // Constructing the parser must not throw despite the corrupt YAML.
        assertDoesNotThrow(() -> new ConfigParser<>(
                TestKeys.class,
                "test",
                "1.0",
                tempDir.toFile(),
                langFile,
                fileDatabase,
                getClass().getClassLoader(),
                "es"
        ));

        // The corrupt file must have been moved aside.
        File parent = tempDir.toFile();
        File[] quarantined = parent.listFiles((d, n) -> n.startsWith("test.yml.corrupt-"));
        assertNotNull(quarantined);
        assertTrue(quarantined.length >= 1,
                "Corrupt file should have been quarantined as test.yml.corrupt-<ts>");
    }

    @Test
    void identityMappedKeysDoNotShortCircuitMigration() throws IOException {
        // Regression for the messages.yml symptom: the active locale's mapping
        // contains identity entries (e.g. es/messages.lang.yml: `infoTickets:
        // infoTickets`). An on-disk English file that contains `infoTickets`
        // would falsely register as "already in active locale" and skip the
        // migration, leaving every other (renamed) key unresolvable.
        File configFile = tempDir.resolve("test.yml").toFile();
        // 'gamma' is identity-mapped in both locales below, while 'alpha'/'beta'
        // are renamed by the active locale. The English file uses canonical
        // names; without the fix, gamma's identity mapping would short-circuit.
        Files.writeString(configFile.toPath(),
                "alpha: 10\nbeta: 20\ngamma: 30\nversion: 1.0\n");

        File langDir = tempDir.resolve("lang").toFile();
        assertTrue(langDir.mkdirs() || langDir.isDirectory());
        File langFile = new File(langDir, "test.lang.yml");
        // Identity mapping for gamma; renames for alpha/beta.
        Files.writeString(langFile.toPath(),
                "alpha: alfa\nbeta: beta_es\ngamma: gamma\nversion: version\n");

        YamlFileDatabase fileDatabase = new YamlFileDatabase(tempDir.toFile());
        ConfigParser<TestKeys> parser = new ConfigParser<>(
                TestKeys.class,
                "test",
                "1.0",
                tempDir.toFile(),
                langFile,
                fileDatabase,
                getClass().getClassLoader(),
                "es"
        );

        // Migration must have run despite the identity mapping for gamma.
        File backup = tempDir.resolve("test.yml.old1").toFile();
        assertTrue(backup.exists(),
                "Identity-mapped keys must not block locale migration");
        assertEquals(10, parser.getData(TestKeys.alpha));
        assertEquals(20, parser.getData(TestKeys.beta));
        assertEquals(30, parser.getData(TestKeys.gamma));
    }

    @Test
    void englishFileWithEnglishLocaleIsNotMigrated() throws IOException {
        // Baseline: identity mapping (English) should never trigger migration.
        File configFile = tempDir.resolve("test.yml").toFile();
        Files.writeString(configFile.toPath(),
                "alpha: 7\nbeta: 8\nversion: 1.0\n");

        YamlFileDatabase fileDatabase = new YamlFileDatabase(tempDir.toFile());
        ConfigParser<TestKeys> parser = new ConfigParser<>(
                TestKeys.class,
                "test",
                "1.0",
                tempDir.toFile(),
                null,
                fileDatabase
        );

        File backup = tempDir.resolve("test.yml.old1").toFile();
        assertFalse(backup.exists(),
                "Identity-mapped (English) file must not be migrated");
        assertEquals(7, parser.getData(TestKeys.alpha));
        assertEquals(8, parser.getData(TestKeys.beta));
    }

    @Test
    void noRenamesUnderActiveLocaleSkipsMigration() throws IOException {
        // Reproduces the integrations.yml symptom: the active locale has no
        // localized JAR resource for this file, so loadLangFile seeded an
        // identity mapping (every key maps to itself). Without this guard,
        // detectAndPreserveLocaleMismatch would back up the file as foreign
        // and "re-extract" identical defaults on every reload.
        File configFile = tempDir.resolve("test.yml").toFile();
        Files.writeString(configFile.toPath(),
                "alpha: 1\nbeta: 2\nversion: 1.0\n");

        File langDir = tempDir.resolve("lang").toFile();
        assertTrue(langDir.mkdirs() || langDir.isDirectory());
        File langFile = new File(langDir, "test.lang.yml");
        // Pure identity mapping — locale renames no keys for this file.
        Files.writeString(langFile.toPath(),
                "alpha: alpha\nbeta: beta\ngamma: gamma\nversion: version\n");

        YamlFileDatabase fileDatabase = new YamlFileDatabase(tempDir.toFile());
        ConfigParser<TestKeys> parser = new ConfigParser<>(
                TestKeys.class,
                "test",
                "1.0",
                tempDir.toFile(),
                langFile,
                fileDatabase,
                getClass().getClassLoader(),
                "es"
        );

        File backup = tempDir.resolve("test.yml.old1").toFile();
        assertFalse(backup.exists(),
                "Files with no locale renames must not be backed up on every reload");
        assertEquals(1, parser.getData(TestKeys.alpha));
        assertEquals(2, parser.getData(TestKeys.beta));

        // Second construction (simulating /rtp reload) must also leave file alone.
        new ConfigParser<>(
                TestKeys.class, "test", "1.0", tempDir.toFile(),
                langFile, fileDatabase, getClass().getClassLoader(), "es");
        assertFalse(backup.exists(),
                "Subsequent reloads must remain idempotent");
    }

    @Test
    void unmodifiedEnglishValuesAreReplacedByLocalizedDefaults() throws IOException {
        // Reproduces the latest reported symptom: after the en→es key rename
        // landed, the on-disk values were still the English defaults because
        // the migration preserved every recovered scalar verbatim. Now the
        // migration must compare each on-disk value against the English
        // baseline shipped in the JAR and only preserve genuinely modified
        // entries — so unmodified defaults get the localized text.
        File configFile = tempDir.resolve("test.yml").toFile();
        // alpha == English default ("hello"); beta is operator-modified.
        Files.writeString(configFile.toPath(),
                "alpha: hello\nbeta: customized\nversion: 1.0\n");

        File langDir = tempDir.resolve("lang").toFile();
        assertTrue(langDir.mkdirs() || langDir.isDirectory());
        File langFile = new File(langDir, "test.lang.yml");
        Files.writeString(langFile.toPath(),
                "alpha: alfa\nbeta: beta_es\ngamma: gamma_es\nversion: version\n");

        // Provide both the English baseline (for value comparison) and the
        // Spanish localized resource (extracted to disk by renameFiles()).
        File langEsDir = tempDir.resolve("lang/es").toFile();
        assertTrue(langEsDir.mkdirs() || langEsDir.isDirectory());
        // The "JAR" Spanish file uses Spanish keys with translated values.
        String esYaml = "alfa: hola\nbeta_es: hola_beta\nversion: \"1.0\"\n";

        YamlFileDatabase fileDatabase = new YamlFileDatabase(tempDir.toFile());
        // First call to getResourceFromJar("test.yml") comes from
        // loadEnglishBaselineDefaults() in detectAndPreserveLocaleMismatch and
        // must return the ENGLISH baseline. Subsequent calls come from
        // renameFiles() → saveResourceFromJar() and must return the SPANISH
        // localized content (extractLocalizedResource() is private and uses the
        // real classloader, so we can't intercept it; falling through to
        // saveResourceFromJar is fine for the test).
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        ConfigParser<TestKeys> parser = new ConfigParser<TestKeys>(
                TestKeys.class,
                "test",
                "1.0",
                tempDir.toFile(),
                langFile,
                fileDatabase,
                getClass().getClassLoader(),
                "es"
        ) {
            @Override
            public java.io.InputStream getResourceFromJar(String filename) {
                String norm = filename.replace('\\', '/');
                if (!norm.endsWith("test.yml")) return null;
                int n = calls.getAndIncrement();
                if (n == 0) {
                    return new java.io.ByteArrayInputStream(
                            "alpha: hello\nbeta: original\nversion: \"1.0\"\n".getBytes(
                                    java.nio.charset.StandardCharsets.UTF_8));
                }
                return new java.io.ByteArrayInputStream(
                        esYaml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        };

        // Migration ran (file was foreign-locale).
        assertTrue(tempDir.resolve("test.yml.old1").toFile().exists(),
                "Foreign-locale file should be backed up");

        // alpha was the English default → must now be the Spanish default.
        assertEquals("hola", parser.getData(TestKeys.alpha),
                "Unmodified English default must be replaced by the localized default");
        // beta was customized → must be preserved verbatim across the switch.
        assertEquals("customized", parser.getData(TestKeys.beta),
                "Operator customization must survive the en→es migration");
    }
}
