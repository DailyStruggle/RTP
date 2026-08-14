package io.github.dailystruggle.rtp.common.database;

import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link YamlFileDatabase} using a JUnit {@code @TempDir} so no
 * real plugin directory is needed.
 */
class YamlFileDatabaseTest extends AbstractDatabaseAccessorTest {

    @TempDir
    Path tempDir;

    private YamlFileDatabase db;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
        db = new YamlFileDatabase(tempDir.toFile());
    }

    @Override
    protected DatabaseAccessor<?> accessor() {
        return db;
    }

    // -------------------------------------------------------------------------
    // name / connect / disconnect
    // -------------------------------------------------------------------------

    @Test
    void name_returnsYAML() {
        assertEquals("YAML", db.name());
    }

    @Test
    void connect_emptyDirectory_returnsEmptyMap() {
        // RTPTestSetup.install() extracts the core config files into the shared
        // plugin directory (tempDir), so point at a dedicated, genuinely-empty
        // sub-directory to exercise the empty-directory contract.
        File emptyDir = tempDir.resolve("empty").toFile();
        assertTrue(emptyDir.mkdirs(), "test setup should create the empty directory");
        YamlFileDatabase emptyDb = new YamlFileDatabase(emptyDir);
        Map<String, RtpYamlConfig> result = emptyDb.connect();
        assertNotNull(result);
        assertTrue(result.isEmpty(), "connect on empty dir should return empty map");
    }

    @Test
    void connect_nonExistentDirectory_createsItAndReturnsEmpty() {
        File subDir = tempDir.resolve("subdir").toFile();
        assertFalse(subDir.exists());
        YamlFileDatabase subDb = new YamlFileDatabase(subDir);
        Map<String, RtpYamlConfig> result = subDb.connect();
        assertTrue(subDir.exists(), "connect should create the directory");
        assertTrue(result.isEmpty());
    }

    @Test
    void connect_withExistingYamlFile_loadsIt() throws Exception {
        File RtpYamlConfig = tempDir.resolve("player.yml").toFile();
        RtpYamlConfig.createNewFile();
        io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig yf = new RtpYamlConfig(RtpYamlConfig);
        yf.set("health", 20);
        yf.save();

        Map<String, RtpYamlConfig> result = db.connect();
        assertTrue(result.containsKey("player.yml"), "connect should load existing yaml files");
    }

    @Test
    void connect_withNonYamlFile_skipsIt() throws Exception {
        File txtFile = tempDir.resolve("notes.txt").toFile();
        txtFile.createNewFile();
        java.nio.file.Files.writeString(txtFile.toPath(), "not yaml: [broken");

        Map<String, RtpYamlConfig> result = db.connect();
        assertFalse(result.containsKey("notes.txt"), "non-yaml files should be skipped");
    }

    @Test
    void disconnect_savesFilesToDisk() throws Exception {
        File RtpYamlConfig = tempDir.resolve("data.yml").toFile();
        RtpYamlConfig yf = new RtpYamlConfig(RtpYamlConfig);
        yf.createNewFile(true);
        yf.set("key", "value");

        Map<String, RtpYamlConfig> database = Map.of("data.yml", yf);
        db.disconnect(database);

        assertTrue(RtpYamlConfig.exists(), "disconnect should save files to disk");
        RtpYamlConfig reloaded = new RtpYamlConfig(RtpYamlConfig);
        reloaded.load();
        assertEquals("value", reloaded.getString("key"));
    }

    // -------------------------------------------------------------------------
    // write / read / delete
    // -------------------------------------------------------------------------

    @Test
    void write_thenRead_returnsRow() throws Exception {
        File RtpYamlConfig = tempDir.resolve("locations.yml").toFile();
        RtpYamlConfig yf = new RtpYamlConfig(RtpYamlConfig);
        yf.createNewFile(true);
        Map<String, RtpYamlConfig> database = new HashMap<>();
        database.put("locations.yml", yf);

        Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> row = new LinkedHashMap<>();
        row.put(new DatabaseAccessor.TableObj("loc-001"), new DatabaseAccessor.TableObj("world,100,64,200"));

        db.write(database, "locations.yml", row);

        Optional<Map<String, Object>> result = db.read(database, "locations.yml",
                new AbstractMap.SimpleEntry<>("loc-001", "loc-001"));
        assertTrue(result.isPresent(), "read should return the written row");
    }

    @Test
    void write_emptyMap_isNoOp() throws Exception {
        File RtpYamlConfig = tempDir.resolve("empty.yml").toFile();
        RtpYamlConfig yf = new RtpYamlConfig(RtpYamlConfig);
        yf.createNewFile(true);
        Map<String, RtpYamlConfig> database = new HashMap<>();
        database.put("empty.yml", yf);

        // YamlFileDatabase.write iterates over the map; empty map is a no-op
        assertDoesNotThrow(() -> db.write(database, "empty.yml", new HashMap<>()));
    }

    @Test
    void read_missingTable_returnsEmpty() {
        Map<String, RtpYamlConfig> database = new HashMap<>();
        Optional<Map<String, Object>> result = db.read(database, "nonexistent.yml",
                new AbstractMap.SimpleEntry<>("key", "val"));
        assertFalse(result.isPresent());
    }

    @Test
    void delete_byUUID_removesEntry() throws Exception {
        File RtpYamlConfig = tempDir.resolve("del.yml").toFile();
        RtpYamlConfig yf = new RtpYamlConfig(RtpYamlConfig);
        yf.createNewFile(true);
        Map<String, RtpYamlConfig> database = new HashMap<>();
        database.put("del.yml", yf);

        // Write a top-level key that delete() can remove via UUID lookup
        Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> row = new LinkedHashMap<>();
        row.put(new DatabaseAccessor.TableObj("abc-123"), new DatabaseAccessor.TableObj("someValue"));
        db.write(database, "del.yml", row);
        // Persist to disk so delete()'s loadWithComments() sees the key
        yf.save();

        // delete() removes the key when lookup key is "UUID" and saves the file
        db.delete(database, "del.yml", new AbstractMap.SimpleEntry<>("UUID", "abc-123"));

        // delete() calls file.save(), so reload from disk and verify key is gone
        RtpYamlConfig reloaded = new RtpYamlConfig(RtpYamlConfig);
        reloaded.loadWithComments();
        assertFalse(reloaded.contains("abc-123"), "key should be absent after delete");
    }

    @Test
    void delete_missingTable_doesNotThrow() {
        Map<String, RtpYamlConfig> database = new HashMap<>();
        assertDoesNotThrow(() ->
                db.delete(database, "ghost.yml", new AbstractMap.SimpleEntry<>("k", "v")));
    }

    // -------------------------------------------------------------------------
    // loadCachedLocations
    // -------------------------------------------------------------------------

    @Test
    void loadCachedLocations_emptyDirectory_returnsEmptyList() {
        List<DatabaseAccessor.StoredLocation> locs = db.loadCachedLocations("spawn");
        assertNotNull(locs);
        assertTrue(locs.isEmpty());
    }

    // -------------------------------------------------------------------------
    // setValue override (YamlFileDatabase overrides the base)
    // -------------------------------------------------------------------------

    @Test
    void setValue_map_storesInLocalTables() {
        db.setValue("myTable", Map.of("alpha", "one"));
        Optional<CompletableFuture<Optional<?>>> opt = db.getValue("myTable", "alpha");
        assertTrue(opt.isPresent(), "setValue(table, map) should populate localTables");
    }

    // -------------------------------------------------------------------------
    // cachedLookup
    // -------------------------------------------------------------------------

    @Test
    void cachedLookup_initiallyEmpty() {
        assertTrue(db.cachedLookup.get().isEmpty());
    }

    @Test
    void cachedLookupLastModified_initiallyEmpty() {
        assertTrue(db.cachedLookupLastModified.get().isEmpty());
    }

    @Test
    void connect_cacheHit_doesNotReloadUnchangedFile() throws Exception {
        File RtpYamlConfig = tempDir.resolve("cached.yml").toFile();
        RtpYamlConfig yf = new RtpYamlConfig(RtpYamlConfig);
        yf.createNewFile(true);
        yf.set("x", 1);
        yf.save();

        // First connect — loads the file
        db.connect();
        int sizeBefore = db.cachedLookup.get().size();

        // Second connect — file unchanged, should use cache
        db.connect();
        int sizeAfter = db.cachedLookup.get().size();

        assertEquals(sizeBefore, sizeAfter, "cache size should be stable on repeated connect");
    }

}
