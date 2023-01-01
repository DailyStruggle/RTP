package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
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

        String filePath = url.substring(url.lastIndexOf(":")+1);
        File databaseFile = new File(filePath);
        try {
            if(!databaseFile.exists()) {
                databaseFile.getParentFile().mkdirs();
                databaseFile.createNewFile();
            }
            res = DriverManager.getConnection(url);
        } catch (SQLException | IOException e) {
            e.printStackTrace();
            return null;
        }
        return res;
    }

    @Override
    public void processQueries(long availableTime) {
        if(readQueue.size() == 0 && writeQueue.size() == 0) return;
        if(stop.get()) return;
        Connection database = connect();
        if(database == null) return;
        if(stop.get()) {
            disconnect(database);
            return;
        }
        long dt;
        long start = System.nanoTime();

        while (writeQueue.size()>0) {
            if(stop.get()) {
                disconnect(database);
                return;
            }
            Map.Entry<String, Map<TableObj, TableObj>> writeRequest = writeQueue.poll();
            if(writeRequest == null) throw new IllegalStateException("invalid database write request");
            if(writeRequest.getValue() == null) throw new IllegalStateException("invalid database write request");
            if(writeRequest.getValue().size() == 0) throw new IllegalStateException("invalid database write request");

            long localStart = System.nanoTime();
            try {
                write(database,writeRequest.getKey(),writeRequest.getValue());
            } catch (Exception e) {
                writeQueue.add(writeRequest);
                disconnect(database);
                return;
            }
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
            if(stop.get()) {
                disconnect(database);
                return;
            }
            Map.Entry<String, Map.Entry<Map.Entry<TableObj, TableObj>, CompletableFuture<Optional<Map<String, Object>>>>> readRequest = readQueue.poll();
            if(readRequest == null) throw new IllegalStateException("null database read request");

            long localStart = System.nanoTime();
            Map.Entry<TableObj, TableObj> keyObj = readRequest.getValue().getKey();
            Map.Entry<String,Object> lookup =
                    new AbstractMap.SimpleEntry<>(keyObj.getKey().object.toString(),keyObj.getValue().object);
            Optional<Map<String, Object>> read;
            try {
                read = read(database, readRequest.getKey(), lookup);
            } catch (Exception e) {
                readQueue.add(readRequest);
                disconnect(database);
                return;
            }

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

    @Override
    public void startup() {
        DriverManager.setLoginTimeout(30);
        Connection connection = connect();
        @NotNull Optional<Map<String, Object>> read = read(connection, "referenceData", new AbstractMap.SimpleEntry<>("UUID",new UUID(0,0)));
        if(!read.isPresent()) {
            try {
                connection.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
            return;
        }

        //get full table
        String tableName = "teleportData";
        String sql = "SELECT * FROM " + tableName;
        Statement statement;

        try {
            statement = connection.createStatement();

            try {
                statement.execute(sql);
            } catch (SQLException e) {
                connection.close();
                return;
            }

            ResultSet resultSet;

            try {
                resultSet = statement.getResultSet();
            } catch (SQLException e) {
                connection.close();
                return;
            }

            if(resultSet == null) {
                connection.close();
                return;
            }

            //each data
            while (resultSet.next()) {
                String uuidStr = resultSet.getString("UUID");
                if(uuidStr == null) continue;
                
                UUID uuid = UUID.fromString(uuidStr);

                TeleportData teleportData = new TeleportData();
                teleportData.completed = true;
                teleportData.time = Long.parseLong(resultSet.getString("time"));
                teleportData.selectedLocation = new RTPLocation(
                        RTP.serverAccessor.getRTPWorld(UUID.fromString(resultSet.getString("selectedWorldId"))),
                        Integer.parseInt(resultSet.getString("selectedX")),
                        Integer.parseInt(resultSet.getString("selectedY")),
                        Integer.parseInt(resultSet.getString("selectedZ"))
                );
                teleportData.originalLocation = new RTPLocation(
                        RTP.serverAccessor.getRTPWorld(UUID.fromString(resultSet.getString("originalWorldId"))),
                        Integer.parseInt(resultSet.getString("originalX")),
                        Integer.parseInt(resultSet.getString("originalY")),
                        Integer.parseInt(resultSet.getString("originalZ"))
                );
                teleportData.cost = Double.parseDouble(resultSet.getString("cost"));

                RTP.getInstance().latestTeleportData.put(uuid,teleportData);
            }
        } catch (SQLException throwables) {
            throwables.printStackTrace();
        } catch (IllegalArgumentException ignored) {

        }

        try {
            connection.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public @NotNull Optional<Map<String, Object>> read(Connection connection, String tableName, Map.Entry<String, Object> lookup) {
        String sql = "PRAGMA table_info(" + tableName + ");";
        Statement statement;
        Map<String, String> column_info = new HashMap<>();

        try {
            statement = connection.createStatement();
            statement.execute(sql);
            ResultSet resultSet = statement.getResultSet();

            if(resultSet == null) return Optional.empty();

            while (resultSet.next()) {
                column_info.put(resultSet.getString("name"), resultSet.getString("type"));
            }
        } catch (SQLException e) {
            return Optional.empty();
        }

        //validate and add necessary column_info
        Map<String,Object> row = new HashMap<>();

        sql = "SELECT * FROM " + tableName + " WHERE " + lookup.getKey() + " = " + "\"" + lookup.getValue().toString() + "\"";

        try {
            statement.execute(sql);
            ResultSet resultSet = statement.getResultSet();
            for(String key : column_info.keySet()) {
                Object object = resultSet.getObject(key);
                if(object == null || object.equals("NULL")) continue;
                row.put(key,object);
            }
            return Optional.of(row);
        } catch (SQLException ignored) {

        }



        return Optional.empty();
    }

    @Override
    public void write(Connection connection, String tableName, Map<TableObj,TableObj> keyValuePairs) {
        if (keyValuePairs == null) throw new IllegalStateException();
        if (keyValuePairs.size() == 0) throw new IllegalStateException();

        //get table info for validation
        String sql = "PRAGMA table_info(" + tableName + ");";
        Statement statement;
        Map<String, String> columns = new HashMap<>();
        try {
            statement = connection.createStatement();

            statement.execute(sql);
            ResultSet resultSet;
            try {
                resultSet = statement.getResultSet();
            } catch (SQLException e) {
                StringBuilder create = new StringBuilder("CREATE TABLE IF NOT EXISTS " + tableName + " (");
                for (Map.Entry<TableObj, TableObj> entry : keyValuePairs.entrySet()) {
                    create = create.append("\"").append(entry.getKey().object.toString()).append("\" ").append("TEXT").append(", ");
                }
                create = create.replace(create.lastIndexOf(","),create.length(), "");
                create = create.append(");");
                statement.execute(create.toString());

                if(keyValuePairs.containsKey(new TableObj("UUID"))) {
                    String unique = "CREATE UNIQUE INDEX IF NOT EXISTS " + "UID" + " ON " + tableName +" (" + "UUID" + ");";
                    statement.execute(unique);
                }

                statement.execute(sql);
                resultSet = statement.getResultSet();
            }

            if(resultSet == null) {
                StringBuilder create = new StringBuilder("CREATE TABLE IF NOT EXISTS " + tableName + " (");
                for (Map.Entry<TableObj, TableObj> entry : keyValuePairs.entrySet()) {
                    create = create.append(entry.getKey().object.toString()).append(" ").append("TEXT").append(", ");
                }
                create = create.replace(create.lastIndexOf(","),create.length(), "");
                create = create.append(");");
                statement.execute(create.toString());

                if(keyValuePairs.containsKey(new TableObj("UUID"))) {
                    String unique = "CREATE UNIQUE INDEX IF NOT EXISTS " + "UID" + " ON " + tableName +" (" + "UUID" + ");";
                    statement.execute(unique);
                }

                statement.execute(sql);
                resultSet = statement.getResultSet();
            }

            Objects.requireNonNull(resultSet);
            int i = 0;
            while (resultSet.next()) {
                i++;
                columns.put(resultSet.getString("name"), resultSet.getString("type"));
            }

            if(i==0) {
                StringBuilder create = new StringBuilder("CREATE TABLE IF NOT EXISTS " + tableName + " (");
                for (Map.Entry<TableObj, TableObj> entry : keyValuePairs.entrySet()) {
                    create = create.append(entry.getKey().object.toString()).append(" ").append("TEXT").append(", ");
                }
                create = create.replace(create.lastIndexOf(","),create.length(), "");
                create = create.append(");");
                statement.execute(create.toString());

                if(keyValuePairs.containsKey(new TableObj("UUID"))) {
                    String unique = "CREATE UNIQUE INDEX IF NOT EXISTS " + "UID" + " ON " + tableName +" (" + "UUID" + ");";
                    statement.execute(unique);
                }

                statement.execute(sql);
                resultSet = statement.getResultSet();

                while (resultSet.next()) {
                    columns.put(resultSet.getString("name"), resultSet.getString("type"));
                }
            }

        } catch (SQLException e) {
            throw new IllegalStateException();
        }



        //validate and add necessary columns
        List<String> keys = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (Map.Entry<TableObj, TableObj> entry : keyValuePairs.entrySet()) {
            String key = entry.getKey().object.toString();

            keys.add(key);
            values.add("\"" + entry.getValue().object.toString() + "\"");

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

        StringBuilder builder = new StringBuilder("INSERT OR REPLACE INTO ").append(tableName).append(" (");
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            builder = builder.append("\"").append(key).append("\"");
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
            throw new IllegalStateException();
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
