package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.api.menu.MenuRendererProvider;
import io.github.dailystruggle.rtp.api.server.PlatformFamily;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Platform-neutral resolution of the {@code /rtp menu} bindings (the
 * {@link MenuRenderer} and the {@link MenuRedeemSubcommand.AnvilInputOpener}),
 * shared by <em>every</em> platform so the menu-binding code path is authored
 * exactly once (ADR-070).
 *
 * <p>Discovery is via {@link java.util.ServiceLoader} against the
 * {@link MenuRendererProvider} (rtp-api) and {@link AnvilInputOpenerProvider}
 * (rtp-core) SPIs: each platform adapter ships an implementation registered
 * through a {@code META-INF/services} entry. On the Bukkit family a single jar
 * must run on plain Spigot <em>and</em> Paper, so the Paper renderer /
 * anvil-opener implementations are only present transitively on the runtime
 * classpath when a Paper adapter is shaded in; {@code ServiceLoader} surfaces
 * their providers exactly in that case (and yields nothing on plain Spigot).
 * Fabric / NeoForge compile-depend on their adapter and register the same way,
 * so the discovery path is identical on every platform rather than each root
 * hand-constructing its renderer. Callers tolerate {@code null} (the picker /
 * open-page paths fall back to the configurable {@code menuInvalid} reject).</p>
 */
public final class MenuBindingSupport {

  private MenuBindingSupport() {}

  /**
   * Resolves the {@link MenuRenderer} from the {@code menu.renderer} config list
   * (first-wins; unknown / failing ids logged at WARNING and skipped). Returns
   * {@code null} if the list is empty, missing, or no listed id has a discoverable
   * provider on the runtime classpath - callers must tolerate {@code null} (the
   * redeem path degrades to the configurable {@code menuInvalid} message).
   *
   * <p>Providers are discovered via {@link MenuRendererProvider}; the platform
   * adapter (when present) registers a {@code "book"} provider.
   */
  public static @Nullable MenuRenderer discoverRenderer() {
    List<String> ids = configuredRendererIds();

    Map<String, MenuRendererProvider> byId = discoverRendererProviders();

    for (String id : ids) {
      MenuRendererProvider provider = byId.get(id);
      if (provider == null) {
        RTP.log(
            Level.WARNING,
            "menu.renderer '" + id + "' has no provider on this platform");
        continue;
      }
      try {
        MenuRenderer r = provider.create();
        if (r != null) return r;
      } catch (RuntimeException ex) {
        RTP.log(
            Level.WARNING,
            "failed to instantiate menu.renderer '" + id + "': " + ex.getMessage(),
            ex);
      }
    }
    RTP.log(
        Level.WARNING,
        "menu.renderer list exhausted (" + ids + "); /rtp menu open-page disabled");
    return null;
  }

  /**
   * Resolves the anvil {@link MenuRedeemSubcommand.AnvilInputOpener} from the
   * first discoverable {@link AnvilInputOpenerProvider}. Returns {@code null}
   * when no provider is on the runtime classpath (e.g. plain Spigot) or
   * instantiation failed; callers tolerate {@code null} (the picker row falls
   * back to the configurable {@code menuInvalid} reject when the player clicks
   * it). The provider is responsible for any platform-listener registration.
   */
  public static @Nullable MenuRedeemSubcommand.AnvilInputOpener discoverAnvilOpener() {
    ServiceLoader<AnvilInputOpenerProvider> loader =
        ServiceLoader.load(
            AnvilInputOpenerProvider.class, AnvilInputOpenerProvider.class.getClassLoader());
    PlatformFamily family = currentPlatformFamily();
    for (AnvilInputOpenerProvider provider : loadProvidersSafely(loader, "menu anvil-input opener")) {
      if (!matchesPlatform(provider.platformFamily(), family)) continue;
      try {
        MenuRedeemSubcommand.AnvilInputOpener opener = provider.create();
        if (opener != null) return opener;
      } catch (RuntimeException ex) {
        RTP.log(
            Level.WARNING,
            "failed to instantiate menu anvil-input opener: " + ex.getMessage(),
            ex);
      }
    }
    RTP.log(Level.INFO, "menu anvil-input unavailable on this platform");
    return null;
  }

  /**
   * Read the configured {@code menu.renderer} id list (lowercased, trimmed),
   * defaulting to {@code [book]} when unset. Operators who explicitly want no
   * renderer can set {@code menu.renderer: []}.
   */
  private static List<String> configuredRendererIds() {
    Object raw = null;
    try {
      @SuppressWarnings("unchecked")
      ConfigParser<ConfigKeys> menuConfig =
          (ConfigParser<ConfigKeys>) RTP.configs.getParser(ConfigKeys.class);
      if (menuConfig != null) {
        Object menuBlock = menuConfig.getConfigValue(ConfigKeys.menu, null);
        if (menuBlock instanceof Map<?, ?> map) {
          raw = map.get("renderer");
        } else {
          raw = menuBlock;
        }
      }
    } catch (RuntimeException ignored) {
      // Config not yet initialised (early-boot) or parser absent - fall
      // through to the default below.
    }
    List<String> ids = new ArrayList<>();
    if (raw instanceof List<?> list) {
      for (Object o : list) {
        if (o != null) ids.add(String.valueOf(o).trim().toLowerCase(Locale.ROOT));
      }
    } else if (raw instanceof String s && !s.isBlank()) {
      ids.add(s.trim().toLowerCase(Locale.ROOT));
    }
    if (ids.isEmpty()) {
      // Default-of-defaults: try `book`.
      ids.add("book");
    }
    return ids;
  }

  /**
   * Discover every {@link MenuRendererProvider} on the runtime classpath, keyed
   * by {@link MenuRendererProvider#id()} (first registration wins on a duplicate
   * id; later ones skipped).
   */
  private static Map<String, MenuRendererProvider> discoverRendererProviders() {
    Map<String, MenuRendererProvider> byId = new LinkedHashMap<>();
    PlatformFamily family = currentPlatformFamily();
    ServiceLoader<MenuRendererProvider> loader =
        ServiceLoader.load(
            MenuRendererProvider.class, MenuRendererProvider.class.getClassLoader());
    for (MenuRendererProvider provider : loadProvidersSafely(loader, "menu renderer provider")) {
      // Skip a provider bound to a different platform. A universal jar shades
      // the Paper, Fabric, and NeoForge adapters together, so all three
      // `book` providers are on the runtime classpath; without this gate the
      // first-enumerated provider would win and the wrong platform's renderer
      // could be selected (e.g. the Fabric book renderer on Paper, which then
      // degrades to the chat fallback because it cannot open a book for a
      // non-Fabric player).
      if (!matchesPlatform(provider.platformFamily(), family)) continue;
      String id;
      try {
        id = provider.id();
      } catch (RuntimeException ex) {
        RTP.log(Level.WARNING, "menu renderer provider id() threw: " + ex.getMessage(), ex);
        continue;
      }
      if (id == null) continue;
      byId.putIfAbsent(id.trim().toLowerCase(Locale.ROOT), provider);
    }
    return byId;
  }

  /**
   * Eagerly materialise every loadable provider from a {@link ServiceLoader},
   * skipping any whose class cannot be linked or instantiated on this runtime.
   *
   * <p>A universal jar shades the Paper, Fabric, and NeoForge adapters together,
   * so a {@code META-INF/services} entry may name a provider class that hard-
   * references a platform type absent at runtime (e.g. the Bukkit anvil opener
   * referencing {@code org.bukkit.plugin.Plugin} on a NeoForge server). The lazy
   * {@code ServiceLoader} iterator surfaces that as a {@link java.util.ServiceConfigurationError}
   * or a raw {@link LinkageError} (e.g. {@link NoClassDefFoundError}) from
   * {@code hasNext()}/{@code next()}; left unhandled it would abort the whole
   * discovery (and the {@code CoreRtpRoot} construction that drives it). We
   * therefore advance defensively and drop the unloadable provider rather than
   * failing the platform that does have a valid one.</p>
   */
  private static <T> List<T> loadProvidersSafely(ServiceLoader<T> loader, String what) {
    List<T> providers = new ArrayList<>();
    Iterator<T> it = loader.iterator();
    while (true) {
      T provider;
      try {
        if (!it.hasNext()) break;
        provider = it.next();
      } catch (java.util.ServiceConfigurationError | LinkageError err) {
        // Unloadable on this platform (wrong-family provider in a universal
        // jar). The lazy iterator has already advanced past the bad entry, so
        // continue to surface any remaining valid providers.
        RTP.log(
            Level.FINE,
            "skipping unloadable " + what + ": " + err);
        continue;
      }
      providers.add(provider);
    }
    return providers;
  }

  /**
   * The running server's {@link PlatformFamily}, or {@code null} when the
   * accessor is not yet wired (early boot / tests) so the gate degrades to
   * "accept any provider".
   */
  private static @Nullable PlatformFamily currentPlatformFamily() {
    try {
      if (RTP.serverAccessor == null) return null;
      return RTP.serverAccessor.getPlatformFamily();
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  /**
   * A provider is eligible when it declares no platform family ({@code null} =
   * platform-neutral), when the running family is unknown / unavailable, or when
   * its declared family equals the running one.
   */
  static boolean matchesPlatform(
      @Nullable PlatformFamily providerFamily, @Nullable PlatformFamily running) {
    if (providerFamily == null) return true;
    if (running == null || running == PlatformFamily.UNKNOWN) return true;
    return providerFamily == running;
  }
}
