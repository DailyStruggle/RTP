package io.github.dailystruggle.menu.api;

import io.github.dailystruggle.server.api.BookMenuSpec;
import io.github.dailystruggle.server.api.MenuContextAccessor;
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
 * AnvilInputOpenerProvider} gated by {@link PlatformFamily}, plus {@link MenuDescriptor} to {@link
 * BookMenuSpec} translation for {@link ServerAccessor}-backed book and chat rendering (ADR-035,
 * ADR-048, ADR-070, ADR-115).
 *
 * <p>Resilient to {@link LinkageError} and {@link ServiceConfigurationError} so universal jars
 * containing multiple platform providers degrade gracefully on any server runtime.
 *
 * @governedBy ADR-035
 * @governedBy ADR-048
 * @governedBy ADR-070
 * @governedBy ADR-115
 * @trace REQ-SLEUTH-UI-001
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

  /**
   * Discovers a {@link MenuRenderer} via {@link #discoverRenderer(List)} and, if no {@link
   * MenuRendererProvider} is registered on the classpath, falls back to a {@link
   * MenuContextAccessor#renderBookOrChat}-backed renderer bound to {@code accessor} (or {@link
   * ServerAccessors#get()} when {@code accessor} is {@code null}).
   *
   * @param preferredIds ordered renderer identifiers (for example, {@code ["book", "chat"]})
   * @param accessor explicit {@link MenuContextAccessor}, or {@code null} to query {@link
   *     ServerAccessors#get()}
   * @return resolved {@link MenuRenderer}, or {@code null} if neither a provider nor an accessor is
   *     available
   */
  public static @Nullable MenuRenderer discoverRendererOrAccessor(
      List<String> preferredIds, @Nullable MenuContextAccessor accessor) {
    MenuRenderer discovered = discoverRenderer(preferredIds);
    if (discovered != null) {
      return discovered;
    }
    MenuContextAccessor resolved = accessor != null ? accessor : ServerAccessors.get();
    if (resolved == null) {
      return null;
    }
    boolean preferBook = false;
    for (String id : preferredIds) {
      if (id != null && id.trim().equalsIgnoreCase("book")) {
        preferBook = true;
        break;
      }
    }
    final boolean allowBook = preferBook;
    return (viewer, descriptor) -> {
      BookMenuSpec spec = toBookMenuSpec(descriptor);
      if (allowBook) {
        resolved.renderBookOrChat(viewer, spec);
      } else {
        for (BookMenuSpec.Page page : spec.pages()) {
          for (BookMenuSpec.Line line : page.lines()) {
            resolved.sendInteractiveLine(viewer, line);
          }
        }
      }
    };
  }

  /**
   * Converts a declarative {@link MenuDescriptor} into a platform-neutral {@link BookMenuSpec}
   * ready for {@link MenuContextAccessor#openBook} or {@link MenuContextAccessor#renderBookOrChat}.
   *
   * @param descriptor source menu descriptor; must not be {@code null}
   * @return translated {@link BookMenuSpec}
   */
  public static BookMenuSpec toBookMenuSpec(MenuDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    String author = descriptor.styleMetadata().getOrDefault("author", "Leaf");
    List<BookMenuSpec.Page> pages = new ArrayList<>(descriptor.pages().size());
    for (MenuDescriptor.Page page : descriptor.pages()) {
      List<BookMenuSpec.Line> lines = new ArrayList<>(page.lines().size());
      for (MenuDescriptor.Line line : page.lines()) {
        List<BookMenuSpec.Fragment> fragments = new ArrayList<>(line.fragments().size());
        for (MenuDescriptor.Fragment fragment : line.fragments()) {
          fragments.add(toBookFragment(fragment));
        }
        lines.add(new BookMenuSpec.Line(fragments));
      }
      pages.add(new BookMenuSpec.Page(lines));
    }
    return new BookMenuSpec(descriptor.title(), author, pages);
  }

  /**
   * Converts a single {@link MenuDescriptor.Fragment} and its {@link MenuAction} into a {@link
   * BookMenuSpec.Fragment}.
   *
   * @param fragment menu descriptor fragment; must not be {@code null}
   * @return translated {@link BookMenuSpec.Fragment}
   */
  public static BookMenuSpec.Fragment toBookFragment(MenuDescriptor.Fragment fragment) {
    Objects.requireNonNull(fragment, "fragment");
    String text = BookContrastValidator.sanitizeForParchmentPage(fragment.text());
    String hover = BookContrastValidator.sanitizeForDarkHoverTooltip(fragment.hover());
    MenuAction action = fragment.action();
    if (action == null) {
      return new BookMenuSpec.Fragment(text, hover, BookMenuSpec.ClickAction.NONE, null);
    }
    if (action instanceof MenuAction.RunCommand run) {
      if (run.args().isEmpty()) {
        return new BookMenuSpec.Fragment(text, hover, BookMenuSpec.ClickAction.NONE, null);
      }
      String joined = String.join(" ", run.args()).trim();
      if (joined.isEmpty()) {
        return new BookMenuSpec.Fragment(text, hover, BookMenuSpec.ClickAction.NONE, null);
      }
      String command = joined.startsWith("/") ? joined : "/" + joined;
      return BookMenuSpec.Fragment.runCommand(text, hover, command);
    }
    if (action instanceof MenuAction.SuggestInput suggest) {
      return BookMenuSpec.Fragment.suggestCommand(text, hover, suggest.prefix());
    }
    if (action instanceof MenuAction.ChangePage change) {
      return BookMenuSpec.Fragment.changePage(text, hover, change.pageIndex());
    }
    if (action instanceof MenuAction.OpenExternalUri uri) {
      return BookMenuSpec.Fragment.openUrl(text, hover, uri.uri().toString());
    }
    if (action instanceof MenuAction.OpenSubmenu submenu && !submenu.path().isEmpty()) {
      String joined = String.join(" ", submenu.path()).trim();
      if (!joined.isEmpty()) {
        String command = joined.startsWith("/") ? joined : "/" + joined;
        return BookMenuSpec.Fragment.runCommand(text, hover, command);
      }
    }
    return new BookMenuSpec.Fragment(text, hover, BookMenuSpec.ClickAction.NONE, null);
  }
}
