package io.github.dailystruggle.rtp.common.importer;

import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Foreign configuration importer for JustRTP (ADR-066).
 * Reads plugins/justRTP/config.yml (and cache.yml if present).
 * Maps per-world radii, shapes (ROUND -> CIRCLE, SQUARE -> SQUARE), and cooldowns into LeafRTP Region and World configs.
 */
public class JustRtpConfigImporter implements ForeignConfigImporter {

    @Override
    public String sourceName() {
        return "justrtp";
    }

    @Override
    public boolean canImport(Path sourcePluginDir) {
        if (sourcePluginDir == null || !Files.isDirectory(sourcePluginDir)) {
            return false;
        }
        return Files.exists(sourcePluginDir.resolve("config.yml"));
    }

    @Override
    public ImportResult importConfiguration(Path sourcePluginDir, Path destinationDir, boolean overwrite) {
        if (sourcePluginDir == null || !Files.isDirectory(sourcePluginDir)) {
            return ImportResult.failure(sourceName(),
                    Collections.singletonList("Source directory does not exist or is not a directory: " + sourcePluginDir),
                    Collections.emptyList());
        }

        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> mapped = new ArrayList<>();
        List<Path> written = new ArrayList<>();

        Path configYmlPath = sourcePluginDir.resolve("config.yml");
        Path cacheYmlPath = sourcePluginDir.resolve("cache.yml");

        RtpYamlConfig configCfg = loadYamlSafe(configYmlPath, warnings);
        RtpYamlConfig cacheCfg = loadYamlSafe(cacheYmlPath, warnings);

        if (configCfg == null) {
            return ImportResult.failure(sourceName(),
                    Collections.singletonList("No valid config.yml found in " + sourcePluginDir),
                    warnings);
        }

        // Global defaults from config.yml
        int globalMinRadius = 64;
        int globalMaxRadius = 2048;
        int globalCenterX = 0;
        int globalCenterZ = 0;
        String globalShape = "CIRCLE";
        long globalCooldown = 0L;
        double globalCost = 0.0;
        int globalCacheCap = 50;

        // Try extracting global shape
        if (configCfg.contains("shape")) {
            globalShape = mapShape(configCfg.getString("shape"));
        } else if (configCfg.contains("landing_shape")) {
            globalShape = mapShape(configCfg.getString("landing_shape"));
        }

        // Try extracting global radius / min-radius
        if (configCfg.contains("radius.max")) {
            globalMaxRadius = configCfg.getInt("radius.max", globalMaxRadius);
        } else if (configCfg.contains("radius")) {
            Object r = configCfg.get("radius");
            if (r instanceof Number) {
                globalMaxRadius = ((Number) r).intValue();
            } else if (r instanceof String) {
                try {
                    globalMaxRadius = Integer.parseInt((String) r);
                } catch (NumberFormatException ignored) {}
            }
        } else if (configCfg.contains("max_radius")) {
            globalMaxRadius = configCfg.getInt("max_radius", globalMaxRadius);
        } else if (configCfg.contains("max-distance")) {
            globalMaxRadius = configCfg.getInt("max-distance", globalMaxRadius);
        }

        if (configCfg.contains("radius.min")) {
            globalMinRadius = configCfg.getInt("radius.min", globalMinRadius);
        } else if (configCfg.contains("min_radius")) {
            globalMinRadius = configCfg.getInt("min_radius", globalMinRadius);
        } else if (configCfg.contains("min-radius")) {
            globalMinRadius = configCfg.getInt("min-radius", globalMinRadius);
        } else if (configCfg.contains("min-distance")) {
            globalMinRadius = configCfg.getInt("min-distance", globalMinRadius);
        }

        // Center
        if (configCfg.contains("center.x")) {
            globalCenterX = configCfg.getInt("center.x", globalCenterX);
        } else if (configCfg.contains("center-x")) {
            globalCenterX = configCfg.getInt("center-x", globalCenterX);
        }
        if (configCfg.contains("center.z")) {
            globalCenterZ = configCfg.getInt("center.z", globalCenterZ);
        } else if (configCfg.contains("center-z")) {
            globalCenterZ = configCfg.getInt("center-z", globalCenterZ);
        }

        // Cooldown
        if (configCfg.contains("settings.cooldown")) {
            globalCooldown = configCfg.getLong("settings.cooldown", globalCooldown);
        } else if (configCfg.contains("cooldown")) {
            globalCooldown = configCfg.getLong("cooldown", globalCooldown);
        } else if (configCfg.contains("cooldown_seconds")) {
            globalCooldown = configCfg.getLong("cooldown_seconds", globalCooldown);
        } else if (configCfg.contains("cooldown-seconds")) {
            globalCooldown = configCfg.getLong("cooldown-seconds", globalCooldown);
        }

        // Cost
        if (configCfg.contains("economy.cost")) {
            globalCost = configCfg.getDouble("economy.cost", globalCost);
        } else if (configCfg.contains("cost")) {
            globalCost = configCfg.getDouble("cost", globalCost);
        }

        // Cache size from cache.yml or config.yml location_cache.cache_size
        if (cacheCfg != null) {
            if (cacheCfg.contains("size")) {
                globalCacheCap = cacheCfg.getInt("size", globalCacheCap);
            } else if (cacheCfg.contains("cache_size")) {
                globalCacheCap = cacheCfg.getInt("cache_size", globalCacheCap);
            } else if (cacheCfg.contains("max_locations")) {
                globalCacheCap = cacheCfg.getInt("max_locations", globalCacheCap);
            }
        } else if (configCfg.contains("location_cache.cache_size")) {
            globalCacheCap = configCfg.getInt("location_cache.cache_size", globalCacheCap);
        }

        // Look for per-world settings in config.yml
        // Keys might be "worlds", "custom_worlds", "world_settings"
        Map<String, WorldConfigEntry> worldEntries = new LinkedHashMap<>();

        Object worldsObj = configCfg.get("worlds");
        if (worldsObj == null) worldsObj = configCfg.get("custom_worlds");
        if (worldsObj == null) worldsObj = configCfg.get("world_settings");

        if (worldsObj instanceof RtpYamlSection sec) {
            for (String worldKey : sec.getKeys(false)) {
                worldEntries.put(worldKey, parseWorldEntry(sec, worldKey, globalShape, globalMinRadius, globalMaxRadius,
                        globalCenterX, globalCenterZ, globalCooldown, globalCost, globalCacheCap));
            }
        } else if (worldsObj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String worldKey = String.valueOf(entry.getKey());
                if (entry.getValue() instanceof Map<?, ?> wMap) {
                    worldEntries.put(worldKey, parseWorldEntryMap(wMap, worldKey, globalShape, globalMinRadius, globalMaxRadius,
                            globalCenterX, globalCenterZ, globalCooldown, globalCost, globalCacheCap));
                }
            }
        }

        // If no per-world entries found, check if a single "world" string or default world exists,
        // or check rtp_gui.worlds or location_cache.worlds
        if (worldEntries.isEmpty()) {
            Object guiWorldsObj = configCfg.get("rtp_gui.worlds");
            if (guiWorldsObj instanceof RtpYamlSection guiSec) {
                for (String key : guiSec.getKeys(false)) {
                    String wName = guiSec.getString(key + ".world_name", key);
                    if (wName != null && !wName.trim().isEmpty()) {
                        worldEntries.put(wName, new WorldConfigEntry(
                                wName, globalShape, globalMinRadius, globalMaxRadius,
                                globalCenterX, globalCenterZ, globalCooldown, globalCost, globalCacheCap));
                    }
                }
            } else {
                Object cacheWorldsObj = configCfg.get("location_cache.worlds");
                if (cacheWorldsObj instanceof RtpYamlSection cSec) {
                    for (String key : cSec.getKeys(false)) {
                        worldEntries.put(key, new WorldConfigEntry(
                                key, globalShape, globalMinRadius, globalMaxRadius,
                                globalCenterX, globalCenterZ, globalCooldown, globalCost, globalCacheCap));
                    }
                }
            }
        }

        if (worldEntries.isEmpty()) {
            String defWorld = configCfg.getString("default_world", configCfg.getString("world", "world"));
            worldEntries.put(defWorld, new WorldConfigEntry(
                    defWorld, globalShape, globalMinRadius, globalMaxRadius,
                    globalCenterX, globalCenterZ, globalCooldown, globalCost, globalCacheCap));
        }

        // Prepare destination directories
        Path regionsDir = destinationDir.resolve("regions");
        Path worldsDir = destinationDir.resolve("worlds");
        try {
            Files.createDirectories(regionsDir);
            Files.createDirectories(worldsDir);
        } catch (IOException e) {
            return ImportResult.failure(sourceName(),
                    Collections.singletonList("Failed to create destination directories: " + e.getMessage()),
                    warnings);
        }

        long maxCooldown = globalCooldown;

        // Generate Region and World configs for each world entry
        for (WorldConfigEntry entry : worldEntries.values()) {
            String worldName = entry.worldName;
            String regionName = worldName + "_region";
            Path regionFile = regionsDir.resolve(regionName + ".yml");
            Path worldFile = worldsDir.resolve(worldName + ".yml");

            if (!overwrite) {
                if (Files.exists(regionFile)) {
                    errors.add("Region file already exists and overwrite is disabled: " + regionFile);
                    continue;
                }
                if (Files.exists(worldFile)) {
                    errors.add("World file already exists and overwrite is disabled: " + worldFile);
                    continue;
                }
            }

            if (entry.cooldown > maxCooldown) {
                maxCooldown = entry.cooldown;
            }

            // Generate Region YAML
            RtpYamlConfig regionYaml = new RtpYamlConfig();
            regionYaml.set("displayName", "&b" + regionName);
            regionYaml.set("world", worldName);
            regionYaml.set("worldBorderOverride", false);

            RtpYamlSection shapeSec = regionYaml.createSection("shape");
            shapeSec.set("name", entry.shape);
            shapeSec.set("mode", "ACCUMULATE");
            shapeSec.set("radius", entry.maxRadius);
            shapeSec.set("centerRadius", entry.minRadius);
            shapeSec.set("centerX", entry.centerX);
            shapeSec.set("centerZ", entry.centerZ);
            shapeSec.set("weight", 1.0);
            shapeSec.set("uniquePlacements", false);
            shapeSec.set("expand", false);

            RtpYamlSection vertSec = regionYaml.createSection("vert");
            vertSec.set("name", "JUMP");
            vertSec.set("minY", 32);
            vertSec.set("maxY", 255);
            vertSec.set("step", 16);
            vertSec.set("requireSkyLight", false);

            regionYaml.set("requirePermission", false);
            regionYaml.set("override", "default");
            regionYaml.set("cacheCap", entry.cacheCap);
            regionYaml.set("backlogCacheCap", 1000);
            regionYaml.set("activeChunkCap", 10);
            regionYaml.set("price", entry.cost);
            regionYaml.set("spatialResolution", 3);
            regionYaml.set("version", "1.0");

            try {
                regionYaml.save(regionFile.toFile());
                written.add(regionFile);
                mapped.add("Region: " + regionName + " (world=" + worldName + ", shape=" + entry.shape
                        + ", radius=" + entry.maxRadius + ", minRadius=" + entry.minRadius + ")");
            } catch (IOException e) {
                errors.add("Failed to write region file " + regionFile + ": " + e.getMessage());
            }

            // Generate World YAML
            RtpYamlConfig worldYaml = new RtpYamlConfig();
            worldYaml.set("region", regionName);
            worldYaml.set("requirePermission", false);
            worldYaml.set("override", "[0]");
            worldYaml.set("version", "1.0");

            try {
                worldYaml.save(worldFile.toFile());
                written.add(worldFile);
                mapped.add("World: " + worldName + " -> " + regionName);
            } catch (IOException e) {
                errors.add("Failed to write world file " + worldFile + ": " + e.getMessage());
            }
        }

        // Check custom_locations.yml if present
        Path customLocationsPath = sourcePluginDir.resolve("custom_locations.yml");
        if (Files.exists(customLocationsPath)) {
            RtpYamlConfig locCfg = loadYamlSafe(customLocationsPath, warnings);
            if (locCfg != null) {
                Object locsObj = locCfg.get("locations");
                if (locsObj instanceof RtpYamlSection locsSec) {
                    for (String locKey : locsSec.getKeys(false)) {
                        String locPrefix = locKey + ".";
                        String targetWorld = locsSec.getString(locPrefix + "world", "world");
                        int locCenterX = locsSec.getInt(locPrefix + "center-x", 0);
                        int locCenterZ = locsSec.getInt(locPrefix + "center-z", 0);
                        int locMinRadius = locsSec.getInt(locPrefix + "min-radius", 0);
                        int locMaxRadius = locsSec.getInt(locPrefix + "max-radius", 2500);
                        String dispName = locsSec.getString(locPrefix + "display-name", locKey);

                        String locRegionName = "justrtp_loc_" + locKey.toLowerCase(Locale.ROOT);
                        Path locRegionFile = regionsDir.resolve(locRegionName + ".yml");

                        if (!overwrite && Files.exists(locRegionFile)) {
                            errors.add("Region file already exists and overwrite is disabled: " + locRegionFile);
                            continue;
                        }

                        RtpYamlConfig locRegionYaml = new RtpYamlConfig();
                        locRegionYaml.set("displayName", "&6" + dispName);
                        locRegionYaml.set("world", targetWorld);
                        locRegionYaml.set("worldBorderOverride", false);

                        RtpYamlSection shapeSec = locRegionYaml.createSection("shape");
                        shapeSec.set("name", "CIRCLE");
                        shapeSec.set("mode", "ACCUMULATE");
                        shapeSec.set("radius", locMaxRadius);
                        shapeSec.set("centerRadius", locMinRadius);
                        shapeSec.set("centerX", locCenterX);
                        shapeSec.set("centerZ", locCenterZ);
                        shapeSec.set("weight", 1.0);
                        shapeSec.set("uniquePlacements", false);
                        shapeSec.set("expand", false);

                        RtpYamlSection vertSec = locRegionYaml.createSection("vert");
                        vertSec.set("name", "JUMP");
                        vertSec.set("minY", 32);
                        vertSec.set("maxY", 255);
                        vertSec.set("step", 16);
                        vertSec.set("requireSkyLight", false);

                        locRegionYaml.set("requirePermission", false);
                        locRegionYaml.set("override", "default");
                        locRegionYaml.set("cacheCap", globalCacheCap);
                        locRegionYaml.set("backlogCacheCap", 1000);
                        locRegionYaml.set("activeChunkCap", 10);
                        locRegionYaml.set("price", globalCost);
                        locRegionYaml.set("spatialResolution", 3);
                        locRegionYaml.set("version", "1.0");

                        try {
                            locRegionYaml.save(locRegionFile.toFile());
                            written.add(locRegionFile);
                            mapped.add("Custom Location: " + locKey + " -> " + locRegionName
                                    + " (world=" + targetWorld + ", center=[" + locCenterX + "," + locCenterZ + "], radius=" + locMaxRadius + ")");
                        } catch (IOException e) {
                            errors.add("Failed to write custom location region file " + locRegionFile + ": " + e.getMessage());
                        }
                    }
                }
            }
        }

        // Update cooldown in destination config.yml if available
        if (maxCooldown > 0) {
            Path destConfigPath = destinationDir.resolve("config.yml");
            try {
                RtpYamlConfig destConfig;
                if (Files.exists(destConfigPath)) {
                    destConfig = RtpYamlConfig.load(destConfigPath.toFile());
                } else {
                    destConfig = new RtpYamlConfig();
                    destConfig.set("version", "1.0");
                }
                destConfig.set("teleportCooldown", maxCooldown);
                destConfig.save(destConfigPath.toFile());
                written.add(destConfigPath);
                mapped.add("Cooldown: " + maxCooldown + "s mapped to config.yml#teleportCooldown");
            } catch (IOException e) {
                warnings.add("Failed to update destination config.yml with cooldown: " + e.getMessage());
            }
        }

        // 1.2 SQL Database Setup Mirroring (ADR-066)
        mirrorDatabaseConfig(configCfg, destinationDir, overwrite, mapped, written, warnings);

        // 1.3 Competitor Effects Mirroring (ADR-066)
        mirrorEffectsConfig(configCfg, destinationDir, overwrite, mapped, written, warnings);

        // 1.4 JustRTP rtp_zones.yml Importer Seam (ADR-066)
        mirrorZonesConfig(sourcePluginDir, destinationDir, overwrite, mapped, written, warnings, errors);

        boolean success = errors.isEmpty() && !written.isEmpty();
        return new ImportResult(success, sourceName(), written, warnings, errors, mapped);
    }

    private void mirrorDatabaseConfig(RtpYamlConfig sourceConfig, Path destinationDir, boolean overwrite,
                                      List<String> mapped, List<Path> written, List<String> warnings) {
        RtpYamlSection dbSec = getSectionCaseInsensitive(sourceConfig, "database");
        if (dbSec == null) return;

        String rawType = getStringCaseInsensitive(dbSec, "sqlite", "type", "Type", "database");
        String type = rawType.toLowerCase(Locale.ROOT);
        if (type.contains("mysql")) type = "mysql";
        else if (type.contains("postgre")) type = "postgresql";
        else type = "sqlite";

        String host = getStringCaseInsensitive(dbSec, "127.0.0.1", "host", "Host", "server", "ip");
        int port = getIntCaseInsensitive(dbSec, 3306, "port", "Port");
        String dbName = getStringCaseInsensitive(dbSec, "rtp", "name", "Name", "database", "Database", "db");
        String user = getStringCaseInsensitive(dbSec, "root", "user", "User", "username", "Username");
        String password = getStringCaseInsensitive(dbSec, "password", "password", "Password", "pass");
        boolean useSSL = getBooleanCaseInsensitive(dbSec, false, "ssl", "SSL", "useSSL", "usessl");

        Path advancedDir = destinationDir.resolve("advanced");
        Path targetDbFile = advancedDir.resolve("database.yml");
        Path rootDbFile = destinationDir.resolve("database.yml");

        List<Path> targets = new ArrayList<>();
        targets.add(targetDbFile);
        if (Files.exists(rootDbFile)) {
            targets.add(rootDbFile);
        }

        for (Path target : targets) {
            if (Files.exists(target) && !overwrite) {
                warnings.add("Skipping database config (already exists and overwrite=false): " + target);
                continue;
            }

            try {
                Files.createDirectories(target.getParent());
                RtpYamlConfig dbConfig = new RtpYamlConfig();
                RtpYamlSection innerSec = dbConfig.createSection("database");
                innerSec.set("type", type);
                innerSec.set("host", host);
                innerSec.set("port", port);
                innerSec.set("name", dbName);
                innerSec.set("username", user);
                innerSec.set("password", password);
                innerSec.set("useSSL", useSSL);
                dbConfig.set("version", 1.1);

                dbConfig.save(target.toFile());
                written.add(target);
                mapped.add("Database: type=" + type + ", host=" + host + ", port=" + port + ", name=" + dbName);
            } catch (IOException e) {
                warnings.add("Failed to write database config to " + target + ": " + e.getMessage());
            }
        }
    }

    private void mirrorEffectsConfig(RtpYamlConfig sourceConfig, Path destinationDir, boolean overwrite,
                                     List<String> mapped, List<Path> written, List<String> warnings) {
        RtpYamlSection effectsSec = getSectionCaseInsensitive(sourceConfig, "effects");
        if (effectsSec == null) {
            effectsSec = getSectionCaseInsensitive(sourceConfig, "Effects");
        }

        List<String> effectTokens = new ArrayList<>();

        // 1. Sounds
        RtpYamlSection soundSec = effectsSec != null ? getSectionCaseInsensitive(effectsSec, "sound") : null;
        if (soundSec == null && effectsSec != null) soundSec = getSectionCaseInsensitive(effectsSec, "sounds");
        if (soundSec == null) soundSec = getSectionCaseInsensitive(sourceConfig, "sound");
        if (soundSec != null) {
            String soundName = getStringCaseInsensitive(soundSec, "ENTITY_ENDERMAN_TELEPORT", "name", "sound", "Sound");
            double volRaw = getDoubleCaseInsensitive(soundSec, 1.0, "volume", "Volume");
            double pitchRaw = getDoubleCaseInsensitive(soundSec, 1.0, "pitch", "Pitch");
            int vol = (volRaw > 0 && volRaw <= 1.0) ? (int) Math.round(volRaw * 100) : (int) Math.round(volRaw);
            int pitch = (pitchRaw > 0 && pitchRaw <= 2.0) ? (int) Math.round(pitchRaw * 100) : (int) Math.round(pitchRaw);
            if (vol <= 0) vol = 100;
            if (pitch <= 0) pitch = 100;
            effectTokens.add("SOUND." + soundName.toUpperCase(Locale.ROOT) + "." + vol + "." + pitch + ".0.0.0");
        }

        // 2. Titles
        RtpYamlSection titleSec = effectsSec != null ? getSectionCaseInsensitive(effectsSec, "title") : null;
        if (titleSec == null) titleSec = getSectionCaseInsensitive(sourceConfig, "title");
        if (titleSec != null) {
            String title = getStringCaseInsensitive(titleSec, "", "title", "Title");
            String subtitle = getStringCaseInsensitive(titleSec, "", "sub_title", "subtitle", "Subtitle");
            int fadeIn = getIntCaseInsensitive(titleSec, 10, "fade_in", "fadein", "FadeIn");
            int stay = getIntCaseInsensitive(titleSec, 70, "stay", "Stay");
            int fadeOut = getIntCaseInsensitive(titleSec, 20, "fade_out", "fadeout", "FadeOut");
            if (!title.isEmpty() || !subtitle.isEmpty()) {
                effectTokens.add("TITLE." + title + "." + subtitle + "." + fadeIn + "." + stay + "." + fadeOut);
            }
        }

        // 3. Action Bars
        RtpYamlSection actionSec = effectsSec != null ? getSectionCaseInsensitive(effectsSec, "action_bar") : null;
        if (actionSec == null && effectsSec != null) actionSec = getSectionCaseInsensitive(effectsSec, "actionbar");
        if (actionSec == null) actionSec = getSectionCaseInsensitive(sourceConfig, "action_bar");
        if (actionSec != null) {
            String msg = getStringCaseInsensitive(actionSec, "", "text", "message", "Message", "Text");
            if (!msg.isEmpty()) {
                effectTokens.add("COMMAND.CONSOLE.title [player] actionbar {\"text\":\"" + msg + "\"}");
            }
        }

        // 4. Potions / Buffs
        RtpYamlSection potionSec = effectsSec != null ? getSectionCaseInsensitive(effectsSec, "potions") : null;
        if (potionSec == null && effectsSec != null) potionSec = getSectionCaseInsensitive(effectsSec, "Potions");
        if (potionSec != null) {
            List<String> rawPotions = potionSec.getStringList("list");
            if (rawPotions == null || rawPotions.isEmpty()) {
                rawPotions = potionSec.getStringList("List");
            }
            if (rawPotions != null) {
                for (String pot : rawPotions) {
                    String[] parts = pot.split("[:\\s]+");
                    if (parts.length >= 1) {
                        String type = parts[0].toUpperCase(Locale.ROOT);
                        int duration = (parts.length >= 2) ? parseDurationTicks(parts[1]) : 100;
                        int amp = (parts.length >= 3) ? parseAmp(parts[2]) : 1;
                        effectTokens.add("POTION." + type + "." + duration + "." + amp + ".false.false.false");
                    }
                }
            }
        } else if (effectsSec != null && effectsSec.contains("potions")) {
            List<String> rawPotions = effectsSec.getStringList("potions");
            if (rawPotions != null) {
                for (String pot : rawPotions) {
                    String[] parts = pot.split("[:\\s]+");
                    if (parts.length >= 1) {
                        String type = parts[0].toUpperCase(Locale.ROOT);
                        int duration = (parts.length >= 2) ? parseDurationTicks(parts[1]) : 100;
                        int amp = (parts.length >= 3) ? parseAmp(parts[2]) : 1;
                        effectTokens.add("POTION." + type + "." + duration + "." + amp + ".false.false.false");
                    }
                }
            }
        }

        if (effectTokens.isEmpty()) return;

        Path targetEffectFile = destinationDir.resolve("definitions/effects/imported_justrtp_teleport.yml");
        if (Files.exists(targetEffectFile) && !overwrite) {
            warnings.add("Skipping effect profile (already exists and overwrite=false): " + targetEffectFile);
            return;
        }

        try {
            Files.createDirectories(targetEffectFile.getParent());
            RtpYamlConfig effectConfig = new RtpYamlConfig();
            effectConfig.set("version", "1.0");
            effectConfig.set("when", "postteleport");
            effectConfig.set("effects", effectTokens);
            effectConfig.save(targetEffectFile.toFile());

            written.add(targetEffectFile);
            mapped.add("Effects: imported_justrtp_teleport (" + effectTokens.size() + " tokens)");
        } catch (IOException e) {
            warnings.add("Failed to write effect profile to " + targetEffectFile + ": " + e.getMessage());
        }
    }

    private void mirrorZonesConfig(Path sourcePluginDir, Path destinationDir, boolean overwrite,
                                  List<String> mapped, List<Path> written, List<String> warnings, List<String> errors) {
        Path zonesFile = sourcePluginDir.resolve("rtp_zones.yml");
        if (!Files.exists(zonesFile)) return;

        RtpYamlConfig zonesConfig = loadYamlSafe(zonesFile, warnings);
        if (zonesConfig == null) return;

        RtpYamlSection rootZonesSec = getSectionCaseInsensitive(zonesConfig, "zones");
        if (rootZonesSec == null) {
            rootZonesSec = zonesConfig;
        }

        Set<String> zoneKeys = rootZonesSec.getKeys(false);
        if (zoneKeys.isEmpty()) return;

        Path actionsDir = destinationDir.resolve("definitions/actions");

        for (String zoneKey : zoneKeys) {
            if ("version".equalsIgnoreCase(zoneKey)) continue;
            RtpYamlSection zoneSec = getSectionCaseInsensitive(rootZonesSec, zoneKey);
            if (zoneSec == null) continue;

            String zoneId = zoneKey.toLowerCase(Locale.ROOT);
            Path actionFile = actionsDir.resolve("zone_" + zoneId + ".yml");
            if (Files.exists(actionFile) && !overwrite) {
                warnings.add("Skipping zone action (already exists and overwrite=false): " + actionFile);
                continue;
            }

            String triggerWorld = getStringCaseInsensitive(zoneSec, "world", "world", "World");
            String triggerType = getStringCaseInsensitive(zoneSec, "STEP_IN", "type", "Type").toUpperCase(Locale.ROOT);
            if (triggerType.contains("PORTAL")) triggerType = "PORTAL";
            else if (triggerType.contains("PLATE")) triggerType = "PRESSURE_PLATE";
            else triggerType = "STEP_IN";

            int minX = 0, minY = 0, minZ = 0;
            int maxX = 0, maxY = 0, maxZ = 0;

            RtpYamlSection pos1Sec = getSectionCaseInsensitive(zoneSec, "pos1");
            RtpYamlSection pos2Sec = getSectionCaseInsensitive(zoneSec, "pos2");
            if (pos1Sec != null && pos2Sec != null) {
                int x1 = pos1Sec.getInt("x", 0);
                int y1 = pos1Sec.getInt("y", 0);
                int z1 = pos1Sec.getInt("z", 0);
                int x2 = pos2Sec.getInt("x", 0);
                int y2 = pos2Sec.getInt("y", 0);
                int z2 = pos2Sec.getInt("z", 0);
                minX = Math.min(x1, x2);
                minY = Math.min(y1, y2);
                minZ = Math.min(z1, z2);
                maxX = Math.max(x1, x2);
                maxY = Math.max(y1, y2);
                maxZ = Math.max(z1, z2);
            } else {
                int x1 = getIntCaseInsensitive(zoneSec, 0, "x1", "minX", "min_x");
                int y1 = getIntCaseInsensitive(zoneSec, 0, "y1", "minY", "min_y");
                int z1 = getIntCaseInsensitive(zoneSec, 0, "z1", "minZ", "min_z");
                int x2 = getIntCaseInsensitive(zoneSec, x1, "x2", "maxX", "max_x");
                int y2 = getIntCaseInsensitive(zoneSec, y1, "y2", "maxY", "max_y");
                int z2 = getIntCaseInsensitive(zoneSec, z1, "z2", "maxZ", "max_z");
                minX = Math.min(x1, x2);
                minY = Math.min(y1, y2);
                minZ = Math.min(z1, z2);
                maxX = Math.max(x1, x2);
                maxY = Math.max(y1, y2);
                maxZ = Math.max(z1, z2);
            }

            int cooldownSec = getIntCaseInsensitive(zoneSec, 5, "cooldown", "cooldown_seconds", "Cooldown");
            int batchIntervalSec = getIntCaseInsensitive(zoneSec, 0, "interval", "batch_interval", "batchInterval", "wave_interval");

            String targetRegion = getStringCaseInsensitive(zoneSec, "default", "region", "target_region", "Region");
            String shape = mapShape(getStringCaseInsensitive(zoneSec, "CIRCLE", "shape", "landing_shape"));
            int radius = getIntCaseInsensitive(zoneSec, 2048, "radius", "max_radius", "max-radius", "max-distance");
            int minRadius = getIntCaseInsensitive(zoneSec, 64, "min_radius", "min-radius", "min-distance", "centerRadius");
            int minSeparation = getIntCaseInsensitive(zoneSec, 16, "minSeparation", "min_separation", "separation");

            try {
                Files.createDirectories(actionFile.getParent());
                RtpYamlConfig actionYaml = new RtpYamlConfig();
                actionYaml.set("version", "1.0");
                actionYaml.set("alias", "zone_" + zoneId);
                actionYaml.set("permission", "rtp.action.zone." + zoneId);
                actionYaml.set("description", "Imported JustRTP zone " + zoneId);

                // Triggers block
                RtpYamlSection triggersSec = actionYaml.createSection("triggers");
                RtpYamlSection specSec = triggersSec.createSection("zone_" + zoneId + "_trigger");
                specSec.set("type", triggerType);
                specSec.set("world", triggerWorld);
                RtpYamlSection p1 = specSec.createSection("pos1");
                p1.set("x", minX);
                p1.set("y", minY);
                p1.set("z", minZ);
                RtpYamlSection p2 = specSec.createSection("pos2");
                p2.set("x", maxX);
                p2.set("y", maxY);
                p2.set("z", maxZ);
                specSec.set("cooldown", cooldownSec + "s");
                if (batchIntervalSec > 0) {
                    specSec.set("batchInterval", batchIntervalSec + "s");
                }

                // Placement block
                RtpYamlSection placeSec = actionYaml.createSection("placement");
                placeSec.set("region", targetRegion);
                placeSec.set("anchor", "regionQueue");
                RtpYamlSection shapeSec = placeSec.createSection("shape");
                shapeSec.set("name", shape);
                shapeSec.set("radius", radius);
                shapeSec.set("centerRadius", minRadius);
                placeSec.set("minSeparation", minSeparation);

                // Lifecycle block
                RtpYamlSection lifecycleSec = actionYaml.createSection("lifecycle");
                RtpYamlSection onStartSec = lifecycleSec.createSection("onStart");
                List<Map<String, Object>> forEachList = new ArrayList<>();
                Map<String, Object> msgMap = new LinkedHashMap<>();
                msgMap.put("MESSAGE", "<green>Teleporting via zone " + zoneId + "...</green>");
                forEachList.add(msgMap);
                onStartSec.set("FOR_EACH", forEachList);

                actionYaml.save(actionFile.toFile());
                written.add(actionFile);
                mapped.add("Zone Action: zone_" + zoneId + " (bounds=[" + minX + "," + minY + "," + minZ + "] to ["
                        + maxX + "," + maxY + "," + maxZ + "], type=" + triggerType + ")");
            } catch (IOException e) {
                errors.add("Failed to write zone action " + actionFile + ": " + e.getMessage());
            }
        }
    }

    private int parseDurationTicks(String s) {
        try {
            int val = Integer.parseInt(s.trim());
            return val > 30 ? val : val * 20;
        } catch (NumberFormatException e) {
            return 100;
        }
    }

    private int parseAmp(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private WorldConfigEntry parseWorldEntry(RtpYamlSection sec, String worldKey,
                                             String defShape, int defMinRadius, int defMaxRadius,
                                             int defCenterX, int defCenterZ, long defCooldown,
                                             double defCost, int defCacheCap) {
        String shape = defShape;
        int minRadius = defMinRadius;
        int maxRadius = defMaxRadius;
        int centerX = defCenterX;
        int centerZ = defCenterZ;
        long cooldown = defCooldown;
        double cost = defCost;
        int cacheCap = defCacheCap;

        String prefix = worldKey + ".";
        if (sec.contains(prefix + "shape")) {
            shape = mapShape(sec.getString(prefix + "shape"));
        } else if (sec.contains(prefix + "landing_shape")) {
            shape = mapShape(sec.getString(prefix + "landing_shape"));
        }

        if (sec.contains(prefix + "radius.max")) {
            maxRadius = sec.getInt(prefix + "radius.max", maxRadius);
        } else if (sec.contains(prefix + "radius")) {
            Object r = sec.get(prefix + "radius");
            if (r instanceof Number) {
                maxRadius = ((Number) r).intValue();
            } else if (r instanceof String) {
                try {
                    maxRadius = Integer.parseInt((String) r);
                } catch (NumberFormatException ignored) {}
            }
        } else if (sec.contains(prefix + "max_radius")) {
            maxRadius = sec.getInt(prefix + "max_radius", maxRadius);
        } else if (sec.contains(prefix + "max-radius")) {
            maxRadius = sec.getInt(prefix + "max-radius", maxRadius);
        } else if (sec.contains(prefix + "max-distance")) {
            maxRadius = sec.getInt(prefix + "max-distance", maxRadius);
        }

        if (sec.contains(prefix + "radius.min")) {
            minRadius = sec.getInt(prefix + "radius.min", minRadius);
        } else if (sec.contains(prefix + "min_radius")) {
            minRadius = sec.getInt(prefix + "min_radius", minRadius);
        } else if (sec.contains(prefix + "min-radius")) {
            minRadius = sec.getInt(prefix + "min-radius", minRadius);
        } else if (sec.contains(prefix + "min-distance")) {
            minRadius = sec.getInt(prefix + "min-distance", minRadius);
        }

        if (sec.contains(prefix + "center.x")) {
            centerX = sec.getInt(prefix + "center.x", centerX);
        } else if (sec.contains(prefix + "center-x")) {
            centerX = sec.getInt(prefix + "center-x", centerX);
        }

        if (sec.contains(prefix + "center.z")) {
            centerZ = sec.getInt(prefix + "center.z", centerZ);
        } else if (sec.contains(prefix + "center-z")) {
            centerZ = sec.getInt(prefix + "center-z", centerZ);
        }

        if (sec.contains(prefix + "cooldown")) {
            cooldown = sec.getLong(prefix + "cooldown", cooldown);
        } else if (sec.contains(prefix + "cooldown_seconds")) {
            cooldown = sec.getLong(prefix + "cooldown_seconds", cooldown);
        }

        if (sec.contains(prefix + "cost")) {
            cost = sec.getDouble(prefix + "cost", cost);
        }

        if (sec.contains(prefix + "cache_size")) {
            cacheCap = sec.getInt(prefix + "cache_size", cacheCap);
        }

        return new WorldConfigEntry(worldKey, shape, minRadius, maxRadius, centerX, centerZ, cooldown, cost, cacheCap);
    }

    private WorldConfigEntry parseWorldEntryMap(Map<?, ?> map, String worldKey,
                                                String defShape, int defMinRadius, int defMaxRadius,
                                                int defCenterX, int defCenterZ, long defCooldown,
                                                double defCost, int defCacheCap) {
        String shape = defShape;
        int minRadius = defMinRadius;
        int maxRadius = defMaxRadius;
        int centerX = defCenterX;
        int centerZ = defCenterZ;
        long cooldown = defCooldown;
        double cost = defCost;
        int cacheCap = defCacheCap;

        if (map.containsKey("shape")) {
            shape = mapShape(String.valueOf(map.get("shape")));
        } else if (map.containsKey("landing_shape")) {
            shape = mapShape(String.valueOf(map.get("landing_shape")));
        }

        if (map.get("radius") instanceof Number n) {
            maxRadius = n.intValue();
        } else if (map.get("max_radius") instanceof Number n) {
            maxRadius = n.intValue();
        } else if (map.get("max-radius") instanceof Number n) {
            maxRadius = n.intValue();
        } else if (map.get("max-distance") instanceof Number n) {
            maxRadius = n.intValue();
        }

        if (map.get("min_radius") instanceof Number n) {
            minRadius = n.intValue();
        } else if (map.get("min-radius") instanceof Number n) {
            minRadius = n.intValue();
        } else if (map.get("min-distance") instanceof Number n) {
            minRadius = n.intValue();
        }

        Object centerObj = map.get("center");
        if (centerObj instanceof Map<?, ?> cMap) {
            if (cMap.get("x") instanceof Number n) centerX = n.intValue();
            if (cMap.get("z") instanceof Number n) centerZ = n.intValue();
        }
        if (map.get("center-x") instanceof Number n) centerX = n.intValue();
        if (map.get("center-z") instanceof Number n) centerZ = n.intValue();

        if (map.get("cooldown") instanceof Number n) {
            cooldown = n.longValue();
        } else if (map.get("cooldown_seconds") instanceof Number n) {
            cooldown = n.longValue();
        }

        if (map.get("cost") instanceof Number n) {
            cost = n.doubleValue();
        }

        if (map.get("cache_size") instanceof Number n) {
            cacheCap = n.intValue();
        }

        return new WorldConfigEntry(worldKey, shape, minRadius, maxRadius, centerX, centerZ, cooldown, cost, cacheCap);
    }

    private String mapShape(String foreignShape) {
        if (foreignShape == null) return "CIRCLE";
        String s = foreignShape.trim().toUpperCase(Locale.ROOT);
        if (s.contains("SQUARE")) {
            return "SQUARE";
        }
        if (s.contains("ROUND") || s.contains("CIRCLE") || s.contains("RING")) {
            return "CIRCLE";
        }
        return "CIRCLE";
    }

    private RtpYamlConfig loadYamlSafe(Path path, List<String> warnings) {
        return loadForeignYaml(path, warnings);
    }

    private static class WorldConfigEntry {
        final String worldName;
        final String shape;
        final int minRadius;
        final int maxRadius;
        final int centerX;
        final int centerZ;
        final long cooldown;
        final double cost;
        final int cacheCap;

        WorldConfigEntry(String worldName, String shape, int minRadius, int maxRadius,
                         int centerX, int centerZ, long cooldown, double cost, int cacheCap) {
            this.worldName = worldName;
            this.shape = shape;
            this.minRadius = minRadius;
            this.maxRadius = maxRadius;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.cooldown = cooldown;
            this.cost = cost;
            this.cacheCap = cacheCap;
        }
    }

    private RtpYamlSection getSectionCaseInsensitive(RtpYamlConfig config, String key) {
        if (config == null) return null;
        Object direct = config.get(key);
        if (direct instanceof RtpYamlSection sec) return sec;

        for (String k : config.getKeys(false)) {
            if (k.equalsIgnoreCase(key)) {
                Object obj = config.get(k);
                if (obj instanceof RtpYamlSection sec) return sec;
            }
        }
        return null;
    }

    private RtpYamlSection getSectionCaseInsensitive(RtpYamlSection config, String key) {
        if (config == null) return null;
        Object direct = config.get(key);
        if (direct instanceof RtpYamlSection sec) return sec;

        for (String k : config.getKeys(false)) {
            if (k.equalsIgnoreCase(key)) {
                Object obj = config.get(k);
                if (obj instanceof RtpYamlSection sec) return sec;
            }
        }
        return null;
    }

    private String getStringCaseInsensitive(RtpYamlSection section, String def, String... keys) {
        for (String k : keys) {
            if (section.contains(k)) {
                String val = section.getString(k);
                if (val != null) return val;
            }
        }
        for (String actual : section.getKeys(false)) {
            for (String target : keys) {
                if (actual.equalsIgnoreCase(target)) {
                    String val = section.getString(actual);
                    if (val != null) return val;
                }
            }
        }
        return def;
    }

    private int getIntCaseInsensitive(RtpYamlSection section, int def, String... keys) {
        for (String k : keys) {
            if (section.contains(k)) {
                return section.getInt(k, def);
            }
        }
        for (String actual : section.getKeys(false)) {
            for (String target : keys) {
                if (actual.equalsIgnoreCase(target)) {
                    return section.getInt(actual, def);
                }
            }
        }
        return def;
    }

    private double getDoubleCaseInsensitive(RtpYamlSection section, double def, String... keys) {
        for (String k : keys) {
            if (section.contains(k)) {
                return section.getDouble(k, def);
            }
        }
        for (String actual : section.getKeys(false)) {
            for (String target : keys) {
                if (actual.equalsIgnoreCase(target)) {
                    return section.getDouble(actual, def);
                }
            }
        }
        return def;
    }

    private boolean getBooleanCaseInsensitive(RtpYamlSection section, boolean def, String... keys) {
        for (String k : keys) {
            if (section.contains(k)) {
                return section.getBoolean(k, def);
            }
        }
        for (String actual : section.getKeys(false)) {
            for (String target : keys) {
                if (actual.equalsIgnoreCase(target)) {
                    return section.getBoolean(actual, def);
                }
            }
        }
        return def;
    }
}
