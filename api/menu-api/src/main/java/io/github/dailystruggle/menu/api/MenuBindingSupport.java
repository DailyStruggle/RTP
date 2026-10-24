package io.github.dailystruggle.menu.api;

import io.github.dailystruggle.server.api.PlatformFamily;
import io.github.dailystruggle.server.api.ServerAccessor;
import io.github.dailystruggle.server.api.ServerAccessors;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Platform-neutral {@link ServiceLoader} discovery for {@link MenuRendererProvider} and {@link
 * AnvilInputOpenerProvider} gated by {@link PlatformFamily} (ADR-070).
 *
 * <p>Resilient to {@link LinkageError} and {@link ServiceConfigurationError} so universal jars
 * containing multiple platform providers degrade gracefully on any server runtime.
 */
public final class MenuBindingSupport {

  /** Default renderer preference list when none is specified. */
  public static final List<String> DEFAULT_RENDERER_IDS = List.of("book");

  private MenuBindingSupport() {}

  /**
   * Discovers the first matching {@link MenuRenderer} using {@link #DEFAULT_RENDERER_IDS} and the
   * active {@link ServerAccessors#get()} platform family.
   *
   * @return resolved {@link MenuRenderer}, or {@code null} if unavailable
   */
  public static @Nullable MenuRenderer discoverRenderer() {
    return discoverRenderer(DEFAULT_RENDERER_IDS);
  }

  /**
   * Discovers the first matching {@link MenuRenderer} for the ordered {@code preferredIds} list.
   *
   * @param preferredIds ordered renderer identifiers (for example, {@code ["book", "chat"]})
   * @return resolved {@link MenuRenderer}, or {@code null} if none matched
   */
  public static @Nullable MenuRenderer discoverRenderer(List<String> preferredIds) {
    Objects.requireNonNull(preferredIds, "preferredIds");
    ServiceLoader<MenuRendererProvider> loader =
        ServiceLoader.load(
            MenuRendererProvider.class, MenuRendererProvider.class.getClassLoader());
    List<MenuRendererProvider> providers = loadProvidersSafely(loader, "menu renderer");
    return discoverRenderer(providers, preferredIds, currentPlatformFamily());
  }

  /**
   * Resolves a {@link MenuRenderer} from an explicit provider sequence, ordered preference list,
   * and running {@link PlatformFamily}.
   *
   * @param providers candidate providers; must not be {@code null}
   * @param preferredIds ordered renderer IDs; must not be {@code null}
   * @param runningFamily active platform family, or {@code null}
   * @return resolved {@link MenuRenderer}, or {@code null}
   */
  public static @Nullable MenuRenderer discoverRenderer(
      Iterable<? extends MenuRendererProvider> providers,
      List<String> preferredIds,
      @Nullable PlatformFamily runningFamily) {
    Objects.requireNonNull(providers, "providers");
    Objects.requireNonNull(preferredIds, "preferredIds");
    List<MenuRendererProvider> eligible = new ArrayList<>();
    for (MenuRendererProvider provider : providers) {
      if (provider != null && matchesPlatform(provider.platformFamily(), runningFamily)) {
        eligible.add(provider);
      }
    }
    for (String rawId : preferredIds) {
      if (rawId == null || rawId.isBlank()) continue;
      String wanted = rawId.trim().toLowerCase(Locale.ROOT);
      for (MenuRendererProvider provider : eligible) {
        String id = provider.id();
        if (id == null || !id.trim().toLowerCase(Locale.ROOT).equals(wanted)) continue;
        try {
          MenuRenderer renderer = provider.create();
          if (renderer != null) {
            return renderer;
          }
        } catch (RuntimeException | LinkageError ex) {
          ServerAccessors.log(
              Level.WARNING, "failed to instantiate menu renderer '" + wanted + "': " + ex, ex);
        }
      }
    }
    return null;
  }

  /**
   * Discovers the first matching {@link AnvilInputOpener} via {@link ServiceLoader} for the active
   * {@link ServerAccessors#get()} platform family.
   *
   * @return resolved {@link AnvilInputOpener}, or {@code null} if unavailable
   */
  public static @Nullable AnvilInputOpener discoverAnvilOpener() {
    ServiceLoader<AnvilInputOpenerProvider> loader =
        ServiceLoader.load(
            AnvilInputOpenerProvider.class, AnvilInputOpenerProvider.class.getClassLoader());
    List<AnvilInputOpenerProvider> providers =
        loadProvidersSafely(loader, "menu anvil-input opener");
    return discoverAnvilOpener(providers, currentPlatformFamily());
  }

  /**
   * Resolves an {@link AnvilInputOpener} from an explicit provider sequence and running {@link
   * PlatformFamily}.
   *
   * @param providers candidate providers; must not be {@code null}
   * @param runningFamily active platform family, or {@code null}
   * @return resolved {@link AnvilInputOpener}, or {@code null}
   */
  public static @Nullable AnvilInputOpener discoverAnvilOpener(
      Iterable<? extends AnvilInputOpenerProvider> providers,
      @Nullable PlatformFamily runningFamily) {
    Objects.requireNonNull(providers, "providers");
    for (AnvilInputOpenerProvider provider : providers) {
      if (provider == null || !matchesPlatform(provider.platformFamily(), runningFamily)) {
        continue;
      }
      try {
        AnvilInputOpener opener = provider.create();
        if (opener != null) {
          return opener;
        }
      } catch (RuntimeException | LinkageError ex) {
        ServerAccessors.log(
            Level.WARNING, "failed to instantiate menu anvil-input opener: " + ex, ex);
      }
    }
    return null;
  }

  /**
   * Materializes providers from {@link ServiceLoader}, catching {@link LinkageError} and {@link
   * ServiceConfigurationError} to skip unloadable platform classes in universal jars.
   *
   * @param loader service loader to iterate; must not be {@code null}
   * @param what diagnostic label for log messages; must not be {@code null}
   * @param <T> provider type
   * @return list of successfully loaded provider instances
   */
  public static <T> List<T> loadProvidersSafely(ServiceLoader<T> loader, String what) {
    Objects.requireNonNull(loader, "loader");
    Objects.requireNonNull(what, "what");
    return loadIteratorSafely(loader.iterator(), what);
  }

  /**
   * Materializes providers from a lazy provider {@link Iterator}, catching {@link LinkageError} and
   * {@link ServiceConfigurationError} on individual elements and continuing iteration.
   *
   * @param iterator provider iterator; must not be {@code null}
   * @param what diagnostic label for log messages; must not be {@code null}
   * @param <T> provider type
   * @return list of successfully loaded provider instances
   */
  public static <T> List<T> loadIteratorSafely(Iterator<T> iterator, String what) {
    Objects.requireNonNull(iterator, "iterator");
    Objects.requireNonNull(what, "what");
    List<T> providers = new ArrayList<>();
    while (true) {
      T provider;
      try {
        if (!iterator.hasNext()) break;
        provider = iterator.next();
      } catch (ServiceConfigurationError | LinkageError err) {
        ServerAccessors.log(Level.FINE, "skipping unloadable " + what + ": " + err);
        continue;
      }
      if (provider != null) {
        providers.add(provider);
      }
    }
    return providers;
  }

  /**
   * Returns the active server's {@link PlatformFamily} via {@link ServerAccessors#get()}, or {@code
   * null} when no accessor is installed.
   *
   * @return active platform family, or {@code null}
   */
  public static @Nullable PlatformFamily currentPlatformFamily() {
    try {
      ServerAccessor accessor = ServerAccessors.get();
      if (accessor == null) return null;
      return accessor.getPlatformFamily();
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  /**
   * Returns whether a provider declaring {@code providerFamily} is eligible on {@code running}.
   *
   * @param providerFamily provider's declared platform family, or {@code null} if platform-neutral
   * @param running running server platform family, or {@code null} if unknown
   * @return {@code true} if the provider is eligible
   */
  public static boolean matchesPlatform(
      @Nullable PlatformFamily providerFamily, @Nullable PlatformFamily running) {
    if (providerFamily == null) return true;
    if (running == null || running == PlatformFamily.UNKNOWN) return true;
    return providerFamily == running;
  }
}
