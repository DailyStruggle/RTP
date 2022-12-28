package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.simpleyaml.configuration.ConfigurationSection;
import org.simpleyaml.configuration.file.YamlFile;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * a "database" that's just reading and writing yaml files,
 *      using SimpleYaml (a server independent yaml library that can preserve comments)
 */
public class YamlFileDatabase extends DatabaseAccessor<Map<String,YamlFile>> {
    private final File directory;

    /**
     * constructor
     *
     * provide directory where yml/yaml files should exist
     */
    public YamlFileDatabase(File directory) {
        this.directory = directory;
    }

    /**
     * container for map reference access, such set() will propagate changes where == would not
     */
    public final AtomicReference<Map<String, YamlFile>> cachedLookup = new AtomicReference<>();
    {
        cachedLookup.set(new ConcurrentHashMap<>());
    }

    @Override
    public String name() {
        return "YAML";
    }

    @Override
    @NotNull
    public Map<String, YamlFile> connect() {
        if(!directory.exists()) {
            if(!directory.mkdirs()) throw new IllegalStateException("unable to create directory " + directory.getAbsolutePath());
        }

        File[] files = directory.listFiles();
        Map<String,YamlFile> res = new HashMap<>();
        if(files == null) return res;
        for (File file : files) {
            if (!file.isFile()) continue;

            YamlFile yamlFile;
            try {
                yamlFile = new YamlFile(file);
                yamlFile.loadWithComments();
            } catch (Exception exception) { //not a yaml file
                continue;
            }
            res.put(file.getName(),yamlFile);
            localTables.putIfAbsent(file.getName(),new ConcurrentHashMap<>());
            Map<TableObj, TableObj> map = localTables.get(file.getName());
            Map<String, Object> values = yamlFile.getMapValues(true);
            for(Map.Entry<String,Object> entry : values.entrySet()) {
                map.put(new TableObj(entry.getKey()),new TableObj(entry.getValue()));
            }
        }
        cachedLookup.get().clear();
        cachedLookup.get().putAll(res);
        return res;
    }

    @Override
    public void disconnect(Map<String, YamlFile> database) {
        for (YamlFile file : database.values()) {
            try {
                if(!file.exists()) file.createNewFile(true);
                file.save();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public @NotNull Optional<Map<String, Object>> read(Map<String, YamlFile> database, String tableName, Map.Entry<String,Object> lookup) {
        if(!(database instanceof Map)) throw new IllegalStateException();

        if(!StringUtils.endsWithIgnoreCase(tableName,".yml")) tableName = tableName + ".yml";

        YamlFile file = database.get(tableName);
        if(file == null || !file.exists()) return Optional.empty();
        String s = lookup.getKey();
        Object o = file.get(s,lookup.getValue());
        if(o == null) return Optional.empty();
        Map<String,Object> res = new HashMap<>();
        res.put(s,o);
        return Optional.of(res);
    }

    @Override
    public void write(Map<String, YamlFile> database, String tableName, Map<TableObj,TableObj> keyValuePairs) {
        if(!StringUtils.endsWithIgnoreCase(tableName,".yml")) tableName = tableName + ".yml";
        YamlFile file = database.get(tableName);
        if(file == null) file = new YamlFile(directory.getAbsolutePath() + File.separator + tableName);
        if(!file.exists()) {
            String filePath = file.getFilePath();
            if(database.containsKey("default.yml")) {
                try {
                    database.get("default.yml").copyTo(filePath);
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }
            }
            else {
                try {
                    file.createNewFile();
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }
            }
            String substring = filePath.substring(filePath.lastIndexOf(File.separator) + 1);
            database.put(substring,file);
        }
        try {
            file.loadWithComments();
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        for(Map.Entry<TableObj,TableObj> entry : keyValuePairs.entrySet()) {
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
    public void setValue(String tableName, Map<?,?> keyValuePairs) {
        super.setValue(tableName, keyValuePairs);
        Map<TableObj,TableObj> writeValues = new HashMap<>();
        keyValuePairs.forEach((o, o2) -> writeValues.put(new TableObj(o), new TableObj(o2)));
        Map.Entry<String,Map<TableObj,TableObj>> writeRequest
                = new AbstractMap.SimpleEntry<>(tableName,writeValues);
        writeQueue.add(writeRequest);
    }

    @Override
    public void startup() {
        Map<String,YamlFile> lookup = connect();
        @NotNull Optional<Map<String, Object>> read = read(lookup, "referenceData", new AbstractMap.SimpleEntry<>("referenceTime",0L));
        if(read.isPresent()) {
            YamlFile yamlFile = lookup.get("teleportData.yml");
            try {
                long referenceTime = Long.parseLong(read.get().get("referenceTime").toString());
                Map<String, Object> mapValues = yamlFile.getMapValues(false);
                for(Map.Entry<String,Object> entry : mapValues.entrySet()) {
                    String key = entry.getKey();
                    Object value = entry.getValue();

                    Map<String,Object> dataMap;
                    if(value instanceof Map) {
                        dataMap = (Map<String, Object>) value;
                    }
                    else if(value instanceof ConfigurationSection) {
                        dataMap = ((ConfigurationSection) value).getMapValues(false);
                    }
                    else throw new IllegalStateException();

                    try {
                        UUID id = UUID.fromString(key.split("\\.")[0]);
                        TeleportData teleportData = new TeleportData();
                        teleportData.sender = RTP.serverAccessor.getSender(UUID.fromString(dataMap.get("senderId").toString()));
                        teleportData.time = (Long) dataMap.getOrDefault("time", 0L);
                        if(teleportData.time!=0L) teleportData.time = System.currentTimeMillis() - Math.abs(referenceTime - teleportData.time);
                        RTPWorld originalWorldId = RTP.serverAccessor.getRTPWorld(UUID.fromString(dataMap.get("originalWorldId").toString()));
                        if(originalWorldId!=null)
                            teleportData.originalLocation = new RTPLocation(originalWorldId,
                                    ((Number)dataMap.get("originalX")).intValue(),
                                    ((Number)dataMap.get("originalY")).intValue(),
                                    ((Number)dataMap.get("originalZ")).intValue());
                        RTPWorld selectedWorldId = RTP.serverAccessor.getRTPWorld(UUID.fromString(dataMap.get("selectedWorldId").toString()));
                        if(selectedWorldId!=null)
                            teleportData.selectedLocation = new RTPLocation(selectedWorldId,
                                    ((Number)dataMap.get("selectedX")).intValue(),
                                    ((Number)dataMap.get("selectedY")).intValue(),
                                    ((Number)dataMap.get("selectedZ")).intValue());
                        teleportData.attempts = ((Number)dataMap.get("attempts")).intValue();
                        teleportData.cost = ((Number)dataMap.get("cost")).intValue();
                        teleportData.targetRegion = RTP.getInstance().selectionAPI.getRegion(dataMap.get("region").toString());
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

    private static void setSection(ConfigurationSection section, Map<String,Object> map) {
        Map<String, Object> mapValues = section.getMapValues(false);

        for(Map.Entry<String,Object> e : mapValues.entrySet()) {
            Object o = e.getValue();
            if(!map.containsKey(e.getKey())) continue;
            Object value = map.get(e.getKey());
            if(o instanceof ConfigurationSection) {
                if(value instanceof FactoryValue<?>) {
                    EnumMap<?, Object> data = ((FactoryValue<?>) value).getData();
                    Map<String,Object> subMap = new HashMap<>();
                    for(Map.Entry<? extends Enum<?>,?> d : data.entrySet()) subMap.put(d.getKey().name(),d.getValue());
                    setSection((ConfigurationSection) o,subMap);
                }
                else if(value instanceof Map) {
                    setSection((ConfigurationSection) o, (Map<String, Object>) value);
                }
                else throw new IllegalArgumentException();
            }
            else {
                section.set(e.getKey(),value);
            }
        }
    }
}
