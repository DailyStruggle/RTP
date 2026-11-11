package io.github.dailystruggle.search;

import java.util.Collection;
import java.util.List;

/**
 * Pure-Java fuzzy search and string matching engine ({@code :yaml-api}).
 *
 * @governedBy ADR-104, ADR-115
 */
public final class FuzzySearchEngine {

    public static final int MAX_EDIT_DISTANCE =
            io.github.dailystruggle.rtp.common.search.FuzzySearchEngine.MAX_EDIT_DISTANCE;
    public static final int MIN_FUZZY_LENGTH =
            io.github.dailystruggle.rtp.common.search.FuzzySearchEngine.MIN_FUZZY_LENGTH;
    public static final int LENGTH_THRESHOLD_TWO_EDITS =
            io.github.dailystruggle.rtp.common.search.FuzzySearchEngine.LENGTH_THRESHOLD_TWO_EDITS;
    public static final int MIN_CONTAINMENT_LENGTH =
            io.github.dailystruggle.rtp.common.search.FuzzySearchEngine.MIN_CONTAINMENT_LENGTH;

    public record MatchReason(String type, String text) {}

    public record ScoredCandidate(int score, List<MatchReason> reasons) {}

    public record FuzzyLookupResult<T>(
            T match,
            int distance,
            boolean exact,
            boolean perceptible,
            boolean imperceptible
    ) {
        public boolean hasMatch() {
            return match != null;
        }
    }

    private FuzzySearchEngine() {}

    public static String normalize(String input) {
        return io.github.dailystruggle.rtp.common.search.FuzzySearchEngine.normalize(input);
    }

    public static int levenshtein(String a, String b) {
        return io.github.dailystruggle.rtp.common.search.FuzzySearchEngine.levenshtein(a, b);
    }

    public static int levenshtein(String a, String b, int maxDist) {
        return io.github.dailystruggle.rtp.common.search.FuzzySearchEngine.levenshtein(a, b, maxDist);
    }

    public static boolean isFuzzyMatch(String query, String candidate) {
        return io.github.dailystruggle.rtp.common.search.FuzzySearchEngine.isFuzzyMatch(query, candidate);
    }

    public static ScoredCandidate scoreCandidateCascade(
            String query,
            String filePath,
            String leafName,
            String valueText,
            OnlineQueryExpander onlineExpander,
            long nowEpochMs) {
        var res = io.github.dailystruggle.rtp.common.search.FuzzySearchEngine.scoreCandidateCascade(
                query,
                filePath,
                leafName,
                valueText,
                onlineExpander != null ? onlineExpander.delegate() : null,
                nowEpochMs);
        List<MatchReason> reasons = res.reasons().stream()
                .map(r -> new MatchReason(r.type(), r.text()))
                .toList();
        return new ScoredCandidate(res.score(), reasons);
    }

    public static FuzzyLookupResult<String> resolveCandidate(
            String query,
            Collection<String> candidates,
            int maxDistance) {
        var res = io.github.dailystruggle.rtp.common.search.FuzzySearchEngine.resolveCandidate(
                query, candidates, maxDistance);
        return new FuzzyLookupResult<>(
                res.match(),
                res.editDistance(),
                res.isExact(),
                res.isPerceptible(),
                res.isImperceptible());
    }
}
