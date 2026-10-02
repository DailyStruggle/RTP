package io.github.dailystruggle.rtp.common.importer;

import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Universal heuristic foreign configuration importer capable of importing
 * from any random teleport plugin directory by automatically detecting
 * its configuration topology and extracting world, region, safety, and global settings.
 */
public class UniversalConfigImporter extends AbstractForeignConfigImporter {

    public static final String SOURCE_NAME = "universal";

    private static final List<String> COMMON_SUBDIRS = List.of(
            "rtpSettings",
            "distributions",
            "worlds",
            "regions",
            "locations",
            "zones",
            "settings"
    );

    private static final String[] CANDIDATE_SECTION_KEYS = new String[]{
            "CustomWorlds",
            "worlds",
            "Worlds",
            "gui.worlds",
            "locations",
            "custom-locations",
            "zones",
            "CustomZones",
            "regions",
            "world-limits",
            "limits",
            "rtp-limits"
    };

    @Override
    public String sourceName() {
        return SOURCE_NAME;
    }

    @Override
    public List<String> directoryAliases() {
        return List.of("universal", "generic", "auto");
    }

    @Override
    public List<String> indicatorFiles() {
        return List.of("config.yml", "rtp.yml", "settings.yml");
    }

    @Override
    public boolean canImport(Path sourcePluginDir) {
        if (sourcePluginDir == null || !Files.isDirectory(sourcePluginDir)) {
            return false;
        }

        // Check if primary candidate file exists
        for (String f : indicatorFiles()) {
            if (Files.exists(sourcePluginDir.resolve(f))) {
                return true;
            }
        }

        // Check if any subdirectories have yml files
        for (String sub : COMMON_SUBDIRS) {
            Path subPath = sourcePluginDir.resolve(sub);
            if (Files.isDirectory(subPath)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(subPath, "*.{yml,yaml}")) {
                    if (stream.iterator().hasNext()) {
                        return true;
                    }
                } catch (IOException ignored) {}
            }
        }

        // Or if root contains any .yml / .yaml file with typical RTP markers
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sourcePluginDir, "*.{yml,yaml}")) {
            for (Path p : stream) {
                RtpYamlConfig cfg = loadYamlSafe(p, new ArrayList<>());
                if (cfg != null && hasRtpMarkers(cfg)) {
                    return true;
                }
            }
        } catch (IOException ignored) {}

        return false;
    }

    @Override
    public ImportResult importConfiguration(Path sourcePluginDir, Path destinationDir, boolean overwrite) {
        List<Path> createdFiles = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> mappedEntities = new ArrayList<>();

        if (!canImport(sourcePluginDir)) {
            errors.add("Directory " + sourcePluginDir + " does not contain any recognizable RTP configuration files.");
            return new ImportResult(false, sourceName(), createdFiles, warnings, errors, mappedEntities);
        }

        // 1. Gather all YAML configs in root and known subdirectories
        Map<String, RtpYamlConfig> rootConfigs = new LinkedHashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sourcePluginDir, "*.{yml,yaml}")) {
            for (Path p : stream) {
                RtpYamlConfig cfg = loadYamlSafe(p, warnings);
                if (cfg != null) {
                    rootConfigs.put(p.getFileName().toString().toLowerCase(Locale.ROOT), cfg);
                }
            }
        } catch (IOException e) {
            warnings.add("Failed to scan root configs: " + e.getMessage());
        }

        // Find primary config
        RtpYamlConfig primaryConfig = rootConfigs.get("config.yml");
        if (primaryConfig == null) {
            primaryConfig = rootConfigs.get("rtp.yml");
        }
        if (primaryConfig == null && !rootConfigs.isEmpty()) {
            primaryConfig = rootConfigs.values().iterator().next();
        }

        // 2. Discover world/region sections across topologies
        Map<String, RtpYamlSection> worldSections = new LinkedHashMap<>();

        // Topology 1: Check known subdirectories (e.g. rtpSettings, worlds, distributions)
        for (String sub : COMMON_SUBDIRS) {
            Map<String, RtpYamlConfig> subConfigs = loadDirectoryConfigs(sourcePluginDir.resolve(sub), warnings);
            if (!subConfigs.isEmpty()) {
                for (Map.Entry<String, RtpYamlConfig> entry : subConfigs.entrySet()) {
                    worldSections.put(entry.getKey(), entry.getValue());
                }
            }
        }

        // Topology 2 & 3: Check candidate sections in all root configs
        if (worldSections.isEmpty()) {
            for (RtpYamlConfig cfg : rootConfigs.values()) {
                Map<String, RtpYamlSection> discovered = discoverWorldSections(
                        sourcePluginDir,
                        cfg,
                        null,
                        CANDIDATE_SECTION_KEYS
                );
                if (!discovered.isEmpty()) {
                    worldSections.putAll(discovered);
                }
            }
        }

        // Collect disabled worlds across all root configs (e.g. DisabledWorlds, blacklisted-worlds: [events, admin_world, dungeon])
        Set<String> disabledWorlds = new HashSet<>();
        for (RtpYamlConfig cfg : rootConfigs.values()) {
            List<String> dwList = getStringListCaseInsensitive(cfg,
                    "DisabledWorlds", "disabledworlds", "disabled-worlds", "blacklisted-worlds", "blacklisted_worlds");
            disabledWorlds.addAll(dwList);
        }

        // Topology 4: Fallback to root configs themselves if they define RTP coordinates/radii
        if (worldSections.isEmpty()) {
            // Check if any root config defines worlds list (e.g. EzRTP's rtp.yml worlds: [world],
            // AdvancedRTP's default-worlds: [world], or AsyRTP's only-allow-in-worlds: [world])
            List<String> declaredWorlds = new ArrayList<>();
            for (RtpYamlConfig cfg : rootConfigs.values()) {
                List<String> wList = getStringListCaseInsensitive(cfg,
                        "worlds", "default-worlds", "default_worlds", "only-allow-in-worlds", "allowed-worlds");
                if (wList != null && !wList.isEmpty()) {
                    declaredWorlds.addAll(wList);
                }
            }

            // Create a merged root config overlaying all root configs (e.g. rtp.yml + config.yml + limits.yml)
            RtpYamlConfig mergedRoot = new RtpYamlConfig();
            for (RtpYamlConfig cfg : rootConfigs.values()) {
                for (String k : cfg.getKeys(true)) {
                    if (!mergedRoot.contains(k)) {
                        mergedRoot.set(k, cfg.get(k));
                    }
                }
            }

            if (!declaredWorlds.isEmpty()) {
                for (String w : declaredWorlds) {
                    worldSections.put(w, mergedRoot);
                }
            } else {
                for (Map.Entry<String, RtpYamlConfig> entry : rootConfigs.entrySet()) {
                    RtpYamlConfig cfg = entry.getValue();
                    if (hasRtpMarkers(cfg)) {
                        String worldName = getStringCaseInsensitive(cfg, "world", "World", "world-name", "name");
                        if (worldName == null || worldName.isBlank()) {
                            String stem = entry.getKey();
                            int dot = stem.lastIndexOf('.');
                            worldName = dot > 0 ? stem.substring(0, dot) : stem;
                            if (worldName.equalsIgnoreCase("config") || worldName.equalsIgnoreCase("rtp")) {
                                worldName = "world";
                            }
                        }
                        worldSections.put(worldName, mergedRoot);
                        break;
                    }
                }
            }
        }

        // If still empty, default to "world" mapped against primaryConfig
        if (worldSections.isEmpty() && primaryConfig != null) {
            worldSections.put("world", primaryConfig);
        }

        // 3. Extract DiscoveredWorldRegion models
        List<DiscoveredWorldRegion> discoveredList = new ArrayList<>();
        int defaultMaxRadius = 5000;
        int defaultMinRadius = 100;
        int defaultCenterX = 0;
        int defaultCenterZ = 0;
        String defaultShape = "CIRCLE";

        // Check if primaryConfig defines default radii or centers (like BetterRTP's Default or AsyRTP's teleport)
        RtpYamlSection defaultSec = getSectionCaseInsensitive(primaryConfig, "Default", "teleport");
        if (defaultSec != null) {
            defaultMaxRadius = getIntCaseInsensitive(defaultSec, defaultMaxRadius, "MaxRadius", "max-radius", "radius", "max");
            defaultMinRadius = getIntCaseInsensitive(defaultSec, defaultMinRadius, "MinRadius", "min-radius", "min");
            defaultCenterX = getIntCaseInsensitive(defaultSec, defaultCenterX, "CenterX", "center-x", "center.x", "x");
            defaultCenterZ = getIntCaseInsensitive(defaultSec, defaultCenterZ, "CenterZ", "center-z", "center.z", "z");
            defaultShape = mapShape(getStringCaseInsensitive(defaultSec, defaultShape, "shape", "distribution", "pattern"));
        }

        // Also check root for centers/radii/shapes
        defaultCenterX = getIntCaseInsensitive(primaryConfig, defaultCenterX, "CenterX", "center-x", "center.x", "x");
        defaultCenterZ = getIntCaseInsensitive(primaryConfig, defaultCenterZ, "CenterZ", "center-z", "center.z", "z");
        defaultShape = mapShape(getStringCaseInsensitive(primaryConfig, defaultShape, "shape", "distribution", "pattern"));

        for (Map.Entry<String, RtpYamlSection> entry : worldSections.entrySet()) {
            String targetName = entry.getKey();
            RtpYamlSection sec = entry.getValue();

            // Skip non-world utility sections if detected as child
            if ((targetName.equalsIgnoreCase("default") || targetName.equalsIgnoreCase("teleport")) && worldSections.size() > 1) {
                continue;
            }

            // Skip worlds present in disabled worlds list
            if (disabledWorlds.contains(targetName)) {
                warnings.add("World '" + targetName + "' is in disabled worlds list, skipping import.");
                continue;
            }

            DiscoveredWorldRegion defaults = new DiscoveredWorldRegion(
                    targetName, targetName, defaultShape, defaultMinRadius, defaultMaxRadius, defaultCenterX, defaultCenterZ, 64, 320, 0.0, null
            );

            // If price wasn't defined in the section, check if any root config defined cost/price globally
            double globalPrice = 0.0;
            for (RtpYamlConfig cfg : rootConfigs.values()) {
                double p = getDoubleCaseInsensitive(cfg, -1.0, "cost", "price", "vault-cost");
                if (p > globalPrice) globalPrice = p;
            }
            if (globalPrice > 0.0) {
                defaults = new DiscoveredWorldRegion(
                        defaults.name(), defaults.world(), defaults.shape(),
                        defaults.minRadius(), defaults.maxRadius(),
                        defaults.centerX(), defaults.centerZ(),
                        defaults.minY(), defaults.maxY(),
                        globalPrice, defaults.biomes()
                );
            }

            DiscoveredWorldRegion extracted = extractWorldRegion(targetName, sec, defaults);

            // Check for additional worlds or call-from-worlds
            List<String> additionalWorlds = getStringListCaseInsensitive(sec, "call-from-worlds", "additional-worlds", "worlds");
            if (!additionalWorlds.isEmpty()) {
                extracted = new DiscoveredWorldRegion(
                        extracted.name(),
                        extracted.world(),
                        extracted.shape(),
                        extracted.minRadius(),
                        extracted.maxRadius(),
                        extracted.centerX(),
                        extracted.centerZ(),
                        extracted.minY(),
                        extracted.maxY(),
                        extracted.price(),
                        extracted.cacheCap(),
                        extracted.biomes(),
                        additionalWorlds
                );
            }

            discoveredList.add(extracted);
        }

        if (discoveredList.isEmpty()) {
            discoveredList.add(new DiscoveredWorldRegion(
                    "world", "world", "CIRCLE", defaultMinRadius, defaultMaxRadius, 0, 0, 64, 320, 0.0, null
            ));
        }

        emitRegionsAndWorlds(discoveredList, destinationDir, overwrite, createdFiles, mappedEntities, errors);

        // 4. Global settings extraction across root configs
        long cooldown = -1L;
        long delay = -1L;
        long lockAfter = -1L;
        boolean cancelOnMove = false;
        boolean setRespawn = false;
        boolean rtpOnFirstJoin = false;
        boolean rtpOnDeath = false;
        int maxAttempts = -1;
        int queueTargetSize = -1;

        for (RtpYamlConfig cfg : rootConfigs.values()) {
            long cd = getLongCaseInsensitive(cfg, -1L, "cooldown", "teleport-cooldown", "rtp-cooldown", "teleportCooldown", "cooldown-seconds");
            long del = getLongCaseInsensitive(cfg, -1L, "delay", "teleport-delay", "teleportDelay", "warmup", "delay-seconds");
            long lock = getLongCaseInsensitive(cfg, -1L, "lock-after", "lockAfter", "lockafter");

            // Also inspect nested "settings" / "teleportation" / "teleport" / "cooldown" / "delay" sections if present
            RtpYamlSection settingsSec = getSectionCaseInsensitive(cfg, "settings", "teleportation", "teleport", "general");
            RtpYamlSection rootCdSec = getSectionCaseInsensitive(cfg, "cooldown");
            if (rootCdSec != null) {
                long nestedCd = getLongCaseInsensitive(rootCdSec, -1L, "duration", "time", "seconds", "cooldown", "fallback-seconds");
                if (nestedCd > cd) cd = nestedCd;
                long nestedLock = getLongCaseInsensitive(rootCdSec, -1L, "lockafter", "lock-after", "lock");
                if (nestedLock > lock) lock = nestedLock;
            }
            RtpYamlSection rootDelSec = getSectionCaseInsensitive(cfg, "delay", "warmup");
            if (rootDelSec != null) {
                long nestedDel = getLongCaseInsensitive(rootDelSec, -1L, "duration", "time", "seconds", "delay");
                if (nestedDel > del) del = nestedDel;
                if (getBooleanCaseInsensitive(rootDelSec, false, "cancelonmove", "cancel-on-move")) {
                    cancelOnMove = true;
                }
            }
            if (settingsSec != null) {
                RtpYamlSection cdSec = getSectionCaseInsensitive(settingsSec, "cooldown");
                if (cdSec != null) {
                    long nestedCd = getLongCaseInsensitive(cdSec, -1L, "duration", "time", "seconds", "cooldown", "fallback-seconds");
                    if (nestedCd > cd) cd = nestedCd;
                    long nestedLock = getLongCaseInsensitive(cdSec, -1L, "lockafter", "lock-after", "lock");
                    if (nestedLock > lock) lock = nestedLock;
                } else {
                    long nestedCd = getLongCaseInsensitive(settingsSec, -1L, "cooldown", "teleport-cooldown", "time", "cooldown-seconds", "fallback-seconds", "duration");
                    if (nestedCd > cd) cd = nestedCd;
                }

                RtpYamlSection delSec = getSectionCaseInsensitive(settingsSec, "delay", "warmup");
                if (delSec != null) {
                    long nestedDel = getLongCaseInsensitive(delSec, -1L, "duration", "time", "seconds", "delay");
                    if (nestedDel > del) del = nestedDel;
                    if (getBooleanCaseInsensitive(delSec, false, "cancelonmove", "cancel-on-move")) {
                        cancelOnMove = true;
                    }
                } else {
                    long nestedDel = getLongCaseInsensitive(settingsSec, -1L, "delay", "teleport-delay", "warmup", "time", "duration");
                    if (nestedDel > del) del = nestedDel;
                }

                if (getBooleanCaseInsensitive(settingsSec, false, "cancel-on-move", "cancelOnMove", "cancel-on-motion")) {
                    cancelOnMove = true;
                }
                if (getBooleanCaseInsensitive(settingsSec, false, "set-respawn", "setRespawnOnTeleport", "setasrespawn", "respawn-on-rtp")) {
                    setRespawn = true;
                }

                RtpYamlSection firstJoinSec = getSectionCaseInsensitive(settingsSec, "rtponfirstjoin", "rtp-on-first-join", "first-join");
                if (firstJoinSec != null) {
                    if (getBooleanCaseInsensitive(firstJoinSec, false, "enabled")) {
                        rtpOnFirstJoin = true;
                    }
                    if (getBooleanCaseInsensitive(firstJoinSec, false, "setasrespawn", "set-as-respawn", "set-respawn")) {
                        setRespawn = true;
                    }
                } else if (getBooleanCaseInsensitive(settingsSec, false, "first-join", "rtp-on-first-join", "on-first-join")) {
                    rtpOnFirstJoin = true;
                }
                if (getBooleanCaseInsensitive(settingsSec, false, "death-rtp", "rtp-on-death", "on-death")) {
                    rtpOnDeath = true;
                }
            }

            if (cd > cooldown) cooldown = cd;
            if (del > delay) delay = del;
            if (lock > lockAfter) lockAfter = lock;

            if (getBooleanCaseInsensitive(cfg, false, "cancel-on-move", "cancelOnMove", "cancel-on-motion")) {
                cancelOnMove = true;
            }
            if (getBooleanCaseInsensitive(cfg, false, "set-respawn", "setRespawnOnTeleport", "setasrespawn", "respawn-on-rtp")) {
                setRespawn = true;
            }
            if (getBooleanCaseInsensitive(cfg, false, "rtp-on-first-join", "rtpOnFirstJoin", "on-first-join", "first-join")) {
                rtpOnFirstJoin = true;
            }
            if (getBooleanCaseInsensitive(cfg, false, "rtp-on-death", "rtpOnDeath", "on-death", "death-rtp")) {
                rtpOnDeath = true;
            }

            int attempts = getIntCaseInsensitive(cfg, -1, "max-attempts", "maxAttempts", "max-tries", "retries", "attempts");
            if (attempts > maxAttempts) maxAttempts = attempts;

            int targetQueue = getIntCaseInsensitive(cfg, -1, "target-cache-size", "cache-size", "queue-size", "queue.targetSize");
            if (targetQueue > queueTargetSize) queueTargetSize = targetQueue;
        }

        if (cooldown >= 0 || delay >= 0 || lockAfter >= 0 || cancelOnMove || setRespawn || rtpOnFirstJoin || rtpOnDeath || maxAttempts > 0 || queueTargetSize > 0) {
            GlobalSettings gs = GlobalSettings.builder()
                    .cooldown(cooldown)
                    .delay(delay)
                    .lockAfter(lockAfter)
                    .cancelOnMove(cancelOnMove)
                    .setAsRespawn(setRespawn)
                    .rtpOnFirstJoin(rtpOnFirstJoin)
                    .rtpOnDeath(rtpOnDeath)
                    .maxAttempts(maxAttempts)
                    .queueTargetSize(queueTargetSize)
                    .build();
            updateDestinationConfig(destinationDir, gs, mappedEntities, createdFiles, warnings);
        }

        // 5. Mirror database & effects if found in any root config
        for (RtpYamlConfig cfg : rootConfigs.values()) {
            mirrorDatabaseConfig(cfg, destinationDir, overwrite, mappedEntities, createdFiles, warnings);
            mirrorEffectsConfig(cfg, destinationDir, overwrite, mappedEntities, createdFiles, warnings);
        }

        boolean success = errors.isEmpty() && !createdFiles.isEmpty();
        String resultSource = sourcePluginDir.getFileName() != null ? sourcePluginDir.getFileName().toString() : sourceName();
        return new ImportResult(success, resultSource, createdFiles, warnings, errors, mappedEntities);
    }

    private boolean hasRtpMarkers(RtpYamlConfig cfg) {
        if (cfg == null) return false;
        return findValueFuzzy(cfg,
                "radius", "maxradius", "max-radius", "minradius", "min-radius",
                "center", "centerX", "center-x",
                "cooldown", "teleport-delay", "teleportCooldown",
                "CustomWorlds", "worlds", "locations", "zones") != null;
    }
}
