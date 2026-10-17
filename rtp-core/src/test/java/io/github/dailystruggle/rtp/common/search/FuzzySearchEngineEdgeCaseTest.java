package io.github.dailystruggle.rtp.common.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FuzzySearchEngine - null/empty guards, pruning bounds, scoring tiers, and resolution edge cases")
class FuzzySearchEngineEdgeCaseTest {

    @Test
    @DisplayName("FuzzyLookupResult accessors reflect status and match presence")
    void lookupResultAccessors() {
        var hit = new FuzzySearchEngine.FuzzyLookupResult<>(
                FuzzySearchEngine.LookupStatus.PERCEPTIBLE_TYPO, "CIRCLE", "CIRCLE", "circl", 1, List.of("CIRCLE"));
        assertTrue(hit.isPerceptible());
        assertFalse(hit.isExact());
        assertFalse(hit.isImperceptible());
        assertTrue(hit.hasMatch());
        assertEquals("CIRCLE", hit.orElse("fallback"));

        var miss = new FuzzySearchEngine.FuzzyLookupResult<String>(
                FuzzySearchEngine.LookupStatus.IMPERCEPTIBLE, null, "", "zzz", -1, List.of());
        assertTrue(miss.isImperceptible());
        assertFalse(miss.hasMatch());
        assertEquals("fallback", miss.orElse("fallback"));
    }

    @Test
    @DisplayName("ScoredCandidate.none is a zero score with no reasons")
    void scoredCandidateNone() {
        var none = FuzzySearchEngine.ScoredCandidate.none();
        assertEquals(0, none.score());
        assertTrue(none.reasons().isEmpty());
    }

    @Test
    @DisplayName("levenshtein handles empty operands on either side")
    void levenshteinEmptyOperands() {
        assertEquals(3, FuzzySearchEngine.levenshtein("", "abc"));
        assertEquals(3, FuzzySearchEngine.levenshtein("abc", ""));
    }

    @Test
    @DisplayName("bounded levenshtein handles null and empty operands within and beyond the bound")
    void boundedLevenshteinNullAndEmpty() {
        assertEquals(0, FuzzySearchEngine.levenshtein(null, null, 2));
        assertEquals(2, FuzzySearchEngine.levenshtein(null, "ab", 2));
        assertEquals(3, FuzzySearchEngine.levenshtein(null, "abcd", 2));
        assertEquals(2, FuzzySearchEngine.levenshtein("ab", null, 2));
        assertEquals(3, FuzzySearchEngine.levenshtein("abcd", null, 2));
        assertEquals(2, FuzzySearchEngine.levenshtein("", "ab", 2));
        assertEquals(2, FuzzySearchEngine.levenshtein("ab", "", 2));
        assertEquals(0, FuzzySearchEngine.levenshtein("same", "same", 2));
    }

    @Test
    @DisplayName("bounded levenshtein prunes when every row exceeds the bound")
    void boundedLevenshteinRowPruning() {
        assertEquals(3, FuzzySearchEngine.levenshtein("abcdef", "uvwxyz", 2));
        assertEquals(1, FuzzySearchEngine.levenshtein("abcdef", "abcdxf", 2));
    }

    @Test
    @DisplayName("keyMatches rejects null or empty operands and short or distant keys")
    void keyMatchesGuards() {
        assertFalse(FuzzySearchEngine.keyMatches(null, "radius"));
        assertFalse(FuzzySearchEngine.keyMatches("radius", null));
        assertFalse(FuzzySearchEngine.keyMatches("", "radius"));
        assertFalse(FuzzySearchEngine.keyMatches("radius", ""));
        assertFalse(FuzzySearchEngine.keyMatches("abc", "abcde"));
        assertFalse(FuzzySearchEngine.keyMatches("zzzzzz", "radius"));
    }

    @Test
    @DisplayName("nameMatches rejects null or empty operands and short or distant names")
    void nameMatchesGuards() {
        assertFalse(FuzzySearchEngine.nameMatches(null, "plugin"));
        assertFalse(FuzzySearchEngine.nameMatches("plugin", null));
        assertFalse(FuzzySearchEngine.nameMatches("", "plugin"));
        assertFalse(FuzzySearchEngine.nameMatches("plugin", ""));
        assertTrue(FuzzySearchEngine.nameMatches("better", "betterrtp"));
        assertFalse(FuzzySearchEngine.nameMatches("abcdef", "xyz"));
        assertFalse(FuzzySearchEngine.nameMatches("abc", "xyzab"));
        assertFalse(FuzzySearchEngine.nameMatches("abcdefgh", "zyxwvuts"));
    }

    @Test
    @DisplayName("scoreCandidate returns none for an empty normalized query or no signal")
    void scoreCandidateNoSignal() {
        assertEquals(0, FuzzySearchEngine.scoreCandidate("", "default.yml", "radius", "100", null).score());
        assertEquals(0, FuzzySearchEngine.scoreCandidate("!!!", "default.yml", "radius", "100", null).score());

        // Short substring query: no key match, fuzzy skipped, no value or file hit.
        var shortQuery = FuzzySearchEngine.scoreCandidate("di", "", "radius", null, null);
        assertEquals(0, shortQuery.score());
        assertTrue(shortQuery.reasons().isEmpty());
    }

    @Test
    @DisplayName("scoreCandidate scores mid-key containment and file name matches")
    void scoreCandidateContainmentAndFile() {
        var contains = FuzzySearchEngine.scoreCandidate("dius", "", "radius", "", Set.of());
        assertEquals(80, contains.score());
        assertTrue(contains.reasons().stream().anyMatch(r -> "Key Match".equals(r.text())));

        var file = FuzzySearchEngine.scoreCandidate("economy", "economy.yml", "price", "50", null);
        assertEquals(40, file.score());
        assertTrue(file.reasons().stream().anyMatch(r -> "file".equals(r.type())));
    }

    @Test
    @DisplayName("scoreCandidate synonym tier matches containment in either direction and skips unrelated synonyms")
    void scoreCandidateSynonymDirections() {
        var keyContainsSyn = FuzzySearchEngine.scoreCandidate("zzzz", "", "maxradius", null, Set.of("radius"));
        assertEquals(65, keyContainsSyn.score());

        var synContainsKey = FuzzySearchEngine.scoreCandidate("zzzz", "", "rad", null, Set.of("radius"));
        assertEquals(65, synContainsKey.score());

        var unrelated = FuzzySearchEngine.scoreCandidate("zzzz", "", "radius", null, Set.of("price", "cost"));
        assertEquals(0, unrelated.score());
    }

    @Test
    @DisplayName("scoreCandidate fuzzy tier is suppressed by synonym reasons but not by file reasons")
    void scoreCandidateFuzzySuppression() {
        var synonymOnly = FuzzySearchEngine.scoreCandidate("raduis", "", "radius", null, Set.of("radius"));
        assertEquals(65, synonymOnly.score());
        assertTrue(synonymOnly.reasons().stream().noneMatch(r -> "fuzzy".equals(r.type())));

        var fileThenFuzzy = FuzzySearchEngine.scoreCandidate("raduis", "raduis.yml", "radius", null, null);
        assertTrue(fileThenFuzzy.reasons().stream().anyMatch(r -> "file".equals(r.type())));
        assertTrue(fileThenFuzzy.reasons().stream().anyMatch(r -> "fuzzy".equals(r.type())));
        assertEquals(40 + 25, fileThenFuzzy.score());

        // Length 3-4 queries tolerate a single edit.
        var shortFuzzy = FuzzySearchEngine.scoreCandidate("rad", "", "red", "", null);
        assertEquals(35, shortFuzzy.score());

        // Value present but not matching adds nothing.
        var valueMiss = FuzzySearchEngine.scoreCandidate("radius", "", "radius", "other", null);
        assertEquals(120, valueMiss.score());
    }

    @Test
    @DisplayName("resolveCandidate skips null and non-alphanumeric keys and filters them from available candidates")
    void resolveCandidateNullAndEmptyKeys() {
        Map<String, String> map = new HashMap<>();
        map.put(null, "NULL");
        map.put("--", "DASHES");
        map.put("CIRCLE", "CIRCLE");

        var res = FuzzySearchEngine.resolveCandidate("circl", map);
        assertEquals(FuzzySearchEngine.LookupStatus.PERCEPTIBLE_TYPO, res.status());
        assertEquals("CIRCLE", res.match());
        assertFalse(res.availableCandidates().contains(null));

        var exactWithNull = FuzzySearchEngine.resolveCandidate("CIRCLE", map);
        assertTrue(exactWithNull.isExact());
    }

    @Test
    @DisplayName("resolveCandidate returns IMPERCEPTIBLE for punctuation-only or too-short inputs")
    void resolveCandidateShortInputs() {
        Map<String, String> map = Map.of("CIRCLE", "CIRCLE");

        var punct = FuzzySearchEngine.resolveCandidate("---", map);
        assertTrue(punct.isImperceptible());
        assertEquals(-1, punct.editDistance());

        var shortInput = FuzzySearchEngine.resolveCandidate("ci", map);
        assertTrue(shortInput.isImperceptible());
        assertEquals("ci", shortInput.rawInput());
    }

    @Test
    @DisplayName("resolveCandidate widens to three edits for long inputs and refuses zero tolerance")
    void resolveCandidateDistanceBounds() {
        Map<String, String> map = Map.of("RECTANGLE", "RECTANGLE", "CIRCLE", "CIRCLE");

        var threeEdits = FuzzySearchEngine.resolveCandidate("rectanglexyz", map, 3);
        assertEquals(FuzzySearchEngine.LookupStatus.PERCEPTIBLE_TYPO, threeEdits.status());
        assertEquals("RECTANGLE", threeEdits.match());
        assertEquals(3, threeEdits.editDistance());

        var zeroTolerance = FuzzySearchEngine.resolveCandidate("circl", map, 0);
        assertTrue(zeroTolerance.isImperceptible());
        assertEquals(-1, zeroTolerance.editDistance());
    }

    @Test
    @DisplayName("resolveCandidate stays IMPERCEPTIBLE when the lead over the runner-up is under the margin")
    void resolveCandidateNarrowLead() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("CIRCAL", "CIRCAL");
        map.put("CIRCLE", "CIRCLE");

        var res = FuzzySearchEngine.resolveCandidate("circl", map);
        assertTrue(res.isImperceptible());
        assertNull(res.match());
        assertEquals(1, res.editDistance());
    }

    @Test
    @DisplayName("resolveCandidate collection overload ignores null entries")
    void resolveCandidateCollectionWithNull() {
        var res = FuzzySearchEngine.resolveCandidate("poligon", Arrays.asList("CIRCLE", null, "POLYGON"), 2);
        assertEquals(FuzzySearchEngine.LookupStatus.PERCEPTIBLE_TYPO, res.status());
        assertEquals("POLYGON", res.match());
        assertEquals(List.of("CIRCLE", "POLYGON"), res.availableCandidates());
    }
}
