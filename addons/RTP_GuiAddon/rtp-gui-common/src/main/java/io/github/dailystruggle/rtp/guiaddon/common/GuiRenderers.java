package io.github.dailystruggle.rtp.guiaddon.common;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Process-wide registry of installed {@link MenuRenderer}s, keyed by style.
 *
 * <p>Each platform module registers one renderer per style it offers (e.g. a Bukkit
 * chest renderer under {@code "chest"}, a book renderer under {@code "book"}). More
 * than one may be registered at once; the {@code menuStyle} value in {@code
 * guimenu.yml} decides which one a bare {@code /rtp} opens, so an admin can swap the
 * GUI style without a code change. {@link #resolve(String)} performs that selection,
 * falling back to any available renderer when the configured style is missing.
 *
 * <p>Registration order is preserved so the fallback is deterministic.
 */
public final class GuiRenderers {

  private static final Map<String, MenuRenderer> RENDERERS =
      Collections.synchronizedMap(new LinkedHashMap<>());

  private GuiRenderers() {}

  /**
   * Registers (or replaces) a renderer under its {@link MenuRenderer#key() style key}.
   *
   * @param renderer the renderer to register; ignored if {@code null} or it has a
   *     {@code null}/blank key
   */
  public static void register(MenuRenderer renderer) {
    if (renderer == null) {
      return;
    }
    String key = normalize(renderer.key());
    if (key == null) {
      return;
    }
    RENDERERS.put(key, renderer);
  }

  /**
   * Removes the renderer registered under {@code key} (case-insensitive), if any.
   *
   * @param key the style key to remove
   */
  public static void unregister(String key) {
    String k = normalize(key);
    if (k != null) {
      RENDERERS.remove(k);
    }
  }

  /** Clears every registered renderer (e.g. when the addon unloads). */
  public static void clear() {
    RENDERERS.clear();
  }

  /**
   * Resolves the renderer to use for a bare {@code /rtp}.
   *
   * <p>If {@code preferredKey} names an available registered renderer, it wins.
   * Otherwise the first available renderer in registration order is returned, so a
   * misconfigured or unset {@code menuStyle} still produces a working menu rather than
   * silently falling back to the classic teleport.
   *
   * @param preferredKey the configured {@code menuStyle}; may be {@code null}/blank
   * @return an available renderer, or {@code null} if none is registered/available
   */
  public static MenuRenderer resolve(String preferredKey) {
    String key = normalize(preferredKey);
    if (key != null) {
      MenuRenderer preferred = RENDERERS.get(key);
      if (preferred != null && preferred.isAvailable()) {
        return preferred;
      }
    }
    synchronized (RENDERERS) {
      for (MenuRenderer renderer : RENDERERS.values()) {
        if (renderer.isAvailable()) {
          return renderer;
        }
      }
    }
    return null;
  }

  /**
   * @return the style keys of all registered renderers, in registration order; an
   *     immutable snapshot.
   */
  public static Collection<String> registeredStyles() {
    synchronized (RENDERERS) {
      return Collections.unmodifiableCollection(new LinkedHashMap<>(RENDERERS).keySet());
    }
  }

  private static String normalize(String key) {
    if (key == null) {
      return null;
    }
    String k = key.trim().toLowerCase();
    return k.isEmpty() ? null : k;
  }
}
