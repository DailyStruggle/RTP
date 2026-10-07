package io.github.dailystruggle.rtp.common.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FuzzySearchEngine - normalization, Levenshtein distance, adaptive fuzzy matching, and scoring")
class FuzzySearchEngineTest {

    @Test
    @DisplayName("normalize removes punctuation, spaces, symbols, and lowercases alphanumeric chars")
    void testNormalize() {
        assertEquals("", FuzzySearchEngine.normalize(null));
        assertEquals("", FuzzySearchEngine.normalize(""));
        assertEquals("", FuzzySearchEngine.normalize("   --- ___ ... @#$ "));
        assertEquals("minradius", FuzzySearchEngine.normalize("min-radius"));
        assertEquals("minradius", FuzzySearchEngine.normalize("min_radius"));
        assertEquals("minradius", FuzzySearchEngine.normalize("MinRadius"));
        assertEquals("minradius", FuzzySearchEngine.normalize("min.radius"));
        assertEquals("shape2center", FuzzySearchEngine.normalize("shape[2].center"));
    }

    @Test
    @DisplayName("levenshtein calculates correct edit distance")
    void testLevenshtein() {
        assertEquals(0, FuzzySearchEngine.levenshtein(null, null));
        assertEquals(3, FuzzySearchEngine.levenshtein(null, "abc"));
        assertEquals(3, FuzzySearchEngine.levenshtein("abc", null));
        assertEquals(0, FuzzySearchEngine.levenshtein("radius", "radius"));
        assertEquals(2, FuzzySearchEngine.levenshtein("raduis", "radius"));
        assertEquals(1, FuzzySearchEngine.levenshtein("radiu", "radius"));
        assertEquals(1, FuzzySearchEngine.levenshtein("radiuss", "radius"));
        assertEquals(1, FuzzySearchEngine.levenshtein("radxus", "radius"));
    }

    @Test
    @DisplayName("levenshtein with maxDist prunes early when distance exceeds limit")
    void testLevenshteinWithMaxDist() {
        assertEquals(2, FuzzySearchEngine.levenshtein("raduis", "radius", 2));
        assertEquals(2, FuzzySearchEngine.levenshtein("raduis", "radius", 1)); // exceeds 1 -> returns maxDist + 1 = 2
        assertEquals(3, FuzzySearchEngine.levenshtein("verylongstring", "short", 2)); // length diff > maxDist -> returns 3
    }

    @Test
    @DisplayName("isFuzzyMatch applies adaptive thresholding based on token length")
    void testIsFuzzyMatch() {
        assertFalse(FuzzySearchEngine.isFuzzyMatch(null, "radius"));
        assertFalse(FuzzySearchEngine.isFuzzyMatch("radius", null));
        assertTrue(FuzzySearchEngine.isFuzzyMatch("radius", "radius"));

        // Length < 3: exact only
        assertFalse(FuzzySearchEngine.isFuzzyMatch("ab", "ac"));
        assertTrue(FuzzySearchEngine.isFuzzyMatch("ab", "ab"));

        // Length 3-4: edit distance 1 max
        assertTrue(FuzzySearchEngine.isFuzzyMatch("rad", "red")); // dist 1
        assertFalse(FuzzySearchEngine.isFuzzyMatch("rad", "rot")); // dist 2

        // Length >= 5: edit distance 2 max
        assertTrue(FuzzySearchEngine.isFuzzyMatch("raduis", "radius")); // dist 2
        assertTrue(FuzzySearchEngine.isFuzzyMatch("cooldwon", "cooldown")); // dist 2
        assertFalse(FuzzySearchEngine.isFuzzyMatch("randomword", "radius")); // dist > 2
    }

    @Test
    @DisplayName("keyMatches and nameMatches satisfy permission derivation contract")
    void testKeyAndNameMatches() {
        assertTrue(FuzzySearchEngine.keyMatches("radius", "radius"));
        assertTrue(FuzzySearchEngine.keyMatches("maxradius", "radius")); // containment (term len >= 5)
        assertTrue(FuzzySearchEngine.keyMatches("raduis", "radius")); // Levenshtein <= 2
        assertFalse(FuzzySearchEngine.keyMatches("size", "rad")); // term len < 5

        assertTrue(FuzzySearchEngine.nameMatches("betterrtp", "betterrtp"));
        assertTrue(FuzzySearchEngine.nameMatches("betterrtp", "better")); // containment (len >= 4)
        assertTrue(FuzzySearchEngine.nameMatches("foortp", "foorpt")); // Levenshtein <= 2
        assertFalse(FuzzySearchEngine.nameMatches("rtp", "foortp")); // "rtp" len < 4 containment
    }

    @Test
    @DisplayName("scoreCandidate scores exact, prefix, synonym, fuzzy, and value matches correctly")
    void testScoreCandidate() {
        // Exact key match
        var exact = FuzzySearchEngine.scoreCandidate("radius", "default.yml", "radius", "100", Set.of());
        assertEquals(120, exact.score());
        assertTrue(exact.reasons().stream().anyMatch(r -> "Exact Key".equals(r.text())));

        // Prefix key match
        var prefix = FuzzySearchEngine.scoreCandidate("rad", "default.yml", "radius", "100", Set.of());
        assertEquals(80, prefix.score());
        assertTrue(prefix.reasons().stream().anyMatch(r -> "Key Match".equals(r.text())));

        // Synonym match
        var syn = FuzzySearchEngine.scoreCandidate("dinero", "economy.yml", "price", "50", Set.of("price"));
        assertEquals(65, syn.score());
        assertTrue(syn.reasons().stream().anyMatch(r -> r.text().startsWith("Similar:")));

        // Fuzzy typo match
        var fuzzy = FuzzySearchEngine.scoreCandidate("raduis", "default.yml", "radius", "100", Set.of());
        assertTrue(fuzzy.score() >= 20);
        assertTrue(fuzzy.reasons().stream().anyMatch(r -> r.text().startsWith("Fuzzy ~")));

        // Value match
        var val = FuzzySearchEngine.scoreCandidate("customworld", "worlds.yml", "region", "customworld", Set.of());
        assertTrue(val.score() >= 30);
        assertTrue(val.reasons().stream().anyMatch(r -> "Value Match".equals(r.text())));
    }
}
