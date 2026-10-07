package io.github.dailystruggle.rtp.common.search;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.editor.EditorLoopbackJson;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;

/**
 * Thread-safe, cached in-memory thesaurus index backed by the packaged
 * {@code /editor/editor-data.json} (or {@code docs/editor/search-synonyms.json}).
 *
 * <p>Provides bidirectional concept-to-keys and key-to-concepts mappings,
 * query token expansion with typo tolerance, and concept grouping for foreign
 * config importers, permission derivation, and in-game config search.
 */
public final class ThesaurusIndex {

    public static final String THESAURUS_RESOURCE = "/editor/editor-data.json";

    private static volatile ThesaurusIndex instance;

    private final Map<String, Set<String>> conceptToKeys;
    private final Map<String, Set<String>> keyToConcepts;

    private ThesaurusIndex(@NotNull Map<String, Set<String>> conceptToKeys,
                           @NotNull Map<String, Set<String>> keyToConcepts) {
        this.conceptToKeys = Collections.unmodifiableMap(conceptToKeys);
        this.keyToConcepts = Collections.unmodifiableMap(keyToConcepts);
    }

    /**
     * Singleton instance loaded lazily from the packaged {@link #THESAURUS_RESOURCE}.
     */
    @NotNull
    public static ThesaurusIndex getInstance() {
        ThesaurusIndex current = instance;
        if (current != null) return current;
        synchronized (ThesaurusIndex.class) {
            if (instance == null) {
                instance = load(THESAURUS_RESOURCE);
            }
            return instance;
        }
    }

    /**
     * Loads a thesaurus from a classpath resource JSON payload.
     */
    @NotNull
    public static ThesaurusIndex load(@NotNull String resourcePath) {
        Map<String, Set<String>> conceptMap = new LinkedHashMap<>();
        Map<String, Set<String>> keyMap = new LinkedHashMap<>();

        try (InputStream in = ThesaurusIndex.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                RTP.log(Level.FINE, "[RTP] thesaurus resource missing: " + resourcePath);
                return new ThesaurusIndex(conceptMap, keyMap);
            }

            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Object root = EditorLoopbackJson.parse(content);
            Map<?, ?> synonymsSection = null;

            if (root instanceof Map<?, ?> m) {
                if (m.get("synonyms") instanceof Map<?, ?> syn) {
                    synonymsSection = syn;
                } else {
                    // Raw synonyms json without root envelope
                    synonymsSection = m;
                }
            }

            if (synonymsSection != null) {
                populate(synonymsSection, conceptMap, keyMap);
            }
        } catch (IOException | RuntimeException e) {
            RTP.log(Level.FINE, "[RTP] thesaurus resource unreadable (" + resourcePath + "): " + e);
        }

        return new ThesaurusIndex(conceptMap, keyMap);
    }

    /**
     * Creates an index from an in-memory synonyms map.
     */
    @NotNull
    public static ThesaurusIndex fromMap(@NotNull Map<String, ? extends Collection<String>> synonyms) {
        Map<String, Set<String>> conceptMap = new LinkedHashMap<>();
        Map<String, Set<String>> keyMap = new LinkedHashMap<>();
        populate(synonyms, conceptMap, keyMap);
        return new ThesaurusIndex(conceptMap, keyMap);
    }

    private static void populate(Map<?, ?> rawSynonyms,
                                 Map<String, Set<String>> conceptMap,
                                 Map<String, Set<String>> keyMap) {
        for (Map.Entry<?, ?> e : rawSynonyms.entrySet()) {
            if (!(e.getKey() instanceof String word) || word.isBlank()) continue;
            String normWord = FuzzySearchEngine.normalize(word);
            if (normWord.isEmpty()) continue;

            Set<String> keys = conceptMap.computeIfAbsent(normWord, k -> new LinkedHashSet<>());
            if (e.getValue() instanceof Collection<?> list) {
                for (Object o : list) {
                    if (!(o instanceof String k) || k.isBlank()) continue;
                    String normKey = FuzzySearchEngine.normalize(k);
                    if (!normKey.isEmpty()) {
                        keys.add(normKey);
                        keyMap.computeIfAbsent(normKey, x -> new LinkedHashSet<>()).add(normWord);
                    }
                }
            }
        }
    }

    /**
     * Set of normalized keys associated with a concept word.
     */
    @NotNull
    public Set<String> getKeysForConcept(@Nullable String concept) {
        if (concept == null) return Set.of();
        Set<String> res = conceptToKeys.get(FuzzySearchEngine.normalize(concept));
        return res != null ? res : Set.of();
    }

    /**
     * Set of normalized concept words that map to a specific config key.
     */
    @NotNull
    public Set<String> getConceptsForKey(@Nullable String key) {
        if (key == null) return Set.of();
        Set<String> res = keyToConcepts.get(FuzzySearchEngine.normalize(key));
        return res != null ? res : Set.of();
    }

    /**
     * True if the candidate key matches any of the target keys directly or via thesaurus synonym.
     */
    public boolean hasSynonymMatch(@Nullable String candidate, @Nullable Collection<String> targets) {
        if (candidate == null || targets == null || targets.isEmpty()) return false;
        String normCandidate = FuzzySearchEngine.normalize(candidate);
        if (normCandidate.isEmpty()) return false;

        for (String target : targets) {
            String normTarget = FuzzySearchEngine.normalize(target);
            if (normTarget.isEmpty()) continue;
            if (normCandidate.equals(normTarget)) return true;

            // Direct concept to key match (e.g. candidate="dinero", target="price")
            Set<String> keys = conceptToKeys.get(normCandidate);
            if (keys != null && keys.contains(normTarget)) {
                if (!("shape".equals(normCandidate) && "radius".equals(normTarget))) {
                    return true;
                }
            }

            // Reverse key to concept match (e.g. candidate="price", target="cost")
            Set<String> concepts = keyToConcepts.get(normCandidate);
            if (concepts != null && concepts.contains(normTarget)) {
                if (!("shape".equals(normCandidate) && "radius".equals(normTarget))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Expands query tokens into matching thesaurus synonym words and keys.
     * Follows ADR-104 Section 4.5 parity with the web editor search engine.
     */
    @NotNull
    public Set<String> getMatchingSynonyms(@Nullable Collection<String> tokens) {
        Set<String> matched = new LinkedHashSet<>();
        if (tokens == null || tokens.isEmpty()) return matched;

        for (String rawTok : tokens) {
            if (rawTok == null) continue;
            String tok = FuzzySearchEngine.normalize(rawTok);
            if (tok.isEmpty()) continue;

            for (Map.Entry<String, Set<String>> entry : conceptToKeys.entrySet()) {
                String ck = entry.getKey();
                Set<String> synList = entry.getValue();

                // 1. Direct or substring match on concept key (e.g. "rad" in "radius")
                if (ck.equals(tok) || ck.contains(tok) || tok.contains(ck)) {
                    matched.add(ck);
                    matched.addAll(synList);
                } else if (tok.length() >= 3) {
                    // 2. Fuzzy match on concept key (e.g. typos like "raduis" -> "radius")
                    int maxDist = tok.length() >= 5 ? 2 : 1;
                    if (FuzzySearchEngine.levenshtein(tok, ck, maxDist) <= maxDist) {
                        matched.add(ck);
                        matched.addAll(synList);
                    }
                }

                // 3. Match against items in the synonym list
                for (String syn : synList) {
                    if (syn.equals(tok) || syn.contains(tok) || tok.contains(syn)) {
                        matched.add(syn);
                        matched.add(ck);
                        matched.addAll(synList);
                        break;
                    }
                    if (tok.length() >= 3) {
                        int maxDist = tok.length() >= 5 ? 2 : 1;
                        if (FuzzySearchEngine.levenshtein(tok, syn, maxDist) <= maxDist) {
                            matched.add(syn);
                            matched.add(ck);
                            matched.addAll(synList);
                            break;
                        }
                    }
                }
            }
        }
        return matched;
    }

    /**
     * Constructs a map of concept name to all associated normalized terms, combining
     * canonical spellings and all thesaurus words mapped to those spellings.
     * Replaces custom concept parsing in permission source derivation.
     */
    @NotNull
    public Map<String, Set<String>> getConceptTerms(@NotNull Map<String, Set<String>> canonicalMap) {
        Map<String, Set<String>> terms = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : canonicalMap.entrySet()) {
            terms.put(e.getKey(), new HashSet<>(e.getValue()));
        }

        for (Map.Entry<String, Set<String>> entry : conceptToKeys.entrySet()) {
            String word = entry.getKey();
            if (word.isEmpty()) continue;
            for (Map.Entry<String, Set<String>> c : canonicalMap.entrySet()) {
                for (String key : entry.getValue()) {
                    if (c.getValue().contains(key)) {
                        terms.get(c.getKey()).add(word);
                        break;
                    }
                }
            }
        }

        Map<String, Set<String>> frozen = new LinkedHashMap<>();
        terms.forEach((k, v) -> frozen.put(k, Set.copyOf(v)));
        return Collections.unmodifiableMap(frozen);
    }
}
