package io.github.dailystruggle.rtp.common.bit.config;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitCodes;
import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.bit.BuiltInTestable;
import io.github.dailystruggle.bit.PbitResult;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.bit.BitSeverityPolicy;
import io.github.dailystruggle.rtp.common.bit.RtpBit;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.io.File;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry and runner for configuration PBIT (ADR-121 sections 6 and 7).
 *
 * <p>Each {@link ConfigOwnerCheck} is exposed as a SAFE PBIT {@link BitComponent}
 * {@code config-<id>} (subsystem {@code config}) in {@link RtpBit#RUNNER}, so startup, {@code /rtp
 * test} and the headless task share one code path, and as a {@link BuiltInTestable} view. The
 * reload gate ({@link ConfigCandidateGate}) runs the same checks over a candidate snapshot. Every
 * finding passes through {@link BitSeverityPolicy}.
 */
public final class ConfigPbit {
    public static final String SUBSYSTEM = "config";
    private static final long COMPONENT_TIMEOUT_MILLIS = 5_000;

    public static final BitCode YAML_UNPARSABLE = RtpBitCodes.RTP.register(1001,
            "RTP_YAML_UNPARSABLE", Level.ERROR, BitTier.PBIT, SUBSYSTEM, "",
            "Fix the YAML syntax in source; a reload keeps the running config until the file parses.");
    public static final BitCode YAML_UNPARSABLE_UNOWNED = RtpBitCodes.RTP.register(1002,
            "RTP_YAML_UNSUPPORTED_UNOWNED", Level.WARNING, BitTier.PBIT, SUBSYSTEM, "",
            "RTP does not load this file, so it cannot break a reload; fix or remove it if it should be read.");

    private static final List<ConfigOwnerCheck> CHECKS = new CopyOnWriteArrayList<>();
    private static final Map<String, Boolean> COMPONENT_IDS = new ConcurrentHashMap<>();
    private static volatile boolean componentsRegistered;

    /**
     * Built-in owner: every YAML file in the snapshot parses with the runtime reader. Only files
     * a registered owner claims ({@link ConfigOwnerCheck#owns}) are ERROR; others cannot break a
     * reload. Lang maps and {@code lang/} trees are skipped by the snapshot itself.
     */
    public static final ConfigOwnerCheck YAML_SYNTAX = new ConfigOwnerCheck() {
        @Override
        public String id() {
            return "yaml";
        }

        @Override
        public List<BitFinding> check(ConfigSnapshot snapshot, ConfigEnvironment environment) {
            List<BitFinding> findings = new ArrayList<>();
            snapshot.unparsable().forEach((path, message) -> {
                String messageKey = snapshot.unparsableKeys().get(path);
                boolean unsupported = messageKey != null && messageKey.startsWith("rtpYaml.unsupported.");
                BitCode code = unsupported && !isOwned(path, environment)
                        ? YAML_UNPARSABLE_UNOWNED : YAML_UNPARSABLE;
                findings.add(BitFinding.of(code, path, "", "YAML does not parse: " + message,
                        "YAML the RTP reader accepts", code.remediation()));
            });
            return findings;
        }
    };

    static {
        register(YAML_SYNTAX);
        for (ConfigOwnerCheck owner : DirectiveConfigCheck.owners()) register(owner);
        // owners: directives
        register(ConfigReferenceCheck.INSTANCE);
        register(ConfigConstraintCheck.INSTANCE);
        // owners: references
        register(MessagesConfigCheck.INSTANCE); // owners: messages
        register(NetworkConfigCheck.INSTANCE); // owners: network
        register(EffectsGroupValidator.INSTANCE); // owners: effects
    }

    private ConfigPbit() {}

    /**
     * Registers an owner check (core owners or addons at enable). Returns false when the id is
     * already taken. When the runner components are already registered, the new component is too.
     */
    public static boolean register(ConfigOwnerCheck check) {
        Objects.requireNonNull(check, "check must not be null");
        String id = normaliseId(check.id());
        if (COMPONENT_IDS.putIfAbsent(id, Boolean.TRUE) != null) return false;
        CHECKS.add(check);
        if (componentsRegistered) RtpBit.RUNNER.register(component(check));
        return true;
    }

    /** True when a registered owner, or a fixed RTP config location, loads {@code path}. */
    public static boolean isOwned(String path) {
        return isOwned(path, ConfigEnvironment.HEADLESS);
    }

    private static boolean isOwned(String path, ConfigEnvironment environment) {
        String rel = ConfigSnapshot.normalise(path);
        if (DirectiveConfigCheck.OWNER_FILES.containsValue(rel)) return true;
        if (rel.startsWith("definitions/regions/")
                || rel.startsWith("definitions/worlds/")
                || rel.startsWith("definitions/effects/")) return true;
        for (ConfigOwnerCheck check : CHECKS) {
            if (check != YAML_SYNTAX && check.owns(rel)) return true;
        }
        ClassLoader resources = (environment == null ? ConfigEnvironment.HEADLESS : environment).resources();
        return resources != null && resources.getResource(rel) != null;
    }

    public static List<ConfigOwnerCheck> checks() {
        return List.copyOf(CHECKS);
    }

    /** Registers one SAFE PBIT runner component per owner check; repeated calls are no-ops. */
    public static void registerComponents() {
        componentsRegistered = true;
        for (ConfigOwnerCheck check : CHECKS) RtpBit.RUNNER.register(component(check));
    }

    /** Runs every owner check over {@code snapshot}; findings carry their effective severity. */
    public static BitReport run(ConfigSnapshot snapshot, ConfigEnvironment environment) {
        List<BitFinding> findings = new ArrayList<>();
        for (ConfigOwnerCheck check : CHECKS) findings.addAll(run(check, snapshot, environment).findings());
        return BitReport.of(findings);
    }

    /** Runs one owner check; a thrown check becomes {@code BIT_COMPONENT_THROWN}, never swallowed. */
    public static BitReport run(ConfigOwnerCheck check, ConfigSnapshot snapshot, ConfigEnvironment environment) {
        List<BitFinding> findings = new ArrayList<>();
        ConfigEnvironment inputs = environment == null ? ConfigEnvironment.HEADLESS : environment;
        try {
            List<BitFinding> raw = check.check(snapshot, inputs);
            if (raw != null) {
                for (BitFinding finding : raw) {
                    if (finding != null) BitSeverityPolicy.effectiveFinding(finding).ifPresent(findings::add);
                }
            }
        } catch (RuntimeException e) {
            findings.add(BitFinding.of(BitCodes.COMPONENT_THROWN, Level.ERROR, componentId(check), "",
                    "Config check threw " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    "a BitReport", BitCodes.COMPONENT_THROWN.remediation()));
        }
        return BitReport.of(findings);
    }

    public static String componentId(ConfigOwnerCheck check) {
        return SUBSYSTEM + "-" + normaliseId(check.id());
    }

    static BitComponent component(ConfigOwnerCheck check) {
        return new BitComponent(componentId(check), SUBSYSTEM, BitTier.PBIT, BitSideEffect.SAFE,
                COMPONENT_TIMEOUT_MILLIS, () -> run(check, liveSnapshot(), ConfigEnvironment.live()));
    }

    /** {@link BuiltInTestable} view of an owner check against the live config directory. */
    public static BuiltInTestable asBuiltInTestable(ConfigOwnerCheck check) {
        return new BuiltInTestable() {
            private volatile BitHealth health = BitHealth.HEALTHY;

            @Override
            public PbitResult executePbit() {
                long start = System.nanoTime();
                BitReport report = run(check, liveSnapshot(), ConfigEnvironment.live());
                health = report.health();
                long elapsed = System.nanoTime() - start;
                if (report.health() == BitHealth.FAILED) {
                    return PbitResult.failed(componentId(check), elapsed,
                            report.findings().size() + " finding(s)", null);
                }
                return PbitResult.ok(componentId(check), elapsed);
            }

            @Override
            public BitHealth getHealthStatus() {
                return health;
            }
        };
    }

    /** Snapshot of the running server's data folder, or an empty snapshot when none is known. */
    public static ConfigSnapshot liveSnapshot() {
        Path root = liveRoot();
        return root == null ? ConfigSnapshot.of(Map.of()) : ConfigSnapshot.read(root);
    }

    static Path liveRoot() {
        Configs configs = ActiveConfigs.get();
        if (configs != null && configs.pluginDirectory != null) return configs.pluginDirectory.toPath();
        RTPServerAccessor accessor = RTPRuntime.serverAccessor();
        File directory = accessor == null ? null : accessor.getPluginDirectory();
        return directory == null ? null : directory.toPath();
    }

    private static String normaliseId(String id) {
        String value = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) throw new IllegalArgumentException("config check id must not be blank");
        return value;
    }
}
