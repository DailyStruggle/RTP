package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.BiomesKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Computes a cache invalidation key for a region's persisted shape data (ADR-022).
 *
 * <p>Combines world seed, shape parameters, vertical adjustor parameters, and
 * safety configuration hash into a stable identifier for cache invalidation.
 */
public final class RegionCacheKey {

  private RegionCacheKey() {}

  /**
   * Bumped whenever a new validity-affecting field is added to the hash inputs so that
   * older caches auto-invalidate on plugin upgrade.
   */
  public static final int SCHEMA_VERSION = 2;

  /**
   * Safety-validity keys included in cache hash.
   * Excludes cosmetic/runtime settings (invulnerability, limits, pvp tag gates).
   */
  private static final List<Enum<?>> SAFETY_HASH_KEYS = List.of(
      SafetyKeys.safetyRadius,
      SafetyKeys.platformRadius,
      SafetyKeys.platformAirHeight,
      SafetyKeys.platformDepth,
      SafetyKeys.platformMaterial,
      BlocksKeys.airBlocks,
      BlocksKeys.unsafeBlocks,
      SafetyKeys.anvilPrefilterEnabled,
      BiomesKeys.biomeWhitelist,
      BiomesKeys.biomes);

  /**
   * Compute the human-readable cache key suffix.
   *
   * @return a string of the form {@code "<seed>_<12hex>"}, where {@code 12hex} is the first
   *     12 hex characters of {@code SHA-256(canonical(world, shape, vert, SCHEMA_VERSION))}.
   *     Returns just {@code "<seed>"} when {@code shape} or {@code vert} is null
   *     (region not yet fully bound) - callers should typically gate on world being non-null.
   */
  public static String cacheKey(RTPWorld<?> world, Shape<?> shape, VerticalAdjustor<?> vert) {
    long seed = (world != null) ? world.getSeed() : 0L;
    if (world == null || shape == null || vert == null) return Long.toString(seed);
    return seed + "_" + sha256Hex12(canonicalize(world, shape, vert));
  }

  /**
   * Computes the 64-bit truncated hash key for database column storage.
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
   * Serializes active safety config keys into the canonical hash buffer.
   */
  private static void appendSafetySnapshot(StringBuilder sb) {
    if (RTP.configs == null) return;
    for (Enum<?> key : SAFETY_HASH_KEYS) {
      Object value;
      try {
        value = RTP.configs.getConfigValue(key, null);
      } catch (RuntimeException e) {
        return;
      }
      sb.append("safety.")
          .append(key.name().toLowerCase())
          .append('=')
          .append(serializeSafetyValue(value))
          .append(';');
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
      // SHA-256 is required by the Java platform spec - this is unreachable.
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
