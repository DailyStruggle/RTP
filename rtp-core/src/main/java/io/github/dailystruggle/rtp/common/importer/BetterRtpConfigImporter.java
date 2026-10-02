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
public class BetterRtpConfigImporter extends AbstractForeignConfigImporter {

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
            if (worldName == null || worldName.isBlank()) {
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
}
