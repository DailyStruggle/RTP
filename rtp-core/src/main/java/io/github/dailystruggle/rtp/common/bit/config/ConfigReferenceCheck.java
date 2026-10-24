package io.github.dailystruggle.rtp.common.bit.config;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.bit.Vocabulary;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.bit.BitSeverityPolicy;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.common.configuration.ConfigDefaultResolver;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.tasks.teleport.RegionSchematicService;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owner {@code references}: ADR-073 {@code @<file>} tokens and cross-file name references
 * (ADR-121 section 6).
 *
 * <p>Unresolved references are staged ({@link BitSeverityPolicy#stagedConfig}): the loaders fall
 * back (default parser, dormant region, hard-coded default), so such configs loaded before and stay
 * WARNING unless {@code strictConfig}. World names are checked only against already-loaded worlds;
 * without a world list the check reports one INFO, never a missing world (S-005: no world loads).
 * Action {@code placement.region} is {@code ActionDefinitionValidator}'s.
 */
public final class ConfigReferenceCheck implements ConfigOwnerCheck {
    private static final String SUBSYSTEM = "config";
    static final String REGIONS = "definitions/regions";
    static final String WORLDS = "definitions/worlds";
    static final String ACTIONS = "definitions/actions";
    static final String CONFIG_FILE = "config.yml";
    private static final Pattern WORLD_INDEX = Pattern.compile("^\\[(\\d+)]$");
    private static final List<String> SCHEMATIC_EXTENSIONS = List.of(".schem", ".schematic");

    /** Files a {@code @<file>} token may name; mirrors {@link ConfigDefaultResolver#resolve}. */
    public static final Vocabulary REFERENCE_FILES = new Vocabulary("reference file",
            List.of("config", "economy", "safety"));

    public static final BitCode REFERENCE_UNKNOWN_FILE = BitSeverityPolicy.stagedConfig(RtpBitCodes.RTP.register(
            1201, "RTP_REFERENCE_UNKNOWN_FILE", Level.ERROR, BitTier.PBIT, SUBSYSTEM, "",
            "Use @config, @economy or @safety, or replace the token with a literal value."));
    public static final BitCode REFERENCE_SETTING_MISSING = BitSeverityPolicy.stagedConfig(RtpBitCodes.RTP.register(
            1202, "RTP_REFERENCE_SETTING_MISSING", Level.ERROR, BitTier.PBIT, SUBSYSTEM, "",
            "Add the setting to the referenced file (config.yml: under defaults) or use a literal value."));
    public static final BitCode REGION_UNKNOWN_WORLD = BitSeverityPolicy.stagedConfig(RtpBitCodes.RTP.register(
            1301, "RTP_REGION_UNKNOWN_WORLD", Level.ERROR, BitTier.PBIT, SUBSYSTEM, "",
            "Name a loaded world (or [index]); a region on an unloaded world stays dormant until it loads."));
    public static final BitCode WORLD_UNKNOWN_REGION = BitSeverityPolicy.stagedConfig(RtpBitCodes.RTP.register(
            1302, "RTP_WORLD_UNKNOWN_REGION", Level.ERROR, BitTier.PBIT, SUBSYSTEM, "",
            "Name a region file in definitions/regions; an unknown region falls back to default."));
    public static final BitCode REGION_UNKNOWN_OVERRIDE = BitSeverityPolicy.stagedConfig(RtpBitCodes.RTP.register(
            1303, "RTP_REGION_UNKNOWN_OVERRIDE", Level.ERROR, BitTier.PBIT, SUBSYSTEM, "",
            "Name a region file in definitions/regions as the no-permission override."));
    public static final BitCode WORLD_UNKNOWN_OVERRIDE = BitSeverityPolicy.stagedConfig(RtpBitCodes.RTP.register(
            1304, "RTP_WORLD_UNKNOWN_OVERRIDE", Level.ERROR, BitTier.PBIT, SUBSYSTEM, "",
            "Name a loaded world, a definitions/worlds file or [index] as the no-permission override."));
    public static final BitCode SCHEMATIC_ORPHAN = RtpBitCodes.RTP.register(
            1305, "RTP_SCHEMATIC_ORPHAN", Level.WARNING, BitTier.PBIT, SUBSYSTEM, "",
            "Rename the schematic to <region>.schem, reference it from an action placement.schematic, or remove it.");
    public static final BitCode WORLD_REFERENCES_UNVERIFIED = RtpBitCodes.RTP.register(
            1306, "RTP_WORLD_REFERENCES_UNVERIFIED", Level.INFO, BitTier.PBIT, SUBSYSTEM, "",
            "Run the check on a server (or pass loaded world names) to verify world references.");

    public static final ReferenceSettingCheck.Codes CODES = new ReferenceSettingCheck.Codes(
            REFERENCE_UNKNOWN_FILE, REFERENCE_SETTING_MISSING);
    private static final ReferenceSettingCheck FILE_REFERENCE_CHECK = new ReferenceSettingCheck(
            REFERENCE_FILES, CODES);

    public static final ConfigReferenceCheck INSTANCE = new ConfigReferenceCheck();

    private ConfigReferenceCheck() {}

    @Override
    public String id() {
        return "references";
    }

    @Override
    public boolean owns(String path) {
        return inDirectory(path, REGIONS) || inDirectory(path, WORLDS) || inDirectory(path, ACTIONS);
    }

    static boolean inDirectory(String path, String directory) {
        return path != null && path.startsWith(directory + "/") && path.indexOf('/', directory.length() + 1) < 0;
    }

    @Override
    public List<BitFinding> check(ConfigSnapshot snapshot, ConfigEnvironment environment) {
        List<BitFinding> findings = new ArrayList<>();
        if (snapshot == null) return findings;
        ConfigEnvironment env = environment == null ? ConfigEnvironment.HEADLESS : environment;
        checkFileReferences(snapshot, env, findings);
        Set<String> regions = stems(snapshot, REGIONS);
        Set<String> worldFiles = stems(snapshot, WORLDS);
        List<String> unverified = new ArrayList<>();
        checkRegions(snapshot, env, regions, unverified, findings);
        checkWorlds(snapshot, env, regions, worldFiles, unverified, findings);
        checkSchematics(snapshot, regions, findings);
        if (!unverified.isEmpty()) {
            findings.add(BitFinding.of(WORLD_REFERENCES_UNVERIFIED, unverified.get(0), "",
                    unverified.size() + " world reference(s) not verified: loaded worlds are unknown here",
                    "a loaded-world list", WORLD_REFERENCES_UNVERIFIED.remediation()));
        }
        return findings;
    }

    private static void checkFileReferences(ConfigSnapshot snapshot, ConfigEnvironment env, List<BitFinding> findings) {
        ReferenceSettingCheck.SettingResolver resolver = (file, setting) -> resolves(snapshot, env, file, setting);
        for (ConfigSnapshot.Document document : snapshot.documentsIn(REGIONS).values()) {
            FILE_REFERENCE_CHECK.checkDocument(document, Set.of(), resolver, findings);
        }
        for (ConfigSnapshot.Document document : snapshot.documentsIn(WORLDS).values()) {
            FILE_REFERENCE_CHECK.checkDocument(document, Set.of(), resolver, findings);
        }
        snapshot.document(CONFIG_FILE).ifPresent(config ->
                FILE_REFERENCE_CHECK.checkDocument(config, Set.of(ConfigKeys.defaults.name()), resolver, findings));
    }

    /** Whether {@code setting} exists in the referenced file; empty when the file is unknown here. */
    private static Optional<Boolean> resolves(ConfigSnapshot snapshot, ConfigEnvironment env, String file,
                                              String setting) {
        switch (file) {
            case "config": {
                Optional<ConfigSnapshot.Document> config = documentOrBundled(snapshot, env, CONFIG_FILE);
                if (config.isEmpty()) return Optional.empty();
                Map<?, ?> defaults = ConfigConstraint.asMap(config.get().get(ConfigKeys.defaults.name()));
                if (defaults != null && defaults.containsKey(setting)) return Optional.of(true);
                if (RegionKeys.cooldown.name().equalsIgnoreCase(setting)) {
                    return Optional.of(config.get().get(ConfigKeys.teleportCooldown.name()) != null);
                }
                if (RegionKeys.delay.name().equalsIgnoreCase(setting)) {
                    return Optional.of(config.get().get(ConfigKeys.teleportDelay.name()) != null);
                }
                return Optional.of(false);
            }
            case "economy":
                return resolvesInParser(snapshot, env, "economy.yml", EconomyKeys.class, setting);
            case "safety":
                return resolvesInParser(snapshot, env, "safety.yml", SafetyKeys.class, setting);
            default:
                return Optional.of(false);
        }
    }

    private static <E extends Enum<E>> Optional<Boolean> resolvesInParser(
            ConfigSnapshot snapshot, ConfigEnvironment env, String path, Class<E> keys, String setting) {
        try {
            Enum.valueOf(keys, setting);
        } catch (IllegalArgumentException e) {
            return Optional.of(false);
        }
        Optional<ConfigSnapshot.Document> document = documentOrBundled(snapshot, env, path);
        return document.map(found -> found.get(setting) != null);
    }

    /** Snapshot document, else the bundled default; empty when neither is readable. */
    static Optional<ConfigSnapshot.Document> documentOrBundled(ConfigSnapshot snapshot, ConfigEnvironment env,
                                                               String path) {
        Optional<ConfigSnapshot.Document> document = snapshot.document(path);
        if (document.isPresent() || snapshot.exists(path)) return document;
        try (InputStream in = env.resources().getResourceAsStream(path)) {
            if (in == null) return Optional.empty();
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Optional.of(new ConfigSnapshot.Document(path, RtpYamlConfig.parse(text), Map.of()));
        } catch (IOException | RuntimeException e) {
            RTP.log(java.util.logging.Level.FINE, "[RTP bit] bundled " + path + " unreadable for reference check", e);
            return Optional.empty();
        }
    }

    private static void checkRegions(ConfigSnapshot snapshot, ConfigEnvironment env, Set<String> regions,
                                     List<String> unverified, List<BitFinding> findings) {
        for (ConfigSnapshot.Document region : snapshot.documentsIn(REGIONS).values()) {
            Object world = region.get(RegionKeys.world.name());
            if (isName(world)) {
                String name = String.valueOf(world).trim();
                Optional<Set<String>> loaded = env.worlds();
                if (loaded.isEmpty()) {
                    unverified.add(region.path());
                } else if (!worldResolves(name, loaded.get())) {
                    findings.add(BitFinding.of(REGION_UNKNOWN_WORLD, region.path(),
                            region.localizedKey(RegionKeys.world.name()),
                            "Region world is not loaded: " + name, describe(loaded.get()),
                            suggest("world", loaded.get(), name, REGION_UNKNOWN_WORLD)));
                }
            }
            Object override = region.get(RegionKeys.override.name());
            if (isName(override) && !regions.isEmpty() && !containsIgnoreCase(regions, String.valueOf(override).trim())) {
                String name = String.valueOf(override).trim();
                findings.add(BitFinding.of(REGION_UNKNOWN_OVERRIDE, region.path(),
                        region.localizedKey(RegionKeys.override.name()),
                        "Region override names no region file: " + name, describe(regions),
                        suggest("region", regions, name, REGION_UNKNOWN_OVERRIDE)));
            }
        }
    }

    private static void checkWorlds(ConfigSnapshot snapshot, ConfigEnvironment env, Set<String> regions,
                                    Set<String> worldFiles, List<String> unverified, List<BitFinding> findings) {
        for (ConfigSnapshot.Document world : snapshot.documentsIn(WORLDS).values()) {
            Object region = world.get(WorldKeys.region.name());
            if (isName(region) && !regions.isEmpty() && !containsIgnoreCase(regions, String.valueOf(region).trim())) {
                String name = String.valueOf(region).trim();
                findings.add(BitFinding.of(WORLD_UNKNOWN_REGION, world.path(),
                        world.localizedKey(WorldKeys.region.name()),
                        "World region names no region file: " + name, describe(regions),
                        suggest("region", regions, name, WORLD_UNKNOWN_REGION)));
            }
            Object override = world.get(WorldKeys.override.name());
            if (isName(override)) {
                String name = String.valueOf(override).trim();
                if (WORLD_INDEX.matcher(name).matches() || !containsIgnoreCase(worldFiles, name)) {
                    Optional<Set<String>> loaded = env.worlds();
                    if (loaded.isEmpty()) {
                        unverified.add(world.path());
                    } else if (!worldResolves(name, loaded.get())) {
                        Set<String> known = new TreeSet<>(loaded.get());
                        known.addAll(worldFiles);
                        findings.add(BitFinding.of(WORLD_UNKNOWN_OVERRIDE, world.path(),
                                world.localizedKey(WorldKeys.override.name()),
                                "World override names no loaded world or world file: " + name, describe(known),
                                suggest("world", known, name, WORLD_UNKNOWN_OVERRIDE)));
                    }
                }
            }
        }
    }

    private static void checkSchematics(ConfigSnapshot snapshot, Set<String> regions, List<BitFinding> findings) {
        if (regions.isEmpty()) return;
        String prefix = RegionSchematicService.SCHEMATICS_DIRNAME + "/";
        Set<String> actionSchematics = actionSchematics(snapshot);
        for (String file : snapshot.files()) {
            if (file.startsWith(prefix)) {
                String name = stripSchematicExtension(file.substring(prefix.length()));
                if (name != null
                        && !(name.indexOf('/') < 0 && containsIgnoreCase(regions, name))
                        && !actionSchematics.contains(name.toLowerCase(Locale.ROOT))) {
                    findings.add(BitFinding.of(SCHEMATIC_ORPHAN, file, "",
                            "Schematic matches no region and no action placement.schematic: " + name,
                            "<region>.schem for one of: " + describe(regions), SCHEMATIC_ORPHAN.remediation()));
                }
            }
        }
    }

    private static Set<String> actionSchematics(ConfigSnapshot snapshot) {
        Set<String> names = new TreeSet<>();
        for (ConfigSnapshot.Document action : snapshot.documentsIn(ACTIONS).values()) {
            Map<?, ?> placement = ConfigConstraint.asMap(action.get("placement"));
            Object schematic = placement == null ? null : placement.get("schematic");
            if (schematic == null || String.valueOf(schematic).isBlank()) continue;
            String raw = String.valueOf(schematic).trim().replace('\\', '/');
            while (raw.startsWith("./")) raw = raw.substring(2);
            String stripped = stripSchematicExtension(raw);
            names.add((stripped == null ? raw : stripped).toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private static String stripSchematicExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : SCHEMATIC_EXTENSIONS) {
            if (lower.endsWith(extension) && lower.length() > extension.length()) {
                return name.substring(0, name.length() - extension.length());
            }
        }
        return null;
    }

    private static boolean worldResolves(String name, Set<String> loaded) {
        Matcher index = WORLD_INDEX.matcher(name);
        if (index.matches()) {
            try {
                return Integer.parseInt(index.group(1)) < loaded.size();
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return containsIgnoreCase(loaded, name);
    }

    private static boolean isName(Object value) {
        return value instanceof String text && !text.isBlank() && !ConfigDefaultResolver.isReference(text);
    }

    private static Set<String> stems(ConfigSnapshot snapshot, String directory) {
        Set<String> stems = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (ConfigSnapshot.Document document : snapshot.documentsIn(directory).values()) stems.add(document.stem());
        return stems;
    }

    private static boolean containsIgnoreCase(Set<String> values, String value) {
        for (String candidate : values) {
            if (candidate.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    private static String describe(Set<String> values) {
        return String.join(", ", new TreeSet<>(values));
    }

    private static String suggest(String vocabulary, Set<String> values, String value, BitCode code) {
        return new Vocabulary(vocabulary, values).suggest(value).orElse(code.remediation());
    }
}
