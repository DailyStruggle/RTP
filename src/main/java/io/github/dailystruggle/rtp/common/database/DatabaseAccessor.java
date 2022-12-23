package io.github.dailystruggle.rtp.common.database;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 *
 */
public abstract class DatabaseAccessor<D> {
    protected enum DataType {
        INT,  //INTEGER
        REAL, //FLOATING POINT
        TEXT, //STRING
        BLOB  //OBJECTS AND ARRAYS
    }

    public static class TableObj {
        public final DataType expectedType;
        public final Object object;
        public TableObj(Object o) {
            if(o instanceof Integer || o instanceof Long) {
                expectedType = DataType.INT;
                object = ((Number) o).longValue();
            }
            else if(o instanceof Number) {
                expectedType = DataType.INT;
                object = ((Number) o).doubleValue();
            }
            else if(o instanceof String || o instanceof StringBuilder) {
                expectedType = DataType.TEXT;
                object = o.toString();
            }
            else {
                expectedType = DataType.BLOB;
                object = o;
            }
        }

        @Override
        public boolean equals(Object o) {
            //null check
            if (o == null) return false;

            //validate at deepest layer
            if(object instanceof TableObj) return object.equals(o);

            //fix type
            if(!(o instanceof TableObj)) o = new TableObj(o);
            TableObj tableObj = (TableObj) o;

            //validate at deepest layer
            if(tableObj.object instanceof TableObj) return equals(tableObj.object);

            //fast type check
            if(!expectedType.equals(tableObj.expectedType)) return false;


            return object.equals(tableObj.object);
        }

        @Override
        public int hashCode() {
            return Objects.hash(expectedType) ^ Objects.hash(object);
        }
    }

    protected Map<String,Map<TableObj, TableObj>> localTables = new ConcurrentHashMap<>();

    /**
     * pipelines to reduce overhead from opening/closing files and connections
     */
    protected long avgTimeRead = 0;
    protected long avgTimeWrite = 0;
    protected boolean stop = false;
    protected ConcurrentLinkedQueue<Map.Entry<String,Map.Entry<Map.Entry<TableObj,TableObj>,CompletableFuture<Optional<Map<String, Object>>>>>> readQueue = new ConcurrentLinkedQueue<>();
    protected ConcurrentLinkedQueue<Map.Entry<String,Map<TableObj,TableObj>>> writeQueue = new ConcurrentLinkedQueue<>();

    /**
     * @return what sort of database is this?
     */
    public abstract String name();

    @NotNull
    protected File getMainDirectory() {
        return RTP.configs.pluginDirectory;
    }

    @NotNull
    protected CompletableFuture<Optional<Map<TableObj,TableObj>>> getTable(String tableName) {
        if (!localTables.containsKey(tableName)) return CompletableFuture.completedFuture(Optional.empty());
        Map<TableObj,TableObj> table = localTables.get(tableName);
        if(table == null) return CompletableFuture.completedFuture(Optional.empty());
        return CompletableFuture.completedFuture(Optional.of(table));
    }

    @NotNull
    public Optional<CompletableFuture<Optional<?>>> getValue(String table, Object key) {
        Map<TableObj,TableObj> map = localTables.get(table);
        if(map == null) return Optional.empty();
        TableObj tableKey = new TableObj(key);
        TableObj tableValue = map.get(tableKey);
        if(tableValue==null) return Optional.empty();
        return Optional.of(CompletableFuture.completedFuture(Optional.of(tableValue.object)));
    }

    @NotNull
    public CompletableFuture<Optional<?>> getValue(String table, Object key, Object def) {
        Optional<CompletableFuture<Optional<?>>> res = getValue(table,key);
        Optional<Object> optional = Optional.ofNullable(def);
        if(!res.isPresent()) {
            setValue(table,key,def);
            CompletableFuture<Optional<?>> completableFuture = CompletableFuture.completedFuture(optional);
            res = Optional.of(completableFuture);
        }
        return res.get();
    }

    public void setValue(String tableName, Object key, Object value) {
        @NotNull CompletableFuture<Optional<Map<TableObj, TableObj>>> future = getTable(tableName);

        TableObj tableKey = new TableObj(key);
        Map<TableObj,TableObj> table;
        if(!future.isDone()) {
            future.thenAccept(tableKeyEntryMap -> setValue(tableName,key,value));
            return;
        }
        Optional<Map<TableObj, TableObj>> now = future.getNow(Optional.empty());
        if(!now.isPresent()) {
            table = new ConcurrentHashMap<>();
            localTables.put(tableName, table);
        }
        else {
            table = now.get();
        }

        TableObj tableValue = new TableObj(value);
        table.put(tableKey, tableValue);
        Map<TableObj,TableObj> write = new HashMap<>();
        write.put(tableKey,tableValue);
        writeQueue.add(new AbstractMap.SimpleEntry<>(tableName, write));
    }

    public void setValue(String tableName, Map<?,?> keyValuePairs) {
        @NotNull CompletableFuture<Optional<Map<TableObj, TableObj>>> future = getTable(tableName);

        Map<TableObj,TableObj> pairs = new HashMap<>();
        for(Map.Entry<?,?> entry : keyValuePairs.entrySet()) {
            Object key = entry.getKey();
            Object value = entry.getValue();

            TableObj tableKey = new TableObj(key);
            Map<TableObj, TableObj> table;

            if (!future.isDone()) {
                future.thenAccept(tableKeyEntryMap -> setValue(tableName, key, value));
                return;
            }
            Optional<Map<TableObj, TableObj>> now = future.getNow(Optional.empty());
            if (!now.isPresent()) {
                table = new ConcurrentHashMap<>();
                localTables.put(tableName, table);
            } else {
                table = now.get();
            }

            TableObj tableValue = new TableObj(value);
            table.put(tableKey, tableValue);
        }
        writeQueue.add(new AbstractMap.SimpleEntry<>(tableName, pairs));
    }

    public void processQueries(long availableTime) {
        if(readQueue.size() == 0 && writeQueue.size() == 0) return;
        D database = connect();
        if(stop) return;
        long dt;
        long start = System.nanoTime();

        while (writeQueue.size()>0) {
            if(stop) return;
            Map.Entry<String, Map<TableObj, TableObj>> writeRequest = writeQueue.poll();
            if(writeRequest == null) throw new IllegalStateException("null database write request");

            long localStart = System.nanoTime();
            write(database,writeRequest.getKey(),writeRequest.getValue());
            long localStop = System.nanoTime();

            if(localStop < localStart) localStart = -(Long.MAX_VALUE - localStart);
            long diff = localStop - localStart;
            if(avgTimeWrite == 0) avgTimeWrite = diff;
            else avgTimeWrite = ((avgTimeWrite*7)/8) + (diff/8);

            if(localStop < start) start = -(Long.MAX_VALUE-start); //overflow correction
            dt = localStop - start;
            if(dt+avgTimeWrite>availableTime) break;
        }

        while (readQueue.size()>0) {
            if(stop) return;
            Map.Entry<String, Map.Entry<Map.Entry<TableObj, TableObj>, CompletableFuture<Optional<Map<String, Object>>>>> readRequest = readQueue.poll();
            if(readRequest == null) throw new IllegalStateException("null database read request");

            long localStart = System.nanoTime();
            Optional<Map<String, Object>> read = read(database, readRequest.getKey(), new AbstractMap.SimpleEntry<>(readRequest.getValue().getKey().toString(), readRequest.getValue().getValue()));
            readRequest.getValue().getValue().complete(read);
            long localStop = System.nanoTime();

            if(localStop < localStart) localStart = -(Long.MAX_VALUE - localStart);
            long diff = localStop - localStart;
            if(avgTimeRead == 0) avgTimeRead = diff;
            else avgTimeRead = ((avgTimeRead*7)/8) + (diff/8);

            if(localStop < start) start = -(Long.MAX_VALUE-start); //overflow correction
            dt = localStop - start;
            if(dt+avgTimeRead>availableTime) break;
        }

        disconnect(database);
    }

    @NotNull
    public abstract D connect();

    @NotNull
    public abstract Optional<Map<String,Object>> read(D d, String tableName, Map.Entry<String,Object> lookup);

    public abstract void write(D d, String tableName, Map<TableObj,TableObj> keyValuePairs);

    public abstract void disconnect(D d);

    public static Map<String,Object> toColumns(Object obj) {
        Map<String,Object> res = new HashMap<>();
        if(obj instanceof TableObj) {
            obj = ((TableObj) obj).object;
        }

        if(obj instanceof Map.Entry) {
            Map.Entry<?,?> entry = (Map.Entry<?, ?>) obj;
            res.put(entry.getKey().toString(),entry.getValue());
        }
        else if(obj instanceof Map) {
            Map<?,?> map = (Map<?, ?>) obj;
            map.forEach((o, o2) -> res.put(o.toString(),o2));
        }
        else if(obj instanceof TeleportData) {
            TeleportData teleportData = (TeleportData) obj;
            res.put("senderName", teleportData.sender.name());
            res.put("senderId", teleportData.sender.uuid().toString());
            res.put("time", teleportData.time);
            res.put("selectedX", teleportData.selectedLocation.x());
            res.put("selectedY", teleportData.selectedLocation.y());
            res.put("selectedZ", teleportData.selectedLocation.z());
            res.put("selectedWorldName", teleportData.selectedLocation.world().name());
            res.put("selectedWorldId", teleportData.selectedLocation.world().id().toString());
            res.put("originalX", teleportData.originalLocation.x());
            res.put("originalY", teleportData.originalLocation.y());
            res.put("originalZ", teleportData.originalLocation.z());
            res.put("originalWorldName", teleportData.originalLocation.world().name());
            res.put("originalWorldId", teleportData.originalLocation.world().id().toString());
            res.put("region", teleportData.targetRegion.name);
            res.put("cost", teleportData.cost);
            res.put("attempts", teleportData.attempts);
        }

        return res;
    }
}
