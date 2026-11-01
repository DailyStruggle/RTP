package io.github.dailystruggle.rtp.common.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ThesaurusIndex - packaged resource loading, bidirectional indexing, and query expansion")
class ThesaurusIndexTest {

    @Test
    @DisplayName("ThesaurusIndex loads packaged editor-data.json synonyms")
    void testPackagedThesaurusLoads() {
        ThesaurusIndex index = ThesaurusIndex.getInstance();
        assertNotNull(index);

        // Concept to keys check
        Set<String> moneyKeys = index.getKeysForConcept("money");
        assertFalse(moneyKeys.isEmpty(), "money concept should map to keys");
        assertTrue(moneyKeys.contains("price"), "money should map to price");

        // Multilingual words (Spanish, German, Polish, French)
        assertTrue(index.getKeysForConcept("dinero").contains("price"));
        assertTrue(index.getKeysForConcept("geld").contains("price"));
        assertTrue(index.getKeysForConcept("argent").contains("price"));
        assertTrue(index.getKeysForConcept("pieniadze").contains("price"));

        // Reverse check: key to concepts
        Set<String> priceConcepts = index.getConceptsForKey("price");
        assertTrue(priceConcepts.contains("money"));
        assertTrue(priceConcepts.contains("dinero"));
        assertTrue(priceConcepts.contains("cost"));
    }

    @Test
    @DisplayName("hasSynonymMatch identifies direct and indirect synonym matches")
    void testHasSynonymMatch() {
        ThesaurusIndex index = ThesaurusIndex.getInstance();
        assertTrue(index.hasSynonymMatch("cost", List.of("price")));
        assertTrue(index.hasSynonymMatch("dinero", List.of("price")));
        assertTrue(index.hasSynonymMatch("price", List.of("cost")));
        assertTrue(index.hasSynonymMatch("rad", List.of("radius")));
        assertFalse(index.hasSynonymMatch("shape", List.of("radius")));
        assertFalse(index.hasSynonymMatch("unrelatedkeyword", List.of("price", "radius")));
    }

    @Test
    @DisplayName("getMatchingSynonyms expands tokens with typo tolerance")
    void testGetMatchingSynonyms() {
        ThesaurusIndex index = ThesaurusIndex.getInstance();

        // Exact concept expansion
        Set<String> moneySyns = index.getMatchingSynonyms(List.of("money"));
        assertTrue(moneySyns.contains("price"));

        // Typo in concept ("raduis" -> radius -> shape, range, etc.)
        Set<String> typoSyns = index.getMatchingSynonyms(List.of("raduis"));
        assertTrue(typoSyns.contains("radius"));
    }

    @Test
    @DisplayName("getConceptTerms groups canonical concept spellings with thesaurus terms")
    void testGetConceptTerms() {
        ThesaurusIndex index = ThesaurusIndex.getInstance();
        Map<String, Set<String>> canonical = Map.of(
                "radius", Set.of("radius", "range"),
                "cost", Set.of("price")
        );

        Map<String, Set<String>> grouped = index.getConceptTerms(canonical);
        assertNotNull(grouped);
        assertTrue(grouped.containsKey("radius"));
        assertTrue(grouped.containsKey("cost"));

        // Should contain canonical keys
        assertTrue(grouped.get("radius").contains("radius"));
        assertTrue(grouped.get("cost").contains("price"));

        // Should include thesaurus synonym words mapped to price
        assertTrue(grouped.get("cost").contains("money") || grouped.get("cost").contains("dinero"));
    }

    @Test
    @DisplayName("fromMap allows custom in-memory thesaurus")
    void testCustomThesaurus() {
        ThesaurusIndex custom = ThesaurusIndex.fromMap(Map.of(
                "speed", List.of("velocity", "rate"),
                "jump", List.of("leap", "hop")
        ));

        assertTrue(custom.getKeysForConcept("speed").contains("velocity"));
        assertTrue(custom.getConceptsForKey("velocity").contains("speed"));
        assertTrue(custom.hasSynonymMatch("speed", List.of("velocity")));
        assertFalse(custom.hasSynonymMatch("speed", List.of("leap")));
    }
}
