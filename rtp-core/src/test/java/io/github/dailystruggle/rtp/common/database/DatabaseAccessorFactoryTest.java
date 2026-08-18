package io.github.dailystruggle.rtp.common.database;

import io.github.dailystruggle.rtp.common.database.options.DatabaseAccessorFactory;
import io.github.dailystruggle.rtp.common.database.options.H2DatabaseAccessor;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the requested → H2 → YAML fallback chain in
 * {@link DatabaseAccessorFactory}. The factory is the central guard against
 * "we cannot shade in every database dependency" - if a JDBC driver class is
 * not on the classpath, the corresponding accessor must not be instantiated;
 * instead the chain falls back to H2 (bundled) and finally to flat-file YAML.
 */
class DatabaseAccessorFactoryTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
    }

    @Test
    void yamlRequested_returnsYamlDirectly() {
        DatabaseAccessorFactory.Result r = DatabaseAccessorFactory.create(
                "yaml", tempDir.toFile(), "h", 0, "n", "u", "p");
        assertEquals("yaml", r.effectiveType);
        assertTrue(r.accessor instanceof YamlFileDatabase);
    }

    @Test
    void h2Requested_returnsH2WhenDriverPresent() {
        // H2 is shaded into rtp-core's test classpath, so this must succeed without falling back.
        DatabaseAccessorFactory.Result r = DatabaseAccessorFactory.create(
                "h2", tempDir.toFile(), "h", 0, "n", "u", "p");
        assertEquals("h2", r.effectiveType);
        assertTrue(r.accessor instanceof H2DatabaseAccessor);
    }

    @Test
    void unknownTypeWithNoDriverNeed_fallsThroughToDefaultBranch() {
        // An unrecognised type maps to no driver requirement (sqlite default branch).
        // If sqlite driver is unavailable, this still resolves through the fallback chain
        // and never returns null.
        DatabaseAccessorFactory.Result r = DatabaseAccessorFactory.create(
                "definitely-not-a-real-backend", tempDir.toFile(), "h", 0, "n", "u", "p");
        try {
            assertNotNull(r);
            assertNotNull(r.accessor);
            // Either the default sqlite branch succeeded, or we fell back to H2 / yaml - all are
            // acceptable; the contract is "never crash and never return null".
            assertTrue(
                    "definitely-not-a-real-backend".equals(r.effectiveType)
                            || "h2".equals(r.effectiveType)
                            || "yaml".equals(r.effectiveType)
                            || "sqlite".equals(r.effectiveType),
                    "effectiveType should be the requested name (if it succeeded) or a fallback rung, was: "
                            + r.effectiveType);
        } finally {
            // Release any held file handles so the JUnit @TempDir cleanup can succeed on Windows.
            try { r.accessor.close(); } catch (Throwable ignored) {}
        }
    }

    @Test
    void isDriverAvailable_nullReturnsTrue() {
        // null driver class means "no JDBC driver required" (e.g. yaml backend).
        assertTrue(DatabaseAccessorFactory.isDriverAvailable(null));
    }

    @Test
    void isDriverAvailable_missingClassReturnsFalse() {
        assertFalse(DatabaseAccessorFactory.isDriverAvailable(
                "io.github.dailystruggle.rtp.does.not.exist.FakeDriver"));
    }

    @Test
    void isDriverAvailable_h2DriverIsPresent() {
        // H2 is shaded into the test classpath.
        assertTrue(DatabaseAccessorFactory.isDriverAvailable("org.h2.Driver"));
    }
}
