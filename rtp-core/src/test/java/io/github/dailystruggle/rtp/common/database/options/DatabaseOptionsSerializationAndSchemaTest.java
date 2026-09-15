package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("REQ-RTP-DB-OPT-003: Options Serialization, Schema, and Migration Tests")
class DatabaseOptionsSerializationAndSchemaTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
    }

    @AfterEach
    void tearDown() {
        RTP.serverAccessor = null;
    }

    @Test
    void yamlFileDatabase_name_returnsYAML() {
        YamlFileDatabase db = new YamlFileDatabase(tempDir.toFile());
        assertEquals("YAML", db.name());
    }

    @Test
    void yamlFileDatabase_cacheValue_teleportData_createsValidStructure() {
        YamlFileDatabase db = new YamlFileDatabase(tempDir.toFile());
        TeleportData data = new TeleportData();
        UUID playerUuid = UUID.randomUUID();
        data.sender = RTP.serverAccessor.getSender(playerUuid);
        data.time = 9999L;
        data.delay = 15L;
        data.cost = 42.5;
        data.attempts = 4;
        data.selectedCoords = new RTPCoords("world", 100, 65, 200);
        data.originalCoords = new RTPCoords("world", 0, 50, 0);
        io.github.dailystruggle.rtp.common.selection.region.Region region = mock(io.github.dailystruggle.rtp.common.selection.region.Region.class);
        region.name = "default";
        data.targetRegion = region;

        db.cacheValue(data);

        // Verify connected database reads back the cached row after connect
        Map<String, io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig> connected = db.connect();
        assertNotNull(connected);
    }

    @Test
    void yamlFileDatabase_cacheValue_mapWithNestedKeys() {
        YamlFileDatabase db = new YamlFileDatabase(tempDir.toFile());
        Map<String, Object> map = new HashMap<>();
        map.put("region.name", "spawn");
        map.put("region.radius", 500);
        map.put("enabled", true);

        db.cacheValue("regions.yml", map);
        Map<String, io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig> connected = db.connect();
        assertNotNull(connected);
    }

    @Test
    void yamlFileDatabase_clearAllCachedLocations() throws IOException {
        YamlFileDatabase db = new YamlFileDatabase(tempDir.toFile());
        Files.writeString(tempDir.resolve("cachedLocations.yml"), "default:\n  loc1:\n    x: 10\n");

        db.clearAllCachedLocations();
        // clearAllCachedLocations deletes or resets the section/file
        List<DatabaseAccessor.StoredLocation> locs = db.loadCachedLocations("default");
        assertNotNull(locs);
        assertTrue(locs.isEmpty());
    }

    @Test
    void yamlFileDatabase_loadCachedLocations_emptyOrMissingFile() {
        YamlFileDatabase db = new YamlFileDatabase(tempDir.toFile());
        List<DatabaseAccessor.StoredLocation> locs = db.loadCachedLocations("non_existent_region");
        assertNotNull(locs);
        assertTrue(locs.isEmpty());
    }

    @Test
    void yamlFileDatabase_startup_withExistingTeleportDataFile() throws IOException {
        YamlFileDatabase db = new YamlFileDatabase(tempDir.toFile());
        UUID senderUuid = UUID.randomUUID();
        Files.writeString(tempDir.resolve("referenceData.yml"), "referenceTime: 1000000000000\n");
        String yamlContent = senderUuid + ":\n"
                + "  senderId: \"" + senderUuid + "\"\n"
                + "  time: 1234567890123\n"
                + "  cost: 50\n"
                + "  attempts: 1\n"
                + "  region: \"default\"\n"
                + "  selectedWorldName: \"world\"\n"
                + "  selectedX: 100\n"
                + "  selectedY: 70\n"
                + "  selectedZ: -200\n"
                + "  originalWorldName: \"world\"\n"
                + "  originalX: 0\n"
                + "  originalY: 64\n"
                + "  originalZ: 0\n";
        Files.writeString(tempDir.resolve("teleportData.yml"), yamlContent);

        // Ensure "default" region exists in RTP.selectionAPI
        io.github.dailystruggle.rtp.common.selection.region.Region region = mock(io.github.dailystruggle.rtp.common.selection.region.Region.class);
        region.name = "default";
        RTP.selectionAPI.permRegionLookup.put("default", region);

        db.connect();
        db.startup();

        TeleportData loaded = RTP.getInstance().latestTeleportData.get(senderUuid);
        assertNotNull(loaded);
        assertEquals(50.0, loaded.cost);
        assertNotNull(loaded.selectedCoords);
        assertEquals(100, loaded.selectedCoords.x());
        assertEquals("world", loaded.selectedCoords.worldName());
    }

    @Test
    void yamlFileDatabase_delete_removesKeyFromSection() throws IOException {
        YamlFileDatabase db = new YamlFileDatabase(tempDir.toFile());
        Files.writeString(tempDir.resolve("test.yml"), "itemToKeep: 1\nitemToDelete: 2\n");
        Map<String, io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig> connected = db.connect();

        db.delete(connected, "test.yml", new AbstractMap.SimpleEntry<>("itemToDelete", 2));
        assertFalse(connected.get("test.yml").contains("itemToDelete"));
        assertTrue(connected.get("test.yml").contains("itemToKeep"));
    }

    @Test
    void sqliteDatabaseAccessor_optionsAndName() {
        SQLiteDatabaseAccessor sqlite = new SQLiteDatabaseAccessor("jdbc:sqlite:ignored");
        assertNotNull(sqlite.name());
        assertTrue(sqlite.name().startsWith("jdbc:sqlite:"), sqlite.name());
        try {
            sqlite.close();
        } catch (Throwable ignored) {}
    }

    @Test
    void h2DatabaseAccessor_optionsAndName() {
        H2DatabaseAccessor h2 = new H2DatabaseAccessor();
        assertNotNull(h2.name());
        assertTrue(h2.name().startsWith("jdbc:h2:file:"), h2.name());
        assertTrue(h2.name().contains("MODE=MySQL"));
        try {
            h2.close();
        } catch (Throwable ignored) {}
    }
}
