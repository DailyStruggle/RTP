package io.github.dailystruggle.rtp.common.menu.search;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import io.github.dailystruggle.rtp.common.text.LegacyColorStrip;
import io.github.dailystruggle.rtp.common.text.LegacyColorStrip.StripResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Case-insensitive substring search across loaded {@link ConfigParser} instances.
 * Matches against key names and values with raw-offset projection for highlights.
 */
public final class ConfigSearchResultsBuilder {

    /** Minimum query length below which {@link #search} short-circuits to empty. */
    public static final int MIN_QUERY_LENGTH = 2;

    /** One match against a single config key. */
    public record Hit(
            String fileName,
            String keyName,
            boolean keyMatched,
            String rawValue,
            List<int[]> matchRanges) {
        /** Validates and normalises the record components. */
        public Hit {
            if (fileName == null) throw new IllegalArgumentException("fileName");
            if (keyName == null) throw new IllegalArgumentException("keyName");
            if (rawValue == null) rawValue = "";
            matchRanges = matchRanges == null ? List.of() : List.copyOf(matchRanges);
        }
    }

    private ConfigSearchResultsBuilder() {}

    /**
     * Run a case-insensitive substring search across every loaded parser.
     *
     * @param query free-form user input; trimmed; matched case-insensitively.
     * @return ordered list of hits, or empty if query is short or configs is null
     */
    public static List<Hit> search(String query) {
        if (query == null) return List.of();
        String trimmed = query.trim();
        if (trimmed.length() < MIN_QUERY_LENGTH) return List.of();
        Configs configs = RTP.configs;
        if (configs == null) return List.of();
        return search(query, configs);
    }

    /**
     * Variant accepting an explicit {@link Configs}; used by tests.
     *
     * @param query   free-form user input; trimmed; matched case-insensitively
     * @param configs the configs instance to search; {@code null} returns empty
     * @return ordered list of hits; empty if query is too short or configs is null
     */
    public static List<Hit> search(String query, Configs configs) {
        if (query == null || configs == null) return List.of();
        String trimmed = query.trim();
        if (trimmed.length() < MIN_QUERY_LENGTH) return List.of();
        String needle = trimmed.toLowerCase(Locale.ROOT);

        List<Hit> hits = new ArrayList<>();
        for (ConfigParser<?> parser : configs.configParserMap.values()) {
            if (parser == null) continue;
            collectFromParser(parser, stripYml(parser.name), needle, hits);
        }
        for (MultiConfigParser<?> mcp : configs.multiConfigParserMap.values()) {
            if (mcp == null) continue;
            // Multiconfig sub-parsers (per-region/world/effect entries) are
            // addressed by the curated config dispatch as "<kind>/<entry>"
            // (e.g. "regions/default"); the slash short-circuit in
            // MenuRedeemSubcommand.dispatchOpenConfigKey routes that form to
            // the correct nested leaf. Emitting a bare "default" file name
            // here makes the row click fail file resolution ("that menu
            // command is invalid"), so prefix the multiconfig kind.
            String kind = mcp.name;
            for (String name : mcp.listParsers()) {
                ConfigParser<?> sub = mcp.getParser(name);
                if (sub == null) continue;
                String fileName = (kind == null || kind.isEmpty())
                        ? stripYml(sub.name)
                        : kind + "/" + stripYml(sub.name);
                collectFromParser(sub, fileName, needle, hits);
            }
        }
        return Collections.unmodifiableList(hits);
    }

    private static String stripYml(String name) {
        if (name == null) return "";
        String n = name;
        if (n.toLowerCase(Locale.ROOT).endsWith(".yml")) {
            n = n.substring(0, n.length() - 4);
        }
        return n;
    }

    private static <E extends Enum<E>> void collectFromParser(
            ConfigParser<E> parser, String fileName, String needle, List<Hit> out) {
        EnumMap<E, Object> data;
        try {
            data = parser.getData();
        } catch (RuntimeException e) {
            return;
        }
        // Flatten every enum value into leaves. Scalar values map to a single
        // leaf keyed by the enum name; section/map values (e.g. RegionKeys.shape)
        // are flattened to dotted sub-paths ("shape.radius", "vert.minY") so each
        // nested scalar surfaces as its own hit with a clean scalar rawValue,
        // instead of the whole section being String.valueOf'd into one blob.
        LinkedHashMap<String, Object> leaves = new LinkedHashMap<>();
        for (Map.Entry<E, Object> entry : data.entrySet()) {
            E enumKey = entry.getKey();
            if (enumKey == null) continue;
            collectLeaves(enumKey.name(), entry.getValue(), leaves);
        }
        for (Map.Entry<String, Object> leaf : leaves.entrySet()) {
            collectFromLeaf(fileName, leaf.getKey(), leaf.getValue(), needle, out);
        }
    }

    /**
     * Flatten a single enum value into scalar leaves keyed by dotted path.
     * Scalars become a single {@code prefix -> value} leaf; {@link RtpYamlSection}
     * and {@link Map} values are walked depth-first so nested config entries
     * (e.g. {@code shape.radius}) each become their own leaf.
     */
    private static void collectLeaves(
            String prefix, Object value, LinkedHashMap<String, Object> out) {
        if (value instanceof io.github.dailystruggle.rtp.common.factory.FactoryValue<?> fv) {
            // Shape / VerticalAdjustor region keys hold a typed FactoryValue
            // (after RegionConfigLoader replaces the raw YAML section with the
            // deserialized object). Its getData() carries the tunables
            // (radius, centerX, ...); the factory discriminator is exposed as
            // a "name" leaf so it surfaces the same dotted rows the curated
            // config editor renders (CommandTreeMenuBuilder.factoryValueToMap).
            // Without this branch the FactoryValue falls through to the scalar
            // else-branch below and String.valueOf's into one blob.
            out.put(prefix + ".name", fv.name);
            java.util.EnumMap<?, Object> fvData = fv.getData();
            if (fvData != null) {
                for (Map.Entry<?, Object> e : fvData.entrySet()) {
                    if (e.getKey() == null) continue;
                    collectLeaves(prefix + "." + e.getKey(), e.getValue(), out);
                }
            }
        } else if (value instanceof RtpYamlSection section) {
            for (Map.Entry<String, Object> e : section.getMapValues(true).entrySet()) {
                out.put(prefix + "." + e.getKey(), e.getValue());
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                collectLeaves(prefix + "." + e.getKey(), e.getValue(), out);
            }
        } else {
            out.put(prefix, value);
        }
    }

    /** Run the key-name and value substring match for a single flattened leaf. */
    private static void collectFromLeaf(
            String fileName, String keyName, Object value, String needle, List<Hit> out) {
        String rawValue = value == null ? "" : String.valueOf(value);

        // Key match (color-stripped, though keys are rarely colorized). The
        // dotted keyName is matched whole, so "shape" surfaces "shape.radius"
        // and "radius" matches the leaf segment.
        StripResult keyStrip = LegacyColorStrip.strip2(keyName);
        if (keyStrip.stripped.toLowerCase(Locale.ROOT).contains(needle)) {
            out.add(new Hit(fileName, keyName, true, rawValue, List.of()));
        }

        // Value match (color-stripped haystack, raw-offset projection).
        if (rawValue.isEmpty()) return;
        StripResult valStrip = LegacyColorStrip.strip2(rawValue);
        String hay = valStrip.stripped.toLowerCase(Locale.ROOT);
        List<int[]> ranges = new ArrayList<>();
        int from = 0;
        while (true) {
            int idx = hay.indexOf(needle, from);
            if (idx < 0) break;
            int endStripped = idx + needle.length();
            int rawStart = valStrip.strippedToRaw[idx];
            // Raw-end-exclusive: take the raw offset of the char *after*
            // the match's last character, or rawValue.length() if the
            // match ends at the stripped tail.
            int rawEnd;
            if (endStripped < valStrip.strippedToRaw.length) {
                rawEnd = valStrip.strippedToRaw[endStripped];
            } else {
                rawEnd = rawValue.length();
            }
            ranges.add(new int[] {rawStart, rawEnd});
            from = endStripped;
        }
        if (!ranges.isEmpty()) {
            out.add(new Hit(fileName, keyName, false, rawValue, ranges));
        }
    }
}
