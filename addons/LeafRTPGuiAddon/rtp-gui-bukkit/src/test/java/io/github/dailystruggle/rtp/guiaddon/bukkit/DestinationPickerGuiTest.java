package io.github.dailystruggle.rtp.guiaddon.bukkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DestinationPickerGuiTest {

  @Test
  void colorize_handlesNullAndEmpty() {
    assertEquals("", DestinationPickerGui.colorize(null));
    assertEquals("", DestinationPickerGui.colorize(""));
  }

  @Test
  void colorize_translatesLegacyAmpersandCodes() {
    assertEquals("§aGreen §lBold", DestinationPickerGui.colorize("&aGreen &lBold"));
  }

  @Test
  void colorize_translatesHexPatterns() {
    // &#55ff55 -> §x§5§5§f§f§5§5
    assertEquals("§x§5§5§f§f§5§5Verdant Wilds", DestinationPickerGui.colorize("&#55ff55Verdant Wilds"));
    // #ff5533 -> §x§f§f§5§5§3§3
    assertEquals("§x§f§f§5§5§3§3Ashen Wastes", DestinationPickerGui.colorize("#ff5533Ashen Wastes"));
  }

  @Test
  void colorize_translatesMiniMessageTags() {
    // <green> -> &a, </green> -> &r -> §r
    assertEquals("§aWilds§r", DestinationPickerGui.colorize("<green>Wilds</green>"));
    // <#55ff55> -> &#55ff55 -> §x§5§5§f§f§5§5, </#55ff55> -> &r -> §r
    assertEquals("§x§5§5§f§f§5§5Verdant§r", DestinationPickerGui.colorize("<#55ff55>Verdant</#55ff55>"));
  }
}
