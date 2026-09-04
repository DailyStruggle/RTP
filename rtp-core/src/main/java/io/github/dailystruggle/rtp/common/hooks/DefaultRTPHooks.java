package io.github.dailystruggle.rtp.common.hooks;

import io.github.dailystruggle.rtp.api.economy.RTPEconomy;
import io.github.dailystruggle.rtp.api.hooks.AnvilPrefilterRegistry;
import io.github.dailystruggle.rtp.api.hooks.EconomyProviderRegistry;
import io.github.dailystruggle.rtp.api.hooks.PlaceholderProviderRegistry;
import io.github.dailystruggle.rtp.api.hooks.PlatformCreatorRegistry;
import io.github.dailystruggle.rtp.api.hooks.PvPCombatStateRegistry;
import io.github.dailystruggle.rtp.api.hooks.RTPHooks;
import io.github.dailystruggle.rtp.api.hooks.RegionVerifierRegistry;
import io.github.dailystruggle.rtp.api.hooks.RootActionRegistry;
import io.github.dailystruggle.rtp.api.hooks.WorldBorderProviderRegistry;
import io.github.dailystruggle.rtp.api.platform.PlatformCreator;
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
 * Default implementation of {@link RTPHooks} facade for {@code rtp-core} (ADR-026).
 * Provides API registration surface for verifiers, economy, placeholders, world border, and prefilters.
 */
public final class DefaultRTPHooks implements RTPHooks {

  /** Constructs a DefaultRTPHooks instance with all registries initialised to their empty state. */
  public DefaultRTPHooks() {}

  private final RegionVerifierRegistry verifierRegistry = new RegionVerifierRegistry() {
    @Override public void register(Class<?> source, Predicate<RTPCoords> verifier) {
      GlobalRegionVerifiers.addGlobalRegionVerifier(source, verifier);
    }
    @Override public void register(Predicate<RTPCoords> verifier) {
      GlobalRegionVerifiers.addGlobalRegionVerifier(verifier);
    }
    @Override public void registerAsync(Class<?> source, Function<RTPCoords, CompletableFuture<Boolean>> verifier) {
      GlobalRegionVerifiers.addGlobalRegionVerifierAsync(source, verifier);
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

  private volatile PvPCombatStateRegistry.Provider pvpProvider;
  private final PvPCombatStateRegistry pvpRegistry = new PvPCombatStateRegistry() {
    @Override public void bind(Provider provider) {
      if (provider == null) {
        throw new IllegalArgumentException("[RTP API] PvP combat-state provider must not be null");
      }
      pvpProvider = provider;
    }
    @Override public Provider current() { return pvpProvider; }
    @Override public void clear() { pvpProvider = null; }
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

  private volatile PlatformCreator platformCreator;
  private final PlatformCreatorRegistry platformCreatorRegistry = new PlatformCreatorRegistry() {
    @Override public void bind(PlatformCreator creator) {
      if (creator == null) {
        throw new IllegalArgumentException("[RTP API] platform creator must not be null");
      }
      platformCreator = creator;
    }
    @Override public PlatformCreator current() { return platformCreator; }
    @Override public void clear() { platformCreator = null; }
  };

  private volatile RootActionRegistry.Action rootAction;
  private final RootActionRegistry rootActionRegistry = new RootActionRegistry() {
    @Override public void bind(Action action) {
      if (action == null) {
        throw new IllegalArgumentException("[RTP API] root action must not be null");
      }
      rootAction = action;
    }
    @Override public Action current() { return rootAction; }
    @Override public void clear() { rootAction = null; }
  };

  @Override public RegionVerifierRegistry verifiers() { return verifierRegistry; }
  @Override public EconomyProviderRegistry economy() { return economyRegistry; }
  @Override public PlaceholderProviderRegistry placeholders() { return placeholderRegistry; }
  @Override public WorldBorderProviderRegistry worldBorder() { return borderRegistry; }
  @Override public AnvilPrefilterRegistry anvilPrefilter() { return anvilRegistry; }
  @Override public PvPCombatStateRegistry pvpCombatState() { return pvpRegistry; }
  @Override public RootActionRegistry rootAction() { return rootActionRegistry; }
  @Override public PlatformCreatorRegistry platformCreator() { return platformCreatorRegistry; }
}
