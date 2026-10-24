package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.api.menu.MenuRendererProvider;
import io.github.dailystruggle.server.api.PlatformFamily;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;

import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Platform-neutral resolution of {@code /rtp menu} bindings (ADR-070).
 * Discovers {@link MenuRendererProvider} and {@link AnvilInputOpenerProvider} via {@link ServiceLoader}.
 * Tolerates null when no provider matches the running platform.
 */
public final class MenuBindingSupport {

  private MenuBindingSupport() {}

  /**
   * Resolves the {@link MenuRenderer} from configured renderer IDs.
   * Discovers via {@link MenuRendererProvider}; returns {@code null} if unavailable.
   *
   * @return resolved {@link MenuRenderer}, or {@code null}
   */
  public static @Nullable MenuRenderer discoverRenderer() {
    return io.github.dailystruggle.rtp.common.commands.base.MenuRendererDiscovery.discoverRenderer();
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
      } catch (RuntimeException | LinkageError ex) {
        // See discoverRenderer: a platform opener may link a runtime type absent
        // on this server (LinkageError / NoClassDefFoundError), which is not a
        // RuntimeException. Catch both so discovery degrades gracefully.
        io.github.dailystruggle.rtp.common.bit.PlatformHookBitComponent.recordAnvilInputDegraded(
            provider.getClass().getName(), ex.getClass().getSimpleName() + ": " + ex.getMessage());
        RTPRuntime.log(
            Level.WARNING,
            "failed to instantiate menu anvil-input opener: " + ex,
            ex);
      }
    }
    RTPRuntime.log(Level.INFO, "menu anvil-input unavailable on this platform");
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
          (ConfigParser<ConfigKeys>) ActiveConfigs.get().getParser(ConfigKeys.class);
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
   * Materialises providers from {@link ServiceLoader}, catching {@link LinkageError} and
   * {@link java.util.ServiceConfigurationError} to safely skip mismatched platform classes in universal jars.
   */
  private static <T> List<T> loadProvidersSafely(ServiceLoader<T> loader, String what) {
    return io.github.dailystruggle.menu.api.MenuBindingSupport.loadProvidersSafely(loader, what);
  }

  /**
   * The running server's {@link PlatformFamily}, or {@code null} when the
   * accessor is not yet wired (early boot / tests) so the gate degrades to
   * "accept any provider".
   */
  private static @Nullable PlatformFamily currentPlatformFamily() {
    try {
      if (RTPRuntime.serverAccessor() == null) return null;
      return RTPRuntime.serverAccessor().getPlatformFamily();
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
    return io.github.dailystruggle.menu.api.MenuBindingSupport.matchesPlatform(
        providerFamily, running);
  }
}
