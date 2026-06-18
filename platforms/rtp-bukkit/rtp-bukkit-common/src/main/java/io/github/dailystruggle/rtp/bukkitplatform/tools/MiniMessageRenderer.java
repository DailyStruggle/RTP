package io.github.dailystruggle.rtp.bukkitplatform.tools;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    Component component = MINI_MESSAGE.deserialize(legacyToMiniMessage(miniMessageText));
    String json = GsonComponentSerializer.gson().serialize(component);
    return ComponentSerializer.parse(json);
  }

  // Legacy single-character codes ('&' or section sign) -> MiniMessage tag name.
  private static final Map<Character, String> LEGACY_CODES = new HashMap<>();

  static {
    LEGACY_CODES.put('0', "black");
    LEGACY_CODES.put('1', "dark_blue");
    LEGACY_CODES.put('2', "dark_green");
    LEGACY_CODES.put('3', "dark_aqua");
    LEGACY_CODES.put('4', "dark_red");
    LEGACY_CODES.put('5', "dark_purple");
    LEGACY_CODES.put('6', "gold");
    LEGACY_CODES.put('7', "gray");
    LEGACY_CODES.put('8', "dark_gray");
    LEGACY_CODES.put('9', "blue");
    LEGACY_CODES.put('a', "green");
    LEGACY_CODES.put('b', "aqua");
    LEGACY_CODES.put('c', "red");
    LEGACY_CODES.put('d', "light_purple");
    LEGACY_CODES.put('e', "yellow");
    LEGACY_CODES.put('f', "white");
    LEGACY_CODES.put('k', "obfuscated");
    LEGACY_CODES.put('l', "bold");
    LEGACY_CODES.put('m', "strikethrough");
    LEGACY_CODES.put('n', "underlined");
    LEGACY_CODES.put('o', "italic");
    LEGACY_CODES.put('r', "reset");
  }

  // Bukkit hex form: §x§r§r§g§g§b§b (the indicator may also be '&').
  private static final Pattern LEGACY_HEX_PATTERN =
      Pattern.compile("[&\u00a7]x((?:[&\u00a7][0-9a-fA-F]){6})");
  // Direct hex form: &#rrggbb, §#rrggbb, or a bare #rrggbb.
  private static final Pattern DIRECT_HEX_PATTERN =
      Pattern.compile("[&\u00a7]?#([0-9a-fA-F]{6})");
  // Single legacy color/format code: &c or §c.
  private static final Pattern LEGACY_CODE_PATTERN =
      Pattern.compile("[&\u00a7]([0-9a-fk-orK-OR])");

  // A MiniMessage tag span: <name...> or </name>. Matches the conservative shape
  // used by SendMessage.miniMessageTagPattern. Used to segment the text so the
  // legacy->MiniMessage translation skips bytes that live *inside* a tag (tag
  // name + arguments), which would otherwise corrupt e.g. a gradient's #rrggbb
  // color stops into <#rrggbb>.
  private static final Pattern MINIMESSAGE_TAG_SPAN =
      Pattern.compile("<(/?[a-zA-Z#][^<>]*)>");

  /**
   * Translates leftover legacy color/format codes (both {@code &} and section-sign
   * forms, including the Bukkit {@code §x§r§r§g§g§b§b} hex sequence and direct
   * {@code #rrggbb} / {@code &#rrggbb} hex) into the equivalent MiniMessage tags so
   * a single {@link MiniMessage#deserialize} pass renders them instead of printing
   * the raw codes literally. MiniMessage {@code <tag>} markup already present in the
   * text is left untouched.
   */
  static String legacyToMiniMessage(String text) {
    if (text == null || text.isEmpty()) return text;

    // Segment the text into MiniMessage tag spans (copied verbatim) and the
    // plain-text gaps between them. Legacy->MiniMessage translation is applied
    // only to the gaps, so a tag's own arguments (e.g. <gradient:#ff0000:#00ff00>)
    // are never rewritten. Placeholders are already resolved before this method
    // runs, so any markup or legacy codes they introduced are part of `text`
    // here and are segmented/translated like everything else.
    Matcher tagMatcher = MINIMESSAGE_TAG_SPAN.matcher(text);
    StringBuilder out = new StringBuilder();
    int last = 0;
    while (tagMatcher.find()) {
      out.append(translateLegacySegment(text.substring(last, tagMatcher.start())));
      out.append(tagMatcher.group()); // tag span verbatim
      last = tagMatcher.end();
    }
    out.append(translateLegacySegment(text.substring(last)));
    return out.toString();
  }

  /**
   * Translates leftover legacy color/format codes in a single plain-text segment
   * (no MiniMessage tag spans) into the equivalent MiniMessage tags. See
   * {@link #legacyToMiniMessage} for the segmentation that guarantees this only
   * sees text outside {@code <...>} tags.
   */
  private static String translateLegacySegment(String text) {
    if (text == null || text.isEmpty()) return text;

    // 1. Bukkit §x hex sequence -> <#rrggbb>.
    Matcher hexMatcher = LEGACY_HEX_PATTERN.matcher(text);
    StringBuffer hexBuf = new StringBuffer();
    while (hexMatcher.find()) {
      String hex = hexMatcher.group(1).replaceAll("[&\u00a7]", "");
      hexMatcher.appendReplacement(hexBuf, Matcher.quoteReplacement("<#" + hex.toLowerCase(Locale.ROOT) + ">"));
    }
    hexMatcher.appendTail(hexBuf);
    text = hexBuf.toString();

    // 2. Direct #rrggbb / &#rrggbb / §#rrggbb -> <#rrggbb>.
    Matcher directMatcher = DIRECT_HEX_PATTERN.matcher(text);
    StringBuffer directBuf = new StringBuffer();
    while (directMatcher.find()) {
      directMatcher.appendReplacement(directBuf,
          Matcher.quoteReplacement("<#" + directMatcher.group(1).toLowerCase(Locale.ROOT) + ">"));
    }
    directMatcher.appendTail(directBuf);
    text = directBuf.toString();

    // 3. Single legacy codes -> named MiniMessage tags.
    Matcher codeMatcher = LEGACY_CODE_PATTERN.matcher(text);
    StringBuffer codeBuf = new StringBuffer();
    while (codeMatcher.find()) {
      char code = Character.toLowerCase(codeMatcher.group(1).charAt(0));
      String tag = LEGACY_CODES.get(code);
      String replacement = (tag != null) ? "<" + tag + ">" : codeMatcher.group(0);
      codeMatcher.appendReplacement(codeBuf, Matcher.quoteReplacement(replacement));
    }
    codeMatcher.appendTail(codeBuf);
    return codeBuf.toString();
  }
}
