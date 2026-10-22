package io.github.dailystruggle.rtp.common.bit.config;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.bit.KeyPath;
import io.github.dailystruggle.bit.Vocabulary;
import io.github.dailystruggle.effectsapi.common.EffectStage;
import io.github.dailystruggle.effectsapi.common.EffectsGroupKeys;
import io.github.dailystruggle.rtp.common.bit.BitSeverityPolicy;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Config PBIT owner for effect groups under {@code definitions/effects/*.yml} (ADR-121 section 6,
 * effects-api-ADR-005). Allowed keys and stage tokens come from {@link EffectsGroupKeys} and
 * {@link EffectStage}; no local copy (ADR-121 section 3).
 *
 * <p>Group names are file stems, matched case-insensitively as {@code EffectsResolver} does. Only
 * explicit {@code inherit:} edges are walked; the implicit default-for-stage parent cannot cycle.
 * Effect names are checked only when the environment knows the registered set.
 */
public final class EffectsGroupValidator implements ConfigOwnerCheck {
    public static final String DIRECTORY = "definitions/effects";
    private static final String SUBSYSTEM = ConfigPbit.SUBSYSTEM;

    public static final BitCode UNKNOWN_KEY = BitSeverityPolicy.stagedConfig(RtpBitCodes.RTP.register(1600,
            "RTP_EFFECTS_UNKNOWN_KEY", Level.ERROR, BitTier.PBIT, SUBSYSTEM, "",
            "Remove or rename the key; an effect group accepts only version, when, permission, players, "
                    + "inherit and effects."));
    public static final BitCode MISSING_STAGE = BitSeverityPolicy.stagedConfig(RtpBitCodes.RTP.register(1601,
            "RTP_EFFECTS_MISSING_STAGE", Level.ERROR, BitTier.PBIT, SUBSYSTEM, "",
            "Add when: <stage>; a group without a stage never fires."));
    public static final BitCode UNKNOWN_STAGE = BitSeverityPolicy.stagedConfig(RtpBitCodes.RTP.register(1602,
            "RTP_EFFECTS_UNKNOWN_STAGE", Level.ERROR, BitTier.PBIT, SUBSYSTEM, "",
            "Set when: to one stage token, e.g. preteleport or postteleport."));
    public static final BitCode STAGE_NOT_DISPATCHED = RtpBitCodes.RTP.register(1603,
            "RTP_EFFECTS_STAGE_NOT_DISPATCHED", Level.WARNING, BitTier.PBIT, SUBSYSTEM, "",
            "No platform fires this stage; bind the group to a dispatched stage or use an action instead.");
    public static final BitCode INHERIT_UNRESOLVED = BitSeverityPolicy.stagedConfig(RtpBitCodes.RTP.register(1604,
            "RTP_EFFECTS_INHERIT_UNRESOLVED", Level.ERROR, BitTier.PBIT, SUBSYSTEM, "",
            "Name an existing group file in definitions/effects (without .yml) or remove the entry."));
    public static final BitCode INHERIT_CYCLE = RtpBitCodes.RTP.register(1605,
            "RTP_EFFECTS_INHERIT_CYCLE", Level.ERROR, BitTier.PBIT, SUBSYSTEM, "",
            "Break the inherit: loop; at runtime the walk is truncated and effects are lost.");
    public static final BitCode UNKNOWN_EFFECT = BitSeverityPolicy.stagedConfig(RtpBitCodes.RTP.register(1606,
            "RTP_EFFECTS_UNKNOWN_EFFECT", Level.ERROR, BitTier.PBIT, SUBSYSTEM, "",
            "Use a registered effect name (first token before '.') or install the addon that provides it."));
    public static final BitCode BAD_TYPE = BitSeverityPolicy.stagedConfig(RtpBitCodes.RTP.register(1607,
            "RTP_EFFECTS_BAD_TYPE", Level.ERROR, BitTier.PBIT, SUBSYSTEM, "",
            "when and permission take one string; players, inherit and effects take a list of strings."));

    public static final EffectsGroupValidator INSTANCE = new EffectsGroupValidator();

    private static final Vocabulary KEYS = new Vocabulary("effect group key",
            Arrays.stream(EffectsGroupKeys.values()).map(Enum::name).toList());
    private static final Vocabulary STAGES = new Vocabulary("effect stage", EffectStage.tokens());

    private EffectsGroupValidator() {}

    @Override
    public String id() {
        return "effects";
    }

    @Override
    public boolean owns(String path) {
        return ConfigReferenceCheck.inDirectory(path, DIRECTORY);
    }

    @Override
    public List<BitFinding> check(ConfigSnapshot snapshot, ConfigEnvironment environment) {
        List<BitFinding> findings = new ArrayList<>();
        if (snapshot == null) return findings;
        Map<String, ConfigSnapshot.Document> groups = new LinkedHashMap<>();
        for (ConfigSnapshot.Document document : snapshot.documentsIn(DIRECTORY).values()) {
            groups.putIfAbsent(document.stem().toLowerCase(Locale.ROOT), document);
        }
        Optional<Vocabulary> effects = environment == null ? Optional.empty()
                : environment.effects().map(names -> new Vocabulary("effect", names));
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (Map.Entry<String, ConfigSnapshot.Document> group : groups.entrySet()) {
            edges.put(group.getKey(), checkGroup(group.getValue(), groups.keySet(), effects, findings));
        }
        findCycles(groups, edges, findings);
        return findings;
    }

    /** Checks one group; returns its resolved explicit inherit targets (lower-case). */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType") // empty = effects vocabulary unavailable
    private static List<String> checkGroup(ConfigSnapshot.Document document, Set<String> groupNames,
                                           Optional<Vocabulary> effects, List<BitFinding> findings) {
        String source = document.path();
        Map<String, Object> values = document.values();
        for (String key : values.keySet()) {
            if (KEYS.contains(key)) continue;
            findings.add(BitFinding.of(UNKNOWN_KEY, source, key, "Unknown effect group key: " + key,
                    String.join(", ", KEYS.values()), KEYS.suggest(key).orElse(UNKNOWN_KEY.remediation())));
        }

        checkStage(document, values.get(EffectsGroupKeys.when.name()), findings);

        Object permission = values.get(EffectsGroupKeys.permission.name());
        if (permission != null && !isScalar(permission)) {
            findings.add(badType(document, EffectsGroupKeys.permission, "a permission node string"));
        }

        stringList(document, EffectsGroupKeys.players, values, findings);

        List<String> parents = new ArrayList<>();
        List<String> inherit = stringList(document, EffectsGroupKeys.inherit, values, findings);
        if (inherit != null) {
            KeyPath base = KeyPath.root(document.localizedKey(EffectsGroupKeys.inherit.name()));
            for (int i = 0; i < inherit.size(); i++) {
                String parent = inherit.get(i);
                if (parent == null || parent.isBlank()) continue;
                String name = parent.trim().toLowerCase(Locale.ROOT);
                if (groupNames.contains(name)) {
                    parents.add(name);
                    continue;
                }
                findings.add(BitFinding.of(INHERIT_UNRESOLVED, source, base.index(i).toString(),
                        "inherit references undefined group '" + parent.trim() + "'",
                        String.join(", ", new TreeSet<>(groupNames)),
                        new Vocabulary("group", groupNames).suggest(name).orElse(INHERIT_UNRESOLVED.remediation())));
            }
        }

        List<String> tokens = stringList(document, EffectsGroupKeys.effects, values, findings);
        if (tokens != null && effects.isPresent()) {
            Vocabulary registered = effects.get();
            KeyPath base = KeyPath.root(document.localizedKey(EffectsGroupKeys.effects.name()));
            for (int i = 0; i < tokens.size(); i++) {
                String token = tokens.get(i);
                if (token == null || token.isBlank()) continue;
                String type = effectType(token);
                if (type.isEmpty() || registered.contains(type)) continue;
                findings.add(BitFinding.of(UNKNOWN_EFFECT, source, base.index(i).toString(),
                        "Unknown effect '" + type + "' in token '" + token.trim() + "'",
                        String.join(", ", new TreeSet<>(registered.values())),
                        registered.suggest(type).orElse(UNKNOWN_EFFECT.remediation())));
            }
        }
        return parents;
    }

    private static void checkStage(ConfigSnapshot.Document document, Object when, List<BitFinding> findings) {
        String source = document.path();
        String path = document.localizedKey(EffectsGroupKeys.when.name());
        if (when == null || (isScalar(when) && when.toString().isBlank())) {
            findings.add(BitFinding.of(MISSING_STAGE, source, path, "Effect group has no when: stage",
                    String.join(", ", STAGES.values()), MISSING_STAGE.remediation()));
            return;
        }
        if (!isScalar(when)) {
            findings.add(badType(document, EffectsGroupKeys.when, "one stage token; a list never matches"));
            return;
        }
        String token = when.toString().trim();
        Optional<EffectStage> stage = EffectStage.fromToken(token);
        if (stage.isEmpty()) {
            findings.add(BitFinding.of(UNKNOWN_STAGE, source, path, "Unknown effect stage: " + token,
                    String.join(", ", STAGES.values()), STAGES.suggest(token).orElse(UNKNOWN_STAGE.remediation())));
        } else if (!stage.get().dispatched()) {
            findings.add(BitFinding.of(STAGE_NOT_DISPATCHED, source, path,
                    "Stage '" + stage.get().token() + "' is never fired by a platform handler",
                    dispatchedStages(), STAGE_NOT_DISPATCHED.remediation()));
        }
    }

    /**
     * Reads a list-or-scalar field as strings (scalar = one-element list, as {@code EffectsResolver}
     * does). Returns {@code null} when absent; non-scalar values become {@link #BAD_TYPE} findings.
     */
    private static List<String> stringList(ConfigSnapshot.Document document, EffectsGroupKeys key,
                                           Map<String, Object> values, List<BitFinding> findings) {
        Object value = values.get(key.name());
        if (value == null) return null;
        if (isScalar(value)) return List.of(value.toString());
        if (!(value instanceof List<?> raw)) {
            findings.add(badType(document, key, "a list of strings"));
            return List.of();
        }
        List<String> out = new ArrayList<>(raw.size());
        KeyPath base = KeyPath.root(document.localizedKey(key.name()));
        for (int i = 0; i < raw.size(); i++) {
            Object entry = raw.get(i);
            if (entry != null && !isScalar(entry)) {
                findings.add(BitFinding.of(BAD_TYPE, document.path(), base.index(i).toString(),
                        key.name() + " entry is not a string", "a string", BAD_TYPE.remediation()));
                out.add(null);
            } else {
                out.add(entry == null ? null : entry.toString());
            }
        }
        return out;
    }

    /** One finding per distinct cycle, reported on its lexically first member. */
    private static void findCycles(Map<String, ConfigSnapshot.Document> groups, Map<String, List<String>> edges,
                                   List<BitFinding> findings) {
        Set<Set<String>> reported = new HashSet<>();
        Set<String> done = new HashSet<>();
        for (String start : edges.keySet()) {
            walk(start, edges, new ArrayList<>(), done, reported, groups, findings);
        }
    }

    private static void walk(String node, Map<String, List<String>> edges, List<String> stack, Set<String> done,
                             Set<Set<String>> reported, Map<String, ConfigSnapshot.Document> groups,
                             List<BitFinding> findings) {
        int at = stack.indexOf(node);
        if (at >= 0) {
            List<String> cycle = new ArrayList<>(stack.subList(at, stack.size()));
            if (!reported.add(new HashSet<>(cycle))) return;
            String first = new TreeSet<>(cycle).first();
            ConfigSnapshot.Document document = groups.get(first);
            List<String> ordered = rotate(cycle, first);
            ordered.add(first);
            findings.add(BitFinding.of(INHERIT_CYCLE, document.path(),
                    document.localizedKey(EffectsGroupKeys.inherit.name()),
                    "inherit cycle: " + String.join(" -> ", ordered), "an acyclic inherit chain",
                    INHERIT_CYCLE.remediation()));
            return;
        }
        if (done.contains(node)) return;
        stack.add(node);
        for (String parent : edges.getOrDefault(node, List.of())) {
            walk(parent, edges, stack, done, reported, groups, findings);
        }
        stack.remove(stack.size() - 1);
        done.add(node);
    }

    private static List<String> rotate(List<String> cycle, String first) {
        int start = cycle.indexOf(first);
        List<String> out = new ArrayList<>(cycle.subList(start, cycle.size()));
        out.addAll(cycle.subList(0, start));
        return out;
    }

    /** Effect name of a token: text before the first unescaped '.' or whitespace (EffectFactory grammar). */
    static String effectType(String token) {
        String raw = token.trim();
        boolean escaping = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escaping) {
                escaping = false;
            } else if (c == '\\') {
                escaping = true;
            } else if (c == '.' || Character.isWhitespace(c)) {
                return raw.substring(0, i).toUpperCase(Locale.ROOT);
            }
        }
        return raw.toUpperCase(Locale.ROOT);
    }

    private static BitFinding badType(ConfigSnapshot.Document document, EffectsGroupKeys key, String expected) {
        return BitFinding.of(BAD_TYPE, document.path(), document.localizedKey(key.name()),
                key.name() + " has the wrong type", expected, BAD_TYPE.remediation());
    }

    private static boolean isScalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Character;
    }

    private static String dispatchedStages() {
        Set<String> out = new LinkedHashSet<>();
        for (EffectStage stage : EffectStage.values()) {
            if (stage.dispatched()) out.add(stage.token());
        }
        return String.join(", ", out);
    }
}
