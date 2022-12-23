package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import org.jetbrains.annotations.NotNull;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class SQLiteDatabaseAccessor extends DatabaseAccessor<Connection> {
    private final String url;

    public SQLiteDatabaseAccessor(String url) {
        this.url = url;
    }

    @Override
    public String name() {
        return url;
    }

    @Override
    public @NotNull Connection connect() {
        Connection res;
        try {
            res = DriverManager.getConnection(url);
            if(res!=null) {
                DatabaseMetaData meta = res.getMetaData();

            }
        } catch (SQLException e) {
            e.printStackTrace();
            throw new IllegalStateException();
        }
        return res;
    }

    @Override
    public void processQueries(long availableTime) {
        Connection database = connect();
        if(stop) return;
        long dt;
        long start = System.nanoTime();

        while (writeQueue.size()>0) {
            if(stop) return;
            Map.Entry<String, Map<TableObj, TableObj>> writeRequest = writeQueue.poll();
            if(writeRequest == null) throw new IllegalStateException("null database write request");

            long localStart = System.nanoTime();

            write(database, writeRequest.getKey(),writeRequest.getValue());

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
            Map.Entry<TableObj, TableObj> keyObj = readRequest.getValue().getKey();
            Map.Entry<String,Object> lookup =
                    new AbstractMap.SimpleEntry<>(keyObj.getKey().object.toString(),keyObj.getValue().object);
            readRequest.getValue().getValue().complete(
                    read(database,readRequest.getKey(), lookup)
            );
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

    @Override
    public @NotNull Optional<Map<String, Object>> read(Connection connection, String tableName, Map.Entry<String, Object> lookup) {
        String sql = "PRAGMA table_info(" + tableName + ");";
        Statement statement;
        Map<String, String> columns = new HashMap<>();

        try {
            statement = connection.createStatement();
            statement.execute(sql);
            ResultSet resultSet = statement.getResultSet();
            while (resultSet.next()) {
                columns.put(resultSet.getString("name"), resultSet.getString("type"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
            throw new IllegalStateException();
        }

        //validate and add necessary columns
        List<String> keys = new ArrayList<>();
        List<String> values = new ArrayList<>();


        return Optional.empty(); //todo
    }

    @Override
    public void write(Connection connection, String tableName, Map<TableObj,TableObj> keyValuePairs) {
        //get table info for validation
        String sql = "PRAGMA table_info(" + tableName + ");";
        Statement statement;
        Map<String, String> columns = new HashMap<>();
        try {
            statement = connection.createStatement();
            statement.execute(sql);
            ResultSet resultSet = statement.getResultSet();
            while (resultSet.next()) {
                columns.put(resultSet.getString("name"), resultSet.getString("type"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
            throw new IllegalStateException();
        }

        //validate and add necessary columns
        List<String> keys = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (Map.Entry<TableObj, TableObj> entry : keyValuePairs.entrySet()) {
            String key = entry.getKey().object.toString();

            keys.add(key);
            values.add(entry.getValue().object.toString());

            if(columns.containsKey(key)) continue;

            String typeStr;
            switch (entry.getValue().expectedType) {
                case INT:
                    typeStr = "INTEGER";
                    break;
                case REAL:
                    typeStr = "REAL";
                    break;
                case TEXT:
                    typeStr = "TEXT";
                    break;
                case BLOB:
                    typeStr = "BLOB";
                    break;
                default:
                    throw new IllegalStateException("Unexpected value: " + entry.getKey().expectedType);
            }

            sql = "ALTER TABLE " + tableName +" ADD " + key + " " + typeStr + ";";
            try {
                statement.execute(sql);
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }

        StringBuilder builder = new StringBuilder("INSERT INTO ").append(tableName).append(" (");
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            builder = builder.append(key);
            if(i<keys.size()-1) builder = builder.append(',');
        }
        builder = builder.append(") VALUES(");
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            builder = builder.append(value);
            if(i<values.size()-1) builder = builder.append(',');
        }
        builder = builder.append(");");

        try {
            statement.execute(builder.toString());
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void disconnect(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
