package io.github.dailystruggle.rtp.common.hooks;

import io.github.dailystruggle.rtp.api.economy.RTPEconomy;
import io.github.dailystruggle.rtp.api.hooks.AnvilPrefilterRegistry;
import io.github.dailystruggle.rtp.api.hooks.EconomyProviderRegistry;
import io.github.dailystruggle.rtp.api.hooks.PlaceholderProviderRegistry;
import io.github.dailystruggle.rtp.api.hooks.RTPHooks;
import io.github.dailystruggle.rtp.api.hooks.RegionVerifierRegistry;
import io.github.dailystruggle.rtp.api.hooks.WorldBorderProviderRegistry;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Default in-process implementation of the {@link RTPHooks} facade for {@code rtp-core}.
 *
 * <p>Purpose: provide a single, API-only registration surface for every third-party
 * "behavior modification" seam (region verifiers, economy, placeholders, world
 * border, anvil pre-filter) so that integrations can depend on {@code rtp-api}
 * alone (ADR-026; see {@code docs/dev/EXTERNAL_HOOKS.md}).
 *
 * <p>Backward compatibility: {@link RegionVerifierRegistry#register(Predicate)}
 * delegates to {@link GlobalRegionVerifiers#addGlobalRegionVerifier(Predicate)}
 * and vice-versa, so addons compiled against either entry point keep working.
 *
 * <p>Thread safety: all registries are backed by simple synchronised collections or
 * volatile single-binding fields; this class itself is stateless.
 */
public final class DefaultRTPHooks implements RTPHooks {

  private final RegionVerifierRegistry verifierRegistry = new RegionVerifierRegistry() {
    @Override public void register(Predicate<RTPCoords> verifier) {
      GlobalRegionVerifiers.addGlobalRegionVerifier(verifier);
    }
    @Override public void registerAsync(Function<RTPCoords, CompletableFuture<Boolean>> verifier) {
      GlobalRegionVerifiers.addGlobalRegionVerifierAsync(verifier);
    }
    @Override public void clear() {
      GlobalRegionVerifiers.clearGlobalRegionVerifiers();
    }
    @Override public int size() {
      return GlobalRegionVerifiers.registeredCount();
    }
  };

  private final EconomyProviderRegistry economyRegistry = new EconomyProviderRegistry() {
    @Override public void bind(RTPEconomy provider) {
      if (provider == null) {
        throw new IllegalArgumentException("[RTP API] economy provider must not be null");
      }
      RTP.economy = provider;
    }
    @Override public RTPEconomy current() {
      return RTP.economy;
    }
    @Override public void clear() {
      RTP.economy = null;
    }
  };

  private final ConcurrentHashMap<String, BiFunction<UUID, String, String>> placeholders =
      new ConcurrentHashMap<>();
  private final PlaceholderProviderRegistry placeholderRegistry = new PlaceholderProviderRegistry() {
    @Override public void register(String key, BiFunction<UUID, String, String> resolver) {
      if (key == null || key.isEmpty() || resolver == null) {
        throw new IllegalArgumentException("[RTP API] placeholder key/resolver must not be null/empty");
      }
      placeholders.put(key, resolver);
    }
    @Override public boolean unregister(String key) {
      return key != null && placeholders.remove(key) != null;
    }
    @Override public String resolve(UUID playerId, String key) {
      if (key == null) return null;
      BiFunction<UUID, String, String> r = placeholders.get(key);
      return r == null ? null : r.apply(playerId, key);
    }
  };

  private volatile WorldBorderProviderRegistry.Provider borderProvider;
  private final WorldBorderProviderRegistry borderRegistry = new WorldBorderProviderRegistry() {
    @Override public void bind(Provider provider) {
      if (provider == null) {
        throw new IllegalArgumentException("[RTP API] world-border provider must not be null");
      }
      borderProvider = provider;
    }
    @Override public Provider current() { return borderProvider; }
    @Override public void clear() { borderProvider = null; }
  };

  private volatile AnvilPrefilterRegistry.Provider anvilProvider;
  private final AnvilPrefilterRegistry anvilRegistry = new AnvilPrefilterRegistry() {
    @Override public void bind(Provider provider) {
      if (provider == null) {
        throw new IllegalArgumentException("[RTP API] anvil pre-filter provider must not be null");
      }
      anvilProvider = provider;
    }
    @Override public Provider current() { return anvilProvider; }
    @Override public void clear() { anvilProvider = null; }
  };

  @Override public RegionVerifierRegistry verifiers() { return verifierRegistry; }
  @Override public EconomyProviderRegistry economy() { return economyRegistry; }
  @Override public PlaceholderProviderRegistry placeholders() { return placeholderRegistry; }
  @Override public WorldBorderProviderRegistry worldBorder() { return borderRegistry; }
  @Override public AnvilPrefilterRegistry anvilPrefilter() { return anvilRegistry; }
}
