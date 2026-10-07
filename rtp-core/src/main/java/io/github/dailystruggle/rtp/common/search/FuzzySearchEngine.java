package io.github.dailystruggle.rtp.common.search;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Unified fuzzy search and string matching engine.
 *
 * <p>Consolidates fast-path alphanumeric normalization, bounded Levenshtein distance,
 * adaptive typo-tolerance thresholds, and multi-tier relevance scoring across the
 * configuration importer, permission derivation, and in-game search subsystems.
 */
public final class FuzzySearchEngine {

    /** Maximum allowed edit distance for standard fuzzy matches. */
    public static final int MAX_EDIT_DISTANCE = 2;

    /** Minimum query/token length below which fuzzy distance calculations are skipped. */
    public static final int MIN_FUZZY_LENGTH = 3;

    /** Minimum token length required for tolerance of 2 edit distance. */
    public static final int LENGTH_THRESHOLD_TWO_EDITS = 5;

    /** Shortest name length permitted to match by substring containment. */
    public static final int MIN_CONTAINMENT_LENGTH = 4;

    /**
     * Explanation for a search match.
     */
    public record MatchReason(@NotNull String type, @NotNull String text) {
        public MatchReason {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * Result of scoring a candidate key/value against a query.
     */
    public record ScoredCandidate(int score, @NotNull List<MatchReason> reasons) {
        public ScoredCandidate {
            Objects.requireNonNull(reasons, "reasons");
        }

        public static ScoredCandidate none() {
            return new ScoredCandidate(0, List.of());
        }
    }

    private FuzzySearchEngine() {}

    /**
     * Normalizes a string by stripping non-alphanumeric characters and converting to lower-case.
     * E.g. "min-radius", "min_radius", "MinRadius", "min.radius" -> "minradius".
     */
    @NotNull
    public static String normalize(@Nullable String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int k = 0; k < s.length(); k++) {
            char c = Character.toLowerCase(s.charAt(k));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Calculates the Levenshtein distance between two strings using an allocation-conscious
     * two-row dynamic programming matrix.
     */
    public static int levenshtein(@Nullable String a, @Nullable String b) {
        if (a == null && b == null) return 0;
        if (a == null) return b.length();
        if (b == null) return a.length();
        if (a.equals(b)) return 0;

        int m = a.length();
        int n = b.length();
        if (m == 0) return n;
        if (n == 0) return m;

        int[] prev = new int[n + 1];
        int[] cur = new int[n + 1];

        for (int j = 0; j <= n; j++) prev[j] = j;

        for (int i = 1; i <= m; i++) {
            cur[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= n; j++) {
                int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] temp = prev;
            prev = cur;
            cur = temp;
        }

        return prev[n];
    }

    /**
     * Calculates Levenshtein distance with early-exit pruning when distance exceeds {@code maxDist}.
     */
    public static int levenshtein(@Nullable String a, @Nullable String b, int maxDist) {
        if (a == null && b == null) return 0;
        if (a == null) return b.length() <= maxDist ? b.length() : maxDist + 1;
        if (b == null) return a.length() <= maxDist ? a.length() : maxDist + 1;
        if (a.equals(b)) return 0;

        int m = a.length();
        int n = b.length();

        if (Math.abs(m - n) > maxDist) return maxDist + 1;
        if (m == 0) return n;
        if (n == 0) return m;

        int[] prev = new int[n + 1];
        int[] cur = new int[n + 1];

        for (int j = 0; j <= n; j++) prev[j] = j;

        for (int i = 1; i <= m; i++) {
            cur[0] = i;
            char ca = a.charAt(i - 1);
            int rowMin = Integer.MAX_VALUE;

            for (int j = 1; j <= n; j++) {
                int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                if (cur[j] < rowMin) rowMin = cur[j];
            }

            if (rowMin > maxDist) return maxDist + 1;

            int[] temp = prev;
            prev = cur;
            cur = temp;
        }

        return prev[n] <= maxDist ? prev[n] : maxDist + 1;
    }

    /**
     * True if {@code a} and {@code b} match under adaptive Levenshtein thresholding
     * (max distance 1 for length 3-4, max distance 2 for length >= 5).
     */
    public static boolean isFuzzyMatch(@Nullable String a, @Nullable String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        int minLen = Math.min(a.length(), b.length());
        if (minLen < MIN_FUZZY_LENGTH) return false;
        int maxDist = minLen >= LENGTH_THRESHOLD_TWO_EDITS ? 2 : 1;
        return levenshtein(a, b, maxDist) <= maxDist;
    }

    /**
     * Concept key matcher used by permission derivation and heuristic importers:
     * exact match, containment (for term length >= 5), or Levenshtein distance <= 2.
     */
    public static boolean keyMatches(@Nullable String key, @Nullable String term) {
        if (key == null || term == null || key.isEmpty() || term.isEmpty()) return false;
        if (key.equals(term)) return true;
        if (term.length() < 5) return false;
        if (key.contains(term)) return true;
        return key.length() >= 5 && levenshtein(key, term, 2) <= 2;
    }

    /**
     * Folder/plugin name prefix matcher used by permission derivation:
     * exact match, containment (for length >= 4), or Levenshtein distance <= 2 (for length >= 5).
     */
    public static boolean nameMatches(@Nullable String a, @Nullable String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        if (Math.min(a.length(), b.length()) >= MIN_CONTAINMENT_LENGTH && (a.contains(b) || b.contains(a))) {
            return true;
        }
        return a.length() >= 5 && b.length() >= 5 && levenshtein(a, b, 2) <= 2;
    }

    /**
     * Computes relevance score and reasons for a config candidate key against a query.
     * Follows ADR-104 §4.5 parity with the web editor search engine.
     */
    @NotNull
    public static ScoredCandidate scoreCandidate(@NotNull String rawQuery,
                                                 @NotNull String fileName,
                                                 @NotNull String keyName,
                                                 @Nullable String rawValue,
                                                 @Nullable Set<String> matchedSynonyms) {
        String query = normalize(rawQuery);
        if (query.isEmpty()) return ScoredCandidate.none();

        String normKey = normalize(keyName);
        String normFile = normalize(fileName);
        String normVal = rawValue == null ? "" : normalize(rawValue);

        int score = 0;
        List<MatchReason> reasons = new ArrayList<>();

        // 1. Exact Key Match
        if (normKey.equals(query)) {
            score += 120;
            reasons.add(new MatchReason("reason", "Exact Key"));
        } else if (normKey.startsWith(query) || (normKey.contains(query) && query.length() >= MIN_FUZZY_LENGTH)) {
            score += 80;
            reasons.add(new MatchReason("reason", "Key Match"));
        }

        // 2. File Name Match
        if (!normFile.isEmpty() && normFile.contains(query)) {
            score += 40;
            reasons.add(new MatchReason("file", "File Match"));
        }

        // 3. Thesaurus / Similar Phrase Match
        if (matchedSynonyms != null && !matchedSynonyms.isEmpty()) {
            for (String syn : matchedSynonyms) {
                if (normKey.equals(syn) || normKey.contains(syn) || syn.contains(normKey)) {
                    score += 65;
                    reasons.add(new MatchReason("synonym", "Similar: " + syn));
                    break;
                }
            }
        }

        // 4. Fuzzy Levenshtein Distance (Typo Tolerance)
        if (query.length() >= MIN_FUZZY_LENGTH) {
            int maxAllowed = query.length() >= LENGTH_THRESHOLD_TWO_EDITS ? 2 : 1;
            int dist = levenshtein(query, normKey, maxAllowed);
            if (dist <= maxAllowed && !reasons.stream().anyMatch(r -> "reason".equals(r.type()) || "synonym".equals(r.type()))) {
                int fuzzyScore = Math.max(45 - dist * 10, 20);
                score += fuzzyScore;
                reasons.add(new MatchReason("fuzzy", "Fuzzy ~" + keyName));
            }
        }

        // 5. Value Match
        if (!normVal.isEmpty() && normVal.contains(query)) {
            score += 30;
            reasons.add(new MatchReason("reason", "Value Match"));
        }

        return score > 0 ? new ScoredCandidate(score, reasons) : ScoredCandidate.none();
    }
}
