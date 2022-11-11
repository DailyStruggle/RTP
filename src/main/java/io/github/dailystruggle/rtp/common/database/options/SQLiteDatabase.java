package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;

import java.sql.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SQLiteDatabase extends DatabaseAccessor<Connection> {
    private final String url;

    public SQLiteDatabase(String url) {
        this.url = url;
    }


    @Override
    public String name() {
        return url;
    }

    @Override
    public Connection connect() {
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
    public Optional<TableObj> read(Connection connection, String tableName, TableObj key) {
        return Optional.empty();
    }

    @Override
    public void write(Connection connection, String tableName, TableObj key, TableObj value) {
        String typeStr;
        switch (key.expectedType) {
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
                throw new IllegalStateException("Unexpected value: " + key.expectedType);
        }

        String typeStrValue;
        switch (value.expectedType) {
            case INT:
                typeStrValue = "INTEGER";
                break;
            case REAL:
                typeStrValue = "REAL";
                break;
            case TEXT:
                typeStrValue = "TEXT";
                break;
            case BLOB:
                typeStrValue = "BLOB";
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + value.expectedType);
        }


        String sql = "CREATE TABLE IF NOT EXISTS " + tableName + " (\n"
                + "	key " + typeStr +" PRIMARY KEY,\n"
                + "	value " + typeStrValue + " NOT NULL,\n"
                + ");";

        Statement statement;
        try {
            statement = connection.createStatement();
            statement.execute(sql);
        } catch (SQLException e) {
            e.printStackTrace();
            throw new IllegalStateException();
        }

        sql = "INSERT INTO " + tableName + "(key,value) VALUES(?,?)";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            switch (key.expectedType) {
                case INT:
                    pstmt.setLong(1,((Number)key.object).longValue());
                    break;
                case REAL:
                    pstmt.setDouble(1,((Number)key.object).doubleValue());
                    break;
                case TEXT:
                    pstmt.setString(1,key.object.toString());
                    break;
                case BLOB: //todo: maps n shit
                    if(key.object instanceof Map) {
                        throw new IllegalStateException("map support not done");
                    }
                    else if(key.object instanceof List) {
                        throw new IllegalStateException("list support not done");
                    }
                    break;
                default:
                    throw new IllegalStateException("Unexpected value: " + key.expectedType);
            }
        } catch (SQLException throwables) {
            throwables.printStackTrace();
        }
    }

    @Override
    public void disconnect(Connection connection) {

    }
}
