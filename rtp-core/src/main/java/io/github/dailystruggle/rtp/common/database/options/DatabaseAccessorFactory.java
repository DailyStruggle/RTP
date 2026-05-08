package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;

import java.io.File;
import java.util.Locale;
import java.util.logging.Level;

/**
 * Shared factory for {@link DatabaseAccessor} construction across platform handlers
 * (Bukkit, Fabric, etc.).
 *
 * <p>Because RTP cannot shade in every JDBC driver (size budget, license, native
 * binaries — see {@code ADR-024-rtp-lite-assembly-variant.md}), the factory probes
 * driver availability and instantiation success at the call site and falls back along
 * a fixed chain:
 *
 * <ol>
 *     <li>Requested backend (whatever the user configured).</li>
 *     <li>H2 — bundled into the standard RTP jar; embedded, no native deps.</li>
 *     <li>{@link YamlFileDatabase} — flat-file, no JDBC driver required at all.</li>
 * </ol>
 *
 * <p>Each rung is attempted only if the previous one is unreachable (driver class
 * absent or constructor throws). A loud {@link Level#WARNING} is logged on every
 * fallback so server admins can correct the config or drop the missing driver jar
 * onto the classpath.
 */
public final class DatabaseAccessorFactory {

    private DatabaseAccessorFactory() {
    }

    /** Map a backend type string to its expected JDBC driver class. */
    private static String driverClassFor(String type) {
        switch (type.toLowerCase(Locale.ROOT)) {
            case "h2":         return "org.h2.Driver";
            case "sqlite":     return "org.sqlite.JDBC";
            case "mysql":      return "com.mysql.cj.jdbc.Driver";
            case "postgresql": return "org.postgresql.Driver";
            default:           return null; // yaml or unknown — no driver needed
        }
    }

    /**
     * Probe whether the given JDBC driver class is reachable from the current
     * classloader. Returns {@code true} for {@code null} (no driver required, e.g. yaml).
     */
    public static boolean isDriverAvailable(String driverClass) {
        if (driverClass == null) return true;
        try {
            Class.forName(driverClass, false, DatabaseAccessorFactory.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Throwable t) {
            // Linkage / init error in the driver — treat as unavailable.
            return false;
        }
    }

    /**
     * Build the accessor for the requested {@code type}. If the driver is missing or
     * construction throws, falls back to H2; if H2 is also unavailable, falls back to
     * {@link YamlFileDatabase}. Never returns {@code null}.
     *
     * @param type              configured backend type ({@code yaml}, {@code h2}, {@code sqlite},
     *                          {@code mysql}, {@code postgresql}, or anything else → defaults to sqlite).
     * @param databaseDirectory directory used by yaml + sqlite + h2 backends.
     * @param host              JDBC host (mysql / postgresql).
     * @param port              JDBC port (mysql / postgresql).
     * @param name              database name (mysql / postgresql).
     * @param username          credentials (mysql / postgresql).
     * @param password          credentials (mysql / postgresql).
     * @return an accessor and the effective type string actually used (after any fallback).
     */
    public static Result create(String type,
                                File databaseDirectory,
                                String host,
                                int port,
                                String name,
                                String username,
                                String password) {
        String requested = type == null ? "" : type.toLowerCase(Locale.ROOT);

        DatabaseAccessor<?> accessor = tryCreate(requested, databaseDirectory, host, port, name, username, password);
        if (accessor != null) {
            // Normalise "" / unknown names to "sqlite" since tryCreate's default branch
            // builds a SQLiteDatabaseAccessor for those.
            String effective = requested;
            if (effective.isEmpty()
                    || !("yaml".equals(effective)
                            || "h2".equals(effective)
                            || "mysql".equals(effective)
                            || "postgresql".equals(effective)
                            || "sqlite".equals(effective))) {
                effective = "sqlite";
            }
            return new Result(accessor, effective);
        }

        // Fall back to H2 unless H2 was already what we tried.
        if (!"h2".equals(requested) && !"yaml".equals(requested)) {
            RTP.log(Level.WARNING,
                    "DatabaseAccessorFactory: backend '" + requested + "' is unavailable "
                            + "(JDBC driver missing or construction failed). Falling back to H2. "
                            + "Drop the appropriate driver jar onto the server classpath, or set "
                            + "database.type to 'h2' or 'yaml' to silence this warning.");
            DatabaseAccessor<?> h2 = tryCreate("h2", databaseDirectory, host, port, name, username, password);
            if (h2 != null) {
                return new Result(h2, "h2");
            }
            RTP.log(Level.WARNING,
                    "DatabaseAccessorFactory: H2 fallback unavailable as well. "
                            + "Falling back to flat-file (yaml) storage.");
        } else if ("h2".equals(requested)) {
            RTP.log(Level.WARNING,
                    "DatabaseAccessorFactory: H2 backend unavailable "
                            + "(driver missing or construction failed). Falling back to flat-file (yaml) storage.");
        }

        // Final rung — flat file. YamlFileDatabase only depends on the JVM + RTP itself.
        return new Result(new YamlFileDatabase(databaseDirectory), "yaml");
    }

    /**
     * Attempt to instantiate the accessor for {@code type}. Returns {@code null} if the
     * driver is unavailable or the constructor throws.
     */
    private static DatabaseAccessor<?> tryCreate(String type,
                                                 File databaseDirectory,
                                                 String host,
                                                 int port,
                                                 String name,
                                                 String username,
                                                 String password) {
        String driver = driverClassFor(type);
        if (!isDriverAvailable(driver)) {
            return null;
        }
        try {
            switch (type) {
                case "yaml":
                    return new YamlFileDatabase(databaseDirectory);
                case "h2":
                    return new H2DatabaseAccessor();
                case "mysql":
                    return new MySQLDatabaseAccessor(host, port, name, username, password);
                case "postgresql":
                    return new PostgreSQLDatabaseAccessor(host, port, name, username, password);
                case "sqlite":
                case "":
                default:
                    return new SQLiteDatabaseAccessor(
                            "jdbc:sqlite:" + databaseDirectory.getAbsolutePath() + File.separator + "RTP.db");
            }
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "DatabaseAccessorFactory: failed to construct '" + type + "' accessor: " + t.getMessage(), t);
            return null;
        }
    }

    /** Result of {@link #create}: the accessor plus the effective type after any fallback. */
    public static final class Result {
        public final DatabaseAccessor<?> accessor;
        public final String effectiveType;

        Result(DatabaseAccessor<?> accessor, String effectiveType) {
            this.accessor = accessor;
            this.effectiveType = effectiveType;
        }
    }
}
