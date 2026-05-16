package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class YamlFileDatabaseTest {

    @TempDir
    Path tempDir;

    private YamlFileDatabase db;

    @BeforeEach
    void setUp() {
        db = new YamlFileDatabase(tempDir.toFile());
    }

    @AfterEach
    void tearDown() {
        // YamlFileDatabase holds no persistent open handles; nothing to close.
    }

    // -------------------------------------------------------------------------
    // connect()
    // -------------------------------------------------------------------------

    @Test
    void connect_emptyDirectory_returnsEmptyMap() {
        Map<String, RtpYamlConfig> result = db.connect();
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void connect_createsDirectoryIfAbsent() {
        File subDir = tempDir.resolve("subdir").toFile();
        YamlFileDatabase subDb = new YamlFileDatabase(subDir);
        assertFalse(subDir.exists());
        Map<String, RtpYamlConfig> result = subDb.connect();
        assertTrue(subDir.exists());
        assertNotNull(result);
    }

    @Test
    void connect_loadsValidYamlFile() throws IOException {
        Files.writeString(tempDir.resolve("data.yml"), "key: value\n");

        Map<String, RtpYamlConfig> result = db.connect();
        assertTrue(result.containsKey("data.yml"));
        RtpYamlConfig loaded = result.get("data.yml");
        assertNotNull(loaded);
        assertEquals("value", loaded.getString("key"));
    }

    @Test
    void connect_skipsNonYamlFiles() throws IOException {
        Files.writeString(tempDir.resolve("readme.txt"), "hello");
        assertDoesNotThrow(() -> db.connect());
    }

    @Test
    void connect_skipsMalformedYamlFile() throws IOException {
        Files.writeString(tempDir.resolve("bad.yml"), "key: [\nunclosed bracket\n\t\t{{{{");
        // connect() catches parse exceptions and skips the file — must not throw
        assertDoesNotThrow(() -> db.connect());
    }

    @Test
    void connect_usesCacheWhenFileUnchanged() throws IOException {
        Files.writeString(tempDir.resolve("cached.yml"), "x: 1\n");

        Map<String, RtpYamlConfig> first = db.connect();
        RtpYamlConfig firstRef = first.get("cached.yml");

        Map<String, RtpYamlConfig> second = db.connect();
        RtpYamlConfig secondRef = second.get("cached.yml");

        // Same RtpYamlConfig instance returned from cache when lastModified is unchanged
        assertSame(firstRef, secondRef);
    }

    @Test
    void connect_reloadsFileAfterModification() throws IOException, InterruptedException {
        Path yamlPath = tempDir.resolve("modified.yml");
        Files.writeString(yamlPath, "v: 1\n");

        Map<String, RtpYamlConfig> first = db.connect();
        assertNotNull(first.get("modified.yml"));

        // Force a future lastModified so the cache detects a change
        Thread.sleep(50);
        Files.writeString(yamlPath, "v: 2\n");
        yamlPath.toFile().setLastModified(System.currentTimeMillis() + 2000);

        Map<String, RtpYamlConfig> second = db.connect();
        RtpYamlConfig reloaded = second.get("modified.yml");
        assertNotNull(reloaded);
        assertEquals(2, reloaded.getInt("v"));
    }

    // -------------------------------------------------------------------------
    // name()
    // -------------------------------------------------------------------------

    @Test
    void name_returnsYAML() {
        assertEquals("YAML", db.name());
    }

    // -------------------------------------------------------------------------
    // read()
    // -------------------------------------------------------------------------

    @Test
    void read_existingKey_returnsValue() throws IOException {
        Files.writeString(tempDir.resolve("store.yml"), "score: 42\n");

        Map<String, RtpYamlConfig> database = db.connect();
        Optional<Map<String, Object>> result =
                db.read(database, "store", new AbstractMap.SimpleEntry<>("score", 0));

        assertTrue(result.isPresent());
        assertEquals(42, result.get().get("score"));
    }

    @Test
    void read_appendsYmlExtensionAutomatically() throws IOException {
        Files.writeString(tempDir.resolve("store.yml"), "score: 7\n");

        Map<String, RtpYamlConfig> database = db.connect();
        Optional<Map<String, Object>> result =
                db.read(database, "store", new AbstractMap.SimpleEntry<>("score", 0));
        assertTrue(result.isPresent());
    }

    @Test
    void read_missingTable_returnsEmpty() {
        Map<String, RtpYamlConfig> database = db.connect();
        Optional<Map<String, Object>> result =
                db.read(database, "nonexistent", new AbstractMap.SimpleEntry<>("key", "default"));
        assertFalse(result.isPresent());
    }

    @Test
    void read_missingKey_returnsDefaultWrapped() throws IOException {
        Files.writeString(tempDir.resolve("store.yml"), "other: hello\n");

        Map<String, RtpYamlConfig> database = db.connect();
        // SimpleYaml returns the supplied default when the key is absent
        Optional<Map<String, Object>> result =
                db.read(database, "store", new AbstractMap.SimpleEntry<>("missing", "fallback"));
        assertTrue(result.isPresent());
        assertEquals("fallback", result.get().get("missing"));
    }

    // -------------------------------------------------------------------------
    // write() + disconnect()
    // -------------------------------------------------------------------------

    @Test
    void write_createsNewFileAndPersistsValue() throws IOException {
        Map<String, RtpYamlConfig> database = db.connect();

        Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> pairs = new LinkedHashMap<>();
        pairs.put(new DatabaseAccessor.TableObj("greeting"), new DatabaseAccessor.TableObj("hello"));

        db.write(database, "newfile", pairs);
        db.disconnect(database);

        File written = tempDir.resolve("newfile.yml").toFile();
        assertTrue(written.exists());
        assertTrue(Files.readString(written.toPath()).contains("hello"));
    }

    @Test
    void write_updatesExistingKey() throws IOException {
        Path yamlPath = tempDir.resolve("update.yml");
        Files.writeString(yamlPath, "counter: 1\n");

        Map<String, RtpYamlConfig> database = db.connect();

        Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> pairs = new LinkedHashMap<>();
        pairs.put(new DatabaseAccessor.TableObj("counter"), new DatabaseAccessor.TableObj(99));

        db.write(database, "update", pairs);
        db.disconnect(database);

        assertTrue(Files.readString(yamlPath).contains("99"));
    }

    @Test
    void write_copiesDefaultYmlWhenPresent() throws IOException {
        Files.writeString(tempDir.resolve("default.yml"), "template: true\n");

        Map<String, RtpYamlConfig> database = db.connect();

        Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> pairs = new LinkedHashMap<>();
        pairs.put(new DatabaseAccessor.TableObj("template"), new DatabaseAccessor.TableObj(false));

        db.write(database, "derived", pairs);
        db.disconnect(database);

        assertTrue(tempDir.resolve("derived.yml").toFile().exists());
    }

    // -------------------------------------------------------------------------
    // delete()
    // -------------------------------------------------------------------------

    @Test
    void delete_removesKeyFromFile() throws IOException {
        Path yamlPath = tempDir.resolve("del.yml");
        Files.writeString(yamlPath, "toDelete: yes\nkeep: no\n");

        Map<String, RtpYamlConfig> database = db.connect();
        db.delete(database, "del", new AbstractMap.SimpleEntry<>("toDelete", "yes"));

        RtpYamlConfig reloaded = new RtpYamlConfig(yamlPath.toFile());
        reloaded.loadWithComments();
        assertNull(reloaded.get("toDelete"));
        assertNotNull(reloaded.get("keep"));
    }

    @Test
    void delete_missingTable_doesNotThrow() {
        Map<String, RtpYamlConfig> database = db.connect();
        assertDoesNotThrow(() ->
                db.delete(database, "ghost", new AbstractMap.SimpleEntry<>("key", "val")));
    }

    @Test
    void delete_byUUIDKey_removesEntry() throws IOException {
        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        Path yamlPath = tempDir.resolve("players.yml");
        Files.writeString(yamlPath, uuid + ":\n  name: Alice\n");

        Map<String, RtpYamlConfig> database = db.connect();
        db.delete(database, "players", new AbstractMap.SimpleEntry<>("UUID", uuid));

        RtpYamlConfig reloaded = new RtpYamlConfig(yamlPath.toFile());
        reloaded.loadWithComments();
        assertNull(reloaded.get(uuid));
    }

    // -------------------------------------------------------------------------
    // disconnect()
    // -------------------------------------------------------------------------

    @Test
    void disconnect_savesModifiedValues() throws IOException {
        Path yamlPath = tempDir.resolve("save.yml");
        Files.writeString(yamlPath, "val: 1\n");

        Map<String, RtpYamlConfig> database = db.connect();
        database.get("save.yml").set("val", 2);
        db.disconnect(database);

        assertTrue(Files.readString(yamlPath).contains("2"));
    }

    // -------------------------------------------------------------------------
    // loadCachedLocations()
    // -------------------------------------------------------------------------

    @Test
    void loadCachedLocations_noFile_returnsEmptyList() {
        assertTrue(db.loadCachedLocations("myRegion").isEmpty());
    }

    @Test
    void loadCachedLocations_withMatchingEntry_returnsLocation() throws IOException {
        // Write YAML using a RtpYamlConfig so SimpleYaml can read it back as RtpYamlSection
        Path yamlPath = tempDir.resolve("rtp_cached_locations.yml");
        RtpYamlConfig yf = new RtpYamlConfig(yamlPath.toFile());
        yf.createNewFile(false);
        yf.set("loc1.region", "myRegion");
        yf.set("loc1.world", "world");
        yf.set("loc1.x", 100);
        yf.set("loc1.y", 64);
        yf.set("loc1.z", -200);
        yf.set("loc1.attempts", 3);
        yf.set("loc1.seed", 12345L);
        yf.set("loc1.player_uuid", "shared");
        yf.save();

        var locations = db.loadCachedLocations("myRegion");
        assertEquals(1, locations.size());
        DatabaseAccessor.StoredLocation loc = locations.get(0);
        assertEquals("myRegion", loc.getRegionName());
        assertEquals("world", loc.getWorldName());
        assertEquals(100, loc.getX());
        assertEquals(64, loc.getY());
        assertEquals(-200, loc.getZ());
    }

    @Test
    void loadCachedLocations_differentRegion_returnsEmpty() throws IOException {
        Path yamlPath = tempDir.resolve("rtp_cached_locations.yml");
        RtpYamlConfig yf = new RtpYamlConfig(yamlPath.toFile());
        yf.createNewFile(false);
        yf.set("loc1.region", "otherRegion");
        yf.set("loc1.world", "world");
        yf.set("loc1.x", 0);
        yf.set("loc1.y", 64);
        yf.set("loc1.z", 0);
        yf.set("loc1.attempts", 1);
        yf.set("loc1.seed", 0L);
        yf.set("loc1.player_uuid", "shared");
        yf.save();

        assertTrue(db.loadCachedLocations("myRegion").isEmpty());
    }
}
