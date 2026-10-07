package io.github.dailystruggle.rtp.common.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FuzzySearchEngine - candidate resolution and unambiguity margin tests")
class FuzzySearchEngineCandidateTest {

    private final Map<String, String> shapes = Map.of(
            "CIRCLE", "CIRCLE",
            "SQUARE", "SQUARE",
            "RECTANGLE", "RECTANGLE",
            "POLYGON", "POLYGON"
    );

    @Test
    @DisplayName("Exact matching: case-insensitive and normalized matches return EXACT silently")
    void testExactMatches() {
        // Direct case-insensitive
        var res1 = FuzzySearchEngine.resolveCandidate("circle", shapes);
        assertEquals(FuzzySearchEngine.LookupStatus.EXACT, res1.status());
        assertEquals("CIRCLE", res1.match());
        assertEquals(0, res1.editDistance());

        var res2 = FuzzySearchEngine.resolveCandidate("SQUARE", shapes);
        assertEquals(FuzzySearchEngine.LookupStatus.EXACT, res2.status());
        assertEquals("SQUARE", res2.match());

        // Normalized match with symbols/spaces
        Map<String, String> props = Map.of("min_radius", "MIN_RADIUS", "max_radius", "MAX_RADIUS");
        var res3 = FuzzySearchEngine.resolveCandidate("min-radius", props);
        assertEquals(FuzzySearchEngine.LookupStatus.EXACT, res3.status());
        assertEquals("MIN_RADIUS", res3.match());

        var res4 = FuzzySearchEngine.resolveCandidate("minradius", props);
        assertEquals(FuzzySearchEngine.LookupStatus.EXACT, res4.status());
        assertEquals("MIN_RADIUS", res4.match());
    }

    @Test
    @DisplayName("Perceptible typo: edit distance <= 2 with unambiguous lead returns PERCEPTIBLE_TYPO")
    void testPerceptibleTypos() {
        // "circl" -> "CIRCLE" (distance 1, len 5)
        var res1 = FuzzySearchEngine.resolveCandidate("circl", shapes);
        assertEquals(FuzzySearchEngine.LookupStatus.PERCEPTIBLE_TYPO, res1.status());
        assertEquals("CIRCLE", res1.match());
        assertEquals(1, res1.editDistance());

        // "sqare" -> "SQUARE" (distance 1, len 5)
        var res2 = FuzzySearchEngine.resolveCandidate("sqare", shapes);
        assertEquals(FuzzySearchEngine.LookupStatus.PERCEPTIBLE_TYPO, res2.status());
        assertEquals("SQUARE", res2.match());
        assertEquals(1, res2.editDistance());

        // "rectangel" -> "RECTANGLE" (distance 2, len 9)
        var res3 = FuzzySearchEngine.resolveCandidate("rectangel", shapes);
        assertEquals(FuzzySearchEngine.LookupStatus.PERCEPTIBLE_TYPO, res3.status());
        assertEquals("RECTANGLE", res3.match());
        assertEquals(2, res3.editDistance());

        // Overload with Collection
        var res4 = FuzzySearchEngine.resolveCandidate("poligon", List.of("CIRCLE", "SQUARE", "POLYGON"));
        assertEquals(FuzzySearchEngine.LookupStatus.PERCEPTIBLE_TYPO, res4.status());
        assertEquals("POLYGON", res4.match());
    }

    @Test
    @DisplayName("Ambiguous matches: collision where top candidates tie or have delta < 20 returns IMPERCEPTIBLE")
    void testAmbiguousMatches() {
        // "CAT" with "BAT", "CAR", "MAT"
        Map<String, String> map = Map.of(
                "BAT", "BAT",
                "CAR", "CAR",
                "MAT", "MAT"
        );
        var res = FuzzySearchEngine.resolveCandidate("CAT", map);
        assertEquals(FuzzySearchEngine.LookupStatus.IMPERCEPTIBLE, res.status());
        assertNull(res.match());
        assertTrue(res.availableCandidates().contains("BAT"));
    }

    @Test
    @DisplayName("Imperceptible inputs: random gibberish or distance > threshold returns IMPERCEPTIBLE")
    void testImperceptibleInputs() {
        var res1 = FuzzySearchEngine.resolveCandidate("xyz12345", shapes);
        assertEquals(FuzzySearchEngine.LookupStatus.IMPERCEPTIBLE, res1.status());
        assertNull(res1.match());

        var res2 = FuzzySearchEngine.resolveCandidate("foobarqux", shapes);
        assertEquals(FuzzySearchEngine.LookupStatus.IMPERCEPTIBLE, res2.status());
        assertNull(res2.match());
    }

    @Test
    @DisplayName("Edge cases: null, empty, whitespace strings handled safely")
    void testEdgeCases() {
        var resNull = FuzzySearchEngine.resolveCandidate(null, shapes);
        assertEquals(FuzzySearchEngine.LookupStatus.IMPERCEPTIBLE, resNull.status());
        assertNull(resNull.match());

        var resEmpty = FuzzySearchEngine.resolveCandidate("", shapes);
        assertEquals(FuzzySearchEngine.LookupStatus.IMPERCEPTIBLE, resEmpty.status());
        assertNull(resEmpty.match());

        var resBlank = FuzzySearchEngine.resolveCandidate("   ", shapes);
        assertEquals(FuzzySearchEngine.LookupStatus.IMPERCEPTIBLE, resBlank.status());
        assertNull(resBlank.match());

        var resEmptyMap = FuzzySearchEngine.resolveCandidate("circle", Map.of());
        assertEquals(FuzzySearchEngine.LookupStatus.IMPERCEPTIBLE, resEmptyMap.status());
        assertNull(resEmptyMap.match());
        assertTrue(resEmptyMap.availableCandidates().isEmpty());
    }
}
