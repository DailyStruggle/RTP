package io.github.dailystruggle.rtp.api.hooks;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Registry of named placeholder resolvers exposed by RTP to chat/scoreboard plugins (ADR-026).
 * Resolvers may be invoked from any thread and must not block.
 */
@PublicApi
public interface PlaceholderProviderRegistry {

  /**
   * Register or overwrite the resolver for {@code key}.
   *
   * @param key placeholder suffix after {@code rtp_}
   * @param resolver resolver taking player UUID (nullable) and raw key, returning string or null
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
