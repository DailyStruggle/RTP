package io.github.dailystruggle.rtp.common.importer;

import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Foreign configuration importer for EzRTP (ADR-066).
 * Reads plugins/EzRTP/rtp.yml, config.yml, and limits.yml.
 * Maps radii (min-distance, max-distance), center (center-x, center-z), worlds,
 * shapes (circle -> CIRCLE, square -> SQUARE), and cooldowns into LeafRTP Region and World configs.
 */
public class EzRtpConfigImporter extends AbstractForeignConfigImporter {

    @Override
    public String sourceName() {
        return "ezrtp";
    }

    @Override
    public boolean canImport(Path sourcePluginDir) {
        if (sourcePluginDir == null || !Files.isDirectory(sourcePluginDir)) {
            return false;
        }
        return Files.exists(sourcePluginDir.resolve("rtp.yml"))
                || Files.exists(sourcePluginDir.resolve("config.yml"))
                || Files.exists(sourcePluginDir.resolve("limits.yml"));
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

        Path rtpYmlPath = sourcePluginDir.resolve("rtp.yml");
        Path configYmlPath = sourcePluginDir.resolve("config.yml");
        Path limitsYmlPath = sourcePluginDir.resolve("limits.yml");

        RtpYamlConfig rtpCfg = loadYamlSafe(rtpYmlPath, warnings);
        RtpYamlConfig configCfg = loadYamlSafe(configYmlPath, warnings);
        RtpYamlConfig limitsCfg = loadYamlSafe(limitsYmlPath, warnings);

        if (rtpCfg == null && configCfg == null && limitsCfg == null) {
            return ImportResult.failure(sourceName(),
                    Collections.singletonList("No valid EzRTP YAML files found in " + sourcePluginDir),
                    warnings);
        }

        // Global / default values
        int minRadius = 64;
        int maxRadius = 2048;
        int centerX = 0;
        int centerZ = 0;
        String shapeName = "CIRCLE";
        long cooldownSeconds = 0L;
        double cost = 0.0;
        Set<String> worlds = new LinkedHashSet<>();

        // 1. Read limits.yml for cooldowns and costs
        if (limitsCfg != null) {
            long defCd = getLongFromSections(limitsCfg, "rtp-limits.default.cooldown-seconds", "default.cooldown-seconds", "cooldown-seconds");
            if (defCd > 0) {
                cooldownSeconds = defCd;
            }
            double defCost = getDoubleFromSections(limitsCfg, "rtp-limits.default.cost", "default.cost", "cost");
            if (defCost > 0) {
                cost = defCost;
            }

            // Extract any worlds listed in limits
            Object worldsSection = limitsCfg.get("rtp-limits.worlds");
            if (worldsSection == null) worldsSection = limitsCfg.get("worlds");
            if (worldsSection instanceof RtpYamlSection sec) {
                worlds.addAll(sec.getKeys(false));
            } else if (worldsSection instanceof Map<?, ?> m) {
                for (Object k : m.keySet()) {
                    if (k != null) worlds.add(k.toString());
                }
            }
        }

        // 2. Read config.yml
        if (configCfg != null) {
            if (configCfg.contains("cost")) {
                cost = configCfg.getDouble("cost", cost);
            }
            if (configCfg.contains("cooldown-seconds")) {
                cooldownSeconds = configCfg.getLong("cooldown-seconds", cooldownSeconds);
            }
            if (configCfg.contains("world")) {
                String w = configCfg.getString("world");
                if (w != null && !w.isBlank()) {
                    worlds.add(w.trim());
                }
            }
            // Center in config.yml
            if (configCfg.contains("center.x")) {
                centerX = configCfg.getInt("center.x", centerX);
            } else if (configCfg.contains("center.center-x")) {
                centerX = configCfg.getInt("center.center-x", centerX);
            } else if (configCfg.contains("center-x")) {
                centerX = configCfg.getInt("center-x", centerX);
            }
            if (configCfg.contains("center.z")) {
                centerZ = configCfg.getInt("center.z", centerZ);
            } else if (configCfg.contains("center.center-z")) {
                centerZ = configCfg.getInt("center.center-z", centerZ);
            } else if (configCfg.contains("center-z")) {
                centerZ = configCfg.getInt("center-z", centerZ);
            }

            // Radius in config.yml
            if (configCfg.contains("radius.min")) {
                minRadius = configCfg.getInt("radius.min", minRadius);
            } else if (configCfg.contains("radius.min-distance")) {
                minRadius = configCfg.getInt("radius.min-distance", minRadius);
            } else if (configCfg.contains("radius.min-radius")) {
                minRadius = configCfg.getInt("radius.min-radius", minRadius);
            } else if (configCfg.contains("min-distance")) {
                minRadius = configCfg.getInt("min-distance", minRadius);
            } else if (configCfg.contains("min-radius")) {
                minRadius = configCfg.getInt("min-radius", minRadius);
            }

            if (configCfg.contains("radius.max")) {
                maxRadius = configCfg.getInt("radius.max", maxRadius);
            } else if (configCfg.contains("radius.max-distance")) {
                maxRadius = configCfg.getInt("radius.max-distance", maxRadius);
            } else if (configCfg.contains("radius.max-radius")) {
                maxRadius = configCfg.getInt("radius.max-radius", maxRadius);
            } else if (configCfg.contains("max-distance")) {
                maxRadius = configCfg.getInt("max-distance", maxRadius);
            } else if (configCfg.contains("max-radius")) {
                maxRadius = configCfg.getInt("max-radius", maxRadius);
            }
        }

        // 3. Read rtp.yml
        if (rtpCfg != null) {
            String pattern = rtpCfg.getString("search-pattern", null);
            if (pattern != null) {
                shapeName = mapShape(pattern);
            }

            if (rtpCfg.contains("center.x")) {
                centerX = rtpCfg.getInt("center.x", centerX);
            } else if (rtpCfg.contains("center.center-x")) {
                centerX = rtpCfg.getInt("center.center-x", centerX);
            } else if (rtpCfg.contains("center-x")) {
                centerX = rtpCfg.getInt("center-x", centerX);
            }
            if (rtpCfg.contains("center.z")) {
                centerZ = rtpCfg.getInt("center.z", centerZ);
            } else if (rtpCfg.contains("center.center-z")) {
                centerZ = rtpCfg.getInt("center.center-z", centerZ);
            } else if (rtpCfg.contains("center-z")) {
                centerZ = rtpCfg.getInt("center-z", centerZ);
            }

            if (rtpCfg.contains("radius.min")) {
                minRadius = rtpCfg.getInt("radius.min", minRadius);
            } else if (rtpCfg.contains("radius.min-distance")) {
                minRadius = rtpCfg.getInt("radius.min-distance", minRadius);
            } else if (rtpCfg.contains("radius.min-radius")) {
                minRadius = rtpCfg.getInt("radius.min-radius", minRadius);
            } else if (rtpCfg.contains("min-distance")) {
                minRadius = rtpCfg.getInt("min-distance", minRadius);
            } else if (rtpCfg.contains("min-radius")) {
                minRadius = rtpCfg.getInt("min-radius", minRadius);
            }

            if (rtpCfg.contains("radius.max")) {
                maxRadius = rtpCfg.getInt("radius.max", maxRadius);
            } else if (rtpCfg.contains("radius.max-distance")) {
                maxRadius = rtpCfg.getInt("radius.max-distance", maxRadius);
            } else if (rtpCfg.contains("radius.max-radius")) {
                maxRadius = rtpCfg.getInt("radius.max-radius", maxRadius);
            } else if (rtpCfg.contains("max-distance")) {
                maxRadius = rtpCfg.getInt("max-distance", maxRadius);
            } else if (rtpCfg.contains("max-radius")) {
                maxRadius = rtpCfg.getInt("max-radius", maxRadius);
            }

            if (rtpCfg.contains("world")) {
                String w = rtpCfg.getString("world");
                if (w != null && !w.isBlank()) {
                    worlds.add(w.trim());
                }
            }
            if (rtpCfg.contains("worlds")) {
                List<String> wList = rtpCfg.getStringList("worlds");
                if (wList != null) {
                    worlds.addAll(wList);
                }
            }
        }

        if (worlds.isEmpty()) {
            worlds.add("world");
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

        // Create region and world configs for each world
        for (String worldName : worlds) {
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

            // Per-world overrides from limits.yml if present
            int wMinRadius = minRadius;
            int wMaxRadius = maxRadius;
            int wCenterX = centerX;
            int wCenterZ = centerZ;
            double wCost = cost;

            if (limitsCfg != null) {
                String prefix = "rtp-limits.worlds." + worldName + ".";
                if (limitsCfg.contains(prefix + "cost")) {
                    wCost = limitsCfg.getDouble(prefix + "cost", wCost);
                } else if (limitsCfg.contains(prefix + "default.cost")) {
                    wCost = limitsCfg.getDouble(prefix + "default.cost", wCost);
                }
            }

            // Generate Region YAML
            RtpYamlConfig regionYaml = new RtpYamlConfig();
            regionYaml.set("displayName", "&a" + regionName);
            regionYaml.set("world", worldName);
            regionYaml.set("worldBorderOverride", false);

            RtpYamlSection shapeSec = regionYaml.createSection("shape");
            shapeSec.set("name", shapeName);
            shapeSec.set("mode", "ACCUMULATE");
            shapeSec.set("radius", wMaxRadius);
            shapeSec.set("centerRadius", wMinRadius);
            shapeSec.set("centerX", wCenterX);
            shapeSec.set("centerZ", wCenterZ);
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
            regionYaml.set("price", wCost);
            regionYaml.set("spatialResolution", 3);
            regionYaml.set("version", "1.0");

            try {
                regionYaml.save(regionFile.toFile());
                written.add(regionFile);
                mapped.add("Region: " + regionName + " (world=" + worldName + ", shape=" + shapeName
                        + ", radius=" + wMaxRadius + ", minRadius=" + wMinRadius + ")");
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

        // 4. Update LeafRTP config.yml with cooldown if extracted
        if (cooldownSeconds > 0) {
            Path destConfigPath = destinationDir.resolve("config.yml");
            try {
                RtpYamlConfig destConfig;
                if (Files.exists(destConfigPath)) {
                    destConfig = RtpYamlConfig.load(destConfigPath.toFile());
                } else {
                    destConfig = new RtpYamlConfig();
                    destConfig.set("version", "1.0");
                }
                destConfig.set("teleportCooldown", cooldownSeconds);
                destConfig.save(destConfigPath.toFile());
                written.add(destConfigPath);
                mapped.add("Cooldown: " + cooldownSeconds + "s mapped to config.yml#teleportCooldown");
            } catch (IOException e) {
                warnings.add("Failed to update destination config.yml with cooldown: " + e.getMessage());
            }
        }

        // 5. Competitor Effects Mirroring (ADR-066)
        mirrorEffectsConfig(rtpCfg, configCfg, destinationDir, overwrite, mapped, written, warnings);

        boolean success = errors.isEmpty() && !written.isEmpty();
        return new ImportResult(success, sourceName(), written, warnings, errors, mapped);
    }

    private void mirrorEffectsConfig(RtpYamlConfig rtpCfg, RtpYamlConfig configCfg, Path destinationDir, boolean overwrite,
                                     List<String> mapped, List<Path> written, List<String> warnings) {
        RtpYamlConfig source = (configCfg != null && (configCfg.contains("effects") || configCfg.contains("Effects")))
                ? configCfg : rtpCfg;
        if (source == null) return;

        RtpYamlSection effectsSec = getSectionCaseInsensitive(source, "effects");
        if (effectsSec == null) effectsSec = getSectionCaseInsensitive(source, "Effects");

        List<String> effectTokens = new ArrayList<>();

        // 1. Sounds
        RtpYamlSection soundSec = effectsSec != null ? getSectionCaseInsensitive(effectsSec, "sounds") : null;
        if (soundSec == null && effectsSec != null) soundSec = getSectionCaseInsensitive(effectsSec, "sound");
        if (soundSec == null) soundSec = getSectionCaseInsensitive(source, "sounds");
        if (soundSec == null) soundSec = getSectionCaseInsensitive(source, "sound");
        if (soundSec != null) {
            String soundName = getStringCaseInsensitive(soundSec, "ENTITY_ENDERMAN_TELEPORT", "sound", "name", "Sound", "Name");
            double volRaw = getDoubleCaseInsensitive(soundSec, 1.0, "volume", "Volume");
            double pitchRaw = getDoubleCaseInsensitive(soundSec, 1.0, "pitch", "Pitch");
            int vol = (volRaw > 0 && volRaw <= 1.0) ? (int) Math.round(volRaw * 100) : (int) Math.round(volRaw);
            int pitch = (pitchRaw > 0 && pitchRaw <= 2.0) ? (int) Math.round(pitchRaw * 100) : (int) Math.round(pitchRaw);
            if (vol <= 0) vol = 100;
            if (pitch <= 0) pitch = 100;
            effectTokens.add("SOUND." + soundName.toUpperCase(Locale.ROOT) + "." + vol + "." + pitch + ".0.0.0");
        }

        // 2. Titles
        RtpYamlSection titleSec = effectsSec != null ? getSectionCaseInsensitive(effectsSec, "titles") : null;
        if (titleSec == null && effectsSec != null) titleSec = getSectionCaseInsensitive(effectsSec, "title");
        if (titleSec == null) titleSec = getSectionCaseInsensitive(source, "titles");
        if (titleSec == null) titleSec = getSectionCaseInsensitive(source, "title");
        if (titleSec != null) {
            String title = getStringCaseInsensitive(titleSec, "", "title", "Title");
            String subtitle = getStringCaseInsensitive(titleSec, "", "subtitle", "Subtitle");
            int fadeIn = getIntCaseInsensitive(titleSec, 10, "fade-in", "fade_in", "fadeIn");
            int stay = getIntCaseInsensitive(titleSec, 70, "stay", "Stay");
            int fadeOut = getIntCaseInsensitive(titleSec, 20, "fade-out", "fade_out", "fadeOut");
            if (!title.isEmpty() || !subtitle.isEmpty()) {
                effectTokens.add("TITLE." + title + "." + subtitle + "." + fadeIn + "." + stay + "." + fadeOut);
            }
        }

        // 3. Actionbar
        RtpYamlSection actionSec = effectsSec != null ? getSectionCaseInsensitive(effectsSec, "actionbar") : null;
        if (actionSec == null && effectsSec != null) actionSec = getSectionCaseInsensitive(effectsSec, "action_bar");
        if (actionSec == null) actionSec = getSectionCaseInsensitive(source, "actionbar");
        if (actionSec == null) actionSec = getSectionCaseInsensitive(source, "action_bar");
        if (actionSec != null) {
            String msg = getStringCaseInsensitive(actionSec, "", "message", "text", "Message", "Text");
            if (!msg.isEmpty()) {
                effectTokens.add("COMMAND.CONSOLE.title [player] actionbar {\"text\":\"" + msg + "\"}");
            }
        }

        // 4. Potion effects
        RtpYamlSection potionSec = effectsSec != null ? getSectionCaseInsensitive(effectsSec, "potion-effects") : null;
        if (potionSec == null && effectsSec != null) potionSec = getSectionCaseInsensitive(effectsSec, "potions");
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
        }

        if (effectTokens.isEmpty()) return;

        Path targetEffectFile = destinationDir.resolve("definitions/effects/imported_ezrtp_teleport.yml");
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
            mapped.add("Effects: imported_ezrtp_teleport (" + effectTokens.size() + " tokens)");
        } catch (IOException e) {
            warnings.add("Failed to write effect profile to " + targetEffectFile + ": " + e.getMessage());
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

    private long getLongFromSections(RtpYamlConfig cfg, String... paths) {
        for (String p : paths) {
            if (cfg.contains(p)) {
                return cfg.getLong(p, 0L);
            }
        }
        return 0L;
    }

    private double getDoubleFromSections(RtpYamlConfig cfg, String... paths) {
        for (String p : paths) {
            if (cfg.contains(p)) {
                return cfg.getDouble(p, 0.0);
            }
        }
        return 0.0;
    }
}
