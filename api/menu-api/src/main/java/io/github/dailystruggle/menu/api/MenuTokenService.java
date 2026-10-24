package io.github.dailystruggle.menu.api;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Bounded, expiring click-token issuer and redeemer for one-time or session-scoped {@link
 * MenuAction} callbacks (ADR-035).
 */
public final class MenuTokenService {

  /** Default per-service token capacity. */
  public static final int DEFAULT_MAX_TOKENS = 256;

  /** Default token time-to-live (5 minutes). */
  public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

  private record TokenEntry(
      UUID viewer, MenuAction action, long expiresAtNanos, boolean oneTime) {}

  private final int maxTokens;
  private final long ttlNanos;
  private final LongSupplier nanoClock;
  private final SecureRandom random = new SecureRandom();
  private final LinkedHashMap<String, TokenEntry> tokens = new LinkedHashMap<>();

  /** Creates a token service with default capacity ({@code 256}) and TTL ({@code 5 minutes}). */
  public MenuTokenService() {
    this(DEFAULT_MAX_TOKENS, DEFAULT_TTL, System::nanoTime);
  }

  /**
   * Creates a token service with explicit capacity, TTL, and monotonic nanosecond clock.
   *
   * @param maxTokens maximum concurrent active tokens ({@code >= 1})
   * @param ttl token time-to-live; must be positive
   * @param nanoClock monotonic nanosecond time source; must not be {@code null}
   */
  public MenuTokenService(int maxTokens, Duration ttl, LongSupplier nanoClock) {
    if (maxTokens < 1) {
      throw new IllegalArgumentException("maxTokens must be >= 1, got " + maxTokens);
    }
    Objects.requireNonNull(ttl, "ttl");
    if (ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("ttl must be positive, got " + ttl);
    }
    this.maxTokens = maxTokens;
    this.ttlNanos = ttl.toNanos();
    this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
  }

  /**
   * Issues a one-time token bound to {@code viewer} and {@code action}.
   *
   * @param viewer owning viewer UUID; must not be {@code null}
   * @param action bound action; must not be {@code null}
   * @return opaque hex token string; never {@code null}
   */
  public String issue(UUID viewer, MenuAction action) {
    return issue(viewer, action, true);
  }

  /**
   * Issues a token bound to {@code viewer} and {@code action}.
   *
   * @param viewer owning viewer UUID; must not be {@code null}
   * @param action bound action; must not be {@code null}
   * @param oneTime {@code true} to consume the token on first valid redemption, {@code false} to
   *     allow repeated redemptions until expiry
   * @return opaque hex token string; never {@code null}
   */
  public synchronized String issue(UUID viewer, MenuAction action, boolean oneTime) {
    Objects.requireNonNull(viewer, "viewer");
    Objects.requireNonNull(action, "action");
    long now = nanoClock.getAsLong();
    evictExpired(now);
    while (tokens.size() >= maxTokens) {
      Iterator<String> it = tokens.keySet().iterator();
      if (!it.hasNext()) break;
      it.next();
      it.remove();
    }
    byte[] bytes = new byte[12];
    random.nextBytes(bytes);
    String token = HexFormat.of().formatHex(bytes);
    tokens.put(token, new TokenEntry(viewer, action, now + ttlNanos, oneTime));
    return token;
  }

  /**
   * Redeems {@code token} for {@code viewer}, returning the bound {@link MenuAction} if valid,
   * unexpired, and owned by {@code viewer}.
   *
   * @param viewer redeeming viewer UUID; must not be {@code null}
   * @param token token string; must not be {@code null}
   * @return bound action if valid, or {@link Optional#empty()}
   */
  public synchronized Optional<MenuAction> redeem(UUID viewer, String token) {
    Objects.requireNonNull(viewer, "viewer");
    Objects.requireNonNull(token, "token");
    long now = nanoClock.getAsLong();
    evictExpired(now);
    TokenEntry entry = tokens.get(token);
    if (entry == null) {
      return Optional.empty();
    }
    if (!entry.viewer().equals(viewer)) {
      return Optional.empty();
    }
    if (entry.oneTime()) {
      tokens.remove(token);
    }
    return Optional.of(entry.action());
  }

  /** Invalidates all active tokens belonging to {@code viewer}. */
  public synchronized void invalidateViewer(UUID viewer) {
    Objects.requireNonNull(viewer, "viewer");
    tokens.entrySet().removeIf(e -> e.getValue().viewer().equals(viewer));
  }

  /** Clears all tokens across all viewers. */
  public synchronized void invalidateAll() {
    tokens.clear();
  }

  /** Returns the count of currently non-expired tokens. */
  public synchronized int activeTokenCount() {
    evictExpired(nanoClock.getAsLong());
    return tokens.size();
  }

  private void evictExpired(long now) {
    Iterator<Map.Entry<String, TokenEntry>> it = tokens.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<String, TokenEntry> entry = it.next();
      if (now - entry.getValue().expiresAtNanos() >= 0) {
        it.remove();
      }
    }
  }
}
