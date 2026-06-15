package io.github.dailystruggle.rtp.bukkitplatform.tools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.chat.ComponentSerializer;

/**
 * Adventure-backed MiniMessage renderer for the Bukkit family.
 *
 * <p>This class is intentionally isolated from {@link SendMessage}: every symbol
 * it references in the {@code net.kyori.adventure.*} namespace is only available
 * at runtime when the host server bundles Adventure (Paper, Folia, and any
 * server forked from them). On a pure Spigot server those classes are absent, so
 * {@link SendMessage} guards every entry point with a {@code Class.forName}
 * availability probe and never loads this class there. Keeping the Adventure
 * imports out of {@code SendMessage} avoids a {@code NoClassDefFoundError} at
 * link time on Spigot.
 *
 * <p>MiniMessage produces an Adventure {@link Component}. Rather than introduce a
 * second send path (which would require {@code adventure-platform-bukkit} and an
 * {@code Audience} lifecycle), the component is serialized to the standard
 * Minecraft chat-component JSON and re-parsed into the bungee
 * {@link BaseComponent} graph the rest of {@link SendMessage} already dispatches
 * via {@code spigot().sendMessage(BaseComponent[])}. This preserves full
 * MiniMessage fidelity (named/hex colours, gradients, decorations, hover, click)
 * while reusing the existing transport.
 */
final class MiniMessageRenderer {
  private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

  private MiniMessageRenderer() {}

  /**
   * Parses MiniMessage markup into bungee {@link BaseComponent}s.
   *
   * @param miniMessageText text containing MiniMessage {@code <tag>} markup with
   *     all placeholders already resolved
   * @return the rendered components, suitable for {@code spigot().sendMessage}
   */
  static BaseComponent[] render(String miniMessageText) {
    Component component = MINI_MESSAGE.deserialize(miniMessageText);
    String json = GsonComponentSerializer.gson().serialize(component);
    return ComponentSerializer.parse(json);
  }
}
