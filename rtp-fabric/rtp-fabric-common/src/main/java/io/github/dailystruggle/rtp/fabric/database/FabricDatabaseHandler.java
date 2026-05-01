package io.github.dailystruggle.rtp.fabric.database;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.database.options.H2DatabaseAccessor;
import io.github.dailystruggle.rtp.common.database.options.MySQLDatabaseAccessor;
import io.github.dailystruggle.rtp.common.database.options.PostgreSQLDatabaseAccessor;
import io.github.dailystruggle.rtp.common.database.options.SQLiteDatabaseAccessor;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Level;

/**
 * Fabric-side database bootstrap — mirrors {@code BukkitDatabaseHandler#setupDatabase}.
 *
 * <p>Locates the per-mod config dir via {@link FabricLoader#getConfigDir()} (typically
 * {@code <server>/config/rtp/}) and delegates accessor selection to {@code rtp-core}'s
 * platform-agnostic {@code DatabaseHandler} primitives. No {@code org.bukkit.*} imports
 * (ADR-022 §4 invariant). No new {@code rtp-api} abstraction (ADR-022 §5 / Step D).
 *
 * <p>Phase 2 Step D — minimal slice: handler factory + config-dir resolution. Lifecycle
 * wiring (start at {@code SERVER_STARTED}, flush+close at {@code SERVER_STOPPING}) lands
 * in Step E with the rest of the event bridge.
 *
 * <p>REQ-RTP-S-006 — fail-loud if invoked before {@code rtp-core} is ready.
 */
public final class FabricDatabaseHandler {

    private FabricDatabaseHandler() {
    }

    /**
     * Resolve the Fabric config dir for RTP and return it, creating it if absent.
     *
     * @return {@code <fabric config dir>/rtp/}
     * @throws FileSystemException if directory creation fails
     */
    public static File resolveConfigDirectory() throws FileSystemException {
        Path configRoot = FabricLoader.getInstance().getConfigDir().resolve("rtp");
        File dir = configRoot.toFile();
        boolean mkdirs = dir.mkdirs();
        if (!mkdirs && !dir.exists()) {
            throw new FileSystemException("unable to make directories: " + dir.getAbsolutePath());
        }
        return dir;
    }

    /**
     * Initialise {@code rtp.databaseAccessor} from the configured database type.
     * Mirrors {@code BukkitDatabaseHandler#setupDatabase(RTP)} for Fabric.
     *
     * @param rtp the loaded RTP core instance
     * @throws FileSystemException if the database directory cannot be created
     * @throws IllegalStateException if {@code rtp} is null (REQ-RTP-S-006)
     */
    @SuppressWarnings("unchecked")
    public static void setupDatabase(RTP rtp) throws FileSystemException {
        if (rtp == null) {
            throw new IllegalStateException(
                    "FabricDatabaseHandler.setupDatabase invoked before rtp-core is loaded (REQ-RTP-S-006)");
        }

        File pluginDir = resolveConfigDirectory();
        File databaseDirectory = new File(pluginDir, "database");
        boolean mkdirs = databaseDirectory.mkdirs();
        if (!mkdirs && !databaseDirectory.exists()) {
            throw new FileSystemException("unable to make directories: " + databaseDirectory.getAbsolutePath());
        }

        RTP.configs.reloadConfigs();

        ConfigParser<ConfigKeys> configParser = (ConfigParser<ConfigKeys>) RTP.configs.getParser(ConfigKeys.class);
        Map<String, Object> databaseMap = configParser.getMap(ConfigKeys.database);

        String type = String.valueOf(databaseMap.getOrDefault("type", "sqlite"));
        String host = String.valueOf(databaseMap.getOrDefault("host", "127.0.0.1"));
        int port = ((Number) databaseMap.getOrDefault("port", 3306)).intValue();
        String name = String.valueOf(databaseMap.getOrDefault("name", "rtp"));
        String username = String.valueOf(databaseMap.getOrDefault("username", "root"));
        String password = String.valueOf(databaseMap.getOrDefault("password", "password"));

        File dbStateFile = new File(databaseDirectory, ".db_state");
        String previousType;
        if (dbStateFile.exists()) {
            try {
                previousType = new String(Files.readAllBytes(dbStateFile.toPath())).trim();
            } catch (Exception e) {
                previousType = type;
            }
        } else {
            File teleportDataDir = new File(databaseDirectory, "teleportData");
            String[] list = teleportDataDir.list((dir, filename) -> filename.endsWith(".yml"));
            if (teleportDataDir.exists() && teleportDataDir.isDirectory() && list != null && list.length > 0) {
                previousType = "yaml";
            } else {
                previousType = type;
            }
        }

        switch (type.toLowerCase()) {
            case "yaml":
                rtp.databaseAccessor = new YamlFileDatabase(databaseDirectory);
                break;
            case "h2":
                rtp.databaseAccessor = new H2DatabaseAccessor();
                break;
            case "mysql":
                rtp.databaseAccessor = new MySQLDatabaseAccessor(host, port, name, username, password);
                break;
            case "postgresql":
                rtp.databaseAccessor = new PostgreSQLDatabaseAccessor(host, port, name, username, password);
                break;
            case "sqlite":
            default:
                rtp.databaseAccessor = new SQLiteDatabaseAccessor(
                        "jdbc:sqlite:" + databaseDirectory.getAbsolutePath() + File.separator + "RTP.db");
                break;
        }

        RTP.handleMigration(previousType, type);
        try {
            Files.write(dbStateFile.toPath(), type.getBytes());
        } catch (Exception e) {
            RTP.log(Level.WARNING, "FabricDatabaseHandler: failed to write .db_state", e);
        }

        RTP.configs.reloadRegions();
        RTP.scheduler.runTaskLater(() -> RTP.getInstance().databaseAccessor.startup(), 1);
    }
}
