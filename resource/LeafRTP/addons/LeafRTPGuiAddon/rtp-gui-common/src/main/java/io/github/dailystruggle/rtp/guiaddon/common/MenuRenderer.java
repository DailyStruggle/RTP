package io.github.dailystruggle.rtp.guiaddon.common;

import java.util.UUID;

/**
 * Platform render seam for the destination menu.
 *
 * <p>This is the one piece that genuinely cannot be platform-neutral: drawing the
 * menu requires a platform UI (a Bukkit chest inventory, a Fabric/NeoForge screen or
 * book, ...). Everything else - config, the {@link MenuModel}, target/status
 * resolution, the teleport submit, and result feedback - lives in {@code common}.
 *
 * <p>A renderer registers itself via {@link GuiRenderers#register(MenuRenderer)} from
 * its platform module's entry point, keyed by its {@link #key() style key}. More than
 * one renderer (e.g. a chest and a book) may be registered at once; the {@code
 * menuStyle} config value decides which one a bare {@code /rtp} opens. The common
 * root-action binding resolves the configured style when a player opens the menu; if
 * none is available the bare {@code /rtp} falls back to the classic teleport.
 */
public interface MenuRenderer {

  /**
   * Stable, lower-case style identifier for this renderer (e.g. {@code "chest"},
   * {@code "book"}). It is the value an admin puts under {@code menuStyle} in
   * {@code guimenu.yml} to select this renderer, and the key under which the renderer
   * registers itself in {@link GuiRenderers}. Several renderers may be registered at
   * once (one per style); the configured style decides which one a bare {@code /rtp}
   * opens. Must be unique across installed renderers and never {@code null}.
   *
   * @return the renderer's style key
   */
  String key();

  /**
   * Opens the menu for the given player.
   *
   * @param playerId the player to show the menu to (guaranteed online by the caller)
   * @param model the immutable, pre-resolved menu contents
   */
  void open(UUID playerId, MenuModel model);

  /**
   * Whether this renderer can currently display a menu (e.g. the platform UI is
   * available). Returning {@code false} makes a bare {@code /rtp} defer to the
   * classic teleport rather than appear to do nothing.
   *
   * @return {@code true} if {@link #open} can be honored
   */
  default boolean isAvailable() {
    return true;
  }
}
