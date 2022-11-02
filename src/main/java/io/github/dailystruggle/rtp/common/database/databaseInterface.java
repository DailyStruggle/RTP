package io.github.dailystruggle.rtp.common.database;

import io.github.dailystruggle.rtp.common.RTP;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Level;

public interface databaseInterface {
    String name();

    File getMainDirectory();

    default void createNewDatabase(String fileName) {
        String url = "jdbc:sqlite:C:/sqlite/" + fileName;

        try {
            Connection conn = DriverManager.getConnection(url);
            if (conn != null) {
                DatabaseMetaData meta = conn.getMetaData();
                System.out.println("The driver name is " + meta.getDriverName());
                System.out.println("A new database has been created.");
            }

        } catch (SQLException e) {
            System.out.println(e.getMessage());
        }
    }


    default void connect() {
        Connection conn = null;
        try {
            // db parameters
            String url = "jdbc:sqlite:C:/sqlite/JTP.db";
            // create a connection to the database
            conn = DriverManager.getConnection(url);

            RTP.log(Level.SEVERE, "Connection to SQLite has been established.");

        } catch (SQLException e) {
            RTP.log(Level.SEVERE, e.getMessage());
        } finally {
            try {
                if (conn != null) {
                    conn.close();
                }
            } catch (SQLException ex) {
                RTP.log(Level.SEVERE, ex.getMessage());
            }
        }
    }

    @Nullable
    Object get(String path);


}
