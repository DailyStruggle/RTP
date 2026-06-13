package io.github.dailystruggle.rtp.fabric.database;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.database.options.DatabaseAccessorFactory;
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
 * (ADR-022 §4 invariant). No new {@code rtp-api} abstraction (ADR-022 §5).
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

        // Mirror BukkitDatabaseHandler defaults so the requested → H2 → flat-file fallback
        // chain in DatabaseAccessorFactory is preserved verbatim across platforms.
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

        // Fabric-only first-setup nudge: on a truly fresh install (no .db_state, no
        // existing teleportData/*.yml) coerce the requested type to "yaml". The shipped
        // config.yml ships `type: "sqlite"` for parity with Bukkit-family servers, which
        // bundle the sqlite-jdbc driver as a server runtime; Fabric does not, so SQLite
        // would fall through DatabaseAccessorFactory's requested→H2→flat-file chain and
        // emit a "No suitable driver found" warning before settling on YAML. Coercing the
        // value here keeps the fallback chain intact (admins who explicitly set h2/mysql/
        // postgres/sqlite in config.yml after first run still get exactly what they asked
        // for, including the chain) while sparing fresh installs from the warning. Issue:
        // "we still bother the user with warnings. The config value given should be yaml,
        // we shouldn't remove the chaining."
        if (freshInstall && "sqlite".equalsIgnoreCase(type)) {
            type = "yaml";
            previousType = "yaml";
            // Persist the coerced value back to config.yml so subsequent starts read
            // "yaml" directly (no repeated coercion, no surprise if the admin later
            // inspects config.yml). Issue: "write yaml back to config as well".
            try {
                databaseMap.put("type", "yaml");
                configParser.set(ConfigKeys.database, databaseMap);
                configParser.save();
            } catch (Exception e) {
                RTP.log(Level.WARNING,
                        "FabricDatabaseHandler: failed to persist database.type=yaml to config.yml", e);
            }
        }

        // Delegate to the shared factory: requested → H2 → flat-file fallback chain so a
        // missing JDBC driver (we cannot shade every backend) does not crash startup.
        DatabaseAccessorFactory.Result dbResult = DatabaseAccessorFactory.create(
                type, databaseDirectory, host, port, name, username, password);
        rtp.databaseAccessor = dbResult.accessor;
        String effectiveType = dbResult.effectiveType;

        RTP.handleMigration(previousType, effectiveType);
        try {
            Files.write(dbStateFile.toPath(), effectiveType.getBytes());
        } catch (Exception e) {
            RTP.log(Level.WARNING, "FabricDatabaseHandler: failed to write .db_state", e);
        }

        RTP.configs.reloadRegions();
        RTP.scheduler.runTaskLater(() -> RTP.getInstance().databaseAccessor.startup(), 1);
        // ADR-060: start the emergency-platform restore reaper once the DB is up so any
        // persisted restore jobs resume across the restart.
        RTP.scheduler.runTaskLater(
                io.github.dailystruggle.rtp.common.platform.PlatformRestoreManager::startGlobal, 2);
    }
}
