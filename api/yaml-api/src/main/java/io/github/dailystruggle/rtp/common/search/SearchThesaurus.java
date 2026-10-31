package io.github.dailystruggle.rtp.common.search;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shared, thread-safe bidirectional synset, cross-lingual pivot, phrase, and cascade retrieval
 * thesaurus for all search, configuration discovery, and in-game concierge subsystems
 * ({@code ADR-104}, {@code ADR-115}, {@code leafrtp-ADR-080}, {@code leafguide-ADR-001}).
 *
 * <p>Implements a <b>Three-Stage Local-First Cascade</b> so roundabout synonym or online matches
 * never outrank or pollute direct local matches:
 * <ol>
 *   <li><b>Stage 1 — Local Lexical/Stem Match ({@code 1.00x}):</b> Direct token or stem match in
 *       the candidate document. If Stage 1 matches a token, Stages 2 and 3 are skipped for that
 *       token.</li>
 *   <li><b>Stage 1.5 — Local Corpus Co-Occurrence / PMI ({@code 0.78x}):</b> Server-specific terms
 *       that co-occur with the target domain on the local server.</li>
 *   <li><b>Stage 2 — Thesaurus &amp; Cross-Lingual Pivot ({@code 0.60x}):</b> Curated synsets and
 *       {@code .lang.yml} cross-lingual pivot translations ({@code ES}, {@code FR}, {@code DE},
 *       {@code PL}, {@code PT}, {@code IT}, {@code NL}, {@code RU}, {@code TR}, {@code ZH},
 *       {@code JA}, {@code KO}).</li>
 *   <li><b>Stage 3 — File-Cached Online Expansion ({@code 0.40x}):</b> Non-blocking TTL-cached
 *       semantic expansion via {@link OnlineQueryExpander} for cold unknown words.</li>
 * </ol>
 *
 * @governedBy ADR-080
 * @governedBy ADR-104
 * @governedBy ADR-115
 * @governedBy leafguide-ADR-001
 */
public final class SearchThesaurus {

    /** Weight multiplier for Stage 1 direct local token/stem matches. */
    public static final double STAGE_1_LOCAL_WEIGHT = 1.00;

    /** Weight multiplier for Stage 1.5 local server corpus co-occurrence (PMI) matches. */
    public static final double STAGE_1_5_LOCAL_PMI_WEIGHT = 0.78;

    /** Weight multiplier for Stage 2 offline thesaurus synset and cross-lingual pivot matches. */
    public static final double STAGE_2_THESAURUS_WEIGHT = 0.60;

    /** Weight multiplier for Stage 3 file-cached TTL online expansion matches. */
    public static final double STAGE_3_ONLINE_WEIGHT = 0.40;

    private static final Pattern COMBINING_MARKS_RE =
            Pattern.compile("\\p{InCombiningDiacriticalMarks}+");

    private static final List<String> QUESTION_GREETING_PREFIXES = List.of(
            "hey ", "hi ", "hola ", "salut ", "hallo ", "hej ", "привет "
    );

    private static final List<String> QUESTION_WORD_PREFIXES = List.of(
            "how", "where", "what", "can i", "do i", "is there",
            "como", "donde", "que es", "puedo", "cual",
            "comment", "ou est", "puis je", "puisje", "est ce que", "estce que",
            "wie kann", "wie komme", "wie geht", "wo ist", "wo sind", "wo kann", "kann ich",
            "jak moge", "jak wyjsc", "jak zarobic", "jak ustawic", "gdzie jest", "czy moge",
            "onde fica", "onde esta", "posso",
            "come posso", "come si", "come esco", "dove si",
            "hoe kan", "hoe kom", "waar is",
            "как", "где", "можно ли", "какие",
            "nasil", "nerede"
    );

    private static final List<String> QUESTION_CJK_SUBSTRINGS = List.of(
            "怎么", "如何", "哪里", "在哪", "请问", "能不能", "怎么去",
            "どうやって", "どこ", "コマンドは",
            "어떻게", "어디", "나가는 법", "나가는법", "명령어"
    );

    /**
     * Ordered retrieval cascade tier for a single query token against a document or config node.
     */
    public enum MatchStage {
        STAGE_1_LOCAL(STAGE_1_LOCAL_WEIGHT),
        STAGE_1_5_LOCAL_PMI(STAGE_1_5_LOCAL_PMI_WEIGHT),
        STAGE_2_THESAURUS_OR_PIVOT(STAGE_2_THESAURUS_WEIGHT),
        STAGE_3_ONLINE_EXPANSION(STAGE_3_ONLINE_WEIGHT),
        UNMATCHED(0.0);

        private final double weight;

        MatchStage(double weight) {
            this.weight = weight;
        }

        public double weight() {
            return weight;
        }

        public boolean isMatched() {
            return this != UNMATCHED;
        }
    }

    /**
     * Outcome of matching a single query token through the Local-First Cascade.
     *
     * @param queryToken       normalized query token evaluated
     * @param stage            highest-priority cascade stage that matched
     * @param matchedTerm      the concrete document/candidate token that satisfied the match
     * @param crossLingualPivot true if resolved via a cross-lingual translation pivot
     */
    public record StageMatchResult(
            @NotNull String queryToken,
            @NotNull MatchStage stage,
            @NotNull String matchedTerm,
            boolean crossLingualPivot
    ) {
        public StageMatchResult {
            Objects.requireNonNull(queryToken, "queryToken");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(matchedTerm, "matchedTerm");
        }

        public double weight() {
            return stage.weight();
        }

        public boolean isMatched() {
            return stage.isMatched();
        }
    }

    private static final Map<String, String> DEFAULT_PHRASE_REWRITES = createDefaultPhraseRewrites();
    private static final Map<String, List<String>> DEFAULT_COMPOUND_SPLITS = createDefaultCompoundSplits();
    private static final Map<String, List<String>> DEFAULT_CANONICAL_SYNSETS = createDefaultCanonicalSynsets();
    private static final Map<String, List<String>> DEFAULT_CONFIG_TARGET_SYNONYMS = createDefaultConfigTargetSynonyms();
    private static final Map<String, String> DEFAULT_CROSS_LINGUAL_PIVOTS = createDefaultCrossLingualPivots();

    private static final SearchThesaurus DEFAULT_INSTANCE = new SearchThesaurus(
            DEFAULT_CANONICAL_SYNSETS,
            DEFAULT_CONFIG_TARGET_SYNONYMS,
            DEFAULT_PHRASE_REWRITES,
            DEFAULT_COMPOUND_SPLITS,
            DEFAULT_CROSS_LINGUAL_PIVOTS
    );

    private final Map<String, String> tokenToCanonicalRoot;
    private final Map<String, Set<String>> rootToSynset;
    private final Map<String, Set<String>> tokenToExpandedTargets;
    private final Map<String, String> phraseRewrites;
    private final Map<String, List<String>> compoundSplits;
    private final Map<String, String> crossLingualPivots;

    private SearchThesaurus(
            Map<String, ? extends Collection<String>> canonicalSynsets,
            Map<String, ? extends Collection<String>> configTargetSynonyms,
            Map<String, String> phraseRewrites,
            Map<String, List<String>> compoundSplits,
            Map<String, String> crossLingualPivots
    ) {
        Map<String, String> rootMap = new LinkedHashMap<>();
        Map<String, Set<String>> synsetMap = new LinkedHashMap<>();
        Map<String, Set<String>> targetMap = new LinkedHashMap<>();
        Map<String, String> pivotMap = new LinkedHashMap<>();

        for (Map.Entry<String, ? extends Collection<String>> entry : canonicalSynsets.entrySet()) {
            String root = normalizeToken(entry.getKey());
            if (root.isEmpty()) continue;
            Set<String> group = synsetMap.computeIfAbsent(root, k -> new LinkedHashSet<>());
            group.add(root);
            rootMap.putIfAbsent(root, root);
            if (entry.getValue() != null) {
                for (String member : entry.getValue()) {
                    String normMember = normalizeUnicodeToken(member);
                    if (normMember.isEmpty()) continue;
                    group.add(normMember);
                    rootMap.putIfAbsent(normMember, root);
                }
            }
        }

        if (crossLingualPivots != null) {
            for (Map.Entry<String, String> entry : crossLingualPivots.entrySet()) {
                String foreign = normalizeUnicodeToken(entry.getKey());
                String pivotRoot = normalizeToken(entry.getValue());
                if (foreign.isEmpty() || pivotRoot.isEmpty()) continue;
                pivotMap.put(foreign, pivotRoot);
                rootMap.putIfAbsent(foreign, pivotRoot);
                Set<String> group = synsetMap.computeIfAbsent(pivotRoot, k -> new LinkedHashSet<>());
                group.add(pivotRoot);
                group.add(foreign);
            }
        }

        // Populate bidirectional target expansions from synsets
        for (Map.Entry<String, Set<String>> entry : synsetMap.entrySet()) {
            Set<String> group = entry.getValue();
            for (String member : group) {
                targetMap.computeIfAbsent(member, k -> new LinkedHashSet<>()).addAll(group);
            }
        }

        // Layer config-key / technical target synonyms
        if (configTargetSynonyms != null) {
            for (Map.Entry<String, ? extends Collection<String>> entry : configTargetSynonyms.entrySet()) {
                String key = normalizeUnicodeToken(entry.getKey());
                if (key.isEmpty() || entry.getValue() == null) continue;
                Set<String> targets = targetMap.computeIfAbsent(key, k -> new LinkedHashSet<>());
                targets.add(key);
                for (String val : entry.getValue()) {
                    String normVal = normalizeUnicodeToken(val);
                    if (!normVal.isEmpty()) {
                        targets.add(normVal);
                        targetMap.computeIfAbsent(normVal, k -> new LinkedHashSet<>()).add(key);
                    }
                }
            }
        }

        Map<String, Set<String>> frozenSynsets = new LinkedHashMap<>();
        synsetMap.forEach((k, v) -> frozenSynsets.put(k, Collections.unmodifiableSet(new LinkedHashSet<>(v))));
        Map<String, Set<String>> frozenTargets = new LinkedHashMap<>();
        targetMap.forEach((k, v) -> frozenTargets.put(k, Collections.unmodifiableSet(new LinkedHashSet<>(v))));

        this.tokenToCanonicalRoot = Collections.unmodifiableMap(rootMap);
        this.rootToSynset = Collections.unmodifiableMap(frozenSynsets);
        this.tokenToExpandedTargets = Collections.unmodifiableMap(frozenTargets);
        this.phraseRewrites = Collections.unmodifiableMap(new LinkedHashMap<>(phraseRewrites));
        this.compoundSplits = Collections.unmodifiableMap(new LinkedHashMap<>(compoundSplits));
        this.crossLingualPivots = Collections.unmodifiableMap(pivotMap);
    }

    /** Returns the shared default immutable {@link SearchThesaurus} instance. */
    @NotNull
    public static SearchThesaurus defaultInstance() {
        return DEFAULT_INSTANCE;
    }

    /**
     * Returns a new {@link SearchThesaurus} combining the built-in synsets with custom
     * server/admin-configured synsets (e.g. from {@code config.yml}).
     *
     * @param customSynsets map of canonical root or concept name to synonym words
     * @return merged immutable thesaurus
     */
    @NotNull
    public SearchThesaurus withCustomSynsets(@Nullable Map<String, ? extends Collection<String>> customSynsets) {
        if (customSynsets == null || customSynsets.isEmpty()) {
            return this;
        }
        Map<String, List<String>> mergedSynsets = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : rootToSynset.entrySet()) {
            mergedSynsets.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        for (Map.Entry<String, ? extends Collection<String>> e : customSynsets.entrySet()) {
            String root = normalizeUnicodeToken(e.getKey());
            if (root.isEmpty() || e.getValue() == null) continue;
            List<String> list = mergedSynsets.computeIfAbsent(root, k -> new ArrayList<>(List.of(root)));
            for (String syn : e.getValue()) {
                String normSyn = normalizeUnicodeToken(syn);
                if (!normSyn.isEmpty() && !list.contains(normSyn)) {
                    list.add(normSyn);
                }
            }
        }
        return new SearchThesaurus(mergedSynsets, tokenToExpandedTargets, phraseRewrites, compoundSplits, crossLingualPivots);
    }

    /**
     * Resolves a token to its canonical synset root (e.g. {@code "escape" -> "leave"},
     * {@code "bucks" -> "money"}, {@code "hub" -> "spawn"}, {@code "salir" -> "leave"},
     * {@code "离开" -> "leave"}). Returns the normalized token itself if it does not belong to any
     * synset or cross-lingual pivot.
     *
     * @param rawToken raw word or token
     * @return canonical root token
     */
    @NotNull
    public String canonicalRoot(@Nullable String rawToken) {
        String norm = normalizeUnicodeToken(rawToken);
        if (norm.isEmpty()) {
            return "";
        }
        String pivot = crossLingualPivots.get(norm);
        if (pivot != null) {
            return tokenToCanonicalRoot.getOrDefault(pivot, pivot);
        }
        return tokenToCanonicalRoot.getOrDefault(norm, norm);
    }

    /**
     * Translates a non-English gameplay or configuration token (e.g. {@code "salir"},
     * {@code "retrasoteletransporte"}, {@code "выйти"}, {@code "离开"}, {@code "スポーン"},
     * {@code "스폰"}) into its canonical English pivot root, or returns empty string if the token
     * is not a registered cross-lingual pivot.
     */
    @NotNull
    public String translateToCanonicalPivot(@Nullable String rawToken) {
        String norm = normalizeUnicodeToken(rawToken);
        if (norm.isEmpty()) {
            return "";
        }
        return crossLingualPivots.getOrDefault(norm, "");
    }

    /**
     * Returns {@code true} if {@code rawMessage} exhibits interrogative syntax in English or any
     * supported locale ({@code ES}, {@code FR}, {@code DE}, {@code PL}, {@code PT}, {@code IT},
     * {@code NL}, {@code RU}, {@code TR}, {@code ZH}, {@code JA}, {@code KO}).
     */
    public boolean hasMultilingualQuestionIntent(@Nullable String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return false;
        }
        if (rawMessage.indexOf('?') >= 0 || rawMessage.indexOf('？') >= 0 || rawMessage.indexOf('¿') >= 0) {
            return true;
        }
        String folded = foldDiacritics(rawMessage.trim()).toLowerCase(Locale.ROOT);
        for (String cjk : QUESTION_CJK_SUBSTRINGS) {
            if (folded.contains(cjk)) {
                return true;
            }
        }
        String collapsed = collapseAsciiSpaces(folded);
        for (String greeting : QUESTION_GREETING_PREFIXES) {
            if (collapsed.startsWith(greeting)) {
                collapsed = collapsed.substring(greeting.length()).stripLeading();
                break;
            }
        }
        for (String prefix : QUESTION_WORD_PREFIXES) {
            if (startsWithWordBoundary(collapsed, prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String collapseAsciiSpaces(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean prevSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!prevSpace) {
                    sb.append(' ');
                    prevSpace = true;
                }
            } else {
                sb.append(c);
                prevSpace = false;
            }
        }
        return sb.toString();
    }

    private static boolean startsWithWordBoundary(String text, String prefix) {
        if (!text.startsWith(prefix)) {
            return false;
        }
        if (text.length() == prefix.length()) {
            return true;
        }
        return !Character.isLetterOrDigit(text.charAt(prefix.length()));
    }

    /**
     * Evaluates a single query token against a document or candidate token set using the
     * <b>Three-Stage Local-First Cascade</b>:
     * <ol>
     *   <li><b>Stage 1 ({@code 1.00x}):</b> Direct local match ({@code documentTokens.contains(norm)}).
     *       Short-circuits immediately so synonyms never pollute a local match.</li>
     *   <li><b>Stage 1.5 ({@code 0.78x}):</b> Local server corpus co-occurrence (PMI) match.</li>
     *   <li><b>Stage 2 ({@code 0.60x}):</b> Offline thesaurus synset or cross-lingual pivot match.</li>
     *   <li><b>Stage 3 ({@code 0.40x}):</b> File-cached TTL-invalidated online expansion via
     *       {@link OnlineQueryExpander#getCachedOrScheduleAsync}.</li>
     * </ol>
     *
     * @param queryToken             raw or normalized query token
     * @param documentTokens         tokens present in the candidate document
     * @param localCooccurrenceTerms optional local server PMI co-occurring terms for {@code queryToken}
     * @param onlineExpander         optional file-cached TTL online expander
     * @param nowEpochMs             current epoch milliseconds for TTL evaluation
     * @return cascade stage match result
     */
    @NotNull
    public StageMatchResult matchTokenCascade(
            @Nullable String queryToken,
            @Nullable Set<String> documentTokens,
            @Nullable Set<String> localCooccurrenceTerms,
            @Nullable OnlineQueryExpander onlineExpander,
            long nowEpochMs
    ) {
        String norm = normalizeUnicodeToken(queryToken);
        if (norm.isEmpty() || documentTokens == null || documentTokens.isEmpty()) {
            return new StageMatchResult(norm, MatchStage.UNMATCHED, "", false);
        }

        // Stage 1: Direct Local Match (1.00x) — short-circuits immediately!
        if (documentTokens.contains(norm)) {
            return new StageMatchResult(norm, MatchStage.STAGE_1_LOCAL, norm, false);
        }

        // Stage 1.5: Local Corpus Co-Occurrence / PMI (0.78x)
        if (localCooccurrenceTerms != null && !localCooccurrenceTerms.isEmpty()) {
            for (String pmiTerm : localCooccurrenceTerms) {
                if (documentTokens.contains(pmiTerm)) {
                    return new StageMatchResult(norm, MatchStage.STAGE_1_5_LOCAL_PMI, pmiTerm, false);
                }
            }
        }

        // Stage 2: Thesaurus Synset & Cross-Lingual Pivot (0.60x)
        String pivot = crossLingualPivots.get(norm);
        boolean isCrossLingual = pivot != null;
        if (pivot != null && documentTokens.contains(pivot)) {
            return new StageMatchResult(norm, MatchStage.STAGE_2_THESAURUS_OR_PIVOT, pivot, true);
        }
        String root = canonicalRoot(norm);
        if (!root.equals(norm) && documentTokens.contains(root)) {
            return new StageMatchResult(norm, MatchStage.STAGE_2_THESAURUS_OR_PIVOT, root, isCrossLingual);
        }
        for (String syn : expandSynset(norm)) {
            if (!syn.equals(norm) && documentTokens.contains(syn)) {
                return new StageMatchResult(norm, MatchStage.STAGE_2_THESAURUS_OR_PIVOT, syn, isCrossLingual);
            }
        }

        // Stage 3: File-Cached TTL Online Expansion (0.40x)
        if (onlineExpander != null) {
            Set<String> onlineTerms = onlineExpander.getCachedOrScheduleAsync(norm, nowEpochMs);
            for (String remoteTerm : onlineTerms) {
                if (documentTokens.contains(remoteTerm)) {
                    return new StageMatchResult(norm, MatchStage.STAGE_3_ONLINE_EXPANSION, remoteTerm, false);
                }
                String remoteRoot = canonicalRoot(remoteTerm);
                if (documentTokens.contains(remoteRoot)) {
                    return new StageMatchResult(norm, MatchStage.STAGE_3_ONLINE_EXPANSION, remoteRoot, false);
                }
            }
        }

        return new StageMatchResult(norm, MatchStage.UNMATCHED, "", false);
    }

    /**
     * Expands a single token into its full synset and associated technical/command targets
     * (including canonical root, colloquial synonyms, cross-lingual pivots, and config key targets).
     *
     * @param rawToken raw query or document token
     * @return immutable set of synonymous/related normalized tokens
     */
    @NotNull
    public Set<String> expandSynset(@Nullable String rawToken) {
        String norm = normalizeUnicodeToken(rawToken);
        if (norm.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        result.add(norm);
        String pivot = crossLingualPivots.get(norm);
        if (pivot != null) {
            result.add(pivot);
        }
        String root = canonicalRoot(norm);
        if (!root.isEmpty()) {
            result.add(root);
            Set<String> synset = rootToSynset.get(root);
            if (synset != null) {
                result.addAll(synset);
            }
        }
        Set<String> targets = tokenToExpandedTargets.get(norm);
        if (targets != null) {
            result.addAll(targets);
        }
        if (!root.isEmpty()) {
            Set<String> rootTargets = tokenToExpandedTargets.get(root);
            if (rootTargets != null) {
                result.addAll(rootTargets);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * Expands a collection of query tokens into all matching synset members, compound splits,
     * and typo-tolerant concept matches.
     *
     * @param tokens query tokens
     * @return immutable set of expanded normalized tokens
     */
    @NotNull
    public Set<String> expandTokens(@Nullable Collection<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return Set.of();
        }
        Set<String> expanded = new LinkedHashSet<>();
        for (String raw : tokens) {
            String norm = normalizeUnicodeToken(raw);
            if (norm.isEmpty()) continue;
            expanded.addAll(expandSynset(norm));
            for (String split : splitCompoundToken(norm)) {
                expanded.addAll(expandSynset(split));
            }
            if (containsNonAsciiLetter(norm)) {
                for (Map.Entry<String, String> piv : crossLingualPivots.entrySet()) {
                    if (containsNonAsciiLetter(piv.getKey()) && norm.contains(piv.getKey())) {
                        expanded.addAll(expandSynset(piv.getValue()));
                    }
                }
            }
            if (norm.length() >= 4) {
                int maxDist = norm.length() >= 5 ? 2 : 1;
                for (Map.Entry<String, Set<String>> entry : rootToSynset.entrySet()) {
                    String root = entry.getKey();
                    if (FuzzySearchEngine.levenshtein(norm, root, maxDist) <= maxDist) {
                        expanded.addAll(entry.getValue());
                    }
                }
            }
        }
        return Collections.unmodifiableSet(expanded);
    }

    /**
     * Returns {@code true} if {@code tokenA} and {@code tokenB} share the same canonical synset
     * root or are linked in the bidirectional synonym graph.
     */
    public boolean areSynonyms(@Nullable String tokenA, @Nullable String tokenB) {
        String normA = normalizeUnicodeToken(tokenA);
        String normB = normalizeUnicodeToken(tokenB);
        if (normA.isEmpty() || normB.isEmpty()) {
            return false;
        }
        if (normA.equals(normB)) {
            return true;
        }
        if (("shape".equals(normA) && "radius".equals(normB))
                || ("radius".equals(normA) && "shape".equals(normB))) {
            return false;
        }
        String rootA = canonicalRoot(normA);
        String rootB = canonicalRoot(normB);
        if (rootA.equals(rootB)) {
            return true;
        }
        Set<String> expandedA = expandSynset(normA);
        if (expandedA.contains(normB) || expandedA.contains(rootB)) {
            return true;
        }
        Set<String> expandedB = expandSynset(normB);
        return expandedB.contains(normA) || expandedB.contains(rootA);
    }

    /**
     * Returns {@code true} if {@code queryToken} (or any member of its synset) matches any token
     * in {@code documentTokens}.
     */
    public boolean matchesAnyInDocument(@Nullable String queryToken, @Nullable Set<String> documentTokens) {
        if (queryToken == null || documentTokens == null || documentTokens.isEmpty()) {
            return false;
        }
        String norm = normalizeUnicodeToken(queryToken);
        if (norm.isEmpty()) {
            return false;
        }
        if (documentTokens.contains(norm)) {
            return true;
        }
        String root = canonicalRoot(norm);
        if (documentTokens.contains(root)) {
            return true;
        }
        for (String syn : expandSynset(norm)) {
            if (documentTokens.contains(syn)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Splits a run-on compound token (e.g. {@code "tpdelay" -> ["teleport", "delay", "teleportdelay"]})
     * or returns an empty list if the token is not a known compound.
     */
    @NotNull
    public List<String> splitCompoundToken(@Nullable String rawToken) {
        String norm = normalizeUnicodeToken(rawToken);
        if (norm.isEmpty()) {
            return List.of();
        }
        List<String> split = compoundSplits.get(norm);
        return split != null ? split : List.of();
    }

    /**
     * Normalizes common multi-word phrases and multilingual expressions in a query or document text
     * by rewriting multi-word idioms into their canonical domain tokens (e.g.
     * {@code "fast travel" -> "teleport"}, {@code "out of the hub" -> "leave spawn"},
     * {@code "como salgo del spawn" -> "how leave spawn"}).
     */
    @NotNull
    public String normalizePhrases(@Nullable String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "";
        }
        String normalized = foldDiacritics(rawText.toLowerCase(Locale.ROOT));
        for (Map.Entry<String, String> entry : phraseRewrites.entrySet()) {
            if (normalized.contains(entry.getKey())) {
                normalized = normalized.replace(entry.getKey(), entry.getValue());
            }
        }
        return normalized;
    }

    /** Returns an unmodifiable view of all canonical synset roots and their member sets. */
    @NotNull
    public Map<String, Set<String>> canonicalSynsets() {
        return rootToSynset;
    }

    /** Returns an unmodifiable view of cross-lingual foreign token to canonical English root pivots. */
    @NotNull
    public Map<String, String> crossLingualPivots() {
        return crossLingualPivots;
    }

    /** Normalizes a token to lowercase ASCII alphanumeric root with light plural/gerund stemming. */
    @NotNull
    public static String normalizeToken(@Nullable String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return "";
        }
        String clean = foldDiacritics(rawToken.trim().toLowerCase(Locale.ROOT));
        StringBuilder sb = new StringBuilder(clean.length());
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            }
        }
        return stemEnglishSuffix(sb.toString());
    }

    /**
     * Normalizes a token across Latin (diacritic-folded), Cyrillic, and CJK scripts while applying
     * light English plural/gerund stemming on ASCII tokens.
     */
    @NotNull
    public static String normalizeUnicodeToken(@Nullable String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return "";
        }
        String clean = foldDiacritics(rawToken.trim().toLowerCase(Locale.ROOT));
        StringBuilder sb = new StringBuilder(clean.length());
        boolean allAscii = true;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                sb.append(c);
                allAscii = false;
            }
        }
        String tok = sb.toString();
        return allAscii ? stemEnglishSuffix(tok) : tok;
    }

    private static String stemEnglishSuffix(String tok) {
        if (tok.length() > 4 && tok.endsWith("ing")) {
            return tok.substring(0, tok.length() - 3);
        }
        if (tok.length() > 4 && tok.endsWith("s") && !tok.endsWith("ss") && !tok.endsWith("us") && !tok.equals("rules")) {
            return tok.substring(0, tok.length() - 1);
        }
        return tok;
    }

    private static String foldDiacritics(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        boolean hasLatinExtended = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c >= 0x00C0 && c <= 0x024F) {
                hasLatinExtended = true;
                break;
            }
        }
        if (!hasLatinExtended) {
            return input;
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == 'ł' || c == 'Ł') {
                sb.append('l');
            } else if (c == 'ß') {
                sb.append("ss");
            } else if (c >= 0x00C0 && c <= 0x024F) {
                String nfd = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
                sb.append(COMBINING_MARKS_RE.matcher(nfd).replaceAll(""));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean containsNonAsciiLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> createDefaultPhraseRewrites() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("fast travel", "teleport");
        m.put("out of the hub", "leave spawn");
        m.put("out of the lobby", "leave spawn");
        m.put("out of hub", "leave spawn");
        m.put("out of lobby", "leave spawn");
        m.put("out of spawn", "leave spawn");
        m.put("get out of", "leave");
        m.put("way out of", "leave");
        m.put("golden shovel", "claim");
        m.put("land claim", "claim");
        m.put("keep inventory", "keepinventory");
        m.put("world border", "worldborder");
        m.put("set a home", "sethome");
        m.put("set my home", "sethome");
        m.put("set home", "sethome");
        m.put("random teleport", "rtp");
        // Multilingual phrase rewrites (ES, FR, DE, PL, PT, IT, NL, RU)
        m.put("salir del spawn", "leave spawn");
        m.put("salgo del spawn", "leave spawn");
        m.put("escapar del spawn", "leave spawn");
        m.put("sortir du spawn", "leave spawn");
        m.put("quitter le spawn", "leave spawn");
        m.put("aus dem spawn", "leave spawn");
        m.put("spawn verlassen", "leave spawn");
        m.put("wyjsc ze spawnu", "leave spawn");
        m.put("sair do spawn", "leave spawn");
        m.put("uscire dallo spawn", "leave spawn");
        m.put("выйти со спавна", "leave spawn");
        m.put("покинуть спавн", "leave spawn");
        m.put("как выйти", "how leave");
        return m;
    }

    private static Map<String, List<String>> createDefaultCompoundSplits() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("tpdelay", List.of("teleport", "delay", "teleportdelay"));
        m.put("tpcooldown", List.of("teleport", "cooldown", "teleportcooldown"));
        m.put("tpradius", List.of("teleport", "radius", "maxradius", "minradius"));
        m.put("netherroof", List.of("nether", "roof", "maxy"));
        m.put("hopperlag", List.of("hopper", "cooldown", "batch", "lag"));
        m.put("pvptimer", List.of("pvp", "combat", "duration", "timeout"));
        m.put("claimblocks", List.of("claim", "claims", "initialblocks", "minimumwidth"));
        m.put("worldborder", List.of("world", "border", "radius", "enforcement"));
        m.put("brushsize", List.of("brush", "radius", "limits"));
        m.put("wakeradius", List.of("wake", "radius", "wakeradiusblocks"));
        m.put("sleepradius", List.of("sleep", "radius", "sleepradiusblocks"));
        m.put("fasttravel", List.of("tp", "teleport", "warp", "rtp"));
        m.put("goldenshovel", List.of("claim", "protect", "land"));
        m.put("retrasoteletransporte", List.of("teleport", "delay", "teleportdelay"));
        m.put("enfriamientoteletransporte", List.of("teleport", "cooldown", "teleportcooldown"));
        m.put("radiomaximo", List.of("border", "radius", "maxradius"));
        m.put("radiominimo", List.of("border", "radius", "minradius"));
        return m;
    }

    private static Map<String, List<String>> createDefaultCanonicalSynsets() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        // 1. Spawn, Hub & Leaving into Wilderness
        m.put("spawn", List.of("spawn", "hub", "lobby", "citadel", "spwan"));
        m.put("leave", List.of("leave", "escape", "exit", "depart", "flee"));
        m.put("wild", List.of("wild", "wilderness", "survival", "overworld", "rtp", "unclaimed"));
        // 2. Teleportation & Travel
        m.put("tp", List.of("tp", "teleport", "tpa", "tpahere", "warp", "goto", "fast_travel", "fasttravel"));
        m.put("home", List.of("home", "sethome", "bed", "base", "house", "sanctuary", "headquarters"));
        // 3. Land Claiming, Protection & Griefing
        m.put("claim", List.of("claim", "protect", "protection", "lock", "secure", "shield", "land", "plot",
                "golden_shovel", "goldenshovel", "griefprevention", "worldguard", "towny", "lands", "residence"));
        m.put("grief", List.of("grief", "griefer", "steal", "raid", "raider", "vandalize", "pillage"));
        m.put("chest", List.of("chest", "stash", "container", "barrel", "shulker", "storage", "vault"));
        // 4. Social, Friends & Co-op Trust
        m.put("friend", List.of("friend", "buddy", "mate", "teammate", "partner", "pal", "coop", "co_op"));
        m.put("trust", List.of("trust", "untrust", "accesstrust", "containertrust", "permissiontrust"));
        // 5. Death, KeepInventory & Corpse Recovery
        m.put("die", List.of("die", "died", "death", "perish", "killed", "respawn"));
        m.put("loot", List.of("loot", "stuff", "item", "items", "gear", "inventory", "drops", "corpse", "grave", "gravestone", "backpack"));
        m.put("keepinventory", List.of("keepinventory", "keep_inventory", "keep_items", "lose_items", "drop_items"));
        // 6. Economy, Currency & Shops (including multilingual synonyms)
        m.put("money", List.of("money", "cash", "coin", "coins", "buck", "bucks", "balance", "bal", "currency",
                "economy", "eco", "dollar", "dollars", "funds", "price", "cost", "fee", "refund",
                "dinero", "precio", "argent", "prix", "geld", "kosten", "pieniadze", "cena"));
        m.put("shop", List.of("shop", "market", "marketplace", "store", "bazaar", "auction", "ah", "trade",
                "buy", "sell", "worth", "merchant"));
        m.put("earn", List.of("earn", "grind", "job", "jobs", "salary", "income", "wage", "pay"));
        // 7. World Borders, Radius & Dimensions
        m.put("border", List.of("border", "worldborder", "boundary", "perimeter", "edge", "limit",
                "radius", "rad", "raduis", "maxradius", "minradius", "centerradius", "bounds",
                "range", "distance", "dist", "reach", "diameter", "extent",
                "distancia", "radio", "abstand", "umkreis", "reichweite", "rayon", "odleglosc", "promien"));
        m.put("height", List.of("height", "vertical", "vert", "altitude", "elevation", "miny", "maxy",
                "cave", "underground", "altura", "hauteur", "hoehe", "wysokosc"));
        // 8. Server Rules, Moderation & Help Commands
        m.put("rules", List.of("rules", "rule", "law", "allowed", "legal", "ban", "bannable", "punishment", "tos"));
        m.put("command", List.of("command", "commands", "cmd", "cmds", "help", "guide", "faq", "manual", "tutorial", "info"));
        // 9. Performance, Timers & Cooldowns
        m.put("lag", List.of("lag", "laggy", "slow", "freeze", "tps", "mspt", "performance",
                "rendement", "leistung", "wydajnosc"));
        m.put("delay", List.of("delay", "dealy", "warmup", "timer", "wait", "teleportdelay",
                "retraso", "delai", "wartezeit", "opoznienie"));
        m.put("cooldown", List.of("cooldown", "cooldwon", "teleportcooldown", "reloadcooldown", "transfercooldown"));
        m.put("pvp", List.of("pvp", "combat", "fight", "bleed", "blead", "blood", "damage", "combattimeout"));
        m.put("hopper", List.of("hopper", "hoppr", "funnel", "transfer"));
        m.put("liquid", List.of("liquid", "liquids", "water", "lava", "ocean", "river"));
        return m;
    }

    private static Map<String, List<String>> createDefaultConfigTargetSynonyms() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("leave", List.of("rtp", "wild", "wilderness"));
        m.put("wild", List.of("rtp", "wild", "wilderness"));
        m.put("claim", List.of("claim", "claims", "protect", "grief", "chest", "house", "base", "minimumwidth", "initialblocks", "griefprevention", "worldguard", "safety"));
        m.put("grief", List.of("claim", "claims", "protect", "grief", "chest", "griefprevention", "worldguard", "enforcement"));
        m.put("chest", List.of("claim", "protect", "chest", "container", "lock"));
        m.put("home", List.of("home", "sethome", "claim", "protect", "chest"));
        m.put("tp", List.of("tpa", "teleport", "rtp", "warp", "teleportdelay", "teleportcooldown"));
        m.put("friend", List.of("tpa", "friend", "player", "trust", "teleport"));
        m.put("die", List.of("keepinventory", "death", "die", "loot"));
        m.put("loot", List.of("keepinventory", "death", "die", "loot", "inventory"));
        m.put("keepinventory", List.of("keepinventory", "death", "die", "loot", "inventory"));
        m.put("money", List.of("shop", "balance", "money", "coin", "sell", "buy", "price", "economy", "vault", "cost", "fee", "refund", "paramsprice", "biomeprice", "balancefloor"));
        m.put("shop", List.of("shop", "balance", "money", "coin", "sell", "buy", "price", "economy"));
        m.put("earn", List.of("shop", "balance", "money", "coin", "sell", "jobs"));
        m.put("border", List.of("border", "radius", "worldborder", "worldborderoverride", "centerradius", "minradius", "maxradius", "bounds", "enforcement"));
        m.put("command", List.of("help", "guide", "command", "commands", "faq", "info"));
        m.put("lag", List.of("cooldown", "interval", "batch", "queue", "mspt", "timeout", "wake", "sleep", "unload", "cachecap", "backlogcachecap", "activechunkcap", "ttl", "thread", "cpu"));
        m.put("delay", List.of("delay", "teleportdelay", "cooldown", "cancel", "timer", "duration", "ticks", "seconds"));
        m.put("cooldown", List.of("cooldown", "teleportcooldown", "teleportdelay", "timer", "cancel", "transfercooldownticks", "reloadcooldownticks"));
        m.put("height", List.of("vert", "miny", "maxy", "linear", "jump", "unsafeblocks", "ceiling"));
        m.put("liquid", List.of("water", "lava", "liquids", "safety", "unsafeblocks", "checkblocks"));
        m.put("farm", List.of("wakeradiusblocks", "sleepradiusblocks", "rehibernate", "hysteresis", "governor"));
        return m;
    }

    /**
     * Cross-lingual pivot dictionary seeded from shipped {@code lang/<locale>/** /.*.lang.yml}
     * key aliases and common multilingual player survival questions across {@code ES}, {@code FR},
     * {@code DE}, {@code PL}, {@code PT}, {@code IT}, {@code NL}, {@code RU}, {@code TR},
     * {@code ZH}, {@code JA}, and {@code KO}.
     */
    private static Map<String, String> createDefaultCrossLingualPivots() {
        Map<String, String> m = new LinkedHashMap<>();
        // Leave / Exit / Escape
        for (String w : List.of("salir", "salgo", "escapar", "sortir", "quitter", "echapper",
                "verlassen", "entkommen", "ausgang", "wyjsc", "uciec", "sair", "uscire", "fuggire",
                "verlaten", "ontsnappen", "cikis", "ayrilmak", "выйти", "покинуть", "выбраться",
                "离开", "出去", "出る", "脱出", "나가기", "나가", "나가요", "탈출")) {
            m.put(w, "leave");
        }
        // Spawn / Hub / Lobby
        for (String w : List.of("inicio", "vestibulo", "apparition", "spawnpunkt", "спавн", "спавна",
                "хаб", "лобби", "出生点", "主城", "大厅", "スポーン", "ロビー", "스폰", "로비")) {
            m.put(w, "spawn");
        }
        // Wilderness / Survival / RTP
        for (String w : List.of("salvaje", "supervivencia", "aleatorio", "sauvage", "survie", "aleatoire",
                "wildnis", "uberleben", "zufall", "dzicz", "przetrwanie", "losowy", "selvagem",
                "sobrevivencia", "selvaggio", "sopravvivenza", "wildernis", "overleven", "vahsi",
                "дикий", "выживание", "ртп", "荒野", "生存", "随机传送", "サバイバル", "ランダムテレポ", "야생", "서바이벌", "랜덤텔포")) {
            m.put(w, "wild");
        }
        // Teleport / Travel
        for (String w : List.of("teletransporte", "teletransportar", "teleporter", "teleportieren",
                "teleportacja", "teleporte", "teletrasporto", "teleporteren", "isinlanma",
                "телепорт", "телепортация", "传送", "テレポート", "텔레포트")) {
            m.put(w, "tp");
        }
        // Claim / Protect / Land
        for (String w : List.of("proteger", "reclamar", "terreno", "parcela", "proteger", "revendiquer",
                "terrain", "schutzen", "grundstuck", "gebiet", "dzialka", "chron", "terreno",
                "proteggere", "beschermen", "arsa", "koruma", "приват", "заприватить", "защитить",
                "территория", "领地", "圈地", "保护", "保護", "土地", "땅", "보호")) {
            m.put(w, "claim");
        }
        // Friend / Co-op
        for (String w : List.of("amigo", "amigos", "ami", "amis", "freund", "freunde", "znajomy",
                "koledzy", "amico", "vriend", "arkadas", "друг", "другу", "друзья",
                "朋友", "好友", "友達", "フレンド", "친구")) {
            m.put(w, "friend");
        }
        // Home / Base
        for (String w : List.of("casa", "hogar", "maison", "zuhause", "heim", "dom", "ev",
                "дом", "домой", "сетхом", "家", "设家", "拠点", "ホーム", "집", "셋홈")) {
            m.put(w, "home");
        }
        // Death / Loot / KeepInventory
        for (String w : List.of("morir", "muerte", "mourir", "mort", "sterben", "tod", "smierc",
                "umrzec", "morrer", "morte", "dood", "olum", "смерть", "умереть", "умер",
                "死亡", "死ぬ", "죽음")) {
            m.put(w, "die");
        }
        for (String w : List.of("inventario", "cosas", "objetos", "inventaire", "objets", "inventar",
                "sachen", "ekwipunek", "rzeczy", "przedmioty", "esyalar", "инвентарь", "вещи", "лут",
                "掉落", "死亡不掉落", "物品", "アイテム", "아이템", "인벤세이브")) {
            m.put(w, "loot");
        }
        // Money / Economy / Shop
        for (String w : List.of("dinero", "monedas", "precio", "argent", "monnaie", "prix",
                "geld", "munzen", "kosten", "preis", "pieniadze", "monety", "cena",
                "dinheiro", "moedas", "preco", "soldi", "prezzo", "para", "fiyat",
                "деньги", "монеты", "баланс", "цена", "钱", "金币", "货币", "お金", "コイン", "돈", "코인")) {
            m.put(w, "money");
        }
        for (String w : List.of("tienda", "mercado", "vender", "comprar", "boutique", "magasin", "vendre",
                "laden", "markt", "verkaufen", "kaufen", "sklep", "sprzedac", "kupic",
                "loja", "vender", "negozio", "winkel", "magaza", "satmak",
                "магазин", "рынок", "продать", "купить", "商店", "市场", "出售", "ショップ", "상점")) {
            m.put(w, "shop");
        }
        // Border / Radius (.lang.yml keys + player terms)
        for (String w : List.of("borde", "frontera", "radiomaximo", "radiominimo", "radiocentro",
                "bordure", "frontiere", "rayon", "weltgrenze", "grenze", "umkreis", "abstand",
                "granica", "promien", "odleglosc", "borda", "limite", "confine", "grens", "sinir",
                "граница", "радиус", "максрадиус", "边界", "世界边界", "半径", "ワールド境界", "境界", "월드보더", "경계")) {
            m.put(w, "border");
        }
        // Delay / Cooldown (.lang.yml keys)
        for (String w : List.of("retraso", "retrasoteletransporte", "delai", "delaiteleportation",
                "wartezeit", "teleportationsverzogerung", "opoznienie", "atraso", "ritardo",
                "vertraging", "gecikme", "отсрочка", "отсрочкателепорта", "задержка",
                "传送延迟", "延迟", "遅延", "딜레이")) {
            m.put(w, "delay");
        }
        for (String w : List.of("enfriamiento", "enfriamientoteletransporte", "tempsderecharge",
                "abklingzeit", "odnowienie", "czasodnowienia", "recarga", "ricarica",
                "afkoeltijd", "beklemesuresi", "перезарядка", "кулдаун", "冷却", "クールダウン", "쿨타임")) {
            m.put(w, "cooldown");
        }
        // Rules & Help Commands
        for (String w : List.of("reglas", "normas", "regles", "regeln", "zasady", "regulamin",
                "regras", "regole", "regels", "kurallar", "правила", "规则", "ルール", "규칙")) {
            m.put(w, "rules");
        }
        for (String w : List.of("comandos", "ayuda", "commandes", "aide", "befehle", "hilfe",
                "komendy", "pomoc", "ajuda", "comandi", "aiuto", "commando", "hulp", "komutlar", "yardim",
                "команды", "помощь", "命令", "指令", "帮助", "コマンド", "ヘルプ", "명령어", "도움말")) {
            m.put(w, "command");
        }
        return m;
    }
}
