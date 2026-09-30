package io.github.dailystruggle.rtp.common.importer;

import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Foreign configuration importer for BetterRTP (ADR-066).
 * Reads plugins/BetterRTP/config.yml.
 * Maps Default and CustomWorlds settings (radii, center, shapes, cooldowns, price)
 * into LeafRTP Region and World configs.
 */
public class BetterRtpConfigImporter implements ForeignConfigImporter {

    @Override
    public String sourceName() {
        return "betterrtp";
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
        RtpYamlConfig configCfg = loadYamlSafe(configYmlPath, warnings);

        if (configCfg == null) {
            return ImportResult.failure(sourceName(),
                    Collections.singletonList("No valid config.yml found in " + sourcePluginDir),
                    warnings);
        }

        // Global settings in BetterRTP
        long globalCooldown = 0L;
        long globalDelay = 0L;
        double globalPrice = 0.0;
        long globalLockAfter = 0L;
        boolean globalSetAsRespawn = false;

        RtpYamlSection settingsSec = getSectionCaseInsensitive(configCfg, "Settings");

        if (settingsSec != null) {
            // Check for nested Settings.Cooldown.Time vs Settings.Cooldown
            RtpYamlSection cdSec = getSectionCaseInsensitive(settingsSec, "Cooldown");
            if (cdSec != null) {
                globalCooldown = getLongCaseInsensitive(cdSec, 0L, "Time", "time");
                globalLockAfter = getLongCaseInsensitive(cdSec, 0L, "LockAfter", "lock-after", "lockafter");
            } else {
                globalCooldown = getLongCaseInsensitive(settingsSec, 0L, "Cooldown", "cooldown");
            }

            if (globalLockAfter <= 0) {
                globalLockAfter = getLongCaseInsensitive(settingsSec, 0L, "LockAfter", "lock-after", "lockafter");
            }

            RtpYamlSection delaySec = getSectionCaseInsensitive(settingsSec, "Delay");
            if (delaySec != null) {
                globalDelay = getLongCaseInsensitive(delaySec, 0L, "Time", "time");
            } else {
                globalDelay = getLongCaseInsensitive(settingsSec, 0L, "Delay", "delay");
            }

            globalPrice = getDoubleCaseInsensitive(settingsSec, 0.0, "Price", "price");

            if (getBooleanCaseInsensitive(settingsSec, false, "SetAsRespawn", "set-as-respawn", "setasrespawn")) {
                globalSetAsRespawn = true;
            }
            RtpYamlSection firstJoinSec = getSectionCaseInsensitive(settingsSec, "RtpOnFirstJoin");
            if (firstJoinSec != null && getBooleanCaseInsensitive(firstJoinSec, false, "SetAsRespawn", "set-as-respawn", "setasrespawn")) {
                globalSetAsRespawn = true;
            }
        } else {
            globalCooldown = getLongCaseInsensitive(configCfg, 0L, "Cooldown", "cooldown");
            globalDelay = getLongCaseInsensitive(configCfg, 0L, "Delay", "delay");
            globalPrice = getDoubleCaseInsensitive(configCfg, 0.0, "Price", "price");
            globalLockAfter = getLongCaseInsensitive(configCfg, 0L, "LockAfter", "lock-after", "lockafter");
            if (getBooleanCaseInsensitive(configCfg, false, "SetAsRespawn", "set-as-respawn", "setasrespawn")) {
                globalSetAsRespawn = true;
            }
        }

        // Parse Default section
        RtpYamlSection defaultSection = getSectionCaseInsensitive(configCfg, "Default");
        int defMinRadius = 64;
        int defMaxRadius = 2048;
        int defCenterX = 0;
        int defCenterZ = 0;
        String defShape = "CIRCLE";
        boolean defUseWorldBorder = false;

        if (defaultSection != null) {
            defMinRadius = getIntCaseInsensitive(defaultSection, defMinRadius, "MinRadius", "min-radius", "min");
            defMaxRadius = getIntCaseInsensitive(defaultSection, defMaxRadius, "MaxRadius", "max-radius", "max");
            defCenterX = getIntCaseInsensitive(defaultSection, defCenterX, "CenterX", "center-x", "x");
            defCenterZ = getIntCaseInsensitive(defaultSection, defCenterZ, "CenterZ", "center-z", "z");
            defShape = mapShape(getStringCaseInsensitive(defaultSection, "CIRCLE", "Shape", "shape"));
            defUseWorldBorder = getBooleanCaseInsensitive(defaultSection, false, "UseWorldBorder", "use-world-border");
        }

        // Process CustomWorlds
        List<RtpYamlSection> customWorldSections = new ArrayList<>();
        Object customWorldsObj = configCfg.get("CustomWorlds");
        if (customWorldsObj == null) {
            customWorldsObj = configCfg.get("customworlds");
        }
        if (customWorldsObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    if (m.size() == 1) {
                        Map.Entry<?, ?> singleEntry = m.entrySet().iterator().next();
                        if (singleEntry.getValue() instanceof Map<?, ?> nestedMap) {
                            RtpYamlConfig sub = new RtpYamlConfig();
                            sub.set("Name", singleEntry.getKey().toString());
                            for (Map.Entry<?, ?> nEntry : nestedMap.entrySet()) {
                                if (nEntry.getKey() != null) {
                                    sub.set(nEntry.getKey().toString(), nEntry.getValue());
                                }
                            }
                            customWorldSections.add(sub);
                            continue;
                        }
                    }
                    RtpYamlConfig sub = new RtpYamlConfig();
                    for (Map.Entry<?, ?> entry : m.entrySet()) {
                        if (entry.getKey() != null) {
                            sub.set(entry.getKey().toString(), entry.getValue());
                        }
                    }
                    customWorldSections.add(sub);
                } else if (item instanceof RtpYamlSection sec) {
                    // Check if sec has a single key that represents the world name
                    Set<String> keys = sec.getKeys(false);
                    if (keys.size() == 1) {
                        String singleKey = keys.iterator().next();
                        RtpYamlSection childSec = sec.getConfigurationSection(singleKey);
                        if (childSec != null) {
                            childSec.set("Name", singleKey);
                            customWorldSections.add(childSec);
                            continue;
                        } else if (sec.get(singleKey) instanceof Map<?, ?> nestedMap) {
                            RtpYamlConfig sub = new RtpYamlConfig();
                            sub.set("Name", singleKey);
                            for (Map.Entry<?, ?> nEntry : nestedMap.entrySet()) {
                                if (nEntry.getKey() != null) {
                                    sub.set(nEntry.getKey().toString(), nEntry.getValue());
                                }
                            }
                            customWorldSections.add(sub);
                            continue;
                        }
                    }
                    customWorldSections.add(sec);
                }
            }
        } else if (customWorldsObj instanceof RtpYamlSection sec) {
            for (String key : sec.getKeys(false)) {
                Object child = sec.get(key);
                if (child instanceof RtpYamlSection childSec) {
                    childSec.set("Name", key);
                    customWorldSections.add(childSec);
                } else if (child instanceof Map<?, ?> m) {
                    RtpYamlConfig sub = new RtpYamlConfig();
                    sub.set("Name", key);
                    for (Map.Entry<?, ?> entry : m.entrySet()) {
                        if (entry.getKey() != null) {
                            sub.set(entry.getKey().toString(), entry.getValue());
                        }
                    }
                    customWorldSections.add(sub);
                }
            }
        }

        // Collect disabled worlds
        Set<String> disabledWorlds = new HashSet<>();
        Object disabledObj = configCfg.get("DisabledWorlds");
        if (disabledObj == null) disabledObj = configCfg.get("disabledworlds");
        if (disabledObj instanceof List<?> dwList) {
            for (Object o : dwList) {
                if (o != null) disabledWorlds.add(o.toString().trim());
            }
        }

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

        // Track processed worlds
        Set<String> processedWorlds = new HashSet<>();

        for (RtpYamlSection cw : customWorldSections) {
            String worldName = getStringCaseInsensitive(cw, null, "Name", "World", "name", "world");
            if (worldName == null || worldName.trim().isEmpty()) {
                continue;
            }
            worldName = worldName.trim();
            if (disabledWorlds.contains(worldName)) {
                warnings.add("World '" + worldName + "' is in DisabledWorlds, skipping import.");
                continue;
            }

            processedWorlds.add(worldName);

            int minRadius = getIntCaseInsensitive(cw, defMinRadius, "MinRadius", "min-radius", "min");
            int maxRadius = getIntCaseInsensitive(cw, defMaxRadius, "MaxRadius", "max-radius", "max");
            int centerX = getIntCaseInsensitive(cw, defCenterX, "CenterX", "center-x", "x");
            int centerZ = getIntCaseInsensitive(cw, defCenterZ, "CenterZ", "center-z", "z");
            String shape = mapShape(getStringCaseInsensitive(cw, defShape, "Shape", "shape"));
            double price = getDoubleCaseInsensitive(cw, globalPrice, "Price", "price");

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

            // Write region
            RtpYamlConfig regionYaml = createRegionYaml(regionName, worldName, shape, minRadius, maxRadius, centerX, centerZ, price);
            try {
                regionYaml.save(regionFile.toFile());
                written.add(regionFile);
                mapped.add("Region: " + regionName + " (world=" + worldName + ", shape=" + shape
                        + ", radius=" + maxRadius + ", minRadius=" + minRadius + ", center=[" + centerX + "," + centerZ + "])");
            } catch (IOException e) {
                errors.add("Failed to write region file " + regionFile + ": " + e.getMessage());
            }

            // Write world
            RtpYamlConfig worldYaml = createWorldYaml(regionName);
            try {
                worldYaml.save(worldFile.toFile());
                written.add(worldFile);
                mapped.add("World: " + worldName + " -> " + regionName);
            } catch (IOException e) {
                errors.add("Failed to write world file " + worldFile + ": " + e.getMessage());
            }
        }

        // If no custom worlds were processed, generate for default
        if (processedWorlds.isEmpty()) {
            String worldName = "world";
            String regionName = "world_region";
            Path regionFile = regionsDir.resolve(regionName + ".yml");
            Path worldFile = worldsDir.resolve(worldName + ".yml");

            if (!overwrite) {
                if (Files.exists(regionFile)) {
                    errors.add("Region file already exists and overwrite is disabled: " + regionFile);
                }
                if (Files.exists(worldFile)) {
                    errors.add("World file already exists and overwrite is disabled: " + worldFile);
                }
            }

            if (errors.isEmpty()) {
                RtpYamlConfig regionYaml = createRegionYaml(regionName, worldName, defShape, defMinRadius, defMaxRadius, defCenterX, defCenterZ, globalPrice);
                try {
                    regionYaml.save(regionFile.toFile());
                    written.add(regionFile);
                    mapped.add("Region: " + regionName + " (world=" + worldName + ", shape=" + defShape
                            + ", radius=" + defMaxRadius + ", minRadius=" + defMinRadius + ", center=[" + defCenterX + "," + defCenterZ + "])");
                } catch (IOException e) {
                    errors.add("Failed to write default region file: " + e.getMessage());
                }

                RtpYamlConfig worldYaml = createWorldYaml(regionName);
                try {
                    worldYaml.save(worldFile.toFile());
                    written.add(worldFile);
                    mapped.add("World: " + worldName + " -> " + regionName);
                } catch (IOException e) {
                    errors.add("Failed to write default world file: " + e.getMessage());
                }
            }
        }

        // Update destination config.yml with global settings (cooldown, lockAfter, setRespawn)
        if (globalCooldown > 0 || globalLockAfter > 0 || globalSetAsRespawn) {
            Path destConfigPath = destinationDir.resolve("config.yml");
            try {
                RtpYamlConfig destConfig;
                if (Files.exists(destConfigPath)) {
                    destConfig = RtpYamlConfig.load(destConfigPath.toFile());
                } else {
                    destConfig = new RtpYamlConfig();
                    destConfig.set("version", "1.0");
                }
                if (globalCooldown > 0) {
                    destConfig.set("teleportCooldown", globalCooldown);
                    mapped.add("Global: teleportCooldown=" + globalCooldown);
                }
                if (globalLockAfter > 0) {
                    destConfig.set("lockAfterUses", globalLockAfter);
                    mapped.add("Global: lockAfterUses=" + globalLockAfter);
                }
                if (globalSetAsRespawn) {
                    destConfig.set("setRespawnOnTeleport", true);
                    mapped.add("Global: setRespawnOnTeleport=true");
                }
                destConfig.save(destConfigPath.toFile());
                written.add(destConfigPath);
            } catch (IOException e) {
                warnings.add("Failed to update config.yml: " + e.getMessage());
            }
        }

        // 1.2 SQL Database Setup Mirroring (ADR-066)
        mirrorDatabaseConfig(configCfg, destinationDir, overwrite, mapped, written, warnings);

        // 1.3 Competitor Effects Mirroring (ADR-066)
        mirrorEffectsConfig(configCfg, destinationDir, overwrite, mapped, written, warnings);

        boolean success = errors.isEmpty() && !written.isEmpty();
        return new ImportResult(success, sourceName(), written, warnings, errors, mapped);
    }

    private void mirrorDatabaseConfig(RtpYamlConfig sourceConfig, Path destinationDir, boolean overwrite,
                                      List<String> mapped, List<Path> written, List<String> warnings) {
        RtpYamlSection dbSec = getSectionCaseInsensitive(sourceConfig, "Database");
        if (dbSec == null) return;

        String rawType = getStringCaseInsensitive(dbSec, "sqlite", "Type", "type", "database");
        String type = rawType.toLowerCase(Locale.ROOT);
        if (type.contains("mysql")) type = "mysql";
        else if (type.contains("postgre")) type = "postgresql";
        else type = "sqlite";

        String host = getStringCaseInsensitive(dbSec, "127.0.0.1", "Host", "host", "ip", "server");
        int port = getIntCaseInsensitive(dbSec, 3306, "Port", "port");
        String dbName = getStringCaseInsensitive(dbSec, "rtp", "Database", "database", "name", "db");
        String user = getStringCaseInsensitive(dbSec, "root", "Username", "username", "user");
        String password = getStringCaseInsensitive(dbSec, "password", "Password", "password", "pass");
        boolean useSSL = getBooleanCaseInsensitive(dbSec, false, "UseSSL", "usessl", "ssl");

        // Target: advanced/database.yml (and root database.yml if present)
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
        RtpYamlSection effectsSec = getSectionCaseInsensitive(sourceConfig, "Effects");
        if (effectsSec == null) {
            effectsSec = getSectionCaseInsensitive(sourceConfig, "effects");
        }

        List<String> effectTokens = new ArrayList<>();

        // 1. Sounds
        RtpYamlSection soundSec = effectsSec != null ? getSectionCaseInsensitive(effectsSec, "Sounds") : null;
        if (soundSec == null && effectsSec != null) soundSec = getSectionCaseInsensitive(effectsSec, "Sound");
        if (soundSec == null) soundSec = getSectionCaseInsensitive(sourceConfig, "Sounds");
        if (soundSec != null) {
            boolean enabled = getBooleanCaseInsensitive(soundSec, true, "Enabled", "enabled");
            if (enabled) {
                String soundName = getStringCaseInsensitive(soundSec, "ENTITY_ENDERMAN_TELEPORT", "Sound", "sound", "Name", "name");
                double volRaw = getDoubleCaseInsensitive(soundSec, 1.0, "Volume", "volume");
                double pitchRaw = getDoubleCaseInsensitive(soundSec, 1.0, "Pitch", "pitch");
                int vol = (volRaw > 0 && volRaw <= 1.0) ? (int) Math.round(volRaw * 100) : (int) Math.round(volRaw);
                int pitch = (pitchRaw > 0 && pitchRaw <= 2.0) ? (int) Math.round(pitchRaw * 100) : (int) Math.round(pitchRaw);
                if (vol <= 0) vol = 100;
                if (pitch <= 0) pitch = 100;
                effectTokens.add("SOUND." + soundName.toUpperCase(Locale.ROOT) + "." + vol + "." + pitch + ".0.0.0");
            }
        }

        // 2. Titles
        RtpYamlSection titleSec = effectsSec != null ? getSectionCaseInsensitive(effectsSec, "Title") : null;
        if (titleSec == null) titleSec = getSectionCaseInsensitive(sourceConfig, "Title");
        if (titleSec != null) {
            boolean enabled = getBooleanCaseInsensitive(titleSec, true, "Enabled", "enabled");
            if (enabled) {
                String title = getStringCaseInsensitive(titleSec, "", "Title", "title");
                String subtitle = getStringCaseInsensitive(titleSec, "", "Subtitle", "subtitle", "SubTitle", "sub_title");
                int fadeIn = getIntCaseInsensitive(titleSec, 10, "FadeIn", "fadein", "fade_in");
                int stay = getIntCaseInsensitive(titleSec, 70, "Stay", "stay");
                int fadeOut = getIntCaseInsensitive(titleSec, 20, "FadeOut", "fadeout", "fade_out");
                if (!title.isEmpty() || !subtitle.isEmpty()) {
                    effectTokens.add("TITLE." + title + "." + subtitle + "." + fadeIn + "." + stay + "." + fadeOut);
                }
            }
        }

        // 3. Action Bars
        RtpYamlSection actionSec = effectsSec != null ? getSectionCaseInsensitive(effectsSec, "ActionBar") : null;
        if (actionSec == null && effectsSec != null) actionSec = getSectionCaseInsensitive(effectsSec, "action_bar");
        if (actionSec == null) actionSec = getSectionCaseInsensitive(sourceConfig, "ActionBar");
        if (actionSec != null) {
            boolean enabled = getBooleanCaseInsensitive(actionSec, true, "Enabled", "enabled");
            if (enabled) {
                String msg = getStringCaseInsensitive(actionSec, "", "Message", "message", "Text", "text");
                if (!msg.isEmpty()) {
                    effectTokens.add("COMMAND.CONSOLE.title [player] actionbar {\"text\":\"" + msg + "\"}");
                }
            }
        }

        // 4. Potions / Invulnerable Buffs
        RtpYamlSection potionSec = effectsSec != null ? getSectionCaseInsensitive(effectsSec, "Potions") : null;
        if (potionSec == null && effectsSec != null) potionSec = getSectionCaseInsensitive(effectsSec, "potions");
        if (potionSec != null) {
            boolean enabled = getBooleanCaseInsensitive(potionSec, true, "Enabled", "enabled");
            if (enabled) {
                List<String> rawPotions = potionSec.getStringList("List");
                if (rawPotions == null || rawPotions.isEmpty()) {
                    rawPotions = potionSec.getStringList("list");
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
            }
        }

        // Invulnerable / Invulnerability setting (e.g. Settings.Invulnerable or Invulnerable: 5)
        int invuln = getIntCaseInsensitive(sourceConfig, 0, "Invulnerable", "invulnerable");
        RtpYamlSection settingsSec = getSectionCaseInsensitive(sourceConfig, "Settings");
        if (settingsSec != null && invuln <= 0) {
            invuln = getIntCaseInsensitive(settingsSec, 0, "Invulnerable", "invulnerable");
        }
        if (invuln > 0) {
            int durationTicks = invuln * 20;
            effectTokens.add("POTION.RESISTANCE." + durationTicks + ".1.false.false.false");
        }

        if (effectTokens.isEmpty()) return;

        Path targetEffectFile = destinationDir.resolve("definitions/effects/imported_betterrtp_teleport.yml");
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
            mapped.add("Effects: imported_betterrtp_teleport (" + effectTokens.size() + " tokens)");
        } catch (IOException e) {
            warnings.add("Failed to write effect profile to " + targetEffectFile + ": " + e.getMessage());
        }
    }

    private int parseDurationTicks(String s) {
        try {
            int val = Integer.parseInt(s.trim());
            return val > 30 ? val : val * 20; // seconds to ticks if small
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

    private RtpYamlConfig createRegionYaml(String regionName, String worldName, String shapeName,
                                           int minRadius, int maxRadius, int centerX, int centerZ, double price) {
        RtpYamlConfig regionYaml = new RtpYamlConfig();
        regionYaml.set("displayName", "&a" + regionName);
        regionYaml.set("world", worldName);
        regionYaml.set("worldBorderOverride", false);

        RtpYamlSection shapeSec = regionYaml.createSection("shape");
        shapeSec.set("name", shapeName);
        shapeSec.set("mode", "ACCUMULATE");
        shapeSec.set("radius", maxRadius);
        shapeSec.set("centerRadius", minRadius);
        shapeSec.set("centerX", centerX);
        shapeSec.set("centerZ", centerZ);
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
        regionYaml.set("cacheCap", 50);
        regionYaml.set("backlogCacheCap", 1000);
        regionYaml.set("activeChunkCap", 10);
        regionYaml.set("price", price);
        regionYaml.set("spatialResolution", 3);
        regionYaml.set("version", "1.0");

        return regionYaml;
    }

    private RtpYamlConfig createWorldYaml(String regionName) {
        RtpYamlConfig worldYaml = new RtpYamlConfig();
        worldYaml.set("region", regionName);
        worldYaml.set("requirePermission", false);
        worldYaml.set("override", "[0]");
        worldYaml.set("version", "1.0");
        return worldYaml;
    }

    private String mapShape(String foreignShape) {
        if (foreignShape == null) return "CIRCLE";
        String s = foreignShape.trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "SQUARE", "RECTANGLE" -> "SQUARE";
            case "ROUND", "CIRCLE" -> "CIRCLE";
            default -> "CIRCLE";
        };
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

    private long getLongCaseInsensitive(RtpYamlSection section, long def, String... keys) {
        for (String k : keys) {
            if (section.contains(k)) {
                return section.getLong(k, def);
            }
        }
        for (String actual : section.getKeys(false)) {
            for (String target : keys) {
                if (actual.equalsIgnoreCase(target)) {
                    return section.getLong(actual, def);
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

    private boolean containsCaseInsensitive(RtpYamlSection section, String key) {
        if (section == null) return false;
        if (section.contains(key)) return true;
        for (String k : section.getKeys(false)) {
            if (k.equalsIgnoreCase(key)) return true;
        }
        return false;
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

    private RtpYamlConfig loadYamlSafe(Path file, List<String> warnings) {
        return loadForeignYaml(file, warnings);
    }
}
