package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Computes a cache invalidation key for a region's persisted shape data.
 *
 * <p>Region shape data (the per-cell "known / known-bad" bitmap maintained by
 * {@code MemoryShape}) is only meaningful under a specific combination of:
 * <ul>
 *   <li>world seed (terrain),</li>
 *   <li>shape class and shape parameters (which world chunk corresponds to spiral cell index <i>N</i>),</li>
 *   <li>vertical adjustor class and parameters (which Y is probed).</li>
 * </ul>
 *
 * <p>Editing any of those values shifts the spiral 1D&rarr;2D mapping or changes which
 * candidates the validity predicate accepts. Cached "bad" flags then refer to the wrong
 * chunk or to a stale rule. To invalidate the cache automatically across such edits, the
 * region's on-disk filename and database rows are keyed by both the seed and a stable hash
 * of these inputs.
 *
 * <p>See <a href="../../../../../../../../../docs/adr/ADR-022-shape-cache-key-seed-plus-config-hash.md">ADR-022</a>
 * for design rationale and alternatives considered.
 *
 * <p><b>Note on the {@code seed} database column.</b> Pre-release alpha/beta does not migrate
 * old rows. The existing {@code rtp_cached_locations.seed BIGINT} column is repurposed to
 * carry the 64-bit truncation of this hash via {@link #cacheKeyLong(RTPWorld, Shape, VerticalAdjustor)}.
 * Mismatched rows are dropped on hydrate (see {@code Region#hydrateCacheFromDatabase}).
 */
public final class RegionCacheKey {

  private RegionCacheKey() {}

  /**
   * Bumped whenever a new validity-affecting field is added to the hash inputs so that
   * older caches auto-invalidate on plugin upgrade.
   */
  public static final int SCHEMA_VERSION = 2;

  /**
   * Safety-validity keys that participate in the cache hash. Editing any of these in
   * {@code safety.yml} should invalidate every region's persisted shape data because
   * the predicate that decided "known-bad" no longer applies.
   *
   * <p>Excluded:
   * <ul>
   *   <li>{@link SafetyKeys#invulnerabilityTime} — post-teleport cosmetic, irrelevant to
   *       which candidate is "safe".</li>
   *   <li>{@link SafetyKeys#staleChunkRetryLimit} — runtime budget, does not change the
   *       predicate.</li>
   *   <li>{@link SafetyKeys#platformRestoreSeconds} — post-teleport footprint-restore timer,
   *       does not affect which candidate is "safe".</li>
   *   <li>{@link SafetyKeys#pvpCheckEnabled}, {@link SafetyKeys#pvpCombatTagSeconds},
   *       {@link SafetyKeys#pvpOnCombat}, {@link SafetyKeys#pvpSource},
   *       {@link SafetyKeys#pvpTagVictim}, {@link SafetyKeys#pvpTagAggressor} — pre-flight
   *       combat-tag gate knobs, applied per-player before/after teleport; they never change
   *       which destination is "known-bad".</li>
   *   <li>{@link SafetyKeys#version} — file-format marker.</li>
   * </ul>
   */
  private static final EnumSet<SafetyKeys> SAFETY_HASH_KEYS = EnumSet.of(
      SafetyKeys.safetyRadius,
      SafetyKeys.platformRadius,
      SafetyKeys.platformAirHeight,
      SafetyKeys.platformDepth,
      SafetyKeys.platformMaterial,
      SafetyKeys.airBlocks,
      SafetyKeys.unsafeBlocks,
      SafetyKeys.anvilPrefilterEnabled,
      SafetyKeys.biomeWhitelist,
      SafetyKeys.biomes);

  /**
   * Compute the human-readable cache key suffix.
   *
   * @return a string of the form {@code "<seed>_<12hex>"}, where {@code 12hex} is the first
   *     12 hex characters of {@code SHA-256(canonical(world, shape, vert, SCHEMA_VERSION))}.
   *     Returns just {@code "<seed>"} when {@code shape} or {@code vert} is null
   *     (region not yet fully bound) — callers should typically gate on world being non-null.
   */
  public static String cacheKey(RTPWorld<?> world, Shape<?> shape, VerticalAdjustor<?> vert) {
    long seed = (world != null) ? world.getSeed() : 0L;
    if (world == null || shape == null || vert == null) return Long.toString(seed);
    return seed + "_" + sha256Hex12(canonicalize(world, shape, vert));
  }

  /**
   * Compute the same key folded into a single 64-bit long for storage in the legacy
   * {@code rtp_cached_locations.seed BIGINT} column.
   *
   * <p>The first 8 bytes of the SHA-256 over the canonical input are returned as a signed
   * long. The seed is included in the hash input, so rows from a different seed or a
   * different config snapshot both produce different long values; the existing
   * seed-mismatch check at {@code Region.java:238} therefore continues to work without a
   * schema migration. Collision probability for a few hundred distinct config snapshots
   * is approximately 2<sup>-64</sup>.
   */
  public static long cacheKeyLong(RTPWorld<?> world, Shape<?> shape, VerticalAdjustor<?> vert) {
    if (world == null) return 0L;
    if (shape == null || vert == null) return world.getSeed();
    byte[] digest = sha256(canonicalize(world, shape, vert));
    long v = 0L;
    for (int i = 0; i < 8; i++) {
      v = (v << 8) | (digest[i] & 0xFFL);
    }
    return v;
  }

  /** Build the canonical, sorted, deterministic string used as hash input. */
  private static String canonicalize(RTPWorld<?> world, Shape<?> shape, VerticalAdjustor<?> vert) {
    StringBuilder sb = new StringBuilder(256);
    sb.append("v=").append(SCHEMA_VERSION).append(';');
    sb.append("seed=").append(world.getSeed()).append(';');
    sb.append("shape.class=").append(shape.getClass().getName()).append(';');
    appendEnumMap(sb, "shape", shape.getData());
    sb.append("vert.class=").append(vert.getClass().getName()).append(';');
    appendEnumMap(sb, "vert", vert.getData());
    appendSafetySnapshot(sb);
    return sb.toString();
  }

  /**
   * Fold the validity-affecting subset of the shared {@code safety.yml} parser into
   * the hash input. Iterates the allowlist {@link #SAFETY_HASH_KEYS} in enum
   * declaration order (already deterministic across JVMs) and serializes each value
   * via {@link #serializeSafetyValue}. Collections are sorted so list re-orderings
   * are not treated as invalidating edits.
   *
   * <p>When the safety parser is unavailable (e.g., early-startup tests that have not
   * loaded {@code Configs}), the snapshot is silently omitted — the resulting key still
   * differs from a future call where the parser <i>is</i> available, so stale caches
   * carried into a configured environment will still be flushed on first match.
   */
  private static void appendSafetySnapshot(StringBuilder sb) {
    ConfigParser<SafetyKeys> safety = safetyParser();
    if (safety == null) return;
    for (SafetyKeys key : SAFETY_HASH_KEYS) {
      Object value = safety.getConfigValue(key, null);
      sb.append("safety.")
          .append(key.name().toLowerCase())
          .append('=')
          .append(serializeSafetyValue(value))
          .append(';');
    }
  }

  @SuppressWarnings("unchecked")
  private static ConfigParser<SafetyKeys> safetyParser() {
    try {
      if (RTP.configs == null) return null;
      return (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Render a safety-key value into a stable, order-independent string form. Collections
   * are sorted by their lowercase string form so {@code [STONE, DIRT]} and
   * {@code [dirt, stone]} fold to the same digest. {@code null} maps to the empty string.
   */
  private static String serializeSafetyValue(Object value) {
    if (value == null) return "";
    if (value instanceof Iterable<?>) {
      List<String> items = new ArrayList<>();
      for (Object o : (Iterable<?>) value) {
        items.add(o == null ? "" : o.toString().trim().toLowerCase());
      }
      Collections.sort(items);
      return "[" + String.join(",", items) + "]";
    }
    return value.toString().trim().toLowerCase();
  }

  /** Append a sorted, lowercase, trimmed serialization of an EnumMap. */
  private static <E extends Enum<E>> void appendEnumMap(
      StringBuilder sb, String prefix, EnumMap<E, Object> data) {
    if (data == null || data.isEmpty()) return;
    List<String> keys = new ArrayList<>(data.size());
    for (E key : data.keySet()) keys.add(key.name());
    Collections.sort(keys);
    for (String k : keys) {
      // Round-trip through name() to look up the enum in a type-safe way without exposing E.
      Object value = null;
      for (Map.Entry<E, Object> e : data.entrySet()) {
        if (e.getKey().name().equals(k)) {
          value = e.getValue();
          break;
        }
      }
      sb.append(prefix).append('.')
          .append(k.toLowerCase())
          .append('=')
          .append(value == null ? "" : value.toString().trim())
          .append(';');
    }
  }

  private static String sha256Hex12(String input) {
    byte[] digest = sha256(input);
    StringBuilder hex = new StringBuilder(12);
    for (int i = 0; i < 6; i++) {
      hex.append(String.format("%02x", digest[i] & 0xFF));
    }
    return hex.toString();
  }

  private static byte[] sha256(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return md.digest(input.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is required by the Java platform spec — this is unreachable.
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
