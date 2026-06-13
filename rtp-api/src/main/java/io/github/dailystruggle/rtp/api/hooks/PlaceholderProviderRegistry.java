package io.github.dailystruggle.rtp.api.hooks;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Registry of named placeholder resolvers exposed by RTP to chat/scoreboard plugins
 * such as PlaceholderAPI.
 *
 * <p>Each resolver maps a placeholder key (the part after the {@code rtp_} prefix in
 * a PAPI placeholder, e.g. {@code rtp_cooldown_remaining} → {@code cooldown_remaining})
 * and a player UUID to a string. The bundled {@code PAPI_expansion} class in
 * {@code rtp-plugin} consumes this registry and exposes every entry through
 * PlaceholderAPI; third-party placeholder bridges (e.g. MVdWPlaceholderAPI) may
 * register their own consumer instead (ADR-026).
 *
 * <p><b>Threading.</b> Resolvers may be invoked from any thread (PAPI does not
 * promise main-thread invocation). Implementations shall not call blocking server
 * APIs.
 */
@PublicApi
public interface PlaceholderProviderRegistry {

  /**
   * Register or overwrite the resolver for {@code key}.
   *
   * @param key      non-null, non-empty placeholder key (suffix after {@code rtp_})
   * @param resolver non-null function taking the requesting player UUID
   *                 (may be {@code null} for non-player contexts) and the original
   *                 raw key, returning the resolved string or {@code null} if not
   *                 applicable
   */
  void register(String key, BiFunction<UUID, String, String> resolver);

  /**
   * Remove a previously registered resolver.
   *
   * @param key the placeholder key to unregister
   * @return {@code true} if a resolver was removed
   */
  boolean unregister(String key);

  /**
   * Resolve {@code key} against the registered resolvers.
   *
   * @param playerId requesting player UUID, may be {@code null}
   * @param key      non-null placeholder key
   * @return resolved string, or {@code null} if no resolver matches
   */
  String resolve(UUID playerId, String key);
}
