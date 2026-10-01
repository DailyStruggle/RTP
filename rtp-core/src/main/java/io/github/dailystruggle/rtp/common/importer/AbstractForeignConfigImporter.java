package io.github.dailystruggle.rtp.common.importer;

import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import java.util.regex.Pattern;

/**
 * Skeletal base class for foreign configuration importers providing shared
 * YAML loading, fuzzy/heuristic section and key traversal, common region/world
 * generation, database and effects mirroring, safe file writing, and backup retention logic.
 */
public abstract class AbstractForeignConfigImporter implements ForeignConfigImporter {

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-zA-Z0-9]");

    /**
     * Normalizes a key by stripping punctuation, symbols, and lowercasing for fuzzy matching.
     * E.g. "min-radius", "min_radius", "MinRadius", "min.radius" -> "minradius".
     */
    protected static String normalizeKey(String key) {
        if (key == null) return "";
        return NON_ALPHANUMERIC.matcher(key).replaceAll("").toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Finds an entry value in a section matching any of the candidate keys via exact, case-insensitive,
     * or normalized fuzzy match.
     */
    protected Object findValueFuzzy(RtpYamlSection section, String... candidateKeys) {
        if (section == null || candidateKeys == null || candidateKeys.length == 0) return null;

        // 1. Direct path check (supports dotted keys if present in implementation)
        for (String ck : candidateKeys) {
            if (section.contains(ck)) {
                Object val = section.get(ck);
                if (val != null) return val;
            }
        }

        // 2. Exact/Case-insensitive check across direct keys
        Set<String> directKeys = section.getKeys(false);
        for (String ck : candidateKeys) {
            for (String actual : directKeys) {
                if (actual.equalsIgnoreCase(ck)) {
                    Object val = section.get(actual);
                    if (val != null) return val;
                }
            }
        }

        // 3. Fuzzy normalized match across direct keys
        for (String ck : candidateKeys) {
            String normTarget = normalizeKey(ck);
            if (normTarget.isEmpty()) continue;
            for (String actual : directKeys) {
                String normActual = normalizeKey(actual);
                if (normActual.equals(normTarget)) {
                    Object val = section.get(actual);
                    if (val != null) return val;
                }
            }
        }

        return null;
    }

    /**
     * Safely loads and pre-processes a foreign YAML file, populating warnings on parse errors.
     */
    protected RtpYamlConfig loadYamlSafe(Path file, List<String> warnings) {
        return loadForeignYaml(file, warnings);
    }

    /**
     * Resolves a subsection from a config case-insensitively with fuzzy key matching.
     */
    protected RtpYamlSection getSectionCaseInsensitive(RtpYamlConfig config, String key) {
        if (config == null || key == null) return null;
        Object direct = config.get(key);
        if (direct instanceof RtpYamlSection sec) return sec;

        for (String k : config.getKeys(false)) {
            if (k.equalsIgnoreCase(key)) {
                Object obj = config.get(k);
                if (obj instanceof RtpYamlSection sec) return sec;
            }
        }

        String normKey = normalizeKey(key);
        if (!normKey.isEmpty()) {
            for (String k : config.getKeys(false)) {
                if (normalizeKey(k).equals(normKey)) {
                    Object obj = config.get(k);
                    if (obj instanceof RtpYamlSection sec) return sec;
                }
            }
        }
        return null;
    }

    /**
     * Resolves a subsection from another section using candidate keys with fuzzy fallback.
     */
    protected RtpYamlSection getSectionCaseInsensitive(RtpYamlSection parent, String... candidateKeys) {
        if (parent == null || candidateKeys == null) return null;
        for (String ck : candidateKeys) {
            Object direct = parent.get(ck);
            if (direct instanceof RtpYamlSection sec) return sec;
            for (String key : parent.getKeys(false)) {
                if (key.equalsIgnoreCase(ck)) {
                    Object val = parent.get(key);
                    if (val instanceof RtpYamlSection sec) return sec;
                }
            }
        }

        for (String ck : candidateKeys) {
            String normTarget = normalizeKey(ck);
            if (normTarget.isEmpty()) continue;
            for (String key : parent.getKeys(false)) {
                if (normalizeKey(key).equals(normTarget)) {
                    Object val = parent.get(key);
                    if (val instanceof RtpYamlSection sec) return sec;
                }
            }
        }
        return null;
    }

    /**
     * Checks whether a section contains a given key (case-insensitively).
     */
    protected boolean containsCaseInsensitive(RtpYamlSection section, String key) {
        if (section == null) return false;
        if (section.contains(key)) return true;
        for (String k : section.getKeys(false)) {
            if (k.equalsIgnoreCase(key)) return true;
        }
        return false;
    }

    /**
     * Resolves a string value from a section using alternative candidate keys with fuzzy fallback.
     */
    protected String getStringCaseInsensitive(RtpYamlSection section, String def, String... keys) {
        if (section == null) return def;
        Object val = findValueFuzzy(section, keys);
        return val != null ? val.toString() : def;
    }

    /**
     * Resolves an int value from a section using alternative candidate keys with fuzzy fallback.
     */
    protected int getIntCaseInsensitive(RtpYamlSection section, int def, String... keys) {
        if (section == null) return def;
        Object val = findValueFuzzy(section, keys);
        if (val instanceof Number n) return n.intValue();
        if (val != null) {
            try {
                return Integer.parseInt(val.toString().trim());
            } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    /**
     * Resolves a long value from a section using alternative candidate keys with fuzzy fallback.
     */
    protected long getLongCaseInsensitive(RtpYamlSection section, long def, String... keys) {
        if (section == null) return def;
        Object val = findValueFuzzy(section, keys);
        if (val instanceof Number n) return n.longValue();
        if (val != null) {
            try {
                return Long.parseLong(val.toString().trim());
            } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    /**
     * Resolves a double value from a section using alternative candidate keys with fuzzy fallback.
     */
    protected double getDoubleCaseInsensitive(RtpYamlSection section, double def, String... keys) {
        if (section == null) return def;
        Object val = findValueFuzzy(section, keys);
        if (val instanceof Number n) return n.doubleValue();
        if (val != null) {
            try {
                return Double.parseDouble(val.toString().trim());
            } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    /**
     * Resolves a boolean value from a section using alternative candidate keys with fuzzy fallback.
     */
    protected boolean getBooleanCaseInsensitive(RtpYamlSection section, boolean def, String... keys) {
        if (section == null) return def;
        Object val = findValueFuzzy(section, keys);
        if (val instanceof Boolean b) return b;
        if (val != null) {
            String s = val.toString().trim();
            if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("yes") || s.equalsIgnoreCase("1")) return true;
            if (s.equalsIgnoreCase("false") || s.equalsIgnoreCase("no") || s.equalsIgnoreCase("0")) return false;
        }
        return def;
    }

    /**
     * Resolves a list of strings from a section using alternative candidate keys with fuzzy fallback.
     */
    protected List<String> getStringListCaseInsensitive(RtpYamlSection section, String... keys) {
        if (section == null) return new ArrayList<>();
        Object val = findValueFuzzy(section, keys);
        if (val instanceof List<?> l) {
            List<String> result = new ArrayList<>();
            for (Object o : l) {
                if (o != null) result.add(o.toString());
            }
            return result;
        }
        return new ArrayList<>();
    }

    /**
     * Writes content to a destination file, handling directory creation, backup creation when
     * overwriting, and retention pruning.
     */
    protected boolean writeConfigFile(Path targetFile, String content, boolean overwrite,
                                      List<Path> createdFiles, List<Path> backedUpFiles, List<String> warnings) {
        try {
            if (Files.exists(targetFile)) {
                if (!overwrite) {
                    warnings.add("File exists and overwrite is false: " + targetFile.getFileName());
                    return false;
                }
                // Create backup before overwriting
                String timestamp = String.valueOf(System.currentTimeMillis());
                Path backup = targetFile.resolveSibling(targetFile.getFileName().toString() + ".bak." + timestamp);
                Files.copy(targetFile, backup, StandardCopyOption.REPLACE_EXISTING);
                backedUpFiles.add(backup);
                pruneOldBackups(targetFile.getParent(), targetFile.getFileName().toString());
            } else {
                if (targetFile.getParent() != null) {
                    Files.createDirectories(targetFile.getParent());
                }
            }
            Files.writeString(targetFile, content, StandardCharsets.UTF_8);
            createdFiles.add(targetFile);
            return true;
        } catch (IOException e) {
            warnings.add("Failed to write " + targetFile.getFileName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Builds standard LeafRTP region YAML configuration.
     */
    protected RtpYamlConfig createRegionYaml(String regionName, String worldName, String shape,
                                             int minRadius, int maxRadius, int centerX, int centerZ,
                                             double price, List<String> biomes) {
        RtpYamlConfig regionConfig = new RtpYamlConfig();
        regionConfig.set("displayName", "&a" + regionName);
        regionConfig.set("world", worldName);
        regionConfig.set("worldBorderOverride", false);

        String shapeName = shape != null ? shape : "CIRCLE";
        RtpYamlSection shapeSec = regionConfig.createSection("shape");
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

        RtpYamlSection vertSec = regionConfig.createSection("vert");
        vertSec.set("name", "JUMP");
        vertSec.set("maxY", 320);
        vertSec.set("minY", 64);

        if (biomes != null && !biomes.isEmpty()) {
            RtpYamlSection biomeSec = regionConfig.createSection("biomes");
            biomeSec.set("blacklist", true);
            biomeSec.set("list", biomes);
        }

        regionConfig.set("price", price);
        regionConfig.set("requirePermission", false);
        regionConfig.set("version", "1.0");
        return regionConfig;
    }

    /**
     * Builds standard LeafRTP region YAML configuration without custom biomes.
     */
    protected RtpYamlConfig createRegionYaml(String regionName, String worldName, String shape,
                                             int minRadius, int maxRadius, int centerX, int centerZ,
                                             double price) {
        return createRegionYaml(regionName, worldName, shape, minRadius, maxRadius, centerX, centerZ, price, null);
    }

    /**
     * Builds standard LeafRTP world YAML configuration.
     */
    protected RtpYamlConfig createWorldYaml(String regionName) {
        RtpYamlConfig worldConfig = new RtpYamlConfig();
        worldConfig.set("region", regionName);
        worldConfig.set("name", regionName);
        worldConfig.set("override", "[0,0]");
        worldConfig.set("requirePermission", false);
        worldConfig.set("nearShape", "CIRCLE");
        worldConfig.set("nearRadius", 64);
        worldConfig.set("nearCenterRadius", 0);
        worldConfig.set("nearMinY", 48);
        worldConfig.set("nearMaxY", 96);
        worldConfig.set("version", "1.0");
        return worldConfig;
    }

    /**
     * Mirrors database configuration to advanced/database.yml (and root database.yml if present).
     */
    protected void mirrorDatabaseConfig(RtpYamlConfig sourceConfig, Path destinationDir, boolean overwrite,
                                        List<String> mapped, List<Path> written, List<String> warnings) {
        RtpYamlSection dbSec = getSectionCaseInsensitive(sourceConfig, "Database", "database", "sql", "mysql");
        if (dbSec == null) return;

        String rawType = getStringCaseInsensitive(dbSec, "sqlite", "Type", "type", "database", "driver");
        String type = rawType.toLowerCase(Locale.ROOT);
        if (type.contains("mysql") || type.contains("mariadb")) type = "mysql";
        else if (type.contains("postgre")) type = "postgresql";
        else type = "sqlite";

        String host = getStringCaseInsensitive(dbSec, "127.0.0.1", "Host", "host", "ip", "server", "address");
        int port = getIntCaseInsensitive(dbSec, 3306, "Port", "port");
        String dbName = getStringCaseInsensitive(dbSec, "rtp", "Database", "database", "name", "db", "table");
        String user = getStringCaseInsensitive(dbSec, "root", "Username", "username", "user");
        String password = getStringCaseInsensitive(dbSec, "password", "Password", "password", "pass");
        boolean useSSL = getBooleanCaseInsensitive(dbSec, false, "UseSSL", "usessl", "ssl");

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
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
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

                writeConfigFile(target, dbConfig.saveToString(), overwrite, written, new ArrayList<>(), warnings);
                mapped.add("Database: type=" + type + ", host=" + host + ", port=" + port + ", name=" + dbName);
            } catch (IOException e) {
                warnings.add("Failed to write database config to " + target + ": " + e.getMessage());
            }
        }
    }

    /**
     * Mirrors effects, sounds, titles, and action bar configurations into definitions/effects/imported_<plugin>_teleport.yml
     * as well as effects.yml.
     */
    protected void mirrorEffectsConfig(RtpYamlConfig sourceConfig, Path destinationDir, boolean overwrite,
                                       List<String> mapped, List<Path> written, List<String> warnings) {
        RtpYamlSection effectsSec = getSectionCaseInsensitive(sourceConfig, "Effects", "effects", "Particles", "particles");

        List<String> effectTokens = new ArrayList<>();

        // 1. Sounds
        RtpYamlSection soundSec = effectsSec != null ? getSectionCaseInsensitive(effectsSec, "Sounds", "Sound", "sound", "sounds") : null;
        if (soundSec == null) soundSec = getSectionCaseInsensitive(sourceConfig, "Sounds", "Sound", "sound", "sounds");
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
        RtpYamlSection titleSec = effectsSec != null ? getSectionCaseInsensitive(effectsSec, "Title", "title", "titles") : null;
        if (titleSec == null) titleSec = getSectionCaseInsensitive(sourceConfig, "Title", "title", "titles");
        if (titleSec != null) {
            boolean enabled = getBooleanCaseInsensitive(titleSec, true, "Enabled", "enabled");
            if (enabled) {
                String title = getStringCaseInsensitive(titleSec, "", "Title", "title");
                String subtitle = getStringCaseInsensitive(titleSec, "", "Subtitle", "subtitle", "SubTitle", "sub_title");
                int fadeIn = getIntCaseInsensitive(titleSec, 10, "FadeIn", "fadein", "fade_in", "fade-in");
                int stay = getIntCaseInsensitive(titleSec, 70, "Stay", "stay");
                int fadeOut = getIntCaseInsensitive(titleSec, 20, "FadeOut", "fadeout", "fade_out", "fade-out");
                if (!title.isEmpty() || !subtitle.isEmpty()) {
                    effectTokens.add("TITLE." + title + "." + subtitle + "." + fadeIn + "." + stay + "." + fadeOut);
                }
            }
        }

        // 3. Action Bars
        RtpYamlSection actionSec = effectsSec != null ? getSectionCaseInsensitive(effectsSec, "ActionBar", "action_bar", "actionbar") : null;
        if (actionSec == null) actionSec = getSectionCaseInsensitive(sourceConfig, "ActionBar", "action_bar", "actionbar");
        if (actionSec != null) {
            boolean enabled = getBooleanCaseInsensitive(actionSec, true, "Enabled", "enabled");
            if (enabled) {
                String msg = getStringCaseInsensitive(actionSec, "", "Message", "message", "Text", "text");
                if (!msg.isEmpty()) {
                    effectTokens.add("COMMAND.CONSOLE.title [player] actionbar {\"text\":\"" + msg + "\"}");
                }
            }
        }

        // 4. Potions / Buffs
        RtpYamlSection potionSec = effectsSec != null ? getSectionCaseInsensitive(effectsSec, "Potions", "potions", "potion-effects") : null;
        if (potionSec == null) potionSec = getSectionCaseInsensitive(sourceConfig, "Potions", "potions", "potion-effects");
        if (potionSec != null) {
            boolean enabled = getBooleanCaseInsensitive(potionSec, true, "Enabled", "enabled");
            if (enabled) {
                List<String> rawPotions = getStringListCaseInsensitive(potionSec, "list", "List");
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

        // 5. Invulnerable / Invulnerability setting
        int invuln = getIntCaseInsensitive(sourceConfig, 0, "Invulnerable", "invulnerable");
        RtpYamlSection settingsSec = getSectionCaseInsensitive(sourceConfig, "Settings", "settings");
        if (settingsSec != null && invuln <= 0) {
            invuln = getIntCaseInsensitive(settingsSec, 0, "Invulnerable", "invulnerable");
        }
        if (invuln > 0) {
            int durationTicks = invuln * 20;
            effectTokens.add("POTION.RESISTANCE." + durationTicks + ".1.false.false.false");
        }

        // 6. Particles
        RtpYamlSection particleSec = effectsSec != null ? getSectionCaseInsensitive(effectsSec, "Particles", "Particle", "particles", "particle") : null;
        if (particleSec == null) particleSec = getSectionCaseInsensitive(sourceConfig, "Particles", "Particle", "particles", "particle");
        if (particleSec != null) {
            boolean enabled = getBooleanCaseInsensitive(particleSec, true, "Enabled", "enabled");
            if (enabled) {
                String pType = getStringCaseInsensitive(particleSec, "PORTAL", "Type", "type", "Name", "name", "particle");
                int count = getIntCaseInsensitive(particleSec, 50, "Amount", "amount", "Count", "count");
                effectTokens.add("PARTICLE." + pType.toUpperCase(Locale.ROOT) + "." + count + ".0.5.0.5.0.5.0.1");
            }
        }

        if (effectTokens.isEmpty()) return;

        // Write definitions/effects/imported_<plugin>_teleport.yml
        String profileName = "imported_" + sourceName() + "_teleport";
        Path targetEffectFile = destinationDir.resolve("definitions/effects/" + profileName + ".yml");
        if (!Files.exists(targetEffectFile) || overwrite) {
            try {
                if (targetEffectFile.getParent() != null) {
                    Files.createDirectories(targetEffectFile.getParent());
                }
                RtpYamlConfig effectConfig = new RtpYamlConfig();
                effectConfig.set("version", "1.0");
                effectConfig.set("when", "postteleport");
                effectConfig.set("effects", effectTokens);
                writeConfigFile(targetEffectFile, effectConfig.saveToString(), overwrite, written, new ArrayList<>(), warnings);
                mapped.add("Effects: " + profileName + " (" + effectTokens.size() + " tokens)");
            } catch (IOException e) {
                warnings.add("Failed to write effect profile to " + targetEffectFile + ": " + e.getMessage());
            }
        }

        // Also update effects.yml if it exists or if targeted
        Path effectsFile = destinationDir.resolve("effects.yml");
        if (Files.exists(effectsFile) && overwrite) {
            try {
                RtpYamlConfig effectsConfig = RtpYamlConfig.load(effectsFile.toFile());
                effectsConfig.set("teleport", effectTokens);
                effectsConfig.set("version", 1.0);
                writeConfigFile(effectsFile, effectsConfig.saveToString(), true, written, new ArrayList<>(), warnings);
            } catch (IOException e) {
                warnings.add("Failed to update effects.yml: " + e.getMessage());
            }
        }
    }

    private static int parseDurationTicks(String s) {
        try {
            int val = Integer.parseInt(s.trim());
            return val > 30 ? val : val * 20;
        } catch (NumberFormatException e) {
            return 100;
        }
    }

    private static int parseAmp(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * Normalizes shape names (e.g. ROUND, CIRCLE, SQUARE, RECTANGLE) into LeafRTP shapes (CIRCLE, SQUARE).
     */
    protected String mapShape(String foreignShape) {
        if (foreignShape == null) return "CIRCLE";
        String s = foreignShape.trim().toUpperCase(Locale.ROOT);
        if (s.contains("SQUARE") || s.contains("RECT")) {
            return "SQUARE";
        }
        return "CIRCLE";
    }

    /**
     * Immutable data holder representing a discovered world/region target.
     */
    public record DiscoveredWorldRegion(
            String name,
            String world,
            String shape,
            int minRadius,
            int maxRadius,
            int centerX,
            int centerZ,
            int minY,
            int maxY,
            double price,
            int cacheCap,
            List<String> biomes,
            List<String> additionalWorlds
    ) {
        public DiscoveredWorldRegion(String name, String world, String shape, int minRadius, int maxRadius, int centerX, int centerZ, double price) {
            this(name, world, shape, minRadius, maxRadius, centerX, centerZ, 64, 320, price, -1, null, null);
        }

        public DiscoveredWorldRegion(String name, String world, String shape, int minRadius, int maxRadius, int centerX, int centerZ, double price, List<String> biomes) {
            this(name, world, shape, minRadius, maxRadius, centerX, centerZ, 64, 320, price, -1, biomes, null);
        }

        public DiscoveredWorldRegion(String name, String world, String shape, int minRadius, int maxRadius, int centerX, int centerZ, int minY, int maxY, double price, List<String> biomes) {
            this(name, world, shape, minRadius, maxRadius, centerX, centerZ, minY, maxY, price, -1, biomes, null);
        }

        public DiscoveredWorldRegion(String name, String world, String shape, int minRadius, int maxRadius, int centerX, int centerZ, int minY, int maxY, double price, int cacheCap, List<String> biomes) {
            this(name, world, shape, minRadius, maxRadius, centerX, centerZ, minY, maxY, price, cacheCap, biomes, null);
        }
    }

    /**
     * Immutable data holder representing global gameplay and queue settings.
     */
    public record GlobalSettings(
            long cooldown,
            long delay,
            long lockAfter,
            boolean setAsRespawn,
            boolean rtpOnFirstJoin,
            boolean rtpOnDeath,
            boolean cancelOnMove,
            int maxAttempts,
            int cacheCap,
            int queueTargetSize
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private long cooldown = -1L;
            private long delay = -1L;
            private long lockAfter = -1L;
            private boolean setAsRespawn = false;
            private boolean rtpOnFirstJoin = false;
            private boolean rtpOnDeath = false;
            private boolean cancelOnMove = false;
            private int maxAttempts = -1;
            private int cacheCap = -1;
            private int queueTargetSize = -1;

            public Builder cooldown(long cooldown) { this.cooldown = cooldown; return this; }
            public Builder delay(long delay) { this.delay = delay; return this; }
            public Builder lockAfter(long lockAfter) { this.lockAfter = lockAfter; return this; }
            public Builder setAsRespawn(boolean setAsRespawn) { this.setAsRespawn = setAsRespawn; return this; }
            public Builder rtpOnFirstJoin(boolean rtpOnFirstJoin) { this.rtpOnFirstJoin = rtpOnFirstJoin; return this; }
            public Builder rtpOnDeath(boolean rtpOnDeath) { this.rtpOnDeath = rtpOnDeath; return this; }
            public Builder cancelOnMove(boolean cancelOnMove) { this.cancelOnMove = cancelOnMove; return this; }
            public Builder maxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; return this; }
            public Builder cacheCap(int cacheCap) { this.cacheCap = cacheCap; return this; }
            public Builder queueTargetSize(int queueTargetSize) { this.queueTargetSize = queueTargetSize; return this; }
            public GlobalSettings build() {
                return new GlobalSettings(cooldown, delay, lockAfter, setAsRespawn, rtpOnFirstJoin, rtpOnDeath, cancelOnMove, maxAttempts, cacheCap, queueTargetSize);
            }
        }
    }

    /**
     * Discovers world/region target sections across the 4 finite config topologies:
     * 1. Multi-File Directory (e.g. dir/*.yml)
     * 2. Keyed Mapping (Map-of-Maps) (e.g. root.get(sectionKey) is a section with world keys)
     * 3. Sequence of Entries (List-of-Maps) (e.g. root.get(sectionKey) is a list of maps)
     * 4. Flat / Root-Level Singleton (root itself defines the target)
     *
     * @param sourcePluginDir root folder of foreign plugin
     * @param rootConfig primary YAML config
     * @param subDirectory optional subfolder name (e.g. "rtpSettings", "worlds", "distributions")
     * @param candidateSectionKeys candidate section keys to check in rootConfig (e.g. "CustomWorlds", "worlds", "zones")
     * @return map of world/region name to configuration section
     */
    protected Map<String, RtpYamlSection> discoverWorldSections(Path sourcePluginDir,
                                                                RtpYamlConfig rootConfig,
                                                                String subDirectory,
                                                                String... candidateSectionKeys) {
        Map<String, RtpYamlSection> result = new LinkedHashMap<>();

        // Topology 1: Multi-file directory (e.g. rtpSettings/*.yml or worlds/*.yml)
        if (sourcePluginDir != null && subDirectory != null && !subDirectory.isEmpty()) {
            Path subPath = sourcePluginDir.resolve(subDirectory);
            if (Files.isDirectory(subPath)) {
                try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(subPath, "*.{yml,yaml}")) {
                    for (Path p : stream) {
                        RtpYamlConfig cfg = loadYamlSafe(p, new ArrayList<>());
                        if (cfg != null) {
                            String fName = p.getFileName().toString();
                            String baseName = fName.contains(".") ? fName.substring(0, fName.lastIndexOf('.')) : fName;
                            result.put(baseName, cfg);
                        }
                    }
                } catch (IOException ignored) {}
            }
        }

        if (!result.isEmpty()) {
            return result;
        }

        if (rootConfig == null) {
            return result;
        }

        // Topology 2 & 3: Sections in rootConfig
        if (candidateSectionKeys != null && candidateSectionKeys.length > 0) {
            for (String key : candidateSectionKeys) {
                Object obj = rootConfig.get(key);
                if (obj == null) {
                    RtpYamlSection sec = getSectionCaseInsensitive(rootConfig, key);
                    if (sec != null) obj = sec;
                }

                if (obj instanceof RtpYamlSection sec) {
                    for (String childKey : sec.getKeys(false)) {
                        Object childVal = sec.get(childKey);
                        if (childVal instanceof RtpYamlSection childSec) {
                            result.put(childKey, childSec);
                        } else if (childVal instanceof Map<?, ?> m) {
                            RtpYamlConfig sub = new RtpYamlConfig();
                            for (Map.Entry<?, ?> entry : m.entrySet()) {
                                if (entry.getKey() != null) {
                                    sub.set(entry.getKey().toString(), entry.getValue());
                                }
                            }
                            result.put(childKey, sub);
                        }
                    }
                } else if (obj instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> m) {
                            if (m.size() == 1) {
                                Map.Entry<?, ?> single = m.entrySet().iterator().next();
                                if (single.getValue() instanceof Map<?, ?> nestedMap) {
                                    String name = single.getKey().toString();
                                    RtpYamlConfig sub = new RtpYamlConfig();
                                    sub.set("name", name);
                                    sub.set("world", name);
                                    for (Map.Entry<?, ?> nEntry : nestedMap.entrySet()) {
                                        if (nEntry.getKey() != null) {
                                            sub.set(nEntry.getKey().toString(), nEntry.getValue());
                                        }
                                    }
                                    result.put(name, sub);
                                    continue;
                                }
                            }
                            RtpYamlConfig sub = new RtpYamlConfig();
                            for (Map.Entry<?, ?> entry : m.entrySet()) {
                                if (entry.getKey() != null) {
                                    sub.set(entry.getKey().toString(), entry.getValue());
                                }
                            }
                            String name = getStringCaseInsensitive(sub, "world", "Name", "name", "World", "world");
                            sub.set("world", name);
                            result.put(name, sub);
                        } else if (item instanceof RtpYamlSection sec) {
                            Set<String> keys = sec.getKeys(false);
                            if (keys.size() == 1) {
                                String singleKey = keys.iterator().next();
                                RtpYamlSection childSec = sec.getConfigurationSection(singleKey);
                                if (childSec != null) {
                                    childSec.set("name", singleKey);
                                    childSec.set("world", singleKey);
                                    result.put(singleKey, childSec);
                                    continue;
                                }
                            }
                            String name = getStringCaseInsensitive(sec, "world", "Name", "name", "World", "world");
                            sec.set("world", name);
                            result.put(name, sec);
                        }
                    }
                }

                if (!result.isEmpty()) {
                    return result;
                }
            }
        }

        // Topology 4: Flat / Root-Level Singleton
        if (findValueFuzzy(rootConfig, "maxradius", "radius", "maxdistance", "max") != null) {
            String worldName = getStringCaseInsensitive(rootConfig, "world", "World", "world", "world-name", "name");
            result.put(worldName, rootConfig);
        }

        return result;
    }

    /**
     * Extracts a DiscoveredWorldRegion from a candidate configuration section using fuzzy matching.
     */
    protected DiscoveredWorldRegion extractWorldRegion(String targetName,
                                                       RtpYamlSection section,
                                                       DiscoveredWorldRegion defaults) {
        String worldName = getStringCaseInsensitive(section, defaults != null ? defaults.world() : targetName, "world", "World", "world-name", "world_name", "target-world");
        if (worldName == null || worldName.trim().isEmpty()) {
            worldName = targetName;
        }

        int minRadius = getIntCaseInsensitive(section, defaults != null ? defaults.minRadius() : 64, "min-radius", "min_radius", "minradius", "min", "min-distance", "mindistance", "radius.min-distance", "radius.min-radius", "radius.min");
        int maxRadius = getIntCaseInsensitive(section, defaults != null ? defaults.maxRadius() : 2048, "max-radius", "max_radius", "maxradius", "max", "max-distance", "maxdistance", "range", "radius.max-distance", "radius.max-radius", "radius.max", "radius");
        int centerX = getIntCaseInsensitive(section, defaults != null ? defaults.centerX() : 0, "center-x", "center_x", "center.x", "center.center-x", "centerX", "x", "spawn-x", "spawn.x");
        int centerZ = getIntCaseInsensitive(section, defaults != null ? defaults.centerZ() : 0, "center-z", "center_z", "center.z", "center.center-z", "centerZ", "z", "spawn-z", "spawn.z");
        double price = getDoubleCaseInsensitive(section, defaults != null ? defaults.price() : 0.0, "price", "cost", "vault-cost", "economy.cost");

        // If section contains nested free or paid sections (e.g. AsyRTP gui.worlds.<world>.free / paid)
        RtpYamlSection freeSec = getSectionCaseInsensitive(section, "free");
        if (freeSec != null) {
            minRadius = getIntCaseInsensitive(freeSec, minRadius, "min-radius", "min_radius", "minradius", "min-distance");
            maxRadius = getIntCaseInsensitive(freeSec, maxRadius, "max-radius", "max_radius", "maxradius", "max-distance");
        }
        RtpYamlSection paidSec = getSectionCaseInsensitive(section, "paid");
        if (paidSec != null) {
            price = getDoubleCaseInsensitive(paidSec, price, "cost", "price", "vault-cost");
            if (freeSec == null) {
                minRadius = getIntCaseInsensitive(paidSec, minRadius, "min-radius", "min_radius", "minradius", "min-distance");
                maxRadius = getIntCaseInsensitive(paidSec, maxRadius, "max-radius", "max_radius", "maxradius", "max-distance");
            }
        }

        // If radius is a section itself (e.g. EzRTP's radius: { min-distance: 120, max-distance: 3600 })
        RtpYamlSection radiusSec = getSectionCaseInsensitive(section, "radius");
        if (radiusSec != null) {
            minRadius = getIntCaseInsensitive(radiusSec, minRadius, "min-distance", "min-radius", "min_distance", "min_radius", "min", "center-radius");
            maxRadius = getIntCaseInsensitive(radiusSec, maxRadius, "max-distance", "max-radius", "max_distance", "max_radius", "max", "radius");
        }

        // If center is a section itself (e.g. center: { center-x: 50, center-z: -50 } or { x: 50, z: -50 })
        RtpYamlSection centerSec = getSectionCaseInsensitive(section, "center", "spawn");
        if (centerSec != null) {
            centerX = getIntCaseInsensitive(centerSec, centerX, "center-x", "center_x", "x", "centerX");
            centerZ = getIntCaseInsensitive(centerSec, centerZ, "center-z", "center_z", "z", "centerZ");
        }
        int minY = getIntCaseInsensitive(section, defaults != null ? defaults.minY() : 64,
                "min-y", "min_y", "miny", "minY", "min-y-coordinate", "min_y_coordinate", "bottom-y", "bottom");
        int maxY = getIntCaseInsensitive(section, defaults != null ? defaults.maxY() : 320,
                "max-y", "max_y", "maxy", "maxY", "max-y-coordinate", "max_y_coordinate", "top-y", "top");

        String rawShape = getStringCaseInsensitive(section, defaults != null ? defaults.shape() : "CIRCLE", "shape", "landing_shape", "type", "distribution", "pattern");
        String shape = mapShape(rawShape);

        if (price <= 0.0) {
            price = getDoubleCaseInsensitive(section, defaults != null ? defaults.price() : 0.0, "price", "cost", "vault-cost", "economy.cost");
        }

        List<String> biomes = getStringListCaseInsensitive(section, "biomes", "blacklisted-biomes", "blacklisted_biomes", "biomes.blacklist", "disabled-biomes");
        if (biomes.isEmpty() && defaults != null && defaults.biomes() != null) {
            biomes = defaults.biomes();
        }

        return new DiscoveredWorldRegion(targetName, worldName, shape, minRadius, maxRadius, centerX, centerZ, minY, maxY, price, biomes);
    }

    /**
     * Emits region and world YAML files for all discovered world regions.
     */
    protected void emitRegionsAndWorlds(Collection<DiscoveredWorldRegion> regions,
                                        Path destinationDir,
                                        boolean overwrite,
                                        List<Path> written,
                                        List<String> mapped,
                                        List<String> errors) {
        Path definitionsDir = destinationDir.resolve("definitions");
        boolean useDefinitions = Files.isDirectory(definitionsDir);
        Path regionsDir = useDefinitions ? definitionsDir.resolve("regions") : destinationDir.resolve("regions");
        Path worldsDir = useDefinitions ? definitionsDir.resolve("worlds") : destinationDir.resolve("worlds");

        try {
            Files.createDirectories(regionsDir);
            Files.createDirectories(worldsDir);
        } catch (IOException e) {
            errors.add("Failed to create destination directories: " + e.getMessage());
            return;
        }

        for (DiscoveredWorldRegion r : regions) {
            String regionName;
            if (r.name().endsWith("_region") || r.name().startsWith("justrtp_loc_") || r.name().contains("_loc_")) {
                regionName = r.name();
            } else {
                regionName = r.name() + "_region";
            }
            Path regionFile = regionsDir.resolve(regionName + ".yml");
            Path worldFile = worldsDir.resolve(r.world() + ".yml");

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

            RtpYamlConfig regionConfig = createRegionYaml(regionName, r.world(), r.shape(),
                    r.minRadius(), r.maxRadius(), r.centerX(), r.centerZ(), r.price(), r.biomes());
            if (r.minY() != 64 || r.maxY() != 320) {
                RtpYamlSection vertSec = regionConfig.getConfigurationSection("vert");
                if (vertSec != null) {
                    vertSec.set("minY", r.minY());
                    vertSec.set("maxY", r.maxY());
                }
            }
            if (r.cacheCap() > 0) {
                regionConfig.set("cacheCap", r.cacheCap());
            }

            try {
                regionConfig.save(regionFile.toFile());
                written.add(regionFile);
                mapped.add("Region: " + regionName + " (world=" + r.world() + ", shape=" + r.shape()
                        + ", radius=" + r.maxRadius() + ", minRadius=" + r.minRadius()
                        + ", center=[" + r.centerX() + "," + r.centerZ() + "])");
            } catch (IOException e) {
                errors.add("Failed to write region file " + regionFile + ": " + e.getMessage());
            }

            RtpYamlConfig worldConfig = createWorldYaml(regionName);
            try {
                worldConfig.save(worldFile.toFile());
                written.add(worldFile);
                mapped.add("World: " + r.world() + " -> " + regionName);
            } catch (IOException e) {
                errors.add("Failed to write world file " + worldFile + ": " + e.getMessage());
            }

            if (r.additionalWorlds() != null) {
                for (String addWorld : r.additionalWorlds()) {
                    if (addWorld == null || addWorld.trim().isEmpty() || addWorld.equals(r.world())) {
                        continue;
                    }
                    if (addWorld.contains("*") || addWorld.contains("?")) {
                        continue;
                    }
                    Path addWorldFile = worldsDir.resolve(addWorld + ".yml");
                    if (!overwrite && Files.exists(addWorldFile)) {
                        continue;
                    }
                    RtpYamlConfig addWorldConfig = createWorldYaml(regionName);
                    try {
                        addWorldConfig.save(addWorldFile.toFile());
                        written.add(addWorldFile);
                        mapped.add("World: " + addWorld + " -> " + regionName);
                    } catch (IOException e) {
                        errors.add("Failed to write world file " + addWorldFile + ": " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Loads all YAML files (*.yml, *.yaml) in a directory into a map of base file names to configs.
     */
    protected Map<String, RtpYamlConfig> loadDirectoryConfigs(Path dir, List<String> warnings) {
        Map<String, RtpYamlConfig> configs = new LinkedHashMap<>();
        if (dir == null || !Files.isDirectory(dir)) return configs;

        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.{yml,yaml}")) {
            for (Path p : stream) {
                RtpYamlConfig cfg = loadYamlSafe(p, warnings != null ? warnings : new ArrayList<>());
                if (cfg != null) {
                    String fileName = p.getFileName().toString();
                    String baseName = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
                    configs.put(baseName.toLowerCase(Locale.ROOT), cfg);
                }
            }
        } catch (IOException e) {
            if (warnings != null) {
                warnings.add("Failed to read directory " + dir + ": " + e.getMessage());
            }
        }
        return configs;
    }

    /**
     * Synchronizes global teleport cooldowns, delays, lockAfter, respawn settings into destination config.yml
     * as well as performance parameters (maxAttempts, queueTargetSize) into performance.yml.
     */
    protected void updateDestinationConfig(Path destinationDir,
                                          GlobalSettings settings,
                                          List<String> mapped,
                                          List<Path> written,
                                          List<String> warnings) {
        if (settings == null) return;
        boolean hasConfig = settings.cooldown() >= 0
                || settings.delay() >= 0
                || settings.lockAfter() >= 0
                || settings.setAsRespawn()
                || settings.cancelOnMove()
                || settings.rtpOnFirstJoin()
                || settings.rtpOnDeath()
                || settings.maxAttempts() >= 0
                || settings.cacheCap() >= 0;

        if (hasConfig) {
            Path destConfigPath = destinationDir.resolve("config.yml");
            try {
                RtpYamlConfig destConfig;
                if (Files.exists(destConfigPath)) {
                    destConfig = RtpYamlConfig.load(destConfigPath.toFile());
                } else {
                    destConfig = new RtpYamlConfig();
                    destConfig.set("version", "1.0");
                }

                if (settings.cooldown() >= 0) {
                    destConfig.set("teleportCooldown", settings.cooldown());
                    mapped.add("Global: teleportCooldown=" + settings.cooldown());
                }
                if (settings.delay() >= 0) {
                    destConfig.set("teleportDelay", settings.delay());
                    mapped.add("Global: teleportDelay=" + settings.delay());
                }
                if (settings.lockAfter() >= 0) {
                    destConfig.set("lockAfterUses", settings.lockAfter());
                    mapped.add("Global: lockAfterUses=" + settings.lockAfter());
                }
                if (settings.setAsRespawn()) {
                    destConfig.set("setRespawnOnTeleport", true);
                    mapped.add("Global: setRespawnOnTeleport=true");
                }
                if (settings.rtpOnFirstJoin()) {
                    destConfig.set("rtpOnFirstJoin", true);
                    mapped.add("Global: rtpOnFirstJoin=true");
                }
                if (settings.rtpOnDeath()) {
                    destConfig.set("rtpOnDeath", true);
                    mapped.add("Global: rtpOnDeath=true");
                }
                if (settings.cancelOnMove()) {
                    destConfig.set("cancelOnMove", true);
                    mapped.add("Global: cancelOnMove=true");
                }
                if (settings.maxAttempts() >= 0) {
                    destConfig.set("maxAttempts", settings.maxAttempts());
                    mapped.add("Global: maxAttempts=" + settings.maxAttempts());
                }
                if (settings.cacheCap() >= 0) {
                    destConfig.set("cacheCap", settings.cacheCap());
                    mapped.add("Global: cacheCap=" + settings.cacheCap());
                }

                destConfig.save(destConfigPath.toFile());
                written.add(destConfigPath);
            } catch (IOException e) {
                warnings.add("Failed to update config.yml: " + e.getMessage());
            }
        }

        // Performance configuration updates (performance.yml)
        if (settings.maxAttempts() > 10 || settings.queueTargetSize() > 0) {
            Path destPerfPath = destinationDir.resolve("performance.yml");
            try {
                RtpYamlConfig destPerf;
                if (Files.exists(destPerfPath)) {
                    destPerf = RtpYamlConfig.load(destPerfPath.toFile());
                } else {
                    destPerf = new RtpYamlConfig();
                    destPerf.set("version", "1.0");
                }
                if (settings.maxAttempts() > 0) {
                    destPerf.set("maxAttempts", settings.maxAttempts());
                    mapped.add("Performance: maxAttempts=" + settings.maxAttempts());
                }
                if (settings.queueTargetSize() > 0) {
                    destPerf.set("queue.targetSize", settings.queueTargetSize());
                    mapped.add("Performance: queue.targetSize=" + settings.queueTargetSize());
                }
                destPerf.save(destPerfPath.toFile());
                written.add(destPerfPath);
            } catch (IOException e) {
                warnings.add("Failed to update performance.yml: " + e.getMessage());
            }
        }
    }

    /**
     * Prunes backup files exceeding a maximum retention threshold (keeps newest 5).
     */
    protected void pruneOldBackups(Path dir, String baseFileName) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> backups = stream
                    .filter(p -> p.getFileName().toString().startsWith(baseFileName + ".bak."))
                    .sorted(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }))
                    .toList();

            int excess = backups.size() - 5;
            for (int i = 0; i < excess; i++) {
                try {
                    Files.deleteIfExists(backups.get(i));
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
    }
}
