package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The biome table is stored, saved and loaded in exactly one form: the blocked union. These cases
 * pin that a save/load round-trip reproduces it run-for-run with no rebuild in between, and that a
 * file written in the older per-biome layout still ingests.
 */
class MemoryShapeLoadedUnionTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
    }

    private YamlFileDatabase wireDatabaseAccessor() throws Exception {
        YamlFileDatabase db = new YamlFileDatabase(tempDir.toFile());
        Field daField = RTP.class.getDeclaredField("databaseAccessor");
        daField.setAccessible(true);
        daField.set(RTP.getInstance(), db);
        return db;
    }

    /** Shape carrying two biomes over well-separated runs, already merged. */
    private static Square recorded() {
        Square shape = new Square();
        for (int i = 0; i < 8; i++) {
            shape.addBiomeLocation(1000L + i * 100L, 4L, "ocean");
            shape.addBiomeLocation(5000L + i * 100L, 6L, "forest");
        }
        shape.flushAndRebuild(shape.spatialResolution());
        return shape;
    }

    private static Square reload(YamlFileDatabase db, Square source, String file) throws Exception {
        source.save(file, "test_world");
        db.processQueries(Long.MAX_VALUE);
        Square fresh = new Square();
        CompletableFuture<Void> loadFuture = fresh.load(file, "test_world");
        db.processQueries(Long.MAX_VALUE);
        loadFuture.get(5, TimeUnit.SECONDS);
        return fresh;
    }

    @Test
    void savedUnionReloadsRunForRunWithoutARebuild() throws Exception {
        YamlFileDatabase db = wireDatabaseAccessor();
        Square source = recorded();

        short[] ids = source.getBiomeMappedIdsCache();
        String[] names = source.getBiomeMappedNamesCache();
        assertTrue(ids.length > 0, "source must have recorded runs");

        Square fresh = reload(db, source, "loaded_union");

        // The union is the on-disk form, so it is readable straight off the wire: no rebuild, no
        // per-biome table to fold in first.
        assertEquals(ids.length, fresh.getBiomeMappedIdsCache().length, "run count must round-trip");
        for (int i = 0; i < ids.length; i++) {
            assertEquals(
                    names[ids[i]],
                    fresh.getBiomeMappedNamesCache()[fresh.getBiomeMappedIdsCache()[i]],
                    "owning biome of run " + i);
        }
        assertViewsAgree(source, fresh, "ocean");
        assertViewsAgree(source, fresh, "forest");

        assertEquals(source.biomeWidth("ocean"), fresh.biomeWidth("ocean"));
        assertEquals(source.biomeWidth("forest"), fresh.biomeWidth("forest"));
        assertEquals(source.getEffectiveGoodCount(), fresh.getEffectiveGoodCount());
        assertEquals("OCEAN", fresh.biomeAt(1000L));
        assertEquals("FOREST", fresh.biomeAt(5000L));
        assertNotNull(fresh.biomeRunView("forest"));
    }

    @Test
    void loadingDoesNotDirtyTheBiomeTable() throws Exception {
        YamlFileDatabase db = wireDatabaseAccessor();
        Square fresh = reload(db, recorded(), "loaded_union_once");

        long version = fresh.biomeTableVersion();
        // Nothing is pending and the table is already published, so the per-attempt cadence must
        // not merge - otherwise a loaded shape would pay a full O(recorded runs) rebuild per call.
        for (int i = 0; i < 5; i++) fresh.flushAndRebuildIfNeeded(fresh.spatialResolution());
        assertEquals(version, fresh.biomeTableVersion(), "a loaded table needs no rebuild");
    }

    @Test
    void newObservationsMergeIntoALoadedTable() throws Exception {
        YamlFileDatabase db = wireDatabaseAccessor();
        Square fresh = reload(db, recorded(), "loaded_union_merge");
        long oceanBefore = fresh.biomeWidth("ocean");
        long goodBefore = fresh.getEffectiveGoodCount();

        // The rebuild now sources its base runs from the union itself, so an observation applied
        // after a load must extend the loaded table rather than replace it.
        fresh.addBiomeLocation(20000L, 5L, "desert");
        fresh.flushAndRebuild(fresh.spatialResolution());

        assertEquals(oceanBefore, fresh.biomeWidth("ocean"), "loaded runs must survive the merge");
        assertEquals(5L, fresh.biomeWidth("desert"));
        assertEquals(goodBefore + 5L, fresh.getEffectiveGoodCount());
        assertEquals("DESERT", fresh.biomeAt(20000L));
        assertEquals("OCEAN", fresh.biomeAt(1000L));
    }

    @Test
    void legacyPerBiomeSectionsStillIngest() throws Exception {
        YamlFileDatabase db = wireDatabaseAccessor();
        // A version-3 payload: per-biome sections of fixed-width key + width delta, no run stream.
        writeLegacyFile("legacy_biome_sections");

        Square fresh = new Square();
        CompletableFuture<Void> loadFuture = fresh.load("legacy_biome_sections", "test_world");
        db.processQueries(Long.MAX_VALUE);
        loadFuture.get(5, TimeUnit.SECONDS);

        assertEquals(3, fresh.getBiomeMappedIdsCache().length, "legacy runs must be folded in");
        assertEquals(20L, fresh.biomeWidth("ocean"));
        assertEquals(7L, fresh.biomeWidth("forest"));
        assertEquals(27L, fresh.getEffectiveGoodCount());
        assertEquals("OCEAN", fresh.biomeAt(1000L));
        assertEquals("FOREST", fresh.biomeAt(5000L));
    }

    /** Hand-builds a BIN_VERSION 3 file: no bad runs, two per-biome sections. */
    private static void writeLegacyFile(String name) throws Exception {
        byte[] world = "test_world".getBytes(StandardCharsets.UTF_8);
        byte[] ocean = "OCEAN".getBytes(StandardCharsets.UTF_8);
        byte[] forest = "FOREST".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(0x52545031);
        buf.putInt(3);
        buf.putInt(world.length).put(world);
        buf.putLong(-1L);
        buf.putInt(0); // no bad runs
        buf.putInt(2); // two biome sections
        buf.putInt(ocean.length).put(ocean);
        buf.putInt(2);
        buf.putLong(1000L).putLong(12L); // width 12
        buf.putLong(2000L).putLong(8L); // width 8
        buf.putInt(forest.length).put(forest);
        buf.putInt(1);
        buf.putLong(5000L).putLong(7L);

        File pluginDir = RTP.serverAccessor.getPluginDirectory();
        Path out =
                pluginDir
                        .toPath()
                        .resolve("database")
                        .resolve("regionData")
                        .resolve(name + ".bin");
        Files.createDirectories(out.getParent());
        Files.write(out, java.util.Arrays.copyOf(buf.array(), buf.position()));
    }

    /** Every run key and prefix sum of one biome must survive the round-trip unchanged. */
    private static void assertViewsAgree(Square source, Square fresh, String biome) {
        MemoryShape.BiomeUnionTable.BiomeView before = source.biomeRunView(biome);
        MemoryShape.BiomeUnionTable.BiomeView after = fresh.biomeRunView(biome);
        assertNotNull(before, biome + " must be recorded before the save");
        assertNotNull(after, biome + " must be recorded after the load");
        assertEquals(before.length(), after.length(), biome + " run count");
        for (int k = 0; k < before.length(); k++) {
            assertEquals(before.keyAt(k), after.keyAt(k), biome + " key " + k);
            assertEquals(before.sumAt(k), after.sumAt(k), biome + " prefix sum " + k);
        }
    }
}
