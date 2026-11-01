package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("REQ-RTP-DB-OPT-001: DatabaseAccessorFactory Comprehensive Tests")
class DatabaseAccessorFactoryComprehensiveTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
    }

    @Test
    void isDriverAvailable_allKnownDriversAndNull() {
        assertTrue(DatabaseAccessorFactory.isDriverAvailable(null));
        assertTrue(DatabaseAccessorFactory.isDriverAvailable("org.h2.Driver"));
        assertTrue(DatabaseAccessorFactory.isDriverAvailable("org.sqlite.JDBC"));
        assertFalse(DatabaseAccessorFactory.isDriverAvailable("com.nonexistent.Driver"));
        assertFalse(DatabaseAccessorFactory.isDriverAvailable(""));
    }

    @Test
    void create_nullType_fallsBackToSQLiteOrH2() {
        DatabaseAccessorFactory.Result result = DatabaseAccessorFactory.create(
                null, tempDir.toFile(), "localhost", 3306, "rtp", "user", "pass");
        assertNotNull(result);
        assertNotNull(result.accessor);
        assertNotNull(result.effectiveType);
        // null defaults to sqlite branch in tryCreate, effectiveType is normalized to "sqlite" if successful, or h2/yaml if fallback
        assertTrue("sqlite".equalsIgnoreCase(result.effectiveType)
                || "h2".equalsIgnoreCase(result.effectiveType)
                || "yaml".equalsIgnoreCase(result.effectiveType));
        try {
            result.accessor.close();
        } catch (Throwable ignored) {}
    }

    @Test
    void create_emptyType_fallsBackToSQLiteOrH2() {
        DatabaseAccessorFactory.Result result = DatabaseAccessorFactory.create(
                "", tempDir.toFile(), "localhost", 3306, "rtp", "user", "pass");
        assertNotNull(result);
        assertNotNull(result.accessor);
        assertTrue("sqlite".equalsIgnoreCase(result.effectiveType)
                || "h2".equalsIgnoreCase(result.effectiveType)
                || "yaml".equalsIgnoreCase(result.effectiveType));
        try {
            result.accessor.close();
        } catch (Throwable ignored) {}
    }

    @Test
    void create_yamlType_caseInsensitive() {
        DatabaseAccessorFactory.Result r1 = DatabaseAccessorFactory.create(
                "YAML", tempDir.toFile(), "localhost", 3306, "rtp", "user", "pass");
        assertEquals("yaml", r1.effectiveType);
        assertTrue(r1.accessor instanceof YamlFileDatabase);

        DatabaseAccessorFactory.Result r2 = DatabaseAccessorFactory.create(
                "yAmL", tempDir.toFile(), "localhost", 3306, "rtp", "user", "pass");
        assertEquals("yaml", r2.effectiveType);
        assertTrue(r2.accessor instanceof YamlFileDatabase);
    }

    @Test
    void create_h2Type_caseInsensitive() {
        DatabaseAccessorFactory.Result r = DatabaseAccessorFactory.create(
                "H2", tempDir.toFile(), "localhost", 3306, "rtp", "user", "pass");
        assertEquals("h2", r.effectiveType);
        assertTrue(r.accessor instanceof H2DatabaseAccessor);
        try {
            r.accessor.close();
        } catch (Throwable ignored) {}
    }

    @Test
    void create_sqliteType_caseInsensitive() {
        DatabaseAccessorFactory.Result r = DatabaseAccessorFactory.create(
                "SQLite", tempDir.toFile(), "localhost", 3306, "rtp", "user", "pass");
        assertNotNull(r);
        assertNotNull(r.accessor);
        assertTrue("sqlite".equalsIgnoreCase(r.effectiveType)
                || "h2".equalsIgnoreCase(r.effectiveType)
                || "yaml".equalsIgnoreCase(r.effectiveType));
        try {
            r.accessor.close();
        } catch (Throwable ignored) {}
    }

    @Test
    void create_mysqlType_withInvalidConnection_fallsBackToH2() {
        // MySQL driver might be available or not, but connection or instantiation to localhost:0 without MySQL daemon
        // will either fail during constructor (HikariCP) or fallback to H2
        DatabaseAccessorFactory.Result r = DatabaseAccessorFactory.create(
                "mysql", tempDir.toFile(), "127.0.0.1", 1, "invalid_db", "user", "pass");
        assertNotNull(r);
        assertNotNull(r.accessor);
        // Should fall back to H2 (since H2 driver is on classpath)
        assertTrue("h2".equalsIgnoreCase(r.effectiveType) || "yaml".equalsIgnoreCase(r.effectiveType));
        try {
            r.accessor.close();
        } catch (Throwable ignored) {}
    }

    @Test
    void create_postgresqlType_withInvalidConnection_fallsBackToH2() {
        DatabaseAccessorFactory.Result r = DatabaseAccessorFactory.create(
                "postgresql", tempDir.toFile(), "127.0.0.1", 1, "invalid_db", "user", "pass");
        assertNotNull(r);
        assertNotNull(r.accessor);
        assertTrue("h2".equalsIgnoreCase(r.effectiveType) || "yaml".equalsIgnoreCase(r.effectiveType));
        try {
            r.accessor.close();
        } catch (Throwable ignored) {}
    }

    @Test
    void create_unknownType_fallsBackAppropriately() {
        DatabaseAccessorFactory.Result r = DatabaseAccessorFactory.create(
                "custom_nosql_db", tempDir.toFile(), "localhost", 0, "test", "u", "p");
        assertNotNull(r);
        assertNotNull(r.accessor);
        // Unknown type goes to SQLite branch in tryCreate. If SQLite succeeds, effective is "sqlite". Otherwise fallback to H2/YAML.
        assertTrue("sqlite".equals(r.effectiveType)
                || "h2".equals(r.effectiveType)
                || "yaml".equals(r.effectiveType));
        try {
            r.accessor.close();
        } catch (Throwable ignored) {}
    }

    @Test
    void result_propertiesAreAccessible() {
        YamlFileDatabase yamlDb = new YamlFileDatabase(tempDir.toFile());
        DatabaseAccessorFactory.Result res = new DatabaseAccessorFactory.Result(yamlDb, "yaml");
        assertSame(yamlDb, res.accessor);
        assertEquals("yaml", res.effectiveType);
        try {
            yamlDb.close();
        } catch (Throwable ignored) {}
    }
}
