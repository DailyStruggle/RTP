package io.github.dailystruggle.rtp.common.importer;

import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Foreign configuration importer for JakesRTP (ADR-066).
 * Reads plugins/JakesRTP/config.yml, rtpSettings/*.yml, and distributions/*.yml.
 * Maps shapes (square, circle, rectangle), gaussian distributions (Normal), center,
 * radii, cooldown, warmup, cost, bounds, and cache settings into LeafRTP Region, World,
 * and global configs.
 */
public class JakesRtpConfigImporter extends AbstractForeignConfigImporter {

    @Override
    public String sourceName() {
        return "jakesrtp";
    }

    @Override
    public boolean canImport(Path sourcePluginDir) {
        if (sourcePluginDir == null || !Files.isDirectory(sourcePluginDir)) {
            return false;
        }
        return Files.exists(sourcePluginDir.resolve("config.yml"))
                || Files.isDirectory(sourcePluginDir.resolve("rtpSettings"));
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

        Path rtpSettingsDir = sourcePluginDir.resolve("rtpSettings");
        Path distributionsDir = sourcePluginDir.resolve("distributions");

        // Parse global settings from config.yml if available
        boolean rtpOnFirstJoin = false;
        String firstJoinSettings = "default-settings";
        boolean rtpOnDeath = false;
        boolean locationCacheEnabled = true;
        long cacheRecheckTime = 1800;
        long cacheBetweenTime = 0;

        if (configCfg != null) {
            RtpYamlSection firstJoinSec = getSectionCaseInsensitive(configCfg, "rtp-on-first-join");
            if (firstJoinSec != null) {
                rtpOnFirstJoin = getBooleanCaseInsensitive(firstJoinSec, false, "enabled");
                firstJoinSettings = getStringCaseInsensitive(firstJoinSec, "default-settings", "settings");
            }

            RtpYamlSection deathSec = getSectionCaseInsensitive(configCfg, "rtp-on-death");
            if (deathSec != null) {
                rtpOnDeath = getBooleanCaseInsensitive(deathSec, false, "enabled");
            }

            RtpYamlSection cacheSec = getSectionCaseInsensitive(configCfg, "location-cache-filler");
            if (cacheSec != null) {
                locationCacheEnabled = getBooleanCaseInsensitive(cacheSec, true, "enabled");
                cacheRecheckTime = getLongCaseInsensitive(cacheSec, 1800L, "recheck-time");
                cacheBetweenTime = getLongCaseInsensitive(cacheSec, 0L, "between-time");
            }
        }

        // Cache all available distribution configs
        Map<String, RtpYamlConfig> distributionMap = new HashMap<>();
        if (Files.isDirectory(distributionsDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(distributionsDir, "*.{yml,yaml}")) {
                for (Path distFile : stream) {
                    RtpYamlConfig distCfg = loadYamlSafe(distFile, warnings);
                    if (distCfg != null) {
                        String fileName = distFile.getFileName().toString();
                        String baseName = fileName.substring(0, fileName.lastIndexOf('.')).toLowerCase(Locale.ROOT);
                        distributionMap.put(baseName, distCfg);
                    }
                }
            } catch (IOException e) {
                warnings.add("Failed to read distributions directory: " + e.getMessage());
            }
        }

        // Collect all rtpSettings files
        List<Path> settingFiles = new ArrayList<>();
        if (Files.isDirectory(rtpSettingsDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(rtpSettingsDir, "*.{yml,yaml}")) {
                for (Path p : stream) {
                    settingFiles.add(p);
                }
            } catch (IOException e) {
                warnings.add("Failed to read rtpSettings directory: " + e.getMessage());
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

        long maxCooldownFound = 0L;
        long maxWarmupFound = 0L;
        double maxCostFound = 0.0;
        int maxAttemptsFound = 10;
        int maxCacheLocationsFound = 10;
        Set<String> processedWorlds = new LinkedHashSet<>();

        for (Path settingPath : settingFiles) {
            RtpYamlConfig settingCfg = loadYamlSafe(settingPath, warnings);
            if (settingCfg == null) continue;

            String fileName = settingPath.getFileName().toString();
            String profileName = fileName.substring(0, fileName.lastIndexOf('.'));

            boolean enabled = getBooleanCaseInsensitive(settingCfg, true, "enabled");
            if (!enabled) {
                warnings.add("Settings profile '" + profileName + "' is disabled, skipping.");
                continue;
            }

            String landingWorld = getStringCaseInsensitive(settingCfg, "world", "landing-world", "world");
            List<String> callFromWorlds = new ArrayList<>();
            Object callObj = settingCfg.get("call-from-worlds");
            if (callObj instanceof List<?> list) {
                for (Object o : list) {
                    if (o != null) callFromWorlds.add(o.toString());
                }
            } else if (callObj instanceof String s) {
                callFromWorlds.add(s);
            }

            long cooldown = getLongCaseInsensitive(settingCfg, 0L, "cooldown");
            if (cooldown > maxCooldownFound) maxCooldownFound = cooldown;

            long warmup = 0L;
            RtpYamlSection warmupSec = getSectionCaseInsensitive(settingCfg, "warmup");
            if (warmupSec != null) {
                warmup = getLongCaseInsensitive(warmupSec, 0L, "time");
            } else {
                warmup = getLongCaseInsensitive(settingCfg, 0L, "warmup");
            }
            if (warmup > maxWarmupFound) maxWarmupFound = warmup;

            double cost = getDoubleCaseInsensitive(settingCfg, 0.0, "cost");
            if (cost > maxCostFound) maxCostFound = cost;

            int lowBound = 32;
            int highBound = 255;
            RtpYamlSection boundsSec = getSectionCaseInsensitive(settingCfg, "bounds");
            if (boundsSec != null) {
                lowBound = getIntCaseInsensitive(boundsSec, lowBound, "low");
                highBound = getIntCaseInsensitive(boundsSec, highBound, "high");
            }

            RtpYamlSection attemptsSec = getSectionCaseInsensitive(settingCfg, "max-attempts");
            if (attemptsSec != null) {
                int att = getIntCaseInsensitive(attemptsSec, 10, "value");
                if (att > maxAttemptsFound) maxAttemptsFound = att;
            }

            RtpYamlSection prepSec = getSectionCaseInsensitive(settingCfg, "preparations");
            if (prepSec != null) {
                int cl = getIntCaseInsensitive(prepSec, 10, "cache-locations");
                if (cl > maxCacheLocationsFound) maxCacheLocationsFound = cl;
            }

            // Resolve distribution
            String distName = getStringCaseInsensitive(settingCfg, "default-symmetric", "distribution");
            RtpYamlConfig distCfg = distributionMap.get(distName.toLowerCase(Locale.ROOT));

            int minRadius = 1000;
            int maxRadius = 2000;
            int centerX = 0;
            int centerZ = 0;
            String rawShape = "SQUARE";
            boolean isGaussian = false;

            if (distCfg != null) {
                rawShape = getStringCaseInsensitive(distCfg, "square", "shape");

                RtpYamlSection radSec = getSectionCaseInsensitive(distCfg, "radius");
                if (radSec != null) {
                    minRadius = getIntCaseInsensitive(radSec, minRadius, "min");
                    maxRadius = getIntCaseInsensitive(radSec, maxRadius, "max");
                }

                RtpYamlSection centerSec = getSectionCaseInsensitive(distCfg, "center");
                if (centerSec != null) {
                    RtpYamlSection customSec = getSectionCaseInsensitive(centerSec, "c-custom");
                    if (customSec != null) {
                        centerX = getIntCaseInsensitive(customSec, centerX, "x");
                        centerZ = getIntCaseInsensitive(customSec, centerZ, "z");
                    }
                }

                RtpYamlSection gaussSec = getSectionCaseInsensitive(distCfg, "gaussian-distribution");
                if (gaussSec != null) {
                    isGaussian = getBooleanCaseInsensitive(gaussSec, false, "enabled");
                }
            } else if ("world-border".equalsIgnoreCase(distName)) {
                // Special dynamic world-border distribution in JakesRTP
                maxRadius = 29999984;
                minRadius = 64;
                rawShape = "SQUARE";
            }

            String targetShape;
            String normShape = rawShape.trim().toUpperCase(Locale.ROOT);
            if (normShape.contains("CIRCLE") || normShape.contains("ROUND")) {
                targetShape = isGaussian ? "CIRCLE_NORMAL" : "CIRCLE";
            } else {
                targetShape = isGaussian ? "SQUARE_NORMAL" : "SQUARE";
            }

            String regionName = profileName.replace("-", "_") + "_region";
            Path regionFile = regionsDir.resolve(regionName + ".yml");
            Path worldFile = worldsDir.resolve(landingWorld + ".yml");

            if (!overwrite) {
                if (Files.exists(regionFile)) {
                    errors.add("Region file already exists and overwrite is disabled: " + regionFile);
                    continue;
                }
                if (Files.exists(worldFile) && !processedWorlds.contains(landingWorld)) {
                    errors.add("World file already exists and overwrite is disabled: " + worldFile);
                    continue;
                }
            }

            // Write region
            RtpYamlConfig regionYaml = createRegionYaml(regionName, landingWorld, targetShape,
                    minRadius, maxRadius, centerX, centerZ, lowBound, highBound, cost);
            try {
                regionYaml.save(regionFile.toFile());
                written.add(regionFile);
                mapped.add("Region: " + regionName + " (world=" + landingWorld + ", shape=" + targetShape
                        + ", radius=" + maxRadius + ", minRadius=" + minRadius + ", bounds=[" + lowBound + "," + highBound + "])");
            } catch (IOException e) {
                errors.add("Failed to write region file " + regionFile + ": " + e.getMessage());
            }

            // Write world
            if (!processedWorlds.contains(landingWorld)) {
                RtpYamlConfig worldYaml = createWorldYaml(regionName);
                try {
                    worldYaml.save(worldFile.toFile());
                    written.add(worldFile);
                    mapped.add("World: " + landingWorld + " -> " + regionName);
                    processedWorlds.add(landingWorld);
                } catch (IOException e) {
                    errors.add("Failed to write world file " + worldFile + ": " + e.getMessage());
                }
            }

            // Also map any call-from-worlds if they are specific world names
            for (String fromWorld : callFromWorlds) {
                if (fromWorld != null && !fromWorld.contains("*") && !fromWorld.contains("?")
                        && !processedWorlds.contains(fromWorld)) {
                    Path fromWorldFile = worldsDir.resolve(fromWorld + ".yml");
                    if (overwrite || !Files.exists(fromWorldFile)) {
                        RtpYamlConfig worldYaml = createWorldYaml(regionName);
                        try {
                            worldYaml.save(fromWorldFile.toFile());
                            written.add(fromWorldFile);
                            mapped.add("World: " + fromWorld + " -> " + regionName);
                            processedWorlds.add(fromWorld);
                        } catch (IOException e) {
                            errors.add("Failed to write world file " + fromWorldFile + ": " + e.getMessage());
                        }
                    }
                }
            }
        }

        // If no profiles were processed, generate a default region & world
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
                RtpYamlConfig regionYaml = createRegionYaml(regionName, worldName, "SQUARE",
                        64, 2048, 0, 0, 32, 255, maxCostFound);
                try {
                    regionYaml.save(regionFile.toFile());
                    written.add(regionFile);
                    mapped.add("Region: " + regionName + " (world=" + worldName + ", shape=SQUARE, radius=2048, minRadius=64)");
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

        // Update destination config.yml
        if (maxCooldownFound > 0 || maxWarmupFound > 0 || rtpOnFirstJoin || rtpOnDeath) {
            Path destConfigPath = destinationDir.resolve("config.yml");
            try {
                RtpYamlConfig destConfig;
                if (Files.exists(destConfigPath)) {
                    destConfig = RtpYamlConfig.load(destConfigPath.toFile());
                } else {
                    destConfig = new RtpYamlConfig();
                    destConfig.set("version", "1.0");
                }
                if (maxCooldownFound > 0) {
                    destConfig.set("teleportCooldown", maxCooldownFound);
                    mapped.add("Global: teleportCooldown=" + maxCooldownFound);
                }
                if (maxWarmupFound > 0) {
                    destConfig.set("teleportDelay", maxWarmupFound);
                    mapped.add("Global: teleportDelay=" + maxWarmupFound);
                }
                if (rtpOnFirstJoin) {
                    destConfig.set("rtpOnFirstJoin", true);
                    mapped.add("Global: rtpOnFirstJoin=true");
                }
                if (rtpOnDeath) {
                    destConfig.set("rtpOnDeath", true);
                    mapped.add("Global: rtpOnDeath=true");
                }
                destConfig.save(destConfigPath.toFile());
                written.add(destConfigPath);
            } catch (IOException e) {
                warnings.add("Failed to update config.yml: " + e.getMessage());
            }
        }

        // Update destination performance.yml with maxAttempts and queue cache locations
        if (maxAttemptsFound > 10 || maxCacheLocationsFound > 0) {
            Path destPerfPath = destinationDir.resolve("performance.yml");
            try {
                RtpYamlConfig destPerf;
                if (Files.exists(destPerfPath)) {
                    destPerf = RtpYamlConfig.load(destPerfPath.toFile());
                } else {
                    destPerf = new RtpYamlConfig();
                    destPerf.set("version", "1.0");
                }
                if (maxAttemptsFound > 0) {
                    destPerf.set("maxAttempts", maxAttemptsFound);
                    mapped.add("Performance: maxAttempts=" + maxAttemptsFound);
                }
                if (maxCacheLocationsFound > 0) {
                    destPerf.set("queue.targetSize", maxCacheLocationsFound);
                    mapped.add("Performance: queue.targetSize=" + maxCacheLocationsFound);
                }
                destPerf.save(destPerfPath.toFile());
                written.add(destPerfPath);
            } catch (IOException e) {
                warnings.add("Failed to update performance.yml: " + e.getMessage());
            }
        }

        boolean success = errors.isEmpty() && !written.isEmpty();
        return new ImportResult(success, sourceName(), written, warnings, errors, mapped);
    }

    private RtpYamlConfig createRegionYaml(String regionName, String worldName, String shapeName,
                                           int minRadius, int maxRadius, int centerX, int centerZ,
                                           int minY, int maxY, double price) {
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
        if (shapeName.contains("NORMAL")) {
            shapeSec.set("mean", 0.5);
            shapeSec.set("deviation", 1.0);
        }
        shapeSec.set("weight", 1.0);
        shapeSec.set("uniquePlacements", false);
        shapeSec.set("expand", false);

        RtpYamlSection vertSec = regionYaml.createSection("vert");
        vertSec.set("name", "JUMP");
        vertSec.set("maxY", maxY);
        vertSec.set("minY", minY);

        regionYaml.set("price", price);
        regionYaml.set("requirePermission", false);
        regionYaml.set("version", "1.0");

        return regionYaml;
    }
}
