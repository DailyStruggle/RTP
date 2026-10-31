package io.github.dailystruggle.rtp.common.search;

import io.github.dailystruggle.server.api.ServerAccessor;
import io.github.dailystruggle.server.api.ServerAccessors;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shared, thread-safe, file-cached and TTL-invalidated online query expansion engine
 * ({@code leafrtp-ADR-080} Section 4.5, {@code ADR-104}, {@code ADR-115}, {@code leafguide-ADR-001}).
 *
 * <p>Operates strictly as <b>Stage 3 (0.40x weight)</b> of the Three-Stage Local-First Cascade:
 * <ol>
 *   <li><b>Stage 1 (Local Match — {@code 1.00x}):</b> Direct lexical, stem, or phrase match in the
 *       local index.</li>
 *   <li><b>Stage 2 (Thesaurus &amp; Cross-Lingual Pivot — {@code 0.60x}):</b> Offline synset and
 *       {@code .lang.yml} translation pivot via {@link SearchThesaurus}.</li>
 *   <li><b>Stage 3 (Online Cached Expansion — {@code 0.40x}):</b> Invoked only for query tokens that
 *       miss both Stage 1 and Stage 2, preventing network latency and synonym drift from polluting
 *       local matches.</li>
 * </ol>
 *
 * <p><b>File Caching with TTL Invalidation &amp; Zero-Blocking Guarantee ({@code S-005}):</b>
 * <ul>
 *   <li>Persists resolved positive and negative query expansions to a local TSV cache file with
 *       per-entry epoch-millisecond timestamps, eliminating network round-trips across server
 *       restarts and repeated queries.</li>
 *   <li>Enforces configurable positive and negative TTLs ({@code positiveTtlMs},
 *       {@code negativeTtlMs}). When an entry expires, {@link #getCachedOrScheduleAsync} serves
 *       stale positive terms (stale-while-revalidate) or empty while refreshing asynchronously in
 *       the background so caller threads never stall on network timings.</li>
 *   <li>Sanitizes all outbound tokens and inbound words via {@link #sanitizeToken(String)}
 *       ({@code leafrtp-ADR-080} Section 4.5).</li>
 * </ul>
 *
 * @governedBy ADR-080
 * @governedBy ADR-104
 * @governedBy ADR-115
 * @governedBy leafguide-ADR-001
 * @trace S-004
 * @trace S-005
 */
public final class OnlineQueryExpander {

    /** Default Datamuse semantic expansion endpoint ({@code leafrtp-ADR-080} Section 4.5). */
    public static final String DEFAULT_DATAMUSE_ENDPOINT = "https://api.datamuse.com/words?max=8&ml=";

    /** Default TTL for positive expansion hits (7 days). */
    public static final long DEFAULT_POSITIVE_TTL_MS = 7L * 24L * 3600_000L;

    /** Default TTL for negative/empty expansion hits (1 hour). */
    public static final long DEFAULT_NEGATIVE_TTL_MS = 3600_000L;

    /** Maximum number of cached token entries held in memory and persisted on disk. */
    public static final int DEFAULT_MAX_ENTRIES = 1024;

    private static final Logger FALLBACK_LOGGER = Logger.getLogger("RTP");

    private static final Pattern SAFE_QUERY_TOKEN_RE =
            Pattern.compile("^[\\p{L}a-z0-9][\\p{L}a-z0-9_-]{1,23}$");
    private static final Pattern DATAMUSE_WORD_RE =
            Pattern.compile("\"word\"\\s*:\\s*\"([^\"]{2,32})\"");

    private static final ExecutorService DEFAULT_ASYNC_EXECUTOR = Executors.newFixedThreadPool(
            2,
            r -> {
                Thread t = new Thread(r, "leaf-online-query-expander");
                t.setDaemon(true);
                return t;
            }
    );

    private static final OnlineQueryExpander DEFAULT_INSTANCE = new OnlineQueryExpander(
            defaultCacheFilePath(),
            DEFAULT_POSITIVE_TTL_MS,
            DEFAULT_NEGATIVE_TTL_MS,
            DEFAULT_MAX_ENTRIES,
            false,
            OnlineQueryExpander::fetchFromDatamuseHttp
    );

    /**
     * Pluggable remote word fetcher (defaults to Datamuse HTTPS {@code ml=} lookup; overridable in
     * unit tests or custom offline/proxy environments).
     */
    @FunctionalInterface
    public interface RemoteWordFetcher {
        @NotNull
        Set<String> fetchRelatedWords(@NotNull String sanitizedToken) throws Exception;
    }

    /**
     * Immutable cached expansion record stored in memory and persisted to disk with TTL metadata.
     *
     * @param queryToken       sanitized query token
     * @param expandedTerms    normalized related words (empty on negative hit)
     * @param fetchedAtEpochMs timestamp when this entry was fetched
     * @param negativeHit      true when the remote lookup returned zero usable words
     */
    public record CachedExpansionEntry(
            @NotNull String queryToken,
            @NotNull Set<String> expandedTerms,
            long fetchedAtEpochMs,
            boolean negativeHit
    ) {
        public CachedExpansionEntry {
            Objects.requireNonNull(queryToken, "queryToken");
            expandedTerms = expandedTerms == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(expandedTerms));
        }

        public boolean isExpired(long nowEpochMs, long positiveTtlMs, long negativeTtlMs) {
            long ttl = negativeHit ? negativeTtlMs : positiveTtlMs;
            return (nowEpochMs - fetchedAtEpochMs) > ttl;
        }
    }

    /**
     * Diagnostic snapshot of file-backed TTL cache and network expansion activity.
     *
     * @param cacheHits        number of fresh cache hits served in-memory (0 ms network wait)
     * @param staleHitsServed  number of TTL-expired positive entries served via stale-while-revalidate
     * @param cacheMisses      number of cold cache misses
     * @param ttlInvalidations number of entries invalidated due to TTL expiration
     * @param diskEntriesLoaded number of valid entries loaded from the persistent cache file
     * @param diskFlushes      number of atomic cache file flushes completed
     * @param networkFetches   number of remote HTTP/fetcher invocations executed
     * @param activeEntries    current number of entries in the in-memory cache
     */
    public record CacheStats(
            long cacheHits,
            long staleHitsServed,
            long cacheMisses,
            long ttlInvalidations,
            long diskEntriesLoaded,
            long diskFlushes,
            long networkFetches,
            int activeEntries
    ) {
    }

    private final Path cacheFile;
    private final long positiveTtlMs;
    private final long negativeTtlMs;
    private final int maxEntries;
    private volatile boolean onlineFetchEnabled;
    private final RemoteWordFetcher fetcher;

    private final Map<String, CachedExpansionEntry> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Set<String>>> inFlight = new ConcurrentHashMap<>();

    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder staleHitsServed = new LongAdder();
    private final LongAdder cacheMisses = new LongAdder();
    private final LongAdder ttlInvalidations = new LongAdder();
    private final LongAdder diskEntriesLoaded = new LongAdder();
    private final LongAdder diskFlushes = new LongAdder();
    private final LongAdder networkFetches = new LongAdder();

    public OnlineQueryExpander(
            @Nullable Path cacheFile,
            long positiveTtlMs,
            long negativeTtlMs,
            int maxEntries,
            boolean onlineFetchEnabled,
            @NotNull RemoteWordFetcher fetcher
    ) {
        this.cacheFile = cacheFile;
        this.positiveTtlMs = Math.max(1_000L, positiveTtlMs);
        this.negativeTtlMs = Math.max(500L, negativeTtlMs);
        this.maxEntries = Math.max(16, maxEntries);
        this.onlineFetchEnabled = onlineFetchEnabled;
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        if (this.cacheFile != null) {
            loadFromDisk(System.currentTimeMillis());
        }
    }

    /** Returns the shared process-wide {@link OnlineQueryExpander} instance. */
    @NotNull
    public static OnlineQueryExpander defaultInstance() {
        return DEFAULT_INSTANCE;
    }

    /** Enables or disables live remote network fetching on cold misses. */
    public void setOnlineFetchEnabled(boolean enabled) {
        this.onlineFetchEnabled = enabled;
    }

    /** Returns {@code true} if live remote network fetching is enabled. */
    public boolean isOnlineFetchEnabled() {
        return onlineFetchEnabled;
    }

    /** Returns the backing cache file path, or {@code null} if memory-only. */
    @Nullable
    public Path cacheFile() {
        return cacheFile;
    }

    /**
     * Sanitizes a raw token per {@code leafrtp-ADR-080} Section 4.5 before any cache lookup or
     * outbound query. Returns empty string if the token is too short, too long, or contains
     * disallowed characters.
     */
    @NotNull
    public static String sanitizeToken(@Nullable String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return "";
        }
        String trimmed = rawToken.trim().toLowerCase(Locale.ROOT);
        if (trimmed.length() < 2 || trimmed.length() > 24) {
            return "";
        }
        if (!SAFE_QUERY_TOKEN_RE.matcher(trimmed).matches()) {
            return "";
        }
        return SearchThesaurus.normalizeUnicodeToken(trimmed);
    }

    /**
     * Strictly non-blocking in-memory cache lookup ({@code 0.00 ms} network wait).
     *
     * <p>If a fresh entry exists in memory, returns its expanded terms immediately. If the entry
     * has exceeded its TTL, marks it as TTL-invalidated, schedules an asynchronous background
     * refresh (when online fetching is enabled), and returns the stale positive terms
     * (stale-while-revalidate) so callers never experience latency spikes. On a cold miss,
     * schedules an asynchronous background fetch and returns {@link Set#of()} immediately.
     *
     * @param rawToken   raw query token
     * @param nowEpochMs current epoch timestamp in milliseconds
     * @return cached expanded terms, or empty set
     */
    @NotNull
    public Set<String> getCachedOrScheduleAsync(@Nullable String rawToken, long nowEpochMs) {
        String token = sanitizeToken(rawToken);
        if (token.isEmpty()) {
            return Set.of();
        }
        CachedExpansionEntry entry = cache.get(token);
        if (entry != null) {
            if (!entry.isExpired(nowEpochMs, positiveTtlMs, negativeTtlMs)) {
                cacheHits.increment();
                return entry.expandedTerms();
            }
            // TTL expired: invalidate and trigger async revalidation
            ttlInvalidations.increment();
            if (onlineFetchEnabled) {
                scheduleAsyncFetch(token, nowEpochMs);
            } else {
                cache.remove(token, entry);
            }
            if (!entry.negativeHit() && !entry.expandedTerms().isEmpty()) {
                staleHitsServed.increment();
                return entry.expandedTerms();
            }
            return Set.of();
        }

        cacheMisses.increment();
        if (onlineFetchEnabled) {
            scheduleAsyncFetch(token, nowEpochMs);
        }
        return Set.of();
    }

    /**
     * Expands a token using the file-backed TTL cache first ({@code 0 ms} wait on fresh hit), and
     * on a cold miss or expired entry awaits the asynchronous remote fetch for at most
     * {@code maxWaitMs} milliseconds before failing closed to {@link Set#of()}.
     *
     * <p>Intended for asynchronous worker threads (such as {@code AsyncPlayerChatEvent} or CLI
     * tools) where a bounded wait is permissible on cold misses.
     *
     * @param rawToken   raw query token
     * @param maxWaitMs  maximum milliseconds to wait on a cold network miss
     * @param nowEpochMs current epoch timestamp in milliseconds
     * @return expanded terms
     */
    @NotNull
    public Set<String> expandWithBoundedWait(@Nullable String rawToken, long maxWaitMs, long nowEpochMs) {
        String token = sanitizeToken(rawToken);
        if (token.isEmpty()) {
            return Set.of();
        }
        CachedExpansionEntry entry = cache.get(token);
        if (entry != null && !entry.isExpired(nowEpochMs, positiveTtlMs, negativeTtlMs)) {
            cacheHits.increment();
            return entry.expandedTerms();
        }
        if (entry != null) {
            ttlInvalidations.increment();
        } else {
            cacheMisses.increment();
        }
        if (!onlineFetchEnabled) {
            cache.remove(token);
            return Set.of();
        }
        CompletableFuture<Set<String>> fut = scheduleAsyncFetch(token, nowEpochMs);
        if (maxWaitMs <= 0L) {
            return entry != null && !entry.negativeHit() ? entry.expandedTerms() : Set.of();
        }
        try {
            return fut.get(maxWaitMs, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            logFine("OnlineQueryExpander bounded wait timed out or failed for token '" + token + "'", ex);
            return entry != null && !entry.negativeHit() ? entry.expandedTerms() : Set.of();
        }
    }

    /**
     * Explicitly seeds or updates a cache entry and persists to the backing file if configured.
     */
    public void putCacheEntry(
            @NotNull String rawToken,
            @NotNull Collection<String> expandedTerms,
            long fetchedAtEpochMs
    ) {
        String token = sanitizeToken(rawToken);
        if (token.isEmpty()) {
            return;
        }
        Set<String> sanitizedTerms = new LinkedHashSet<>();
        for (String term : expandedTerms) {
            String clean = sanitizeToken(term);
            if (!clean.isEmpty() && !clean.equals(token)) {
                sanitizedTerms.add(clean);
            }
        }
        enforceCapacity();
        CachedExpansionEntry entry = new CachedExpansionEntry(
                token,
                sanitizedTerms,
                fetchedAtEpochMs,
                sanitizedTerms.isEmpty()
        );
        cache.put(token, entry);
        flushToDisk();
    }

    /**
     * Evicts all entries whose age at {@code nowEpochMs} exceeds their configured TTL and persists
     * the pruned state to disk.
     *
     * @param nowEpochMs current epoch timestamp in milliseconds
     * @return number of expired entries removed
     */
    public int evictExpired(long nowEpochMs) {
        int removed = 0;
        for (Map.Entry<String, CachedExpansionEntry> e : cache.entrySet()) {
            CachedExpansionEntry entry = e.getValue();
            if (entry != null && entry.isExpired(nowEpochMs, positiveTtlMs, negativeTtlMs)) {
                if (cache.remove(e.getKey(), entry)) {
                    removed++;
                    ttlInvalidations.increment();
                }
            }
        }
        if (removed > 0) {
            flushToDisk();
        }
        return removed;
    }

    /**
     * Loads non-expired cached expansion entries from the backing file into memory.
     *
     * @param nowEpochMs current epoch timestamp in milliseconds for TTL validation
     * @return number of fresh entries loaded
     */
    public synchronized int loadFromDisk(long nowEpochMs) {
        if (cacheFile == null || !Files.isRegularFile(cacheFile)) {
            return 0;
        }
        int loaded = 0;
        try {
            List<String> lines = Files.readAllLines(cacheFile, StandardCharsets.UTF_8);
            for (String rawLine : lines) {
                if (rawLine == null || rawLine.isBlank() || rawLine.startsWith("#")) {
                    continue;
                }
                String[] parts = rawLine.split("\t", -1);
                if (parts.length < 4) {
                    continue;
                }
                String token = sanitizeToken(parts[0]);
                if (token.isEmpty()) {
                    continue;
                }
                long fetchedAt;
                try {
                    fetchedAt = Long.parseLong(parts[1].trim());
                } catch (NumberFormatException ex) {
                    logFine("Skipping malformed timestamp in OnlineQueryExpander cache file", ex);
                    continue;
                }
                boolean neg = "1".equals(parts[2].trim()) || Boolean.parseBoolean(parts[2].trim());
                Set<String> terms = new LinkedHashSet<>();
                if (!parts[3].isBlank()) {
                    for (String item : parts[3].split(",")) {
                        String clean = sanitizeToken(item);
                        if (!clean.isEmpty() && !clean.equals(token)) {
                            terms.add(clean);
                        }
                    }
                }
                CachedExpansionEntry entry = new CachedExpansionEntry(token, terms, fetchedAt, neg || terms.isEmpty());
                if (entry.negativeHit() && nowEpochMs >= fetchedAt && entry.isExpired(nowEpochMs, positiveTtlMs, negativeTtlMs)) {
                    ttlInvalidations.increment();
                    continue;
                }
                if (cache.size() < maxEntries) {
                    cache.put(token, entry);
                    loaded++;
                }
            }
            diskEntriesLoaded.add(loaded);
        } catch (IOException ex) {
            logFine("Failed to read OnlineQueryExpander cache file " + cacheFile, ex);
        }
        return loaded;
    }

    /**
     * Atomically writes all current in-memory cache entries to the backing file on disk.
     */
    public synchronized void flushToDisk() {
        if (cacheFile == null) {
            return;
        }
        try {
            Path parent = cacheFile.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            StringBuilder sb = new StringBuilder(1024);
            sb.append("# Leaf OnlineQueryExpander TTL Cache (token\\tfetchedAtEpochMs\\tnegativeHit\\tterms)\n");
            List<String> keys = new ArrayList<>(cache.keySet());
            Collections.sort(keys);
            for (String k : keys) {
                CachedExpansionEntry e = cache.get(k);
                if (e == null) continue;
                sb.append(e.queryToken())
                        .append('\t')
                        .append(e.fetchedAtEpochMs())
                        .append('\t')
                        .append(e.negativeHit() ? '1' : '0')
                        .append('\t')
                        .append(String.join(",", e.expandedTerms()))
                        .append('\n');
            }
            Path tmpFile = cacheFile.resolveSibling(cacheFile.getFileName().toString() + ".tmp");
            Files.writeString(
                    tmpFile,
                    sb.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            try {
                Files.move(tmpFile, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException moveEx) {
                Files.move(tmpFile, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }
            diskFlushes.increment();
        } catch (IOException ex) {
            logFine("Failed to flush OnlineQueryExpander cache file " + cacheFile, ex);
        }
    }

    /** Clears all in-memory entries and resets diagnostic counters. */
    public void clear() {
        cache.clear();
        inFlight.clear();
        cacheHits.reset();
        staleHitsServed.reset();
        cacheMisses.reset();
        ttlInvalidations.reset();
        diskEntriesLoaded.reset();
        diskFlushes.reset();
        networkFetches.reset();
    }

    /** Returns current diagnostic statistics for the file-backed TTL cache. */
    @NotNull
    public CacheStats stats() {
        return new CacheStats(
                cacheHits.sum(),
                staleHitsServed.sum(),
                cacheMisses.sum(),
                ttlInvalidations.sum(),
                diskEntriesLoaded.sum(),
                diskFlushes.sum(),
                networkFetches.sum(),
                cache.size()
        );
    }

    private CompletableFuture<Set<String>> scheduleAsyncFetch(String token, long nowEpochMs) {
        CompletableFuture<Set<String>> created = new CompletableFuture<>();
        CompletableFuture<Set<String>> existing = inFlight.putIfAbsent(token, created);
        if (existing != null) {
            return existing;
        }
        DEFAULT_ASYNC_EXECUTOR.execute(() -> {
            try {
                networkFetches.increment();
                Set<String> sanitized = new LinkedHashSet<>();
                try {
                    Set<String> rawWords = fetcher.fetchRelatedWords(token);
                    if (rawWords != null) {
                        for (String w : rawWords) {
                            String clean = sanitizeToken(w);
                            if (!clean.isEmpty() && !clean.equals(token)) {
                                sanitized.add(clean);
                                if (sanitized.size() >= 8) {
                                    break;
                                }
                            }
                        }
                    }
                } catch (Exception ex) {
                    logFine("OnlineQueryExpander remote fetch failed for token '" + token + "'", ex);
                }
                enforceCapacity();
                Set<String> immutable = Collections.unmodifiableSet(sanitized);
                cache.put(token, new CachedExpansionEntry(token, immutable, nowEpochMs, immutable.isEmpty()));
                flushToDisk();
                inFlight.remove(token, created);
                created.complete(immutable);
            } catch (Throwable t) {
                inFlight.remove(token, created);
                created.completeExceptionally(t);
            }
        });
        return created;
    }

    private static void logFine(String msg, Throwable ex) {
        ServerAccessor accessor = ServerAccessors.get();
        if (accessor != null) {
            accessor.log(Level.WARNING, msg, ex);
        } else {
            FALLBACK_LOGGER.log(Level.WARNING, msg, ex);
        }
    }

    private void enforceCapacity() {
        if (cache.size() < maxEntries) {
            return;
        }
        String oldestKey = null;
        long oldestTs = Long.MAX_VALUE;
        for (Map.Entry<String, CachedExpansionEntry> e : cache.entrySet()) {
            if (e.getValue().fetchedAtEpochMs() < oldestTs) {
                oldestTs = e.getValue().fetchedAtEpochMs();
                oldestKey = e.getKey();
            }
        }
        if (oldestKey != null) {
            cache.remove(oldestKey);
        }
    }

    private static Path defaultCacheFilePath() {
        try {
            String tmpDir = System.getProperty("java.io.tmpdir", "/tmp");
            return Path.of(tmpDir, "leaf-search-cache", "online-query-expansions.tsv");
        } catch (Exception ex) {
            logFine("Failed to resolve default OnlineQueryExpander cache path", ex);
            return null;
        }
    }

    @NotNull
    static Set<String> fetchFromDatamuseHttp(@NotNull String sanitizedToken) throws IOException {
        String encoded = URLEncoder.encode(sanitizedToken, StandardCharsets.UTF_8);
        URI uri = URI.create(DEFAULT_DATAMUSE_ENDPOINT + encoded);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(800);
        conn.setReadTimeout(1000);
        conn.setRequestProperty("Accept", "application/json");
        try {
            int status = conn.getResponseCode();
            if (status != 200) {
                return Set.of();
            }
            try (InputStream in = conn.getInputStream()) {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return parseDatamuseWordsJson(json);
            }
        } finally {
            conn.disconnect();
        }
    }

    @NotNull
    static Set<String> parseDatamuseWordsJson(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        Set<String> words = new LinkedHashSet<>();
        Matcher m = DATAMUSE_WORD_RE.matcher(json);
        while (m.find() && words.size() < 8) {
            String w = sanitizeToken(m.group(1));
            if (!w.isEmpty()) {
                words.add(w);
            }
        }
        return Collections.unmodifiableSet(words);
    }
}
