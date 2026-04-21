package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;
import org.simpleyaml.configuration.ConfigurationSection;
import org.simpleyaml.configuration.file.YamlFile;

/**
 * a "database" that's just reading and writing yaml files, using SimpleYaml ( a server independent
 * yaml library that can preserve comments )
 */
public class YamlFileDatabase extends DatabaseAccessor<Map<String, YamlFile>> {
  /** container for map reference access, such set() will propagate changes where == would not */
  public final AtomicReference<Map<String, YamlFile>> cachedLookup = new AtomicReference<>();

  public final AtomicReference<Map<String, Long>> cachedLookupLastModified =
      new AtomicReference<>();
  private final File directory;

  {
    cachedLookup.set(new ConcurrentHashMap<>());
    cachedLookupLastModified.set(new ConcurrentHashMap<>());
  }

  /**
   * constructor
   *
   * @param directory - location of file provide directory where yml/yaml files should exist
   */
  public YamlFileDatabase(File directory) {
    this.directory = directory;
  }

  private static void setSection(ConfigurationSection section, Map<String, Object> map) {
    Map<String, Object> mapValues = section.getMapValues(false);

    for (Map.Entry<String, Object> e : mapValues.entrySet()) {
      Object o = e.getValue();
      if (!map.containsKey(e.getKey())) continue;
      Object value = map.get(e.getKey());
      if (o instanceof ConfigurationSection) {
        if (value instanceof FactoryValue<?>) {
          EnumMap<?, Object> data = ((FactoryValue<?>) value).getData();
          Map<String, Object> subMap = new HashMap<>();
          for (Map.Entry<? extends Enum<?>, ?> d : data.entrySet())
            subMap.put(d.getKey().name(), d.getValue());
          setSection((ConfigurationSection) o, subMap);
        } else if (value instanceof Map) {
          setSection((ConfigurationSection) o, (Map<String, Object>) value);
        } else throw new IllegalArgumentException();
      } else {
        section.set(e.getKey(), value);
      }
    }
  }

  @Override
  public String name() {
    return "YAML";
  }

  @Override
  @NotNull
  public Map<String, YamlFile> connect() {
    if (!directory.exists()) {
      if (!directory.mkdirs())
        throw new IllegalStateException(
            "unable to create directory " + directory.getAbsolutePath());
    }

    File[] files = directory.listFiles();
    Map<String, YamlFile> res = new HashMap<>();
    if (files == null) return res;
    for (File file : files) {
      if (!file.isFile()) continue;

      long lastModified = file.lastModified();
      long dt = lastModified - cachedLookupLastModified.get().getOrDefault(file.getName(), -1L);
      if (dt == 0) {
        res.put(file.getName(), cachedLookup.get().get(file.getName()));
        continue;
      }

      YamlFile yamlFile;
      try {
        yamlFile = new YamlFile(file);
        yamlFile.loadWithComments();
        cachedLookupLastModified.get().put(file.getName(), lastModified);
        cachedLookup.get().put(file.getName(), yamlFile);
      } catch (Exception exception) { // not a yaml file
        continue;
      }
      res.put(file.getName(), yamlFile);
      localTables.putIfAbsent(file.getName(), new ConcurrentHashMap<>());
      Map<TableObj, TableObj> map = localTables.get(file.getName());
      Map<String, Object> values = yamlFile.getMapValues(true);
      for (Map.Entry<String, Object> entry : values.entrySet()) {
        map.put(new TableObj(entry.getKey()), new TableObj(entry.getValue()));
      }
    }

    return res;
  }

  @Override
  public void disconnect(Map<String, YamlFile> database) {
    for (YamlFile file : database.values()) {
      try {
        if (!file.exists()) file.createNewFile(true);
        file.save();
      } catch (IOException e) {
        RTP.log(Level.WARNING, e.getMessage(), e);
      }
    }
  }

  @Override
  public @NotNull Optional<Map<String, Object>> read(
      Map<String, YamlFile> database, String tableName, Map.Entry<String, Object> lookup) {
    if (!(database instanceof Map)) throw new IllegalStateException();

    if (!tableName.endsWith(".yml")) tableName = tableName + ".yml";

    YamlFile file = database.get(tableName);
    if (file == null || !file.exists()) return Optional.empty();
    String s = lookup.getKey();
    Object o = file.get(s, lookup.getValue());
    if (o == null) return Optional.empty();
    Map<String, Object> res = new HashMap<>();
    res.put(s, o);
    return Optional.of(res);
  }

  @Override
  public void write(
      Map<String, YamlFile> database, String tableName, Map<TableObj, TableObj> keyValuePairs) {
    if (!tableName.endsWith(".yml")) tableName = tableName + ".yml";
    YamlFile file = database.get(tableName);
    if (file == null) file = new YamlFile(directory.getAbsolutePath() + File.separator + tableName);
    if (!file.exists()) {
      String filePath = file.getFilePath();
      if (database.containsKey("default.yml")) {
        try {
          database.get("default.yml").copyTo(filePath);
        } catch (IOException e) {
          RTP.log(Level.WARNING, e.getMessage(), e);
          return;
        }
      } else {
        try {
          file.createNewFile();
        } catch (IOException e) {
          RTP.log(Level.WARNING, e.getMessage(), e);
          return;
        }
      }
      String substring = filePath.substring(filePath.lastIndexOf(File.separator) + 1);
      database.put(substring, file);
    }
    try {
      file.loadWithComments();
    } catch (IOException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
      return;
    }

    for (Map.Entry<TableObj, TableObj> entry : keyValuePairs.entrySet()) {
      String keyStr = entry.getKey().object.toString();
      Object o = file.get(keyStr);
      Object value = entry.getValue().object;

      if (o instanceof ConfigurationSection) {
        ConfigurationSection configurationSection = (ConfigurationSection) o;

        if (configurationSection.getName().equalsIgnoreCase("shape") && value instanceof String) {
          String shapeName = (String) value;
          value = RTP.factoryMap.get(RTP.factoryNames.shape).getOrDefault(shapeName);
        }

        if (configurationSection.getName().equalsIgnoreCase("vert") && value instanceof String) {
          String vertName = (String) value;
          value = RTP.factoryMap.get(RTP.factoryNames.vert).getOrDefault(vertName);
        }

        if (value instanceof FactoryValue<?>) {
          EnumMap<?, Object> data = ((FactoryValue<?>) value).getData();
          Map<String, Object> map = new HashMap<>();
          for (Map.Entry<? extends Enum<?>, Object> d : data.entrySet())
            map.put(d.getKey().name(), d.getValue());
          setSection((ConfigurationSection) o, map);
        } else if (value instanceof Map) {
          setSection((ConfigurationSection) o, (Map<String, Object>) value);
        } else {
          throw new IllegalArgumentException("expected map or similar for " + keyStr);
        }
        file.set(keyStr, o);
      } else {
        file.set(keyStr, value);
      }
    }
  }

  @Override
  public void delete(Map<String, YamlFile> database, String tableName, Map.Entry<String, Object> lookup) {
    if (!tableName.endsWith(".yml")) tableName = tableName + ".yml";
    YamlFile file = database.get(tableName);
    if (file == null || !file.exists()) return;

    try {
      file.loadWithComments();
      if (lookup.getKey().equalsIgnoreCase("UUID") || lookup.getKey().equalsIgnoreCase("id")) {
          file.remove(String.valueOf(lookup.getValue()));
      } else {
          file.remove(lookup.getKey());
      }
      file.save();
    } catch (IOException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
    }
  }

  /**
   * Synchronously wipe every entry in {@code rtp_cached_locations.yml}.
   *
   * <p>Called by {@link #rebuildCachedLocationsFromMemory()} immediately before
   * the in-memory state is re-enqueued. Keeping the file (rather than deleting
   * it) preserves any top-level comments/headers that simpleyaml would lose on
   * recreate.
   */
  @Override
  public void clearAllCachedLocations() {
    File file = new File(directory, "rtp_cached_locations.yml");
    if (!file.exists()) return;
    try {
      YamlFile yamlFile = new YamlFile(file);
      yamlFile.loadWithComments();
      for (String key : new ArrayList<>(yamlFile.getMapValues(false).keySet())) {
        yamlFile.set(key, null);
      }
      yamlFile.save();
      // Invalidate caches so a subsequent connect() re-reads the empty file
      // instead of serving the pre-wipe snapshot.
      cachedLookup.get().remove("rtp_cached_locations.yml");
      cachedLookupLastModified.get().remove("rtp_cached_locations.yml");
      Map<TableObj, TableObj> tableMap = localTables.get("rtp_cached_locations.yml");
      if (tableMap != null) tableMap.clear();
    } catch (IOException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
    }
  }

  @Override
  public List<StoredLocation> loadCachedLocations(String regionName) {
    List<StoredLocation> res = new ArrayList<>();
    Map<String, YamlFile> db = connect();
    YamlFile file = db.get("rtp_cached_locations.yml");
    if (file == null || !file.exists()) return res;

    try {
      file.loadWithComments();
      Map<String, Object> values = file.getMapValues(false);
      for (Map.Entry<String, Object> entry : values.entrySet()) {
        String id = entry.getKey();
        ConfigurationSection section = file.getConfigurationSection(id);
        if (section == null) continue;

        String rowRegion = section.getString("region");
        if (!regionName.equalsIgnoreCase(rowRegion)) continue;

        String worldName = section.getString("world");
        int x = section.getInt("x");
        int y = section.getInt("y");
        int z = section.getInt("z");
        int attempts = section.getInt("attempts");
        long seed = section.getLong("seed", 0L);
        String playerUuidStr = section.getString("player_uuid");

        UUID playerUuid = null;
        if (playerUuidStr != null && !playerUuidStr.equalsIgnoreCase("shared")) {
          try {
            playerUuid = UUID.fromString(playerUuidStr);
          } catch (IllegalArgumentException ignored) {}
        }

        res.add(new StoredLocation(id, regionName, worldName, x, y, z, attempts, seed, playerUuid));
      }
    } catch (IOException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
    }
    return res;
  }

  @Override
  public void setValue(String tableName, Map<?, ?> keyValuePairs) {
    super.setValue(tableName, keyValuePairs);
    Map<TableObj, TableObj> writeValues = new HashMap<>();
    keyValuePairs.forEach((o, o2) -> writeValues.put(new TableObj(o), new TableObj(o2)));
    Map.Entry<String, Map<TableObj, TableObj>> writeRequest =
        new AbstractMap.SimpleEntry<>(tableName, writeValues);
    writeQueue.add(writeRequest);
  }

  @Override
  public void cacheValue(TeleportData data) {
    Map<String, Object> dataMap = toColumns(data);
    Map<String, Object> saveMap = new HashMap<>();
    saveMap.put(data.sender.uuid().toString(), dataMap);
    cacheValue("teleportData", saveMap);
  }

  @Override
  public void startup() {
    Map<String, YamlFile> lookup = connect();

    // Purge stale locations
    YamlFile cachedFile = lookup.get("rtp_cached_locations.yml");
    if (cachedFile != null) {
      try {
        cachedFile.loadWithComments();
        long threshold = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L);
        Map<String, Object> values = cachedFile.getMapValues(false);
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
          Object obj = entry.getValue();
          if (obj instanceof ConfigurationSection section) {
            String playerUuidStr = section.getString("player_uuid");
            if (playerUuidStr != null && !playerUuidStr.equalsIgnoreCase("shared")) {
              long timestamp = section.getLong("timestamp", 0L);
              if (timestamp < threshold) {
                toRemove.add(entry.getKey());
              }
            }
          }
        }
        if (!toRemove.isEmpty()) {
          for (String key : toRemove) cachedFile.set(key, null);
          cachedFile.save();
          if (isSystemDatabaseLoggingEnabled()) {
            RTP.log(Level.INFO, "Purged " + toRemove.size() + " stale cached locations from the database.");
          }
        }
      } catch (IOException e) {
        RTP.log(Level.WARNING, e.getMessage(), e);
      }
    }

    @NotNull
    Optional<Map<String, Object>> read =
        read(lookup, "referenceData", new AbstractMap.SimpleEntry<>("referenceTime", 0L));
    if (read.isPresent()) {
      YamlFile yamlFile = lookup.get("teleportData.yml");
      try {
        long referenceTime = Long.parseLong(read.get().get("referenceTime").toString());
        Map<String, Object> mapValues = yamlFile.getMapValues(false);
        for (Map.Entry<String, Object> entry : mapValues.entrySet()) {
          String key = entry.getKey();
          Object value = entry.getValue();

          Map<String, Object> dataMap;
          if (value instanceof Map) {
            dataMap = (Map<String, Object>) value;
          } else if (value instanceof ConfigurationSection) {
            dataMap = ((ConfigurationSection) value).getMapValues(false);
          } else throw new IllegalStateException();

          try {
            UUID id = UUID.fromString(key.split("\\.")[0]);
            TeleportData teleportData = new TeleportData();
            teleportData.sender =
                RTP.serverAccessor.getSender(UUID.fromString(dataMap.get("senderId").toString()));
            teleportData.time = (Long) dataMap.getOrDefault("time", 0L);
            if (teleportData.time != 0L)
              teleportData.time =
                  System.currentTimeMillis() - Math.abs(referenceTime - teleportData.time);
            Object originalWorldName = dataMap.get("originalWorldName");
            if (originalWorldName != null)
              teleportData.originalCoords =
                  new RTPCoords(
                      originalWorldName.toString(),
                      ((Number) dataMap.get("originalX")).intValue(),
                      ((Number) dataMap.get("originalY")).intValue(),
                      ((Number) dataMap.get("originalZ")).intValue());
            Object selectedWorldName = dataMap.get("selectedWorldName");
            if (selectedWorldName != null)
              teleportData.selectedCoords =
                  new RTPCoords(
                      selectedWorldName.toString(),
                      ((Number) dataMap.get("selectedX")).intValue(),
                      ((Number) dataMap.get("selectedY")).intValue(),
                      ((Number) dataMap.get("selectedZ")).intValue());
            teleportData.attempts = ((Number) dataMap.get("attempts")).intValue();
            teleportData.cost = ((Number) dataMap.get("cost")).intValue();
            teleportData.targetRegion =
                RTP.selectionAPI.getRegion(dataMap.get("region").toString());
            teleportData.completed = true;
            RTP.getInstance().latestTeleportData.put(id, teleportData);
          } catch (Exception ignored) {

          }
        }
      } catch (IllegalArgumentException exception) {
        exception.printStackTrace();
      }
    }
  }
}
