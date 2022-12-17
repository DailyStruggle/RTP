package io.github.dailystruggle.rtp.common.database;

import io.github.dailystruggle.rtp.common.RTP;
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
    private boolean stop = false;
    protected ConcurrentLinkedQueue<Map.Entry<String,Map.Entry<TableObj,CompletableFuture<Optional<TableObj>>>>> readQueue = new ConcurrentLinkedQueue<>();
    protected ConcurrentLinkedQueue<Map.Entry<String,Map.Entry<TableObj,TableObj>>> writeQueue = new ConcurrentLinkedQueue<>();

    /**
     * @return what sort of database is this?
     */
    public abstract String name();

    @NotNull
    protected File getMainDirectory() {
        return RTP.configs.pluginDirectory;
    }

    @NotNull
    protected Optional<CompletableFuture<Optional<Map<TableObj,TableObj>>>> getTable(String tableName) {
        if (!localTables.containsKey(tableName)) return Optional.empty();
        Map<TableObj,TableObj> table = localTables.get(tableName);
        if(table == null) return Optional.empty();
        return Optional.of(CompletableFuture.completedFuture(Optional.of(table)));
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

    public Optional<?> setValue(String tableName, Object key, Object value) {
        Optional<CompletableFuture<Optional<Map<TableObj, TableObj>>>> mapOptional = getTable(tableName);

        TableObj tableKey = new TableObj(key);
        Optional<TableObj> res;
        Map<TableObj,TableObj> table;
        if (mapOptional.isPresent()) {
            CompletableFuture<Optional<Map<TableObj, TableObj>>> future = mapOptional.get();
            if(!future.isDone()) {
                future.whenComplete((tableKeyEntryMap, throwable) -> setValue(tableName,key,value));
                return Optional.empty();
            }
            Optional<Map<TableObj, TableObj>> now = future.getNow(Optional.empty());
            if(!now.isPresent()) {
                table = new ConcurrentHashMap<>();
                localTables.put(tableName, table);
                res = Optional.empty();
            }
            else {
                table = now.get();
                res = Optional.ofNullable(table.get(tableKey));
            }
        } else {
            table = new ConcurrentHashMap<>();
            localTables.put(tableName, table);
            res = Optional.empty();
        }

        TableObj tableValue = new TableObj(value);
        table.put(tableKey, tableValue);
        writeQueue.add(new AbstractMap.SimpleEntry<>(tableName, new AbstractMap.SimpleEntry<>(tableKey, tableValue)));
        return res;
    }

    public void processQueries(long availableTime) {
        D database = connect();
        if(stop) return;
        long dt = 0;
        long start = System.nanoTime();

        while (writeQueue.size()>0) {
            if(stop) return;
            Map.Entry<String, Map.Entry<TableObj, TableObj>> writeRequest = writeQueue.poll();
            if(writeRequest == null) throw new IllegalStateException("null database write request");

            long localStart = System.nanoTime();
            write(database,writeRequest.getKey(),writeRequest.getValue().getKey(), writeRequest.getValue().getValue());
            long localStop = System.nanoTime();

            if(localStop < localStart) localStart = -(Long.MAX_VALUE - localStart);
            long diff = localStop - localStart;
            if(avgTimeWrite == 0) avgTimeWrite = diff;
            else avgTimeWrite = ((avgTimeWrite*7)/8) + (diff/8);

            if(localStop < start) start = -(Long.MAX_VALUE-start); //overflow correction
            dt = localStop -start;
            if(dt+avgTimeWrite>availableTime) break;
        }

        while (readQueue.size()>0) {
            if(stop) return;
            Map.Entry<String, Map.Entry<TableObj, CompletableFuture<Optional<TableObj>>>> readRequest = readQueue.poll();
            if(readRequest == null) throw new IllegalStateException("null database read request");

            long localStart = System.nanoTime();
            readRequest.getValue().getValue().complete(
                    read(database,readRequest.getKey(),readRequest.getValue().getKey())
            );
            long localStop = System.nanoTime();

            if(localStop < localStart) localStart = -(Long.MAX_VALUE - localStart);
            long diff = localStop - localStart;
            if(avgTimeRead == 0) avgTimeRead = diff;
            else avgTimeRead = ((avgTimeRead*7)/8) + (diff/8);

            if(localStop < start) start = -(Long.MAX_VALUE-start); //overflow correction
            dt = localStop -start;
            if(dt+avgTimeRead>availableTime) break;
        }

        disconnect(database);
    }

    @NotNull
    public abstract D connect();

    @NotNull
    public abstract Optional<TableObj> read(D d, String tableName, TableObj key);

    public abstract void write(D d, String tableName, TableObj key, TableObj value);

    public abstract void disconnect(D d);
}