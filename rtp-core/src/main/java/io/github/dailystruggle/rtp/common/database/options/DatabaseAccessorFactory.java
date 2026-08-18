package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;

import java.io.File;
import java.util.Locale;
import java.util.logging.Level;

/**
 * Shared factory for {@link DatabaseAccessor} construction across platform handlers.
 * Falls back along requested backend -> H2 -> {@link YamlFileDatabase}.
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
            default:           return null; // yaml or unknown - no driver needed
        }
    }

    /**
     * Probe whether the given JDBC driver class is reachable.
     *
     * @param driverClass the JDBC driver class name, or {@code null}
     * @return {@code true} if available or {@code driverClass} is {@code null}
     */
    public static boolean isDriverAvailable(String driverClass) {
        if (driverClass == null) return true;
        try {
            Class.forName(driverClass, false, DatabaseAccessorFactory.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Throwable t) {
            // Linkage / init error in the driver - treat as unavailable.
            return false;
        }
    }

    /**
     * Builds database accessor for requested {@code type} with fallback to H2/YAML.
     *
     * @param type backend type
     * @param databaseDirectory directory for local databases
     * @param host host; port port; name database name; username user; password password
     * @return accessor and effective backend type
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

        // Final rung - flat file. YamlFileDatabase only depends on the JVM + RTP itself.
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
        // Reflective instantiation for JDBC-backed accessors so this method's bytecode
        // carries no direct symbolic references to H2/SQLite/MySQL/PostgreSQL accessor
        // classes. The lite assembly (ADR-024) excludes those accessor classes from the
        // jar entirely; if `tryCreate` referenced them by name, the JVM's eager
        // verification/resolution at frame-link time would raise NoClassDefFoundError
        // up to the caller - bypassing this method's try/catch and breaking the
        // requested -> H2 -> yaml fallback chain. YamlFileDatabase stays direct because
        // lite always ships it.
        try {
            if ("yaml".equals(type)) {
                return new YamlFileDatabase(databaseDirectory);
            }
            ClassLoader cl = DatabaseAccessorFactory.class.getClassLoader();
            switch (type) {
                case "h2":
                    return (DatabaseAccessor<?>) Class.forName(
                            "io.github.dailystruggle.rtp.common.database.options.H2DatabaseAccessor",
                            true, cl).getDeclaredConstructor().newInstance();
                case "mysql":
                    return (DatabaseAccessor<?>) Class.forName(
                            "io.github.dailystruggle.rtp.common.database.options.MySQLDatabaseAccessor",
                            true, cl).getDeclaredConstructor(String.class, int.class, String.class, String.class, String.class)
                            .newInstance(host, port, name, username, password);
                case "postgresql":
                    return (DatabaseAccessor<?>) Class.forName(
                            "io.github.dailystruggle.rtp.common.database.options.PostgreSQLDatabaseAccessor",
                            true, cl).getDeclaredConstructor(String.class, int.class, String.class, String.class, String.class)
                            .newInstance(host, port, name, username, password);
                case "sqlite":
                case "":
                default:
                    return (DatabaseAccessor<?>) Class.forName(
                            "io.github.dailystruggle.rtp.common.database.options.SQLiteDatabaseAccessor",
                            true, cl).getDeclaredConstructor(String.class)
                            .newInstance("jdbc:sqlite:" + databaseDirectory.getAbsolutePath() + File.separator + "RTP.db");
            }
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // Accessor class excluded from this assembly (lite) - silent fall through.
            return null;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            RTP.log(Level.WARNING,
                    "DatabaseAccessorFactory: failed to construct '" + type + "' accessor: " + cause.getMessage(), cause);
            return null;
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "DatabaseAccessorFactory: failed to construct '" + type + "' accessor: " + t.getMessage(), t);
            return null;
        }
    }

    /** Result of {@link #create}: the accessor plus the effective type after any fallback. */
    public static final class Result {
        /** The constructed database accessor. */
        public final DatabaseAccessor<?> accessor;
        /** The effective backend type string after any fallback (e.g. {@code "h2"}, {@code "yaml"}). */
        public final String effectiveType;

        Result(DatabaseAccessor<?> accessor, String effectiveType) {
            this.accessor = accessor;
            this.effectiveType = effectiveType;
        }
    }
}
