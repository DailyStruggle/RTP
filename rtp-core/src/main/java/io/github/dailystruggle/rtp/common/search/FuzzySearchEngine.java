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

    /**
     * Tri-state candidate resolution status for configuration and enum lookups.
     */
    public enum LookupStatus {
        /** Identical or normalized alphanumeric match. Silent, zero logging. */
        EXACT,
        /** Typo within edit distance thresholds with an unambiguous lead. Autocorrect and warn. */
        PERCEPTIBLE_TYPO,
        /** Edit distance exceeded or ambiguous match. Fallback to default and warn/error. */
        IMPERCEPTIBLE
    }

    /**
     * Immutable outcome of candidate resolution against a candidate map.
     *
     * @param <T> type of resolved target object
     */
    public record FuzzyLookupResult<T>(
            @NotNull LookupStatus status,
            @Nullable T match,
            @NotNull String matchedKey,
            @NotNull String rawInput,
            int editDistance,
            @NotNull List<String> availableCandidates
    ) {
        public FuzzyLookupResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(matchedKey, "matchedKey");
            Objects.requireNonNull(rawInput, "rawInput");
            Objects.requireNonNull(availableCandidates, "availableCandidates");
        }

        public boolean isExact() {
            return status == LookupStatus.EXACT;
        }

        public boolean isPerceptible() {
            return status == LookupStatus.PERCEPTIBLE_TYPO;
        }

        public boolean isImperceptible() {
            return status == LookupStatus.IMPERCEPTIBLE;
        }

        public boolean hasMatch() {
            return match != null;
        }

        public T orElse(T fallback) {
            return match != null ? match : fallback;
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

    /**
     * Resolves a raw input string against a candidate map using tri-state candidate resolution:
     * {@link LookupStatus#EXACT} (silent), {@link LookupStatus#PERCEPTIBLE_TYPO} (warn and autocorrect),
     * or {@link LookupStatus#IMPERCEPTIBLE} (fallback).
     *
     * <p>A candidate is categorized as a perceptible typo when its Levenshtein distance is within
     * the adaptive threshold (<=1 for length 3-4, <=2 for length >=5) and its score leads the
     * runner-up candidate by an unambiguity margin of at least 20 points.
     *
     * @param rawInput raw configured string to resolve
     * @param candidateMap map of candidate names to their target objects
     * @param maxDist maximum permitted edit distance
     * @param <T> type of resolved object
     * @return lookup result containing resolution status, match, and candidates
     */
    @NotNull
    public static <T> FuzzyLookupResult<T> resolveCandidate(
            @Nullable String rawInput,
            @NotNull Map<String, T> candidateMap,
            int maxDist
    ) {
        List<String> available = candidateMap.keySet().stream()
                .filter(Objects::nonNull)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();

        if (rawInput == null || rawInput.isBlank() || candidateMap.isEmpty()) {
            return new FuzzyLookupResult<>(
                    LookupStatus.IMPERCEPTIBLE,
                    null,
                    "",
                    rawInput == null ? "" : rawInput,
                    -1,
                    available
            );
        }

        String trimmed = rawInput.trim();

        // 1. Direct case-insensitive match (Exact)
        for (Map.Entry<String, T> entry : candidateMap.entrySet()) {
            String key = entry.getKey();
            if (key != null && key.equalsIgnoreCase(trimmed)) {
                return new FuzzyLookupResult<>(
                        LookupStatus.EXACT,
                        entry.getValue(),
                        key,
                        rawInput,
                        0,
                        available
                );
            }
        }

        // 2. Normalized alphanumeric match (Exact)
        String normInput = normalize(trimmed);
        if (!normInput.isEmpty()) {
            for (Map.Entry<String, T> entry : candidateMap.entrySet()) {
                String key = entry.getKey();
                if (key != null && normalize(key).equals(normInput)) {
                    return new FuzzyLookupResult<>(
                            LookupStatus.EXACT,
                            entry.getValue(),
                            key,
                            rawInput,
                            0,
                            available
                    );
                }
            }
        }

        // 3. Check minimum length for fuzzy matching
        if (normInput.length() < MIN_FUZZY_LENGTH) {
            return new FuzzyLookupResult<>(
                    LookupStatus.IMPERCEPTIBLE,
                    null,
                    "",
                    rawInput,
                    -1,
                    available
            );
        }

        // Bounded allowed distance: <= 1 for length 3-4, <= 2 for length 5-8, <= 3 for length >= 9 if maxDist >= 3
        int allowedDist;
        if (normInput.length() >= 9 && maxDist >= 3) {
            allowedDist = 3;
        } else if (normInput.length() >= LENGTH_THRESHOLD_TWO_EDITS) {
            allowedDist = Math.min(maxDist, 2);
        } else {
            allowedDist = Math.min(maxDist, 1);
        }

        if (allowedDist < 1) {
            return new FuzzyLookupResult<>(
                    LookupStatus.IMPERCEPTIBLE,
                    null,
                    "",
                    rawInput,
                    -1,
                    available
            );
        }

        // Score and collect fuzzy matches
        record CandidateMatch<T>(Map.Entry<String, T> entry, int score, int dist) {}
        List<CandidateMatch<T>> matches = new ArrayList<>();

        for (Map.Entry<String, T> entry : candidateMap.entrySet()) {
            String key = entry.getKey();
            if (key == null) continue;
            String normKey = normalize(key);
            if (normKey.isEmpty()) continue;

            int dist = levenshtein(normInput, normKey, allowedDist);
            if (dist <= allowedDist) {
                int score = 100 - (dist * 25);
                if (normKey.startsWith(normInput) || normInput.startsWith(normKey)) {
                    score += 10;
                }
                matches.add(new CandidateMatch<>(entry, score, dist));
            }
        }

        if (matches.isEmpty()) {
            return new FuzzyLookupResult<>(
                    LookupStatus.IMPERCEPTIBLE,
                    null,
                    "",
                    rawInput,
                    -1,
                    available
            );
        }

        // Sort descending by score, ascending by dist, then alphabetical
        matches.sort((a, b) -> {
            int c = Integer.compare(b.score(), a.score());
            if (c != 0) return c;
            c = Integer.compare(a.dist(), b.dist());
            if (c != 0) return c;
            return String.CASE_INSENSITIVE_ORDER.compare(a.entry().getKey(), b.entry().getKey());
        });

        CandidateMatch<T> best = matches.get(0);
        int runnerUpScore = (matches.size() > 1) ? matches.get(1).score() : 0;
        int delta = best.score() - runnerUpScore;

        // Unambiguity margin guard (delta >= 20)
        if (delta >= 20) {
            return new FuzzyLookupResult<>(
                    LookupStatus.PERCEPTIBLE_TYPO,
                    best.entry().getValue(),
                    best.entry().getKey(),
                    rawInput,
                    best.dist(),
                    available
            );
        }

        return new FuzzyLookupResult<>(
                LookupStatus.IMPERCEPTIBLE,
                null,
                "",
                rawInput,
                best.dist(),
                available
        );
    }

    @NotNull
    public static <T> FuzzyLookupResult<T> resolveCandidate(
            @Nullable String rawInput,
            @NotNull Map<String, T> candidateMap
    ) {
        return resolveCandidate(rawInput, candidateMap, MAX_EDIT_DISTANCE);
    }

    @NotNull
    public static FuzzyLookupResult<String> resolveCandidate(
            @Nullable String rawInput,
            @NotNull Collection<String> candidates,
            int maxDist
    ) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String c : candidates) {
            if (c != null) map.put(c, c);
        }
        return resolveCandidate(rawInput, map, maxDist);
    }

    @NotNull
    public static FuzzyLookupResult<String> resolveCandidate(
            @Nullable String rawInput,
            @NotNull Collection<String> candidates
    ) {
        return resolveCandidate(rawInput, candidates, MAX_EDIT_DISTANCE);
    }
}
