package io.github.dailystruggle.rtp.bukkit.database;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.database.options.*;

import java.io.File;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.util.Map;

public class BukkitDatabaseHandler {
    public static void setupDatabase(RTP rtp) throws FileSystemException {
        File databaseDirectory = RTP.configs.pluginDirectory;
        databaseDirectory = new File(databaseDirectory.getAbsolutePath() + File.separator + "database");
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

        // Resolve via the shared factory so an unavailable JDBC driver (we cannot shade
        // every backend) falls back through H2 → flat-file instead of crashing startup.
        DatabaseAccessorFactory.Result dbResult = DatabaseAccessorFactory.create(
                type, databaseDirectory, host, port, name, username, password);
        rtp.databaseAccessor = dbResult.accessor;
        String effectiveType = dbResult.effectiveType;

        RTP.handleMigration(previousType, effectiveType);
        try {
            Files.write(dbStateFile.toPath(), effectiveType.getBytes());
        } catch (Exception e) {
            e.printStackTrace();
        }

        RTP.configs.reloadRegions();
        RTP.scheduler.runTaskLater(() -> RTP.getInstance().databaseAccessor.startup(), 1);
        // ADR-060: start the emergency-platform restore reaper once the DB is up so any
        // persisted restore jobs resume across the restart.
        RTP.scheduler.runTaskLater(
                io.github.dailystruggle.rtp.common.platform.PlatformRestoreManager::startGlobal, 2);
    }
}
