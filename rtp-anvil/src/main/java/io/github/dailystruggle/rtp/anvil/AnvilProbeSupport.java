package io.github.dailystruggle.rtp.anvil;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.UnaryOperator;

/**
 * Platform-neutral orchestration helper shared by every adapter that wires the
 * ADR-016 Anvil subsystem into its {@code RTPWorld.getChunkAt} path (currently
 * Bukkit/Spigot and Folia; Fabric pending).
 *
 * <p>Previously, the per-world Anvil-view cache + FIFO eviction + publish/consume
 * lifecycle was duplicated inside {@code BukkitRTPWorld}. This helper owns that
 * state so platform adapters only need to:
 * <ol>
 *   <li>Decide (per-platform applicability gate) whether to probe,</li>
 *   <li>Call {@link #probeAndPublish} to run {@link AnvilPrefilter#probeDetailed}
 *       off-thread and publish any decoded view into the cache,</li>
 *   <li>In their {@code getCachedChunk(long)} override, call {@link #takeCached}
 *       and wrap the returned view in their platform-specific source-union
 *       {@code RTPChunk} when no live chunk is cached.</li>
 * </ol>
 *
 * <p><b>No platform or RTP-module dependencies.</b> This class operates purely
 * on primitives, {@link AnvilChunkView}, and caller-provided
 * {@link java.nio.file.Path}s + reconciler functions, per the {@code rtp-anvil}
 * module invariants enforced by {@code AnvilPackageBoundaryArchTest}.</p>
 *
 * <p>This class is thread-safe: the underlying cache is a
 * {@link ConcurrentHashMap} and the insertion-order FIFO is a
 * {@link ConcurrentLinkedDeque}. {@link #takeCached} is a non-destructive read
 * (matching the legacy {@code BukkitRTPWorld.getCachedChunk} semantics, which
 * allowed the same view to answer multiple candidate-loop queries for the same
 * chunk key before eviction).</p>
 */
public final class AnvilProbeSupport {

  /**
   * Default soft cap mirroring the legacy {@code BukkitRTPWorld.ANVIL_CACHE_MAX_ENTRIES}.
   * The cache is a short-lived hand-off between {@code getChunkAt} and the
   * immediately-following {@code getCachedChunk} call in {@code LocationGenerator}'s
   * candidate loop; entries are typically consumed within microseconds. The bound
   * protects against pathological candidate churn under a stalled pipe.
   */
  public static final int DEFAULT_MAX_ENTRIES = 1024;

  private final ConcurrentHashMap<Long, AnvilChunkView> cache = new ConcurrentHashMap<>();
  private final ConcurrentLinkedDeque<Long> order = new ConcurrentLinkedDeque<>();
  private final int maxEntries;

  public AnvilProbeSupport() {
    this(DEFAULT_MAX_ENTRIES);
  }

  public AnvilProbeSupport(int maxEntries) {
    if (maxEntries <= 0) {
      throw new IllegalArgumentException("maxEntries must be positive, got " + maxEntries);
    }
    this.maxEntries = maxEntries;
  }

  /**
   * Run the Anvil pre-filter asynchronously and publish any decoded
   * {@link AnvilChunkView} into the per-support cache under the supplied long
   * {@code key}. The returned future resolves to the {@link AnvilPrefilter.ProbeResult}
   * so callers can inspect the advisory {@link Verdict} or short-circuit on
   * {@link Verdict#UNKNOWN} (no view available → caller should fall through to
   * the live chunk-load path).
   *
   * @param worldFolder absolute path to the world folder (parent of the
   *                    region subdirectory)
   * @param dimSubpath  empty string for overworld, {@code "DIM-1"} for nether,
   *                    {@code "DIM1"} for end, or a Fabric-style dimension
   *                    subpath when applicable
   * @param cx          chunk X
   * @param cz          chunk Z
   * @param key         the packed {@code ((cz << 32) | (cx & 0xffffffffL))} key
   *                    the caller will later use in {@code getCachedChunk}
   * @param rawUnsafe   snapshot of {@code SafetyKeys.unsafeBlocks} (empty is OK,
   *                    the prefilter treats it as "never reject")
   * @param reconciler  platform-specific palette-ID → normalized-name reconciler
   * @return future of the probe result (never null; the {@code view()} component
   *         may be null when the probe could not decode the chunk)
   */
  public CompletableFuture<AnvilPrefilter.ProbeResult> probeAndPublish(
      Path worldFolder,
      String dimSubpath,
      int cx,
      int cz,
      long key,
      Set<String> rawUnsafe,
      UnaryOperator<String> reconciler) {
    return AnvilPrefilter
        .probeDetailed(worldFolder, dimSubpath, cx, cz, rawUnsafe, reconciler)
        .thenApply(result -> {
          if (result != null && result.view() != null) {
            publish(key, result.view());
          }
          return result;
        });
  }

  /**
   * Publish a decoded view into the cache under {@code key}, enforcing the
   * FIFO size cap. Safe to call multiple times for the same key — the later
   * call replaces the earlier view without double-counting the FIFO slot.
   */
  public void publish(long key, AnvilChunkView view) {
    if (view == null) return;
    if (cache.put(key, view) == null) {
      order.offer(key);
    }
    while (order.size() > maxEntries) {
      Long evicted = order.poll();
      if (evicted != null) cache.remove(evicted);
    }
  }

  /**
   * Non-destructive read. Returns the cached view for {@code key}, or {@code null}
   * if no view has been published (or if it was evicted by
   * {@link #publish} overflow / explicit {@link #evict}).
   */
  public AnvilChunkView takeCached(long key) {
    return cache.get(key);
  }

  /**
   * Explicit eviction — called by the adapter's {@code getCachedChunk} once a
   * live chunk supersedes the Anvil snapshot for the same key.
   */
  public void evict(long key) {
    if (cache.remove(key) != null) {
      // Leave the key in the FIFO deque; it will be skipped on the next overflow
      // sweep because cache.remove already returned non-null. Walking the deque
      // to remove the entry would be O(n) and is not worth the cost given the
      // cache's short lifetime.
    }
  }

  /**
   * Drop all cached views. Called from the adapter's {@code forgetChunks()}.
   */
  public void clear() {
    cache.clear();
    order.clear();
  }

  /** Current number of published views. Intended for tests and diagnostics. */
  public int size() {
    return cache.size();
  }
}
