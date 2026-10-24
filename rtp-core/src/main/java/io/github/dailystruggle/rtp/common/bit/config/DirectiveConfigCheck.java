package io.github.dailystruggle.rtp.common.bit.config;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.rtp.common.bit.BitSeverityPolicy;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ADR-064 directive PBIT for one single-file {@code ConfigParser} owner (ADR-121 section 6).
 *
 * <p>Directives come from the bundled default resource at the same relative path (English
 * baseline), never from the operator's file, whose comments may be gone. On-disk top-level keys
 * map to canonical through the ADR-076 rename map. Checks: {@code @type}, {@code @options}
 * (case-insensitive), {@code @range}, unknown top-level keys, and a self-test that the bundled
 * value and {@code @default} satisfy their own directives. Unsure cases (unit-bearing strings,
 * {@code @source} registries, sections under a scalar directive) are skipped, not flagged.
 * A missing on-disk file or bundled resource yields no findings.
 */
public final class DirectiveConfigCheck implements ConfigOwnerCheck {
    public static final BitCode TYPE_MISMATCH = BitSeverityPolicy.stagedConfig(RtpBitCodes.RTP.register(1100,
            "RTP_DIRECTIVE_TYPE_MISMATCH", Level.ERROR, BitTier.PBIT, ConfigPbit.SUBSYSTEM, "",
            "Write a value of the type named by the key's @type directive in the bundled default file."));
    public static final BitCode OPTION_UNKNOWN = BitSeverityPolicy.stagedConfig(RtpBitCodes.RTP.register(1101,
            "RTP_DIRECTIVE_OPTION_UNKNOWN", Level.ERROR, BitTier.PBIT, ConfigPbit.SUBSYSTEM, "",
            "Use one of the values listed by the key's @options directive (case does not matter)."));
    public static final BitCode OUT_OF_RANGE = BitSeverityPolicy.stagedConfig(RtpBitCodes.RTP.register(1102,
            "RTP_DIRECTIVE_OUT_OF_RANGE", Level.ERROR, BitTier.PBIT, ConfigPbit.SUBSYSTEM, "",
            "Keep the value inside the key's @range [min, max] (inclusive; null is open-ended)."));
    public static final BitCode UNKNOWN_KEY = BitSeverityPolicy.stagedConfig(RtpBitCodes.RTP.register(1103,
            "RTP_DIRECTIVE_UNKNOWN_KEY", Level.WARNING, BitTier.PBIT, ConfigPbit.SUBSYSTEM, "",
            "Remove or rename the key; it is not read. Compare with the bundled default file."));
    public static final BitCode DEFAULT_INVALID = RtpBitCodes.RTP.register(1104,
            "RTP_DIRECTIVE_DEFAULT_INVALID", Level.ERROR, BitTier.PBIT, ConfigPbit.SUBSYSTEM, "",
            "Shipping bug: the bundled default value or @default violates its own directives; fix the resource.");
    public static final BitCode MALFORMED = RtpBitCodes.RTP.register(1105,
            "RTP_DIRECTIVE_MALFORMED", Level.ERROR, BitTier.PBIT, ConfigPbit.SUBSYSTEM, "",
            "Shipping bug: the bundled default does not parse or has an unreadable directive; fix the resource.");

    public static final DirectiveCodes CODES = new DirectiveCodes(
            TYPE_MISMATCH,
            OPTION_UNKNOWN,
            OUT_OF_RANGE,
            TYPE_MISMATCH,
            UNKNOWN_KEY,
            DEFAULT_INVALID,
            MALFORMED);

    /**
     * Core single-file owners, check id -> path relative to the plugin data folder and to the jar root.
     * Addon-owned files are bound by the addon itself via {@link #bound}, since their defaults live in
     * the addon jar, not on {@link ConfigEnvironment#resources()}.
     */
    static final Map<String, String> OWNER_FILES = ownerFiles();

    private final String id;
    private final String path;
    /** Bundled-default source; null = {@link ConfigEnvironment#resources()}. */
    private final ClassLoader resourceLoader;

    public DirectiveConfigCheck(String id, String path) {
        this(id, path, null);
    }

    private DirectiveConfigCheck(String id, String path, ClassLoader resources) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.path = Objects.requireNonNull(path, "path must not be null");
        this.resourceLoader = resources;
    }

    /**
     * Check for {@code path} whose bundled default (directives) is read from {@code resources}
     * instead of the environment loader; for addons registering their own file through
     * {@link ConfigPbit#register}.
     */
    public static DirectiveConfigCheck bound(String id, String path, ClassLoader resources) {
        return new DirectiveConfigCheck(id, path, Objects.requireNonNull(resources, "resources must not be null"));
    }

    private static Map<String, String> ownerFiles() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("config", "config.yml");
        files.put("safety", "safety.yml");
        files.put("economy", "economy.yml");
        files.put("blocks", "advanced/blocks.yml");
        files.put("biomes", "advanced/biomes.yml");
        files.put("logging", "advanced/logging.yml");
        files.put("database", "advanced/database.yml");
        files.put("performance", "advanced/performance.yml");
        files.put("metrics", "advanced/metrics.yml");
        files.put("ttl", "advanced/ttl.yml");
        files.put("bit", "advanced/bit.yml");
        return Collections.unmodifiableMap(files);
    }

    /** One check per {@link #OWNER_FILES} entry, in declaration order. */
    public static List<ConfigOwnerCheck> owners() {
        List<ConfigOwnerCheck> out = new ArrayList<>();
        OWNER_FILES.forEach((ownerId, file) -> out.add(new DirectiveConfigCheck(ownerId, file)));
        return List.copyOf(out);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean owns(String candidate) {
        return path.equals(candidate);
    }

    public String path() {
        return path;
    }

    @Override
    public List<BitFinding> check(ConfigSnapshot snapshot, ConfigEnvironment environment) {
        ClassLoader loader = this.resourceLoader;
        if (loader == null) {
            loader = environment == null ? ConfigEnvironment.HEADLESS.resources() : environment.resources();
        }
        return DirectiveValidator.validate(snapshot, path, loader, CODES);
    }
}
