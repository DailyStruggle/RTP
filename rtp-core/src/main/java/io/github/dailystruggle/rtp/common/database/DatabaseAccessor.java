package io.github.dailystruggle.rtp.common.database;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Abstract class for database accessors
 *
 * @param <D> the type of the database connection or context
 */
public abstract class DatabaseAccessor<D> {
  /** Atomic boolean to signal the database accessor to stop processing */
  public final AtomicBoolean stop = new AtomicBoolean(false);

  /** Map of local tables */
  protected Map<String, Map<TableObj, TableObj>> localTables = new ConcurrentHashMap<>();

  /** Average time taken for a read operation */
  protected long avgTimeRead = 0;

  /** Average time taken for a write operation */
  protected long avgTimeWrite = 0;

  /** Queue for read operations */
  protected ConcurrentLinkedQueue<
          Map.Entry<
              String,
              Map.Entry<
                  Map.Entry<TableObj, TableObj>, CompletableFuture<Optional<Map<String, Object>>>>>>
      readQueue = new ConcurrentLinkedQueue<>();

  /** Queue for write operations */
  protected ConcurrentLinkedQueue<Map.Entry<String, Map<TableObj, TableObj>>> writeQueue =
      new ConcurrentLinkedQueue<>();

  /** Container for a database deletion request */
  protected static final class DeleteRequest {
    public final String tableName;
    public final Map.Entry<String, Object> lookup;

    public DeleteRequest(String tableName, Map.Entry<String, Object> lookup) {
      this.tableName = tableName;
      this.lookup = lookup;
    }
  }

  /** Container describing a stored cached location */
  public static final class StoredLocation {
    private final String id;
    private final String regionName;
    private final String worldName;
    private final int x;
    private final int y;
    private final int z;
    private final int attempts;
    private final long seed;
    @Nullable private final UUID playerId;

    public StoredLocation(String id, String regionName, String worldName, int x, int y, int z, int attempts, long seed, @Nullable UUID playerId) {
      this.id = id;
      this.regionName = regionName;
      this.worldName = worldName;
      this.x = x;
      this.y = y;
      this.z = z;
      this.attempts = attempts;
      this.seed = seed;
      this.playerId = playerId;
    }

    public String getId() { return id; }
    public String getRegionName() { return regionName; }
    public String getWorldName() { return worldName; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    public int getAttempts() { return attempts; }
    public long getSeed() { return seed; }
    @Nullable public UUID getPlayerId() { return playerId; }
  }

  /** Queue for background deletion operations */
  protected final ConcurrentLinkedQueue<DeleteRequest> deleteQueue = new ConcurrentLinkedQueue<>();

  /** Queue for background file write operations */
  protected final ConcurrentLinkedQueue<FileWriteRequest> fileWriteQueue =
      new ConcurrentLinkedQueue<>();

  /** Queue for background file read operations */
  protected final ConcurrentLinkedQueue<FileReadRequest> fileReadQueue =
      new ConcurrentLinkedQueue<>();

  /** Thread-safe cache for dirty/unsaved rows keyed by composite key: tableName + ":" + primaryKey */
  protected final ConcurrentHashMap<String, Map<String, Object>> dirtyCache = new ConcurrentHashMap<>();

  /** Default constructor for DatabaseAccessor */
  protected DatabaseAccessor() {}

  public boolean isSystemDatabaseLoggingEnabled() {
    if (RTP.configs != null) {
      io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys> logging =
          (io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys>)
              RTP.configs.getParser(io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys.class);
      if (logging != null) {
        Object o = logging.getConfigValue(io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys.system_database, false);
        if (o instanceof Boolean) {
          return (Boolean) o;
        } else if (o != null) {
          return Boolean.parseBoolean(o.toString());
        }
      }
    }
    return false;
  }

  /** Simple container describing a file write request */
  protected static final class FileWriteRequest {
    public final String path;
    public final byte[] data;

    public FileWriteRequest(String path, byte[] data) {
      this.path = path;
      this.data = data;
    }
  }

  /** Simple container describing a file read request */
  protected static final class FileReadRequest {
    public final String path;
    public final CompletableFuture<Optional<byte[]>> result;

    public FileReadRequest(String path, CompletableFuture<Optional<byte[]>> result) {
      this.path = path;
      this.result = result;
    }
  }

  /** Enqueue an asynchronous background file write */
  public void saveFile(String absolutePath, byte[] payload) {
    if (absolutePath == null || absolutePath.isEmpty() || payload == null) return;
    fileWriteQueue.add(new FileWriteRequest(absolutePath, payload));
  }

  /** Enqueue an asynchronous background file read */
  @NotNull
  public CompletableFuture<Optional<byte[]>> loadFile(String absolutePath) {
    CompletableFuture<Optional<byte[]>> fut = new CompletableFuture<>();
    if (absolutePath == null || absolutePath.isEmpty()) {
      fut.complete(Optional.empty());
      return fut;
    }
    fileReadQueue.add(new FileReadRequest(absolutePath, fut));
    return fut;
  }

  /**
   * Convert an object to a map of column names and values
   *
   * @param obj the object to convert
   * @return the map of columns
   */
  public static Map<String, Object> toColumns(Object obj) {
    Map<String, Object> res = new HashMap<>();
    if (obj instanceof TableObj) {
      obj = ((TableObj) obj).object;
    }

    if (obj instanceof Map.Entry) {
      Map.Entry<?, ?> entry = (Map.Entry<?, ?>) obj;
      res.put(entry.getKey().toString(), entry.getValue());
    } else if (obj instanceof Map) {
      Map<?, ?> map = (Map<?, ?>) obj;
      map.forEach((o, o2) -> res.put(o.toString(), o2));
    } else if (obj instanceof TeleportData) {
      TeleportData teleportData = (TeleportData) obj;
      RTPCommandSender sender = teleportData.sender;

      RTPCoords selectedCoords = teleportData.selectedCoords;
      if (selectedCoords == null)
        selectedCoords = new RTPCoords(RTP.serverAccessor.getRTPWorlds().get(0).name(), 0, 0, 0);

      RTPCoords originalCoords = teleportData.originalCoords;
      if (originalCoords == null)
        originalCoords = new RTPCoords(RTP.serverAccessor.getRTPWorlds().get(0).name(), 0, 0, 0);

      Region targetRegion = teleportData.targetRegion;
      if (targetRegion == null) targetRegion = RTP.selectionAPI.getRegion("default");

      if (sender != null) {
        res.put("senderName", sender.name());
        res.put("senderId", sender.uuid().toString());
      } else {
        res.put("senderName", "console");
        res.put("senderId", CommandsAPI.serverId);
      }
      res.put("time", teleportData.time);
      res.put("delay", teleportData.delay);
      res.put("selectedX", selectedCoords.x());
      res.put("selectedY", selectedCoords.y());
      res.put("selectedZ", selectedCoords.z());
      res.put("selectedWorldName", selectedCoords.worldName());
      RTPWorld<?> selectedWorld = RTP.serverAccessor.getRTPWorld(selectedCoords.worldName());
      if (selectedWorld != null) res.put("selectedWorldId", selectedWorld.id().toString());

      res.put("originalX", originalCoords.x());
      res.put("originalY", originalCoords.y());
      res.put("originalZ", originalCoords.z());
      res.put("originalWorldName", originalCoords.worldName());
      RTPWorld<?> originalWorld = RTP.serverAccessor.getRTPWorld(originalCoords.worldName());
      if (originalWorld != null) res.put("originalWorldId", originalWorld.id().toString());

      res.put("region", targetRegion.name);
      res.put("cost", teleportData.cost);
      res.put("attempts", teleportData.attempts);
    } else if (obj instanceof io.github.dailystruggle.rtp.common.selection.region.RTPLocation) {
      io.github.dailystruggle.rtp.common.selection.region.RTPLocation location = (io.github.dailystruggle.rtp.common.selection.region.RTPLocation) obj;
      res.put("world", location.coords().worldName());
      res.put("x", location.coords().x());
      res.put("y", location.coords().y());
      res.put("z", location.coords().z());
      res.put("attempts", location.attempts());
      res.put("timestamp", System.currentTimeMillis());
      RTPWorld<?> world = RTP.serverAccessor.getRTPWorld(location.coords().worldName());
      if (world != null) res.put("seed", world.getSeed());
    }

    return res;
  }

  /**
   * Get the name of the database type
   *
   * @return what sort of database is this?
   */
  public abstract String name();

  /**
   * Get the main directory for database files
   *
   * @return the main directory
   */
  @NotNull
  protected File getMainDirectory() {
    return RTP.configs.pluginDirectory;
  }

  /**
   * Get a table by its name
   *
   * @param tableName the name of the table
   * @return a future that completes with the table map
   */
  @NotNull
  protected CompletableFuture<Optional<Map<TableObj, TableObj>>> getTable(String tableName) {
    if (!localTables.containsKey(tableName))
      return CompletableFuture.completedFuture(Optional.empty());
    Map<TableObj, TableObj> table = localTables.get(tableName);
    if (table == null) return CompletableFuture.completedFuture(Optional.empty());
    return CompletableFuture.completedFuture(Optional.of(table));
  }

  /**
   * Get a value from a table
   *
   * @param table the name of the table
   * @param key the key to get
   * @return an optional future that completes with the value
   */
  @NotNull
  public Optional<CompletableFuture<Optional<?>>> getValue(String table, Object key) {
    Map<TableObj, TableObj> map = localTables.get(table);
    if (map == null) return Optional.empty();
    TableObj tableKey = new TableObj(key);
    TableObj tableValue = map.get(tableKey);
    if (tableValue == null) return Optional.empty();
    return Optional.of(CompletableFuture.completedFuture(Optional.of(tableValue.object)));
  }

  /**
   * Get a value from a table with a default value
   *
   * @param table the name of the table
   * @param key the key to get
   * @param def the default value
   * @return a future that completes with the value
   */
  @NotNull
  public CompletableFuture<Optional<?>> getValue(String table, Object key, Object def) {
    Optional<CompletableFuture<Optional<?>>> res = getValue(table, key);
    Optional<Object> optional = Optional.ofNullable(def);
    if (!res.isPresent()) {
      setValue(table, key, def);
      CompletableFuture<Optional<?>> completableFuture =
          CompletableFuture.completedFuture(optional);
      res = Optional.of(completableFuture);
    }
    return res.get();
  }

  /**
   * Set a value in a table
   *
   * @param tableName the name of the table
   * @param key the key to set
   * @param value the value to set
   */
  public void setValue(String tableName, Object key, Object value) {
    @NotNull CompletableFuture<Optional<Map<TableObj, TableObj>>> future = getTable(tableName);

    TableObj tableKey = new TableObj(key);
    if (!future.isDone()) {
      future.thenAccept(tableKeyEntryMap -> setValue(tableName, key, value));
      return;
    }
    future.thenAccept(
        now -> {
          Map<TableObj, TableObj> table;
          if (!now.isPresent()) {
            table = new ConcurrentHashMap<>();
            localTables.put(tableName, table);
          } else {
            table = now.get();
          }

          TableObj tableValue = new TableObj(value);
          table.put(tableKey, tableValue);
          Map<TableObj, TableObj> write = new HashMap<>();
          write.put(tableKey, tableValue);
          writeQueue.add(new AbstractMap.SimpleEntry<>(tableName, write));
        });
  }

  /**
   * Set multiple values in a table
   *
   * @param tableName the name of the table
   * @param keyValuePairs the key-value pairs to set
   */
  public void setValue(String tableName, Map<?, ?> keyValuePairs) {
    getTable(tableName)
        .thenAccept(
            now -> {
              Map<TableObj, TableObj> pairs = new HashMap<>();
              for (Map.Entry<?, ?> entry : keyValuePairs.entrySet()) {
                Object key = entry.getKey();
                Object value = entry.getValue();

                TableObj tableKey = new TableObj(key);
                Map<TableObj, TableObj> table;

                if (!now.isPresent()) {
                  table = new ConcurrentHashMap<>();
                  localTables.put(tableName, table);
                } else {
                  table = now.get();
                }

                TableObj tableValue = new TableObj(value);
                table.put(tableKey, tableValue);
                pairs.put(tableKey, tableValue);
              }
              writeQueue.add(new AbstractMap.SimpleEntry<>(tableName, pairs));
            });
  }

  /**
   * Cache a row's data without writing to the database. The row is stored using a
   * composite key of the form {@code tableName + ":" + primaryKey}.
   *
   * The primary key is inferred from common identifiers, in order of preference:
   * "id", "uuid", "key", "primaryKey", "senderId", "name". If none are present,
   * a deterministic fallback based on {@code data.hashCode()} is used.
   *
   * @param tableName the table name
   * @param data the row data to cache
   */
  public void cacheValue(String tableName, Map<String, Object> data) {
    if (tableName == null || tableName.isEmpty()) return;
    if (data == null || data.isEmpty()) return;

    String primaryKey = null;
    // Preferred keys in order
    String[] preferredKeys = new String[] {"id", "uuid", "key", "primaryKey", "senderId", "name"};
    for (String k : preferredKeys) {
      if (data.containsKey(k) && data.get(k) != null) {
        primaryKey = String.valueOf(data.get(k));
        break;
      }
    }
    if (primaryKey == null) {
      // Fallback: deterministic composite of the data map
      primaryKey = String.valueOf(data.hashCode());
    }

    String compositeKey = tableName + ":" + primaryKey;
    // Store/replace the cached row atomically
    dirtyCache.put(compositeKey, new HashMap<>(data));
  }

  /**
   * Cache a row's data without writing to the database. The row is stored using a
   * composite key of the form {@code tableName + ":" + primaryKey}.
   *
   * The primary key is inferred from common identifiers, in order of preference:
   * "id", "uuid", "key", "primaryKey", "senderId", "name". If none are present,
   * a deterministic fallback based on {@code data.hashCode()} is used.
   *
   * @param data the row data to cache
   */
  public void cacheValue(TeleportData data) {
    cacheValue("teleportData", toColumns(data));
  }

  /**
   * Save a cached location to the database
   *
   * @param regionName the name of the region
   * @param location the location to save
   * @param playerUuid the UUID of the player for whom the location is cached, or null for the shared cache
   */
  public void saveCachedLocation(String regionName, io.github.dailystruggle.rtp.common.selection.region.RTPLocation location, @Nullable UUID playerUuid) {
    Map<String, Object> columns = toColumns(location);
    columns.put("region", regionName);
    columns.put("player_uuid", (playerUuid != null) ? playerUuid.toString() : "shared");
    // use a unique key for the cacheValue/setValue
    String key = regionName + ":" + location.coords().worldName() + ":" + location.coords().x() + ":" + location.coords().y() + ":" + location.coords().z();
    columns.put("UUID", key);

    Map<String, Object> data = new HashMap<>();
    data.put(key, columns);
    cacheValue("rtp_cached_locations", data);
  }

  /**
   * Delete a cached location from the database
   *
   * @param regionName the name of the region
   * @param location the location to delete
   */
  public void deleteCachedLocation(String regionName, io.github.dailystruggle.rtp.common.selection.region.RTPLocation location) {
    String key = regionName + ":" + location.coords().worldName() + ":" + location.coords().x() + ":" + location.coords().y() + ":" + location.coords().z();
    removeCachedLocation(key);
  }

  /**
   * Delete a cached location from the database by its unique identifier
   *
   * @param id the unique identifier of the cached location
   */
  public void removeCachedLocation(String id) {
    Map.Entry<String, Object> lookup = new AbstractMap.SimpleEntry<>("UUID", id);
    deleteQueue.add(new DeleteRequest("rtp_cached_locations", lookup));
  }

  /**
   * Write all queued operations to the database.
   */
  public void flush() {}

  /**
   * Load cached locations from the database for a specific region.
   *
   * @param regionName the name of the region
   * @return a list of stored locations for that region
   */
  public abstract List<StoredLocation> loadCachedLocations(String regionName);

  /**
   * Write all cached/dirty rows to the database and clear the cache.
   *
   * Each entry in {@code dirtyCache} is identified by a composite key
   * {@code tableName:primaryKey}. The table name is extracted, and
   * {@code setValue(tableName, Map<?, ?> keyValuePairs)} is used for the write.
   */
  public void flushDirtyCache() {
    flush();
    if (dirtyCache.isEmpty()) return;

    Map<String, Integer> saveCounts = new HashMap<>();

    Iterator<Map.Entry<String, Map<String, Object>>> iterator = dirtyCache.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<String, Map<String, Object>> entry = iterator.next();
      String compositeKey = entry.getKey();
      Map<String, Object> data = entry.getValue();

      // Parse tableName from the composite key (tableName:primaryKey)
      int separatorIndex = compositeKey.indexOf(':');
      if (separatorIndex == -1) {
        iterator.remove();
        continue;
      }
      String tableName = compositeKey.substring(0, separatorIndex);

      if (tableName.equalsIgnoreCase("rtp_cached_locations")) {
        String regionName = String.valueOf(data.get("region"));
        saveCounts.put(regionName, saveCounts.getOrDefault(regionName, 0) + 1);
      }

      // Call the existing setValue method to perform the I/O write
      setValue(tableName, data);

      // Remove the entry to prevent memory leaks and redundant writes
      iterator.remove();
    }

    if (!saveCounts.isEmpty() && isSystemDatabaseLoggingEnabled()) {
      ConfigParser<MessagesKeys> messages = (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
      String msgBase = messages.getConfigValue(MessagesKeys.locationSaved, "").toString();
      if (!msgBase.isEmpty()) {
        for (Map.Entry<String, Integer> countEntry : saveCounts.entrySet()) {
          String msg = msgBase.replace("[amount]", String.valueOf(countEntry.getValue()))
                  .replace("[region]", countEntry.getKey());
          RTP.log(Level.INFO, msg);
        }
      }
    }
  }

  /**
   * Process pending read and write queries
   *
   * @param availableTime the time available for processing in nanoseconds
   */
  public void processQueries(long availableTime) {
    // Process file I/O tasks first — they don't require a database connection
    if (!fileWriteQueue.isEmpty() || !fileReadQueue.isEmpty()) {
      long start = System.nanoTime();
      long dt;

      // Drain file writes within time budget
      while (!fileWriteQueue.isEmpty()) {
        FileWriteRequest req = fileWriteQueue.poll();
        if (req == null) continue;
        long localStart = System.nanoTime();
        try {
          java.nio.file.Path p = java.nio.file.Paths.get(req.path);
          java.nio.file.Files.createDirectories(p.getParent());
          java.nio.file.Files.write(p, req.data);
        } catch (Exception e) {
          RTP.log(Level.WARNING, "[DatabaseAccessor] Failed to write file: " + req.path + " - " + e.getMessage(), e);
        }
        long localStop = System.nanoTime();
        if (localStop < start) start = -(Long.MAX_VALUE - start); // overflow correction
        dt = localStop - start;
        if (dt > availableTime) break;
      }

      // Drain file reads within time budget
      while (!fileReadQueue.isEmpty()) {
        FileReadRequest req = fileReadQueue.poll();
        if (req == null) continue;
        long localStart = System.nanoTime();
        try {
          java.nio.file.Path p = java.nio.file.Paths.get(req.path);
          if (!java.nio.file.Files.exists(p)) {
            req.result.complete(Optional.empty());
          } else {
            req.result.complete(Optional.of(java.nio.file.Files.readAllBytes(p)));
          }
        } catch (Exception e) {
          req.result.complete(Optional.empty());
        }
        long localStop = System.nanoTime();
        if (localStop < start) start = -(Long.MAX_VALUE - start); // overflow correction
        dt = localStop - start;
        if (dt > availableTime) break;
      }
    }

    if (readQueue.isEmpty() && writeQueue.isEmpty()) return;
    D database = connect();
    if (database == null) return;
    if (stop.get()) return;
    long dt;
    long start = System.nanoTime();

    while (!writeQueue.isEmpty()) {
      if (stop.get()) return;
      Map.Entry<String, Map<TableObj, TableObj>> writeRequest = writeQueue.poll();
      if (writeRequest == null
          || writeRequest.getValue() == null
          || writeRequest.getValue().isEmpty()) continue;

      long localStart = System.nanoTime();
      write(database, writeRequest.getKey(), writeRequest.getValue());
      long localStop = System.nanoTime();

      if (localStop < localStart) localStart = -(Long.MAX_VALUE - localStart);
      long diff = localStop - localStart;
      if (avgTimeWrite == 0) avgTimeWrite = diff;
      else avgTimeWrite = ((avgTimeWrite * 7) / 8) + (diff / 8);

      if (localStop < start) start = -(Long.MAX_VALUE - start); // overflow correction
      dt = localStop - start;
      if (dt + avgTimeWrite > availableTime) break;
    }

    while (!deleteQueue.isEmpty()) {
      if (stop.get()) return;
      DeleteRequest deleteRequest = deleteQueue.poll();
      if (deleteRequest == null) continue;

      long localStart = System.nanoTime();
      delete(database, deleteRequest.tableName, deleteRequest.lookup);
      long localStop = System.nanoTime();

      if (localStop < localStart) localStart = -(Long.MAX_VALUE - localStart);
      long diff = localStop - localStart;
      // We'll use write timing for delete as well
      if (avgTimeWrite == 0) avgTimeWrite = diff;
      else avgTimeWrite = ((avgTimeWrite * 7) / 8) + (diff / 8);

      if (localStop < start) start = -(Long.MAX_VALUE - start); // overflow correction
      dt = localStop - start;
      if (dt + avgTimeWrite > availableTime) break;
    }

    while (!readQueue.isEmpty()) {
      if (stop.get()) return;
      Map.Entry<
              String,
              Map.Entry<
                  Map.Entry<TableObj, TableObj>, CompletableFuture<Optional<Map<String, Object>>>>>
          readRequest = readQueue.poll();
      if (readRequest == null) throw new IllegalStateException("null database read request");

      long localStart = System.nanoTime();
      Optional<Map<String, Object>> read =
          read(
              database,
              readRequest.getKey(),
              new AbstractMap.SimpleEntry<>(
                  readRequest.getValue().getKey().toString(), readRequest.getValue().getValue()));
      readRequest.getValue().getValue().complete(read);
      long localStop = System.nanoTime();

      if (localStop < localStart) localStart = -(Long.MAX_VALUE - localStart);
      long diff = localStop - localStart;
      if (avgTimeRead == 0) avgTimeRead = diff;
      else avgTimeRead = ((avgTimeRead * 7) / 8) + (diff / 8);

      if (localStop < start) start = -(Long.MAX_VALUE - start); // overflow correction
      dt = localStop - start;
      if (dt + avgTimeRead > availableTime) break;
    }

    disconnect(database);
  }

  /** Start the database accessor */
  public abstract void startup();

  /**
   * Connect to the database
   *
   * @return the database connection or context
   */
  @NotNull
  public abstract D connect();

  /**
   * Read a value from the database
   *
   * @param d the database connection
   * @param tableName the name of the table
   * @param lookup the key-value pair to look up
   * @return an optional map of the read data
   */
  @NotNull
  public abstract Optional<Map<String, Object>> read(
      D d, String tableName, Map.Entry<String, Object> lookup);

  /**
   * Write values to the database
   *
   * @param d the database connection
   * @param tableName the name of the table
   * @param keyValuePairs the key-value pairs to write
   */
  public abstract void write(D d, String tableName, Map<TableObj, TableObj> keyValuePairs);

  /**
   * Delete values from the database
   *
   * @param d the database connection
   * @param tableName the name of the table
   * @param lookup the key-value pair to look up for deletion
   */
  public abstract void delete(D d, String tableName, Map.Entry<String, Object> lookup);

  /**
   * Disconnect from the database
   *
   * @param d the database connection
   */
  public abstract void disconnect(D d);

  /** Enum for database data types */
  protected enum DataType {
    /** Integer type */
    INT, // INTEGER
    /** Floating point type */
    REAL, // FLOATING POINT
    /** Text/String type */
    TEXT, // STRING
    /** Binary/Object type */
    BLOB // OBJECTS AND ARRAYS
  }

  /** Wrapper for objects to be stored in the database */
  public static class TableObj {
    /** The expected database data type for the object */
    public final DataType expectedType;

    /** The object being wrapped */
    public final Object object;

    /**
     * Constructor for TableObj
     *
     * @param o the object to wrap
     */
    public TableObj(Object o) {
      if (o instanceof Integer || o instanceof Long) {
        expectedType = DataType.INT;
        object = ((Number) o).longValue();
      } else if (o instanceof Number) {
        expectedType = DataType.INT;
        object = ((Number) o).doubleValue();
      } else if (o instanceof String || o instanceof StringBuilder) {
        expectedType = DataType.TEXT;
        object = o.toString();
      } else {
        expectedType = DataType.BLOB;
        object = o;
      }
    }

    @Override
    public boolean equals(Object o) {
      // null check
      if (o == null) return false;

      // validate at deepest layer
      if (object instanceof TableObj) return object.equals(o);

      // fix type
      if (!(o instanceof TableObj)) o = new TableObj(o);
      TableObj tableObj = (TableObj) o;

      // validate at deepest layer
      if (tableObj.object instanceof TableObj) return equals(tableObj.object);

      // fast type check
      if (!expectedType.equals(tableObj.expectedType)) return false;

      return object.equals(tableObj.object);
    }

    @Override
    public int hashCode() {
      return Objects.hash(expectedType) ^ Objects.hash(object);
    }
  }
}
