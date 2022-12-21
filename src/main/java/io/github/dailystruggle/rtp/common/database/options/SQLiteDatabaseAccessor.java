package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import org.jetbrains.annotations.NotNull;

import java.sql.*;
import java.util.*;

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

        Map<String,Map<String,String>> table_info = new HashMap<>();

//        while (writeQueue.size()>0) {
//            if(stop) return;
//            Map.Entry<String, Map<TableObj, TableObj>> writeRequest = writeQueue.poll();
//            if(writeRequest == null) throw new IllegalStateException("null database write request");
//
//            long localStart = System.nanoTime();
//
//            String tableName = writeRequest.getKey();
//            TableObj key = writeRequest.getValue().getKey();
//
//            Map<String,String> myTableInfo = table_info.get(tableName);
//            if(myTableInfo == null) {
//                String sql = "CREATE TABLE if not exists " + tableName + "(" + key.object.toString() + " TEXT);";
//
//                Statement statement;
//                try {
//                    statement = database.createStatement();
//                    statement.execute(sql);
//                } catch (SQLException e) {
//                    e.printStackTrace();
//                    throw new IllegalStateException();
//                }
//
//                sql = "PRAGMA table_info(" + tableName + ");";
//                myTableInfo = new HashMap<>();
//                try {
//                    statement = database.createStatement();
//                    statement.execute(sql);
//                    ResultSet resultSet = statement.getResultSet();
//                    while (resultSet.next()) {
//                        myTableInfo.put(resultSet.getString("name"), resultSet.getString("type"));
//                    }
//                } catch (SQLException e) {
//                    e.printStackTrace();
//                    throw new IllegalStateException();
//                }
//
//                table_info.put(tableName,myTableInfo);
//            }
//
//            if(!myTableInfo.containsKey(key.object.toString())) {
//
//            }
//
//
//            write(database, tableName,writeRequest.getValue().getKey(), writeRequest.getValue().getValue());
//            long localStop = System.nanoTime();
//
//            if(localStop < localStart) localStart = -(Long.MAX_VALUE - localStart);
//            long diff = localStop - localStart;
//            if(avgTimeWrite == 0) avgTimeWrite = diff;
//            else avgTimeWrite = ((avgTimeWrite*7)/8) + (diff/8);
//
//            if(localStop < start) start = -(Long.MAX_VALUE-start); //overflow correction
//            dt = localStop - start;
//            if(dt+avgTimeWrite>availableTime) break;
//        }
//
//        while (readQueue.size()>0) {
//            if(stop) return;
//            Map.Entry<String, Map.Entry<TableObj, CompletableFuture<Optional<TableObj>>>> readRequest = readQueue.poll();
//            if(readRequest == null) throw new IllegalStateException("null database read request");
//
//            long localStart = System.nanoTime();
//            readRequest.getValue().getValue().complete(
//                    read(database,readRequest.getKey(),readRequest.getValue().getKey())
//            );
//            long localStop = System.nanoTime();
//
//            if(localStop < localStart) localStart = -(Long.MAX_VALUE - localStart);
//            long diff = localStop - localStart;
//            if(avgTimeRead == 0) avgTimeRead = diff;
//            else avgTimeRead = ((avgTimeRead*7)/8) + (diff/8);
//
//            if(localStop < start) start = -(Long.MAX_VALUE-start); //overflow correction
//            dt = localStop - start;
//            if(dt+avgTimeRead>availableTime) break;
//        }

        disconnect(database);
    }

    @Override
    public @NotNull Optional<TableObj> read(Connection connection, String tableName, TableObj key) {
        return Optional.empty();
    }

    @Override
    public void write(Connection connection, String tableName, Map<TableObj,TableObj> keyValuePairs) {
//        String typeStrKey;
//        switch (key.expectedType) {
//            case INT:
//                typeStrKey = "INTEGER";
//                break;
//            case REAL:
//                typeStrKey = "REAL";
//                break;
//            case TEXT:
//                typeStrKey = "TEXT";
//                break;
//            case BLOB:
//                typeStrKey = "BLOB";
//                break;
//            default:
//                throw new IllegalStateException("Unexpected value: " + key.expectedType);
//        }
//
//        String typeStrValue;
//        switch (value.expectedType) {
//            case INT:
//                typeStrValue = "INTEGER";
//                break;
//            case REAL:
//                typeStrValue = "REAL";
//                break;
//            case TEXT:
//                typeStrValue = "TEXT";
//                break;
//            case BLOB:
//                typeStrValue = "BLOB";
//                break;
//            default:
//                throw new IllegalStateException("Unexpected value: " + value.expectedType);
//        }
//
//        String sql = "PRAGMA table_info(" + tableName + ");";
//        Statement statement;
//        Map<String,String> columns = new HashMap<>();
//        try {
//            statement = connection.createStatement();
//            statement.execute(sql);
//            ResultSet resultSet = statement.getResultSet();
//            while (resultSet.next()) {
//                columns.put(resultSet.getString("name"), resultSet.getString("type"));
//            }
//        } catch (SQLException e) {
//            e.printStackTrace();
//            throw new IllegalStateException();
//        }
//
//        sql = "INSERT INTO " + tableName + "(key,value) VALUES(?,?)";
//
//        String k = key.object.toString();
//
//        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
//            switch (key.expectedType) {
//                case INT:
//                    pstmt.setLong(1,((Number)key.object).longValue());
//                    break;
//                case REAL:
//                    pstmt.setDouble(1,((Number)key.object).doubleValue());
//                    break;
//                case TEXT:
//                    pstmt.setString(1,key.object.toString());
//                    break;
//                case BLOB: //todo: maps n shit
//                    if(key.object instanceof Map) {
//                        Map<?,?> map = (Map<?, ?>) key.object;
//                        JSONObject obj = new JSONObject(map);
//                        throw new IllegalStateException("map support not done - " + obj.toJSONString());
//                    }
//                    else if(key.object instanceof List) {
//                        throw new IllegalStateException("list support not done");
//                    }
//                    else if(key.object instanceof TeleportData) {
//                        throw new IllegalStateException("teleportdata support not done");
//                    }
//                    else if(key.object instanceof UUID) {
//                        pstmt.setString(1,key.object.toString());
//                    }
//                    else {
//                        throw new IllegalStateException("AAAAAAAA");
//                    }
//                    break;
//                default:
//                    throw new IllegalStateException("Unexpected value: " + key.expectedType);
//            }
//
//
//        } catch (SQLException throwables) {
//            throwables.printStackTrace();
//        }
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
