package io.github.dailystruggle.rtp.common.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    @DisplayName("SearchThesaurus provides bidirectional synsets, multi-word phrase rewrites, and compound splitting")
    void testSearchThesaurusIntegration() {
        SearchThesaurus thesaurus = SearchThesaurus.defaultInstance();

        // Canonical root collapsing
        assertEquals("money", thesaurus.canonicalRoot("bucks"));
        assertEquals("money", thesaurus.canonicalRoot("cash"));
        assertEquals("money", thesaurus.canonicalRoot("dinero"));
        assertEquals("leave", thesaurus.canonicalRoot("escape"));
        assertEquals("spawn", thesaurus.canonicalRoot("hub"));
        assertEquals("friend", thesaurus.canonicalRoot("teammate"));

        // Bidirectional synonym check
        assertTrue(thesaurus.areSynonyms("bucks", "coins"));
        assertTrue(thesaurus.areSynonyms("escape", "exit"));
        assertTrue(thesaurus.areSynonyms("hub", "lobby"));
        assertFalse(thesaurus.areSynonyms("shape", "radius"));
        assertFalse(thesaurus.areSynonyms("frog", "spawn"));

        // Multi-word phrase normalization & compound splitting
        assertTrue(thesaurus.normalizePhrases("how to fast travel out of the hub").contains("teleport"));
        assertTrue(thesaurus.normalizePhrases("how to fast travel out of the hub").contains("leave spawn"));
        assertTrue(thesaurus.splitCompoundToken("tpdelay").contains("teleportdelay"));
        assertTrue(thesaurus.splitCompoundToken("worldborder").contains("border"));

        // Automatic thesaurus scoring via FuzzySearchEngine
        var scored = FuzzySearchEngine.scoreCandidateWithThesaurus("bucks", "economy.yml", "price", "50");
        assertTrue(scored.score() >= 65, "bucks should match price via SearchThesaurus");
    }

    @Test
    @DisplayName("OnlineQueryExpander persists file-backed cache, enforces TTL invalidation, and serves stale-while-revalidate without network blocking")
    void testOnlineQueryExpanderFileCacheAndTtlInvalidation(@TempDir Path tempDir) {
        Path cacheFile = tempDir.resolve("online-query-expansions.tsv");
        AtomicInteger networkCalls = new AtomicInteger(0);

        OnlineQueryExpander expander = new OnlineQueryExpander(
                cacheFile,
                10_000L, // 10s positive TTL
                2_000L,  // 2s negative TTL
                128,
                true,
                token -> {
                    networkCalls.incrementAndGet();
                    if ("hearth".equals(token)) {
                        return Set.of("home", "sethome", "sanctuary");
                    }
                    return Set.of();
                }
        );

        // 1. Strict sanitization (ADR-107 §4.5)
        assertEquals("", OnlineQueryExpander.sanitizeToken("https://evil.example/x"));
        assertEquals("", OnlineQueryExpander.sanitizeToken("<script>alert(1)</script>"));
        assertEquals("hearth", OnlineQueryExpander.sanitizeToken("  Hearth "));

        // 2. Cold fetch + disk persistence
        long t0 = 1_000_000L;
        Set<String> fetched = expander.expandWithBoundedWait("hearth", 500L, t0);
        assertTrue(fetched.contains("home"), "Cold expansion must return remote words");
        assertEquals(1, networkCalls.get(), "Exactly 1 network fetch on cold miss");
        assertTrue(expander.stats().diskFlushes() >= 1, "Cache entry must be flushed to TSV file");

        // 3. Reload from disk into a fresh instance with offline/failing network fetcher (0ms network timing!)
        OnlineQueryExpander reloaded = new OnlineQueryExpander(
                cacheFile,
                10_000L,
                2_000L,
                128,
                false,
                token -> {
                    throw new AssertionError("Disk-cached fresh hit must never invoke network fetcher");
                }
        );
        assertEquals(1, reloaded.stats().diskEntriesLoaded(), "Fresh instance must load non-expired entry from disk");
        Set<String> diskHit = reloaded.getCachedOrScheduleAsync("hearth", t0 + 5_000L);
        assertTrue(diskHit.contains("home") && diskHit.contains("sethome"), "Fresh TTL hit must be served from disk-loaded cache");
        assertEquals(1, reloaded.stats().cacheHits());
        assertEquals(0, reloaded.stats().ttlInvalidations());

        // 4. Advance clock past TTL (t0 + 15_000L > t0 + 10_000L): TTL invalidates, serves stale-while-revalidate
        Set<String> staleWhileRevalidate = reloaded.getCachedOrScheduleAsync("hearth", t0 + 15_000L);
        assertTrue(staleWhileRevalidate.contains("home"), "Positive expired entry should serve stale terms immediately without blocking");
        assertEquals(1, reloaded.stats().ttlInvalidations(), "Expired entry must record TTL invalidation");
        assertEquals(1, reloaded.stats().staleHitsServed(), "Expired positive entry must record stale-while-revalidate hit");

        // 5. Explicit evictExpired removes expired entries and updates disk
        expander.putCacheEntry("ephemeral", List.of("tempword"), t0);
        int evicted = expander.evictExpired(t0 + 25_000L);
        assertTrue(evicted >= 1, "evictExpired must prune entries past positiveTtlMs");
    }

    @Test
    @DisplayName("Three-Stage Local-First Cascade prevents synonym/online pollution and resolves 12-language cross-lingual pivots")
    void testThreeStageLocalFirstCascadeAndCrossLingualPivots(@TempDir Path tempDir) {
        SearchThesaurus thesaurus = SearchThesaurus.defaultInstance();
        OnlineQueryExpander onlineExpander = new OnlineQueryExpander(
                tempDir.resolve("cascade-cache.tsv"),
                60_000L,
                5_000L,
                128,
                false,
                tok -> Set.of()
        );
        long now = 2_000_000L;
        onlineExpander.putCacheEntry("hearthstone", List.of("sethome", "home"), now);

        // 1. Stage 1 (Local 120/80) > Stage 2 (Thesaurus/Pivot 65) > Stage 3 (Online 40)
        var stage1Exact = FuzzySearchEngine.scoreCandidateCascade("sethome", "config.yml", "sethome", "true", onlineExpander, now);
        var stage1Prefix = FuzzySearchEngine.scoreCandidateCascade("seth", "config.yml", "sethome", "true", onlineExpander, now);
        var stage2Synonym = FuzzySearchEngine.scoreCandidateCascade("sanctuary", "config.yml", "sethome", "true", onlineExpander, now);
        var stage3Online = FuzzySearchEngine.scoreCandidateCascade("hearthstone", "config.yml", "sethome", "true", onlineExpander, now);

        assertEquals(120, stage1Exact.score(), "Stage 1 exact key match must score 120 without synonym stacking");
        assertEquals(80, stage1Prefix.score(), "Stage 1 prefix match must score 80 without synonym stacking");
        assertEquals(65, stage2Synonym.score(), "Stage 2 thesaurus match must score 65 (< Stage 1)");
        assertEquals(40, stage3Online.score(), "Stage 3 online expansion match must score 40 (< Stage 2)");
        assertTrue(stage1Exact.score() > stage1Prefix.score());
        assertTrue(stage1Prefix.score() > stage2Synonym.score());
        assertTrue(stage2Synonym.score() > stage3Online.score());

        // 2. Per-token cascade short-circuit: if token matches locally in Stage 1, Stage 2/3 are never triggered
        Set<String> docWithLiteralSpawn = Set.of("spawn", "rtp");
        var directMatch = thesaurus.matchTokenCascade("spawn", docWithLiteralSpawn, Set.of(), onlineExpander, now);
        assertEquals(SearchThesaurus.MatchStage.STAGE_1_LOCAL, directMatch.stage());
        assertEquals(1.00, directMatch.weight(), 1e-6);

        var synMatch = thesaurus.matchTokenCascade("lobby", docWithLiteralSpawn, Set.of(), onlineExpander, now);
        assertEquals(SearchThesaurus.MatchStage.STAGE_2_THESAURUS_OR_PIVOT, synMatch.stage());
        assertEquals(0.60, synMatch.weight(), 1e-6);

        var onlineMatch = thesaurus.matchTokenCascade("hearthstone", Set.of("sethome"), Set.of(), onlineExpander, now);
        assertEquals(SearchThesaurus.MatchStage.STAGE_3_ONLINE_EXPANSION, onlineMatch.stage());
        assertEquals(0.40, onlineMatch.weight(), 1e-6);

        // 3. Cross-lingual pivot translation across ES, FR, DE, PL, RU, ZH, JA, KO
        assertEquals("leave", thesaurus.canonicalRoot("salir"));
        assertEquals("leave", thesaurus.canonicalRoot("sortir"));
        assertEquals("leave", thesaurus.canonicalRoot("verlassen"));
        assertEquals("leave", thesaurus.canonicalRoot("wyjść"));
        assertEquals("leave", thesaurus.canonicalRoot("выйти"));
        assertEquals("leave", thesaurus.canonicalRoot("离开"));
        assertEquals("leave", thesaurus.canonicalRoot("出る"));
        assertEquals("leave", thesaurus.canonicalRoot("나가기"));
        assertEquals("delay", thesaurus.canonicalRoot("retrasoTeletransporte"));
        assertEquals("delay", thesaurus.canonicalRoot("传送延迟"));
        assertEquals("delay", thesaurus.canonicalRoot("отсрочкаТелепорта"));

        var zhScored = FuzzySearchEngine.scoreCandidateCascade("传送延迟", "config.yml", "teleportDelay", "3", onlineExpander, now);
        assertEquals(65, zhScored.score(), "CJK localized key '传送延迟' must resolve to teleportDelay via Stage 2 cross-lingual pivot");
    }

    @Test
    @DisplayName("SearchThesaurus and OnlineQueryExpander cover custom synsets, PMI stage, and negative/disabled cache branches")
    void testThesaurusAndOnlineExpanderEdgeBranches(@TempDir Path tempDir) throws Exception {
        SearchThesaurus base = SearchThesaurus.defaultInstance();
        assertSame(base, base.withCustomSynsets(null));
        assertSame(base, base.withCustomSynsets(java.util.Map.of()));

        java.util.Map<String, List<String>> custom = new java.util.LinkedHashMap<>();
        custom.put("spawn", List.of("citadel", "nexus", ""));
        custom.put("customroot", List.of("customsyn", "customroot"));
        custom.put("   ", List.of("ignored"));
        custom.put("nullval", null);
        SearchThesaurus merged = base.withCustomSynsets(custom);
        assertTrue(merged.areSynonyms("spawn", "nexus"));
        assertTrue(merged.areSynonyms("customroot", "customsyn"));
        assertFalse(merged.areSynonyms("shape", "radius"));
        assertFalse(merged.areSynonyms("radius", "shape"));
        assertFalse(merged.areSynonyms("", "spawn"));
        assertFalse(merged.areSynonyms("spawn", null));

        assertEquals("", merged.canonicalRoot(null));
        assertEquals("", merged.canonicalRoot("   "));
        assertEquals("", merged.translateToCanonicalPivot(null));
        assertEquals("", merged.translateToCanonicalPivot("   "));
        assertEquals("leave", merged.translateToCanonicalPivot("salir"));
        assertEquals("", merged.translateToCanonicalPivot("unknownword"));

        assertFalse(merged.hasMultilingualQuestionIntent(null));
        assertFalse(merged.hasMultilingualQuestionIntent("   "));
        assertTrue(merged.hasMultilingualQuestionIntent("How can I leave spawn?"));
        assertTrue(merged.hasMultilingualQuestionIntent("¿Dónde está el spawn?"));
        assertFalse(merged.hasMultilingualQuestionIntent("just a plain statement"));

        // matchTokenCascade edge branches (null/empty, Stage 1.5 PMI, Stage 3 remote root, unmatched)
        assertFalse(merged.matchTokenCascade(null, Set.of("spawn"), null, null, 0L).isMatched());
        assertFalse(merged.matchTokenCascade("spawn", null, null, null, 0L).isMatched());
        assertFalse(merged.matchTokenCascade("spawn", Set.of(), null, null, 0L).isMatched());
        assertTrue(SearchThesaurus.MatchStage.STAGE_1_LOCAL.isMatched());
        assertFalse(SearchThesaurus.MatchStage.UNMATCHED.isMatched());

        var pmiHit = merged.matchTokenCascade("obscure", Set.of("targetdoc"), Set.of("other", "targetdoc"), null, 0L);
        assertEquals(SearchThesaurus.MatchStage.STAGE_1_5_LOCAL_PMI, pmiHit.stage());
        assertTrue(pmiHit.isMatched());

        Path cacheFile = tempDir.resolve("sub/edge-expansions.tsv");
        OnlineQueryExpander expander = new OnlineQueryExpander(
                cacheFile,
                1_000L,
                500L,
                4,
                false,
                tok -> Set.of("leave", "spawn", "a", "too_long_token_exceeding_twenty_four_chars")
        );
        assertEquals(cacheFile, expander.cacheFile());
        assertFalse(expander.isOnlineFetchEnabled());
        expander.setOnlineFetchEnabled(true);
        assertTrue(expander.isOnlineFetchEnabled());

        assertEquals("", OnlineQueryExpander.sanitizeToken(null));
        assertEquals("", OnlineQueryExpander.sanitizeToken("a"));
        assertEquals("", OnlineQueryExpander.sanitizeToken("abcdefghijklmnopqrstuvwxyz"));

        // Negative hit and TTL expiration with onlineFetchEnabled = false
        expander.setOnlineFetchEnabled(false);
        expander.putCacheEntry("a", List.of("ignored"), 100L);
        expander.putCacheEntry("negword", List.of(), 100L);
        assertEquals(Set.of(), expander.getCachedOrScheduleAsync("a", 150L));
        assertEquals(Set.of(), expander.getCachedOrScheduleAsync("negword", 150L));
        // Expire negative hit (100 + 600 > 500) while onlineFetchEnabled=false
        assertEquals(Set.of(), expander.getCachedOrScheduleAsync("negword", 700L));
        assertEquals(Set.of(), expander.expandWithBoundedWait("a", 10L, 700L));
        assertEquals(Set.of(), expander.expandWithBoundedWait("coldmiss", 10L, 700L));

        // Capacity enforcement and maxWaitMs <= 0 branch
        for (int i = 1; i <= 18; i++) {
            expander.putCacheEntry("key" + String.format("%02d", i), List.of("val" + i), 1000L + i);
        }
        expander.putCacheEntry("k5", List.of("v5"), 2000L);
        expander.setOnlineFetchEnabled(true);
        assertEquals(Set.of("v5"), expander.expandWithBoundedWait("k5", 50L, 2100L));
        // Expired positive entry with maxWaitMs = 0 returns stale terms
        assertEquals(Set.of("v5"), expander.expandWithBoundedWait("k5", 0L, 5000L));
        // Expired positive entry via getCachedOrScheduleAsync returns stale terms and schedules refresh
        expander.putCacheEntry("stalekey", List.of("staleval"), 2500L);
        assertEquals(Set.of("staleval"), expander.getCachedOrScheduleAsync("stalekey", 5000L));

        // Stage 3 remoteRoot match in matchTokenCascade (remote term "escape" -> canonical root "leave")
        expander.putCacheEntry("wander", List.of("escape"), 6000L);
        var stage3RootHit = merged.matchTokenCascade("wander", Set.of("leave"), Set.of(), expander, 6100L);
        assertEquals(SearchThesaurus.MatchStage.STAGE_3_ONLINE_EXPANSION, stage3RootHit.stage());
        assertEquals("leave", stage3RootHit.matchedTerm());

        // SearchThesaurus additional methods (matchesAnyInDocument, getters, foldDiacritics, questions)
        assertFalse(merged.matchesAnyInDocument(null, Set.of("spawn")));
        assertFalse(merged.matchesAnyInDocument("spawn", null));
        assertFalse(merged.matchesAnyInDocument("spawn", Set.of()));
        assertFalse(merged.matchesAnyInDocument("   ", Set.of("spawn")));
        assertTrue(merged.matchesAnyInDocument("spawn", Set.of("spawn")));
        assertTrue(merged.matchesAnyInDocument("hub", Set.of("spawn")));
        assertTrue(merged.matchesAnyInDocument("leave", Set.of("escape")));
        assertFalse(merged.matchesAnyInDocument("nonexistenttoken", Set.of("spawn")));
        assertFalse(merged.canonicalSynsets().isEmpty());
        assertFalse(merged.crossLingualPivots().isEmpty());
        assertEquals("", SearchThesaurus.normalizeToken(null));
        assertEquals("", SearchThesaurus.normalizeToken("   "));
        assertEquals("lodz", SearchThesaurus.normalizeToken("Łódź"));
        assertEquals("strasse", SearchThesaurus.normalizeToken("Straße"));
        assertTrue(merged.hasMultilingualQuestionIntent("hey   how can i teleport"));
        assertTrue(merged.hasMultilingualQuestionIntent("hola donde esta el lobby"));
        assertTrue(merged.hasMultilingualQuestionIntent("怎么去主城"));
        assertTrue(merged.hasMultilingualQuestionIntent("how"));

        // OnlineQueryExpander Datamuse JSON parser & failure/timeout branches
        assertEquals(Set.of(), OnlineQueryExpander.parseDatamuseWordsJson(null));
        assertEquals(Set.of(), OnlineQueryExpander.parseDatamuseWordsJson("   "));
        Set<String> parsedJsonWords = OnlineQueryExpander.parseDatamuseWordsJson(
                "[{\"word\":\"teleport\"},{\"word\":\"warp\"},{\"word\":\"a\"},{\"word\":\"portal\"},"
                        + "{\"word\":\"gate\"},{\"word\":\"jump\"},{\"word\":\"blink\"},{\"word\":\"shift\"},"
                        + "{\"word\":\"hop\"},{\"word\":\"extra\"}]");
        assertEquals(8, parsedJsonWords.size());
        assertTrue(parsedJsonWords.contains("teleport"));

        OnlineQueryExpander failingExpander = new OnlineQueryExpander(
                null,
                1_000L,
                500L,
                16,
                true,
                tok -> {
                    throw new java.io.IOException("simulated network error");
                });
        assertEquals(Set.of(), failingExpander.expandWithBoundedWait("failword", 50L, 1000L));

        OnlineQueryExpander slowExpander = new OnlineQueryExpander(
                null,
                1_000L,
                500L,
                16,
                true,
                tok -> {
                    try {
                        Thread.sleep(60L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return Set.of("delayed");
                });
        assertEquals(Set.of(), slowExpander.expandWithBoundedWait("slowword", 5L, 1000L));
        assertEquals(Set.of(), slowExpander.getCachedOrScheduleAsync("slowword", 1000L));

        // Malformed lines in TSV cache file
        java.nio.file.Files.writeString(cacheFile,
                "# comment\n\ninvalid_cols\t100\n!\t100\t0\ta\ngood\tbad_ts\t0\thome\nnegexp\t10\t1\t\nvalid\t6000\t0\thome,sethome\n");
        assertTrue(expander.loadFromDisk(6100L) >= 1);
        expander.clear();
        assertEquals(0, expander.stats().activeEntries());
    }
}
