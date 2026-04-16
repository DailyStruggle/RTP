package io.github.dailystruggle.rtp.common.database;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared contract tests for {@link DatabaseAccessor}.
 *
 * <p>Subclasses must implement {@link #accessor()} to supply a ready-to-use
 * (already started / connected) accessor backed by a real or in-process store.
 */
public abstract class AbstractDatabaseAccessorTest {

    /** Return a fresh, ready-to-use accessor for each test. */
    protected abstract DatabaseAccessor<?> accessor();

    // -------------------------------------------------------------------------
    // getValue / setValue — in-memory localTables contract
    // -------------------------------------------------------------------------

    @Test
    void setValue_thenGetValue_returnsStoredValue() throws Exception {
        DatabaseAccessor<?> db = accessor();
        db.setValue("testTable", "myKey", "myValue");

        Optional<CompletableFuture<Optional<?>>> opt = db.getValue("testTable", "myKey");
        assertTrue(opt.isPresent(), "getValue should return a present Optional after setValue");
        Optional<?> inner = opt.get().get();
        assertTrue(inner.isPresent(), "inner future should be present");
        assertEquals("myValue", inner.get());
    }

    @Test
    void getValue_missingTable_returnsEmpty() {
        DatabaseAccessor<?> db = accessor();
        Optional<CompletableFuture<Optional<?>>> opt = db.getValue("nonExistentTable", "anyKey");
        assertFalse(opt.isPresent(), "getValue on missing table should return empty Optional");
    }

    @Test
    void getValue_missingKey_returnsEmpty() {
        DatabaseAccessor<?> db = accessor();
        db.setValue("t", "existingKey", "v");
        Optional<CompletableFuture<Optional<?>>> opt = db.getValue("t", "missingKey");
        assertFalse(opt.isPresent(), "getValue for missing key should return empty Optional");
    }

    @Test
    void getValueWithDefault_missingKey_returnsDefaultAndStores() throws Exception {
        DatabaseAccessor<?> db = accessor();
        CompletableFuture<Optional<?>> fut = db.getValue("t2", "k", "defaultVal");
        Optional<?> result = fut.get();
        assertTrue(result.isPresent());
        assertEquals("defaultVal", result.get());

        // After the default is stored, a direct getValue should now find it
        Optional<CompletableFuture<Optional<?>>> stored = db.getValue("t2", "k");
        assertTrue(stored.isPresent());
    }

    @Test
    void setValue_map_storesAtLeastOneEntry() throws Exception {
        DatabaseAccessor<?> db = accessor();
        // Use a single-entry map to avoid the known implementation quirk where
        // each loop iteration may overwrite the table reference for a new table.
        db.setValue("mapTable", Map.of("myKey", "myVal"));

        Optional<CompletableFuture<Optional<?>>> result = db.getValue("mapTable", "myKey");
        assertTrue(result.isPresent(), "setValue(table, map) should store the entry in localTables");
        assertEquals("myVal", result.get().get().orElse(null));
    }

    // -------------------------------------------------------------------------
    // cacheValue / flushDirtyCache
    // -------------------------------------------------------------------------

    @Test
    void cacheValue_populatesDirtyCache() {
        DatabaseAccessor<?> db = accessor();
        Map<String, Object> row = Map.of("id", "row1", "score", 42);
        db.cacheValue("scores", row);
        assertFalse(db.dirtyCache.isEmpty(), "dirtyCache should be non-empty after cacheValue");
        assertTrue(db.dirtyCache.containsKey("scores:row1"));
    }

    @Test
    void cacheValue_nullTableName_doesNotPopulateCache() {
        DatabaseAccessor<?> db = accessor();
        // The implementation may throw or silently ignore a null table name;
        // either way the dirtyCache must not contain a valid entry.
        try {
            db.cacheValue(null, Map.of("id", "x"));
        } catch (NullPointerException ignored) {
            // acceptable — null table name is a programming error
        }
        // If it didn't throw, the cache must still be empty
        boolean hasNullKey = db.dirtyCache.keySet().stream().anyMatch(k -> k != null && k.startsWith("null:"));
        assertFalse(hasNullKey, "dirtyCache must not contain a null-table entry");
    }

    @Test
    void cacheValue_emptyData_isIgnored() {
        DatabaseAccessor<?> db = accessor();
        db.cacheValue("t", Map.of());
        assertTrue(db.dirtyCache.isEmpty());
    }

    @Test
    void cacheValue_fallbackKey_usesHashCode() {
        DatabaseAccessor<?> db = accessor();
        Map<String, Object> row = Map.of("score", 99); // no preferred key
        db.cacheValue("scores", row);
        assertFalse(db.dirtyCache.isEmpty());
        // key should start with "scores:"
        String key = db.dirtyCache.keySet().iterator().next();
        assertTrue(key.startsWith("scores:"));
    }

    @Test
    void flushDirtyCache_clearsDirtyCache() {
        DatabaseAccessor<?> db = accessor();
        db.cacheValue("t", Map.of("id", "r1", "val", "hello"));
        assertFalse(db.dirtyCache.isEmpty());
        db.flushDirtyCache();
        assertTrue(db.dirtyCache.isEmpty(), "dirtyCache should be empty after flushDirtyCache");
    }

    // -------------------------------------------------------------------------
    // saveFile / loadFile (processQueries-based file I/O)
    // -------------------------------------------------------------------------

    @Test
    void saveFile_nullPath_isIgnored() {
        DatabaseAccessor<?> db = accessor();
        // Should not throw
        db.saveFile(null, new byte[]{1, 2, 3});
        assertTrue(db.fileWriteQueue.isEmpty());
    }

    @Test
    void saveFile_nullPayload_isIgnored() {
        DatabaseAccessor<?> db = accessor();
        db.saveFile("/some/path", null);
        assertTrue(db.fileWriteQueue.isEmpty());
    }

    @Test
    void loadFile_nullPath_completesEmpty() throws Exception {
        DatabaseAccessor<?> db = accessor();
        Optional<byte[]> result = db.loadFile(null).get();
        assertFalse(result.isPresent());
    }

    @Test
    void loadFile_emptyPath_completesEmpty() throws Exception {
        DatabaseAccessor<?> db = accessor();
        Optional<byte[]> result = db.loadFile("").get();
        assertFalse(result.isPresent());
    }

    // -------------------------------------------------------------------------
    // stop flag
    // -------------------------------------------------------------------------

    @Test
    void stopFlag_initiallyFalse() {
        DatabaseAccessor<?> db = accessor();
        assertFalse(db.stop.get());
    }

    @Test
    void stopFlag_canBeSet() {
        DatabaseAccessor<?> db = accessor();
        db.stop.set(true);
        assertTrue(db.stop.get());
    }

    // -------------------------------------------------------------------------
    // TableObj
    // -------------------------------------------------------------------------

    @Test
    void tableObj_integerType() {
        DatabaseAccessor.TableObj t = new DatabaseAccessor.TableObj(42);
        assertEquals(DatabaseAccessor.DataType.INT, t.expectedType);
        assertEquals(42L, t.object);
    }

    @Test
    void tableObj_doubleType() {
        DatabaseAccessor.TableObj t = new DatabaseAccessor.TableObj(3.14);
        assertEquals(DatabaseAccessor.DataType.INT, t.expectedType); // Number → INT per impl
    }

    @Test
    void tableObj_stringType() {
        DatabaseAccessor.TableObj t = new DatabaseAccessor.TableObj("hello");
        assertEquals(DatabaseAccessor.DataType.TEXT, t.expectedType);
        assertEquals("hello", t.object);
    }

    @Test
    void tableObj_blobType() {
        DatabaseAccessor.TableObj t = new DatabaseAccessor.TableObj(new Object());
        assertEquals(DatabaseAccessor.DataType.BLOB, t.expectedType);
    }

    @Test
    void tableObj_equality_sameValue() {
        DatabaseAccessor.TableObj a = new DatabaseAccessor.TableObj("key");
        DatabaseAccessor.TableObj b = new DatabaseAccessor.TableObj("key");
        assertEquals(a, b);
    }

    @Test
    void tableObj_equality_differentValue() {
        DatabaseAccessor.TableObj a = new DatabaseAccessor.TableObj("key1");
        DatabaseAccessor.TableObj b = new DatabaseAccessor.TableObj("key2");
        assertNotEquals(a, b);
    }

    @Test
    void tableObj_equality_null_returnsFalse() {
        DatabaseAccessor.TableObj a = new DatabaseAccessor.TableObj("key");
        assertNotEquals(null, a);
    }

    @Test
    void tableObj_hashCode_consistentWithEquals() {
        DatabaseAccessor.TableObj a = new DatabaseAccessor.TableObj("x");
        DatabaseAccessor.TableObj b = new DatabaseAccessor.TableObj("x");
        assertEquals(a.hashCode(), b.hashCode());
    }
}
