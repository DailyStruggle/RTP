package io.github.dailystruggle.rtp.neoforge.database;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.database.options.DatabaseAccessorFactory;

import java.io.File;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.util.Map;
import java.util.logging.Level;

/**
 * NeoForge-side database bootstrap — mirrors {@code BukkitDatabaseHandler#setupDatabase}
 * and {@code FabricDatabaseHandler#setupDatabase}.
 *
 * <p>The per-mod config dir is resolved through
 * {@code RTP.serverAccessor.getPluginDirectory()} (which on NeoForge resolves
 * {@code FMLPaths.CONFIGDIR/rtp}); accessor selection is delegated to
 * {@code rtp-core}'s platform-agnostic {@code DatabaseAccessorFactory}. No
 * {@code org.bukkit.*} imports.</p>
 *
 * <p>REQ-RTP-S-006 — fail-loud if invoked before {@code rtp-core} is ready.</p>
 */
public final class NeoForgeDatabaseHandler {

    private NeoForgeDatabaseHandler() {
    }

    /**
     * Initialise {@code rtp.databaseAccessor} from the configured database type.
     * Mirrors {@code FabricDatabaseHandler#setupDatabase(RTP)} for NeoForge.
     *
     * @param rtp the loaded RTP core instance
     * @throws FileSystemException if the database directory cannot be created
     * @throws IllegalStateException if {@code rtp} is null (REQ-RTP-S-006)
     */
    @SuppressWarnings("unchecked")
    public static void setupDatabase(RTP rtp) throws FileSystemException {
        if (rtp == null) {
            throw new IllegalStateException(
                    "NeoForgeDatabaseHandler.setupDatabase invoked before rtp-core is loaded (REQ-RTP-S-006)");
        }

        File pluginDir = RTP.serverAccessor.getPluginDirectory();
        File databaseDirectory = new File(pluginDir, "database");
        boolean mkdirs = databaseDirectory.mkdirs();
        if (!mkdirs && !databaseDirectory.exists()) {
            throw new FileSystemException("unable to make directories: " + databaseDirectory.getAbsolutePath());
        }

        RTP.configs.reloadConfigs();

        ConfigParser<ConfigKeys> configParser = (ConfigParser<ConfigKeys>) RTP.configs.getParser(ConfigKeys.class);
        Map<String, Object> databaseMap = configParser.getMap(ConfigKeys.database);

        // Mirror BukkitDatabaseHandler defaults so the requested -> H2 -> flat-file
        // fallback chain in DatabaseAccessorFactory is preserved verbatim across platforms.
        String type = String.valueOf(databaseMap.getOrDefault("type", "sqlite"));
        String host = String.valueOf(databaseMap.getOrDefault("host", "127.0.0.1"));
        int port = ((Number) databaseMap.getOrDefault("port", 3306)).intValue();
        String name = String.valueOf(databaseMap.getOrDefault("name", "rtp"));
        String username = String.valueOf(databaseMap.getOrDefault("username", "root"));
        String password = String.valueOf(databaseMap.getOrDefault("password", "password"));

        File dbStateFile = new File(databaseDirectory, ".db_state");
        String previousType;
        boolean freshInstall = false;
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
                freshInstall = true;
            }
        }

        // NeoForge-only first-setup nudge (mirrors Fabric): on a truly fresh install
        // coerce the requested "sqlite" type to "yaml". The shipped config.yml ships
        // type: "sqlite" for parity with Bukkit-family servers, which bundle the
        // sqlite-jdbc driver; NeoForge does not, so SQLite would fall through the
        // requested -> H2 -> flat-file chain and emit a "No suitable driver found"
        // warning before settling on YAML. Coercing here keeps the fallback chain
        // intact (admins who explicitly set h2/mysql/postgres/sqlite after first run
        // still get exactly what they asked for) while sparing fresh installs the warning.
        if (freshInstall && "sqlite".equalsIgnoreCase(type)) {
            type = "yaml";
            previousType = "yaml";
            try {
                databaseMap.put("type", "yaml");
                configParser.set(ConfigKeys.database, databaseMap);
                configParser.save();
            } catch (Exception e) {
                RTP.log(Level.WARNING,
                        "NeoForgeDatabaseHandler: failed to persist database.type=yaml to config.yml", e);
            }
        }

        // Delegate to the shared factory: requested -> H2 -> flat-file fallback chain so a
        // missing JDBC driver (we cannot shade every backend) does not crash startup.
        DatabaseAccessorFactory.Result dbResult = DatabaseAccessorFactory.create(
                type, databaseDirectory, host, port, name, username, password);
        rtp.databaseAccessor = dbResult.accessor;
        String effectiveType = dbResult.effectiveType;

        RTP.handleMigration(previousType, effectiveType);
        try {
            Files.write(dbStateFile.toPath(), effectiveType.getBytes());
        } catch (Exception e) {
            RTP.log(Level.WARNING, "NeoForgeDatabaseHandler: failed to write .db_state", e);
        }

        RTP.configs.reloadRegions();
        RTP.scheduler.runTaskLater(() -> RTP.getInstance().databaseAccessor.startup(), 1);
        // ADR-060: start the emergency-platform restore reaper once the DB is up so any
        // persisted restore jobs resume across the restart.
        RTP.scheduler.runTaskLater(
                io.github.dailystruggle.rtp.common.platform.PlatformRestoreManager::startGlobal, 2);
    }
}
