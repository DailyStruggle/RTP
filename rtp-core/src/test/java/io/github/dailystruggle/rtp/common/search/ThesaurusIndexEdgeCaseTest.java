package io.github.dailystruggle.rtp.common.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ThesaurusIndex - resource fallbacks, malformed entries, guard clauses, and shape/radius exclusion")
class ThesaurusIndexEdgeCaseTest {

    @Test
    @DisplayName("load returns an empty index when the resource is missing")
    void loadMissingResource() {
        ThesaurusIndex index = ThesaurusIndex.load("/thesaurus/does-not-exist.json");
        assertTrue(index.getKeysForConcept("speed").isEmpty());
        assertTrue(index.getMatchingSynonyms(List.of("speed")).isEmpty());
    }

    @Test
    @DisplayName("load accepts a raw synonyms object and skips blank, symbol-only, non-string, and non-list entries")
    void loadRawSynonymsWithoutEnvelope() {
        ThesaurusIndex index = ThesaurusIndex.load("/thesaurus/raw-synonyms.json");
        assertEquals(Set.of("velocity", "rate"), index.getKeysForConcept("speed"));
        assertEquals(Set.of("speed"), index.getConceptsForKey("velocity"));
        assertTrue(index.getKeysForConcept("jump").isEmpty());
        assertTrue(index.getConceptsForKey("ignored").isEmpty());
    }

    @Test
    @DisplayName("load returns an empty index for a non-object JSON root")
    void loadArrayRoot() {
        ThesaurusIndex index = ThesaurusIndex.load("/thesaurus/array-root.json");
        assertTrue(index.getKeysForConcept("speed").isEmpty());
    }

    @Test
    @DisplayName("load returns an empty index for malformed JSON instead of throwing")
    void loadMalformedJson() {
        ThesaurusIndex index = assertDoesNotThrow(() -> ThesaurusIndex.load("/thesaurus/malformed.json"));
        assertTrue(index.getKeysForConcept("speed").isEmpty());
    }

    @Test
    @DisplayName("fromMap skips non-string keys and non-collection values")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void fromMapSkipsInvalidEntries() {
        Map raw = new HashMap();
        raw.put(42, List.of("answer"));
        raw.put("jump", "leap");
        raw.put("speed", Arrays.asList("velocity", null, ""));
        ThesaurusIndex index = ThesaurusIndex.fromMap(raw);

        assertEquals(Set.of("velocity"), index.getKeysForConcept("speed"));
        assertTrue(index.getKeysForConcept("42").isEmpty());
        assertTrue(index.getConceptsForKey("answer").isEmpty());
        assertTrue(index.getKeysForConcept("jump").isEmpty());
    }

    @Test
    @DisplayName("concept and key lookups return empty sets for null or unknown input")
    void lookupsNullAndUnknown() {
        ThesaurusIndex index = ThesaurusIndex.fromMap(Map.of("speed", List.of("velocity")));
        assertTrue(index.getKeysForConcept(null).isEmpty());
        assertTrue(index.getKeysForConcept("unknown").isEmpty());
        assertTrue(index.getConceptsForKey(null).isEmpty());
        assertTrue(index.getConceptsForKey("unknown").isEmpty());
    }

    @Test
    @DisplayName("hasSynonymMatch rejects null, empty, and symbol-only inputs")
    void hasSynonymMatchGuards() {
        ThesaurusIndex index = ThesaurusIndex.fromMap(Map.of("speed", List.of("velocity")));
        assertFalse(index.hasSynonymMatch(null, List.of("velocity")));
        assertFalse(index.hasSynonymMatch("speed", null));
        assertFalse(index.hasSynonymMatch("speed", List.of()));
        assertFalse(index.hasSynonymMatch("---", List.of("velocity")));
        assertFalse(index.hasSynonymMatch("speed", List.of("---")));
        assertTrue(index.hasSynonymMatch("speed", List.of("---", "unrelated", "velocity")));
        assertTrue(index.hasSynonymMatch("Speed", List.of("SPEED")));
    }

    @Test
    @DisplayName("hasSynonymMatch excludes only the shape-to-radius pair in both lookup directions")
    void hasSynonymMatchShapeRadiusExclusion() {
        ThesaurusIndex forward = ThesaurusIndex.fromMap(Map.of("shape", List.of("radius", "circle")));
        assertFalse(forward.hasSynonymMatch("shape", List.of("radius")));
        assertTrue(forward.hasSynonymMatch("shape", List.of("circle")));

        ThesaurusIndex reverse = ThesaurusIndex.fromMap(Map.of(
                "radius", List.of("shape"),
                "form", List.of("shape")));
        assertFalse(reverse.hasSynonymMatch("shape", List.of("radius")));
        assertTrue(reverse.hasSynonymMatch("shape", List.of("form")));
    }

    @Test
    @DisplayName("getMatchingSynonyms ignores null, empty, and symbol-only tokens")
    void getMatchingSynonymsGuards() {
        ThesaurusIndex index = ThesaurusIndex.fromMap(Map.of("speed", List.of("velocity", "rate")));
        assertTrue(index.getMatchingSynonyms(null).isEmpty());
        assertTrue(index.getMatchingSynonyms(List.of()).isEmpty());
        assertTrue(index.getMatchingSynonyms(Arrays.asList(null, "---")).isEmpty());
    }

    @Test
    @DisplayName("getMatchingSynonyms expands by concept containment, synonym containment, and synonym typos")
    void getMatchingSynonymsExpansionPaths() {
        ThesaurusIndex index = ThesaurusIndex.fromMap(Map.of("speed", List.of("velocity", "rate")));
        Set<String> all = Set.of("speed", "velocity", "rate");

        assertEquals(all, index.getMatchingSynonyms(List.of("speedy")));
        assertEquals(all, index.getMatchingSynonyms(List.of("velocityx")));
        assertEquals(all, index.getMatchingSynonyms(List.of("velocty")));
        assertEquals(all, index.getMatchingSynonyms(List.of("sped")));
    }

    @Test
    @DisplayName("getMatchingSynonyms returns nothing for short or distant non-matching tokens")
    void getMatchingSynonymsNoMatch() {
        ThesaurusIndex index = ThesaurusIndex.fromMap(Map.of("speed", List.of("velocity", "rate")));
        assertTrue(index.getMatchingSynonyms(List.of("zq")).isEmpty());
        assertTrue(index.getMatchingSynonyms(List.of("qqqqqq")).isEmpty());
        assertTrue(index.getMatchingSynonyms(List.of("zzz")).isEmpty());
    }
}
