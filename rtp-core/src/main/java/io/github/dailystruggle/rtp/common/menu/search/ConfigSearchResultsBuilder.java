package io.github.dailystruggle.rtp.common.menu.search;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.text.LegacyColorStrip;
import io.github.dailystruggle.rtp.common.text.LegacyColorStrip.StripResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Walks every loaded {@link ConfigParser} (single + {@link MultiConfigParser}
 * sub-parsers) and produces a list of case-insensitive substring hits for a
 * query, matching against color-stripped key names and color-stripped value
 * text. Each {@link Hit} carries the raw (un-stripped) value plus the match
 * ranges projected back onto raw offsets so a renderer can overlay a
 * highlight on top of the literal config text per
 * {@code PROPOSAL-rtp-menu-config-search.md} §2.7 and §2.8.
 *
 * <p>Comments are excluded by design (user direction: "if we ignore comment
 * lines"). No result cap (user direction: "the sum of config files aren't
 * that big"). Minimum query length is 2 characters; shorter queries return
 * an empty list and the caller is expected to surface the
 * {@code menuConfigSearchTooShort} locale message.
 *
 * <p>Pure compute, no I/O. Safe to call from any thread; readers see the
 * current {@link Configs} parser snapshot via {@code RTP.configs}.
 */
public final class ConfigSearchResultsBuilder {

    /** Minimum query length below which {@link #search} short-circuits to empty. */
    public static final int MIN_QUERY_LENGTH = 2;

    /**
     * One match against a single config key.
     *
     * @param fileName       parser file/section name (e.g. {@code "messages"} or
     *                       {@code "regions/default"}).
     * @param keyName        enum key name as understood by {@code OpenConfigKey}
     *                       (e.g. {@code "shape"} for {@code RegionKeys.shape}).
     * @param keyMatched     {@code true} iff the match was against the key name
     *                       (vs the value). Both can produce separate hits.
     * @param rawValue       the literal value as written in the config (color
     *                       codes and all). For value matches, the highlight
     *                       ranges below index into this string. For key matches,
     *                       {@code rawValue} is the rendered value (still raw)
     *                       but the highlight ranges are empty.
     * @param matchRanges    raw-offset {@code [start,endExclusive)} ranges into
     *                       {@code rawValue} where the query matched (value
     *                       matches only). May be empty for key matches.
     */
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
     * @return ordered list of hits (single parsers first in insertion order,
     *         then each multi-parser's sub-parsers in {@code listParsers()}
     *         order). Empty if {@code query} is null/blank/shorter than
     *         {@link #MIN_QUERY_LENGTH}.
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
            collectFromParser(parser, needle, hits);
        }
        for (MultiConfigParser<?> mcp : configs.multiConfigParserMap.values()) {
            if (mcp == null) continue;
            for (String name : mcp.listParsers()) {
                ConfigParser<?> sub = mcp.getParser(name);
                if (sub == null) continue;
                collectFromParser(sub, needle, hits);
            }
        }
        return Collections.unmodifiableList(hits);
    }

    private static <E extends Enum<E>> void collectFromParser(
            ConfigParser<E> parser, String needle, List<Hit> out) {
        String fileName = parser.name;
        EnumMap<E, Object> data;
        try {
            data = parser.getData();
        } catch (RuntimeException e) {
            return;
        }
        for (Map.Entry<E, Object> entry : data.entrySet()) {
            E enumKey = entry.getKey();
            if (enumKey == null) continue;
            String keyName = enumKey.name();
            Object value = entry.getValue();
            String rawValue = value == null ? "" : String.valueOf(value);

            // Key match (color-stripped, though keys are rarely colorized).
            StripResult keyStrip = LegacyColorStrip.strip2(keyName);
            if (keyStrip.stripped.toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(new Hit(fileName, keyName, true, rawValue, List.of()));
            }

            // Value match (color-stripped haystack, raw-offset projection).
            if (rawValue.isEmpty()) continue;
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
}
